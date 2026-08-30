package mchorse.bbs_mod.mixin.client;

import mchorse.bbs_mod.actions.types.crowd.CrowdClientMembers;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Puts a crowd member's torso and head where the server said, on the client, at once.
 *
 * <p>Two pieces of vanilla stand between a crowd clip and what ends up on screen, and neither is
 * something the server can reach.</p>
 *
 * <p>The first is that body yaw is never sent. The client works it out itself, swinging the body
 * after the way the entity appears to be moving and only dragging it toward the sent facing once
 * the two are more than seventy-five degrees apart - so a standing crowd keeps whatever facing it
 * happened to be spawned with, however plainly the server says otherwise. For a crowd the sent
 * facing is the answer, so it is simply taken.</p>
 *
 * <p>The second is that rotations arrive as something to ease into over the next few ticks, which
 * is right for a mob that turns of its own accord and wrong for one being posed: it turns a shot
 * that should begin on target into one that arrives at it several frames late. A crowd member's
 * angles are placed rather than approached.</p>
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityCrowdClientBodyMixin extends Entity
{
    /** How far a torso may turn in a tick before it is a cut rather than a turn. */
    private static final float SNAP_DEGREES = 45F;

    /** Matches the rate the server turns a look by, so the two never fight. */
    private static final float TURN_DEGREES_PER_TICK = 25F;

    public LivingEntityCrowdClientBodyMixin()
    {
        super(null, null);
    }

    /**
     * Take the sent facing as the body's, rather than deriving one. The return value only tells
     * the renderer which way to swing the limbs, and a body that faces where it is looking has
     * nothing to flip.
     */
    @Inject(method = "turnHead", at = @At("HEAD"), cancellable = true)
    private void bbs$snapCrowdBodyYaw(float bodyRotation, float headRotation, CallbackInfoReturnable<Float> info)
    {
        if (this.getWorld() != null && this.getWorld().isClient && CrowdClientMembers.isMember(this.getId()))
        {
            LivingEntity self = (LivingEntity) (Object) this;

            /* The sent facing, not one worked out from movement.
             *
             * The server sets a member's yaw for both of the things that decide where it should
             * face: a walk point sets it to the heading of travel (CrowdKeyframeRuntime, where
             * faceTravel is on) and a look keyframe sets it to the target (where the look drives
             * body yaw). Deriving a facing here from how far the member had moved therefore did
             * not add anything - it overrode both. A crowd walking while looking at something
             * kept its torso pointed down the path, so the head turned and the body never did,
             * which is the one thing the body-yaw switch on a look keyframe is for.
             *
             * Where the server has an opinion it is the right one; where it has not - a walk with
             * faceTravel off - the last facing it set is still what was asked for. */
            float target = self.getYaw();
            float delta = net.minecraft.util.math.MathHelper.wrapDegrees(target - self.bodyYaw);

            /* Turns are eased, cuts are not.
             *
             * The renderer draws lerp(tickDelta, prevBodyYaw, bodyYaw), so stepping a little each
             * tick is what makes a turn look continuous rather than twenty jumps a second. But a
             * camera cut, or a scrub, moves a member somewhere else entirely between one tick and
             * the next, and easing across that is the crowd visibly swinging back round for the
             * two or three ticks it takes to catch up - at the start of every clip after a cut.
             *
             * A discontinuity is told from a turn by size: the server turns a body no faster than
             * it turns a head, so anything past that in a single tick did not happen by turning. */
            if (Math.abs(delta) > SNAP_DEGREES)
            {
                self.prevBodyYaw = target;
                self.bodyYaw = target;
            }
            else
            {
                float step = mchorse.bbs_mod.utils.MathUtils.clamp(delta, -TURN_DEGREES_PER_TICK, TURN_DEGREES_PER_TICK);

                self.prevBodyYaw = self.bodyYaw;
                self.bodyYaw = self.bodyYaw + step;
            }

            info.setReturnValue(headRotation);
        }
    }

    @Inject(method = "updateTrackedHeadRotation", at = @At("HEAD"), cancellable = true)
    private void bbs$snapCrowdHeadYaw(float yaw, int interpolationSteps, CallbackInfo info)
    {
        if (CrowdClientMembers.isMember(this.getId()))
        {
            LivingEntity self = (LivingEntity) (Object) this;

            /* The sent angle is taken outright - a posed crowd must not ease toward it over the
             * next few ticks - but the angle it is coming FROM is kept, so the frames inside this
             * tick still interpolate across the turn instead of jumping at the start of it. */
            self.prevHeadYaw = self.getHeadYaw();
            self.setHeadYaw(yaw);

            info.cancel();
        }
    }
}
