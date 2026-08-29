package mchorse.bbs_mod.actions.crowd;

import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.utils.MathUtils;
import mchorse.bbs_mod.utils.interps.AutoBezier;
import mchorse.bbs_mod.utils.interps.InterpContext;
import mchorse.bbs_mod.utils.interps.Interpolations;
import mchorse.bbs_mod.utils.keyframes.BezierUtils;
import mchorse.bbs_mod.utils.keyframes.KeyframeSegment;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/** Exact, allocation-light evaluator shared by server playback and client render sampling. */
public final class CrowdLookEvaluator
{
    private static final double DEFAULT_TARGET_EYE_HEIGHT = 1.62D;

    private CrowdLookEvaluator()
    {}

    public static Sample sample(Film film, String crowdReplayId, float filmTick)
    {
        return sample(film, film == null ? null : (Replay) film.replays.get(crowdReplayId), filmTick);
    }

    public static Sample sample(Film film, Replay crowdReplay, float filmTick)
    {
        return sample(film, crowdReplay, filmTick, null);
    }

    public static Sample sample(Film film, Replay crowdReplay, float filmTick, TargetResolver resolver)
    {
        if (film == null || crowdReplay == null || crowdReplay.keyframes.crowdLookTarget.isEmpty())
        {
            return null;
        }

        float localTick = localTick(crowdReplay, filmTick);
        KeyframeSegment<String> segment = crowdReplay.keyframes.crowdLookTarget.find(localTick);

        if (segment == null)
        {
            return null;
        }

        CrowdLookTarget controlA = CrowdLookTarget.parse(segment.a.getValue());
        CrowdLookTarget controlB = CrowdLookTarget.parse(segment.b.getValue());
        Replay targetA = (Replay) film.replays.get(controlA.replayId());
        Replay targetB = (Replay) film.replays.get(controlB.replayId());

        if (targetA == null && targetB == null)
        {
            return null;
        }

        if (targetA == null) targetA = targetB;
        if (targetB == null) targetB = targetA;

        Vec3d a = targetPosition(targetA, filmTick, resolver);
        Vec3d b = targetPosition(targetB, filmTick, resolver);
        double progress = segment.isSame() ? 0D : progress(segment);

        CrowdLookTarget control = progress >= 1D ? controlB : controlA;

        return new Sample(a, b, MathUtils.clamp(progress, 0D, 1D), control);
    }

    /** Return wrapped yaw and pitch. Angles, rather than target coordinates, are interpolated so
     * opposite-side targets never make the crowd spin the long way around or pass a singularity. */
    public static boolean rotation(double eyeX, double eyeY, double eyeZ, Sample sample, float[] output)
    {
        if (output == null || output.length < 2)
        {
            return false;
        }

        if (!rotationTo(eyeX, eyeY, eyeZ, sample.a, output))
        {
            return rotationTo(eyeX, eyeY, eyeZ, sample.b, output);
        }

        if (sample.progress <= 0D || sample.a.equals(sample.b))
        {
            return true;
        }

        float yawA = output[0];
        float pitchA = output[1];

        if (!rotationTo(eyeX, eyeY, eyeZ, sample.b, output))
        {
            output[0] = yawA;
            output[1] = pitchA;

            return true;
        }

        float progress = (float) sample.progress;
        output[0] = yawA + MathHelper.wrapDegrees(output[0] - yawA) * progress;
        output[1] = MathHelper.clamp(MathHelper.lerp(progress, pitchA, output[1]), -90F, 90F);

        return true;
    }

    private static boolean rotationTo(double eyeX, double eyeY, double eyeZ, Vec3d target, float[] output)
    {
        double dx = target.x - eyeX;
        double dy = target.y - eyeY;
        double dz = target.z - eyeZ;
        double horizontal = Math.sqrt(dx * dx + dz * dz);

        if (horizontal < 0.000001D && Math.abs(dy) < 0.000001D)
        {
            return false;
        }

        output[0] = (float) (Math.atan2(dz, dx) * (180D / Math.PI) - 90D);
        output[1] = MathHelper.clamp((float) (-Math.atan2(dy, horizontal) * (180D / Math.PI)), -90F, 90F);

        return true;
    }

    private static double progress(KeyframeSegment<String> segment)
    {
        if (segment.a.getInterpolation().has(Interpolations.BEZIER))
        {
            return BezierUtils.get(0D, 1D,
                segment.a.getTick(), segment.b.getTick(),
                segment.a.rx, segment.a.ry, segment.b.lx, segment.b.ly,
                segment.x);
        }

        if (segment.a.getInterpolation().has(Interpolations.AUTO) || segment.a.getInterpolation().has(Interpolations.AUTO_CLAMPED))
        {
            return AutoBezier.get(0D, 0D, 1D, 1D,
                segment.preA.getTick(), segment.a.getTick(), segment.b.getTick(), segment.postB.getTick(),
                segment.a.getInterpolation().has(Interpolations.AUTO_CLAMPED), segment.x);
        }

        InterpContext context = new InterpContext().set(0D, 0D, 1D, 1D, segment.x);

        context.isStart = segment.preA == segment.a;
        context.isEnd = segment.postB == segment.b;

        return segment.a.getInterpolation().interpolate(context);
    }

    private static Vec3d replayPosition(Replay replay, float filmTick)
    {
        float tick = localTick(replay, filmTick);

        return new Vec3d(
            replay.keyframes.x.interpolate(tick),
            replay.keyframes.y.interpolate(tick) + DEFAULT_TARGET_EYE_HEIGHT,
            replay.keyframes.z.interpolate(tick)
        );
    }

    private static Vec3d targetPosition(Replay replay, float filmTick, TargetResolver resolver)
    {
        Vec3d resolved = resolver == null ? null : resolver.resolve(replay, filmTick);

        return resolved == null ? replayPosition(replay, filmTick) : resolved;
    }

    private static float localTick(Replay replay, float filmTick)
    {
        int loop = replay.looping.get();

        if (loop <= 0)
        {
            return filmTick;
        }

        float wrapped = filmTick % loop;

        return wrapped < 0F ? wrapped + loop : wrapped;
    }

    public record Sample(Vec3d a, Vec3d b, double progress, CrowdLookTarget control)
    {}

    /** Supplies the live rendered position of a target replay when one exists. */
    @FunctionalInterface
    public interface TargetResolver
    {
        Vec3d resolve(Replay replay, float filmTick);
    }
}
