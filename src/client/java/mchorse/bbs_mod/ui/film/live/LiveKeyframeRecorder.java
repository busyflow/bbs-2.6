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

    /** Last tick written, so one tick is never written twice while frames outrun ticks. */
    private int lastTick;

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
     * Write the transform's current value at {@code tick}.
     *
     * <p>The value is copied through the channel's own factory. Inserting the live object itself
     * would put the very instance the gizmo is still dragging into the channel, so every keyframe
     * in the take would be the same object and the whole thing would read back as one flat pose.</p>
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

        this.channel.insert(tick, this.channel.getFactory().copy(value));
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
        boolean wrote = this.lastTick != -1;

        this.abandon();

        if (!wrote)
        {
            return;
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
    }
}
