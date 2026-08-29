package mchorse.bbs_mod.ui.film.live;

import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.ui.film.UIFilmPanel;
import mchorse.bbs_mod.ui.film.replays.UIReplaysEditorUtils;
import mchorse.bbs_mod.ui.film.utils.undo.LiveRecordingUndo;
import mchorse.bbs_mod.ui.framework.elements.input.UIPropTransform;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframeEditor;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframeSheet;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.factories.UIKeyframeFactory;
import mchorse.bbs_mod.utils.keyframes.Keyframe;
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;

import java.util.ArrayList;
import java.util.List;

/**
 * Records a gizmo drag onto the timeline as it happens.
 *
 * <p>Drag a pose bone or a transform while the film is playing and the pose is written to the
 * timeline at the tick it was made on, every tick, for as long as the drag lasts. That is the whole
 * feature: performing the motion once rather than posing, stepping, posing, stepping.</p>
 *
 * <h3>Why it samples rather than listens</h3>
 *
 * <p>The obvious shape is to hook the transform's change callback and write a keyframe whenever the
 * value moves. It gives the wrong curve. A drag reports changes when the <em>mouse</em> moves, so
 * holding the bone still for half a second writes nothing at all, and the timeline then interpolates
 * straight through the pause as though the motion never stopped - the one thing a performance is
 * usually trying to say. Sampling on the tick instead records what was true at that tick whether or
 * not it changed, so a held pose is held keyframes and reads back exactly as it was performed.</p>
 *
 * <h3>Why it writes at the playhead</h3>
 *
 * <p>The transform is bound to whichever keyframe the editor has selected, so a drag ordinarily
 * edits <em>that</em> keyframe wherever it sits. While recording, the value is read from the same
 * transform but written to a fresh keyframe at the playhead. The keyframe the editor is pointing at
 * therefore behaves as the pose the take starts from, which is what makes starting a take on an
 * existing keyframe continue from it rather than jump.</p>
 */
public class LiveKeyframeRecorder
{
    /** Null unless a take is in progress. */
    private KeyframeChannel channel;
    private UIKeyframeFactory factory;

    /** The channel exactly as it was before the take, and where the playhead was. */
    private BaseType before;
    private int cursorBefore;

    /** Last tick sampled, so one tick is never sampled twice while frames outrun ticks. */
    private int lastTick;

    /**
     * The take, held back until the drag ends.
     *
     * <p>Writing each sample into the channel as it was taken fed the recorder's own output back
     * into the gesture it was recording. A drag solves as a snapshot of the transform taken when
     * the gesture began, plus a delta measured against the gizmo's origin <em>as rendered this
     * frame</em>. Writing a keyframe at the playhead moved the actor, which moved that origin,
     * so the next frame measured its delta from a moved origin against a snapshot that had not
     * moved - counting the same motion twice, again every frame. A translation ran away and a
     * trackball rotation span.</p>
     *
     * <p>Held here instead, the channel is untouched for the length of the drag, the origin stays
     * where the gesture anchored it, and the samples are exactly the motion performed.</p>
     */
    private final List<Sample> take = new ArrayList<>();

    private record Sample(int tick, Object value) {}

    public boolean isRecording()
    {
        return this.channel != null;
    }

    /**
     * Drive the recorder from the film panel's frame.
     *
     * <p>Polled rather than driven by events because a take is bounded by three separate things -
     * the drag ending, playback stopping, and the editor's selection changing out from under it -
     * and only one of them is a mouse event. Asking every frame is a handful of field reads and
     * cannot miss a boundary the way three listeners can.</p>
     */
    public void update(UIFilmPanel panel)
    {
        if (panel == null || panel.getData() == null)
        {
            this.abandon();

            return;
        }

        UIKeyframeEditor editor = panel.replayEditor == null ? null : panel.replayEditor.keyframeEditor;
        UIPropTransform transform = UIReplaysEditorUtils.getEditableTransform(editor);
        boolean live = panel.isRunning() && transform != null && transform.isEditing();

        if (!live)
        {
            this.finish(panel);

            return;
        }

        if (!this.isRecording())
        {
            this.begin(panel, editor);
        }

        /* Still null when the selected track is one whose keyframes cannot be resolved to a
         * channel — nothing to record into, so the drag stays an ordinary edit. */
        if (this.isRecording())
        {
            this.sample(panel.getCursor());
        }
    }

    private void begin(UIFilmPanel panel, UIKeyframeEditor editor)
    {
        UIKeyframeFactory factory = editor.editor;
        Keyframe keyframe = factory == null ? null : factory.getKeyframe();
        UIKeyframeSheet sheet = keyframe == null ? null : editor.getSheet(keyframe);

        if (sheet == null || sheet.channel == null)
        {
            return;
        }

        this.channel = sheet.channel;
        this.factory = factory;
        this.before = this.channel.toData();
        this.cursorBefore = panel.getCursor();

        /* -1 rather than the cursor: the tick the take starts on has not been written yet, and
         * seeding with the cursor would skip it. */
        this.lastTick = -1;
    }

    /**
     * Take the transform's current value for {@code tick}.
     *
     * <p>Copied through the channel's own factory rather than kept by reference: the gizmo goes on
     * mutating that same instance for the rest of the drag, so every sample in the take would end
     * up being the final pose and the whole thing would read back flat.</p>
     */
    private void sample(int tick)
    {
        if (tick == this.lastTick || this.factory == null)
        {
            return;
        }

        Keyframe keyframe = this.factory.getKeyframe();
        Object value = keyframe == null ? null : keyframe.getValue();

        if (value == null)
        {
            return;
        }

        this.take.add(new Sample(tick, this.channel.getFactory().copy(value)));
        this.lastTick = tick;
    }

    /**
     * End the take and hand it to the undo history as one entry.
     *
     * <p>A take that wrote nothing - the drag began and ended inside a single tick - is dropped
     * rather than pushed, so a stray click during playback does not put an empty step in the
     * history for the user to walk back through.</p>
     */
    private void finish(UIFilmPanel panel)
    {
        if (!this.isRecording())
        {
            return;
        }

        KeyframeChannel channel = this.channel;
        BaseType before = this.before;
        int cursorBefore = this.cursorBefore;
        List<Sample> take = new ArrayList<>(this.take);

        this.abandon();

        if (take.isEmpty())
        {
            return;
        }

        /* The whole take at once, now the gesture is over and there is no drag left to disturb. */
        for (Sample sample : take)
        {
            channel.insert(sample.tick(), sample.value());
        }

        BaseType after = channel.toData();

        if (after.equals(before))
        {
            return;
        }

        panel.getUndoHandler().getUndoManager().pushUndo(new LiveRecordingUndo(
            channel.getPath(), before, after, cursorBefore, panel.getCursor()
        ));
    }

    private void abandon()
    {
        this.channel = null;
        this.factory = null;
        this.before = null;
        this.lastTick = -1;
        this.take.clear();
    }
}
