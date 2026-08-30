package mchorse.bbs_mod.ui.film.replays;

import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.film.replays.ReplayKeyframes;
import mchorse.bbs_mod.utils.MathUtils;
import mchorse.bbs_mod.utils.keyframes.Keyframe;
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;
import mchorse.bbs_mod.utils.pose.Transform;
import org.joml.Matrix3f;
import org.joml.Vector3d;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * Immutable source snapshots for a live, rigid transform of complete replay paths.
 * Every preview update is rebuilt from these snapshots, preventing cumulative drift.
 */
public class ReplayGizmoTransform
{
    private final List<Entry> entries = new ArrayList<>();
    private final Vector3d pivot = new Vector3d();

    public ReplayGizmoTransform(List<Replay> replays, float filmTick)
    {
        for (Replay replay : replays)
        {
            Entry entry = new Entry(replay);

            this.entries.add(entry);

            float tick = replay.getTick((int) filmTick);
            this.pivot.add(
                entry.source.x.interpolate(tick, 0D),
                entry.source.y.interpolate(tick, 0D),
                entry.source.z.interpolate(tick, 0D)
            );
        }

        if (!this.entries.isEmpty())
        {
            this.pivot.div(this.entries.size());
        }
    }

    public boolean isEmpty()
    {
        return this.entries.isEmpty();
    }

    public Vector3d getGizmoPosition(Transform transform)
    {
        return new Vector3d(this.pivot).add(transform.translate.x, transform.translate.y, transform.translate.z);
    }

    public void apply(Transform transform)
    {
        Matrix3f rotation = transform.createRotationMatrix();

        for (Entry entry : this.entries)
        {
            this.transformPosition(entry.source, entry.replay.keyframes, transform, rotation);
            this.transformVelocity(entry.source, entry.replay.keyframes, transform, rotation);
            this.transformFacing(entry.source, entry.replay.keyframes, rotation);
        }
    }

    /**
     * Move every position keyframe of one replay.
     *
     * <p>Walked once, writing x, y and z together. It used to run once per component, and each
     * pass re-read all three source channels to rebuild the same point - nine interpolations and
     * three temporary vectors per keyframe to produce one. On a recorded path, where there is a
     * keyframe every tick, that arithmetic is what made dragging the gizmo stutter.</p>
     */
    private void transformPosition(ReplayKeyframes source, ReplayKeyframes target, Transform transform, Matrix3f rotation)
    {
        List<Keyframe<Double>> sourceX = source.x.getKeyframes();
        List<Keyframe<Double>> targetX = target.x.getKeyframes();
        List<Keyframe<Double>> targetY = target.y.getKeyframes();
        List<Keyframe<Double>> targetZ = target.z.getKeyframes();

        /* Each channel is bounded on its own, as the per-component version was. Taking the
         * minimum across all three instead would let one empty channel freeze the other two -
         * and a path that will not move at all is a far worse failure than one axis left be. */
        int count = sourceX.size();
        Vector3f point = new Vector3f();

        for (int i = 0; i < count; i++)
        {
            float tick = sourceX.get(i).getTick();

            point.set(
                (float) (source.x.interpolate(tick, 0D) - this.pivot.x),
                (float) (source.y.interpolate(tick, 0D) - this.pivot.y),
                (float) (source.z.interpolate(tick, 0D) - this.pivot.z)
            );

            point.mul(transform.scale);
            rotation.transform(point);
            point.add(
                (float) this.pivot.x + transform.translate.x,
                (float) this.pivot.y + transform.translate.y,
                (float) this.pivot.z + transform.translate.z
            );

            if (i < targetX.size()) targetX.get(i).setValue((double) point.x);
            if (i < targetY.size()) targetY.get(i).setValue((double) point.y);
            if (i < targetZ.size()) targetZ.get(i).setValue((double) point.z);
        }
    }

    /** As {@link #transformPosition}, one pass over the velocity vector rather than three. */
    private void transformVelocity(ReplayKeyframes source, ReplayKeyframes target, Transform transform, Matrix3f rotation)
    {
        List<Keyframe<Double>> sourceX = source.vX.getKeyframes();
        List<Keyframe<Double>> targetX = target.vX.getKeyframes();
        List<Keyframe<Double>> targetY = target.vY.getKeyframes();
        List<Keyframe<Double>> targetZ = target.vZ.getKeyframes();

        int count = sourceX.size();
        Vector3f vector = new Vector3f();

        for (int i = 0; i < count; i++)
        {
            float tick = sourceX.get(i).getTick();

            vector.set(
                source.vX.interpolate(tick, 0D).floatValue(),
                source.vY.interpolate(tick, 0D).floatValue(),
                source.vZ.interpolate(tick, 0D).floatValue()
            );

            vector.mul(transform.scale);
            rotation.transform(vector);

            if (i < targetX.size()) targetX.get(i).setValue((double) vector.x);
            if (i < targetY.size()) targetY.get(i).setValue((double) vector.y);
            if (i < targetZ.size()) targetZ.get(i).setValue((double) vector.z);
        }
    }

    private void transformFacing(ReplayKeyframes source, ReplayKeyframes target, Matrix3f rotation)
    {
        this.transformAngleChannel(source.yaw, target.yaw, source.yaw, source.pitch, rotation, true);
        this.transformAngleChannel(source.pitch, target.pitch, source.yaw, source.pitch, rotation, false);
        this.transformHorizontalAngleChannel(source.headYaw, target.headYaw, rotation);
        this.transformHorizontalAngleChannel(source.bodyYaw, target.bodyYaw, rotation);
    }

    private void transformAngleChannel(
        KeyframeChannel<Double> sourceChannel,
        KeyframeChannel<Double> targetChannel,
        KeyframeChannel<Double> yaw,
        KeyframeChannel<Double> pitch,
        Matrix3f rotation,
        boolean outputYaw
    )
    {
        List<Keyframe<Double>> sourceFrames = sourceChannel.getKeyframes();
        List<Keyframe<Double>> targetFrames = targetChannel.getKeyframes();

        for (int i = 0; i < sourceFrames.size() && i < targetFrames.size(); i++)
        {
            float tick = sourceFrames.get(i).getTick();
            double originalYaw = yaw.interpolate(tick, 0D);
            double originalPitch = pitch.interpolate(tick, 0D);
            Vector3f forward = direction(originalYaw, originalPitch);

            rotation.transform(forward).normalize();

            double transformedYaw = Math.toDegrees(Math.atan2(-forward.x, forward.z));
            double transformedPitch = Math.toDegrees(Math.asin(MathUtils.clamp(-forward.y, -1F, 1F)));

            targetFrames.get(i).setValue(outputYaw ? unwrapDegrees(transformedYaw, originalYaw) : transformedPitch);
        }
    }

    private void transformHorizontalAngleChannel(KeyframeChannel<Double> source, KeyframeChannel<Double> target, Matrix3f rotation)
    {
        List<Keyframe<Double>> sourceFrames = source.getKeyframes();
        List<Keyframe<Double>> targetFrames = target.getKeyframes();

        for (int i = 0; i < sourceFrames.size() && i < targetFrames.size(); i++)
        {
            double angle = sourceFrames.get(i).getValue();
            Vector3f forward = direction(angle, 0D);

            rotation.transform(forward);
            forward.y = 0F;

            if (forward.lengthSquared() > 1.0E-8F)
            {
                forward.normalize();
                double transformed = Math.toDegrees(Math.atan2(-forward.x, forward.z));

                targetFrames.get(i).setValue(unwrapDegrees(transformed, angle));
            }
        }
    }

    private static Vector3f direction(double yaw, double pitch)
    {
        double yawRad = Math.toRadians(yaw);
        double pitchRad = Math.toRadians(pitch);
        double cosPitch = Math.cos(pitchRad);

        return new Vector3f(
            (float) (-Math.sin(yawRad) * cosPitch),
            (float) -Math.sin(pitchRad),
            (float) (Math.cos(yawRad) * cosPitch)
        );
    }

    private static double unwrapDegrees(double angle, double reference)
    {
        while (angle - reference > 180D) angle -= 360D;
        while (angle - reference < -180D) angle += 360D;

        return angle;
    }

    private static class Entry
    {
        public final Replay replay;
        public final ReplayKeyframes source = new ReplayKeyframes("");

        public Entry(Replay replay)
        {
            this.replay = replay;
            this.source.fromData(replay.keyframes.toData());
        }
    }
}
