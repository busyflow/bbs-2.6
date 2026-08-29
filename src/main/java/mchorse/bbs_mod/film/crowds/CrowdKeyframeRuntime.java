package mchorse.bbs_mod.film.crowds;

import mchorse.bbs_mod.actions.SuperFakePlayer;
import mchorse.bbs_mod.actions.crowd.CrowdBehavior;
import mchorse.bbs_mod.actions.crowd.CrowdJumpEvaluator;
import mchorse.bbs_mod.actions.crowd.CrowdLookEvaluator;
import mchorse.bbs_mod.actions.crowd.CrowdTexture;
import mchorse.bbs_mod.actions.crowd.CrowdLookTarget;
import mchorse.bbs_mod.actions.crowd.CrowdWalkEvaluator;
import mchorse.bbs_mod.actions.types.crowd.CrowdBehaviorActionClip;
import mchorse.bbs_mod.actions.types.crowd.CrowdDrivenEntity;
import mchorse.bbs_mod.actions.types.crowd.CrowdFormation;
import mchorse.bbs_mod.actions.types.crowd.CrowdPaintArea;
import mchorse.bbs_mod.actions.types.crowd.CrowdUtils;
import mchorse.bbs_mod.entity.ActorEntity;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.forms.FormUtils;
import mchorse.bbs_mod.forms.forms.CrowdForm;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.network.ServerNetwork;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.minecraft.server.network.ServerPlayerEntity;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.settings.values.base.BaseValue;
import mchorse.bbs_mod.settings.values.core.ValueColor;
import mchorse.bbs_mod.settings.values.core.ValueLink;
import mchorse.bbs_mod.utils.colors.Color;
import net.minecraft.entity.LivingEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.Heightmap;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Applies a replay's crowd keyframes to the crowd it drives.
 *
 * <p>A crowd exists because the film says it does - that is {@link CrowdReconciler}'s job and it
 * is deliberately separate from this. What this does is the other half: read where the crowd
 * walks, what it looks at, when it jumps and what it wears at this tick, and put that on the
 * members. Keeping the two apart is the point; a crowd that failed to be animated is a still
 * crowd, not a missing one.</p>
 *
 * <p>A channel with no keyframes says nothing rather than saying zero, so a crowd can be walked
 * by keyframes and left to its behaviour clip for everything else.</p>
 */
public class CrowdKeyframeRuntime
{
    private static final float[] ROTATION = new float[2];

    /**
     * How far a member may turn in one tick while walking.
     *
     * <p>Fast enough to have come round before it matters, slow enough to read as turning. A
     * crowd that reaches its new heading in one tick all together is the most mechanical thing a
     * crowd can do, and that is what setting the yaw outright looked like.</p>
     */
    private static final float TURN_DEGREES_PER_TICK = 18F;

    /** Far enough off the floor to count as jumping rather than as rounding. */
    private static final double AIRBORNE_EPSILON = 0.08D;

    /** Vanilla jump strength, so a crowd jumps the height everything else in the world does. */
    private static final double JUMP_VELOCITY = 0.42D;

    private CrowdKeyframeRuntime()
    {}

    public static void apply(ServerWorld world, Film film, int tick, Map<String, LivingEntity> actors)
    {
        if (world == null || film == null || film.crowds.getList().isEmpty())
        {
            return;
        }

        for (Replay replay : film.replays.getList())
        {
            if (!replay.enabled.get() || !(replay.form.get() instanceof CrowdForm form))
            {
                continue;
            }

            Crowd crowd = film.crowds.byTag(form.crowd.get());

            if (crowd == null || !crowd.existsAt(tick))
            {
                continue;
            }

            /* Unlimited range: membership is by tag, and a crowd is routinely wider than any
             * box worth querying. See CrowdUtils#getCrowd. */
            List<LivingEntity> members = CrowdUtils.getCrowd(world, film, crowd.crowdTag.get(), Vec3d.ZERO, 0D);

            if (members.isEmpty())
            {
                continue;
            }

            /* A keyframed behaviour steers the members live (wander, run, freak out, stand), so it
             * owns their movement and jumping this tick - a walk route would only fight it. It does
             * not own where they look: a look keyframe is someone saying outright what the crowd
             * is watching, and a behaviour's own idle glancing is not an argument against it. */
            boolean behaved = applyBehavior(world, film, replay, crowd, tick);

            if (behaved)
            {
                /* A behaviour steers members by velocity and leaves their vertical component alone
                 * (see moveEntity), so a real jump survives on top of it - a wandering crowd can be
                 * made to jump by the jump channel even though the behaviour's own hop only fires
                 * when a member is standing still. */
                applyJump(world, replay, members, tick);
            }
            else
            {
                /* The route places members outright, so a jump has to be part of that placement
                 * rather than a push applied afterwards - a push is undone by the next tick's
                 * placement, and anything that survives it does so by fighting the route. Only a
                 * crowd with no route left to fight gets a real jump. */
                boolean walked = applyWalk(world, film, replay, crowd, members, tick);

                if (!walked)
                {
                    applyJump(world, replay, members, tick);
                }
            }

            /* Last, so it is the final word on the head whatever moved the body this tick. */
            applyLook(film, replay, members, tick, actors);

            applyTexture(replay, members, tick);
            applyColor(replay, members, tick);
        }
    }

    /**
     * One reusable behaviour clip per crowd tag. The clip is stateless between ticks (its
     * randomness is a pure function of tick and member), so reusing it just avoids reallocating
     * a heavy value tree every tick; keying by tag keeps two crowds from sharing per-tick fields.
     */
    private static final Map<String, CrowdBehaviorActionClip> BEHAVIOR_CLIPS = new HashMap<>();

    /**
     * Drive the crowd by its keyframed behaviour, if it has one, and report whether it did.
     *
     * <p>The keyframe just names a mode and a speed; the actual per-tick stepping - wander paths,
     * sprinting, jumping, panicking - is the behaviour clip's, configured from the keyframe and
     * run against the members where they already stand. Switching mode at a keyframe therefore
     * continues from the current positions rather than resetting anyone.</p>
     */
    private static boolean applyBehavior(ServerWorld world, Film film, Replay replay, Crowd crowd, int tick)
    {
        if (replay.keyframes.crowdBehavior.isEmpty())
        {
            return false;
        }

        CrowdBehavior behavior = replay.keyframes.crowdBehavior.interpolate(replay.getTick(tick));

        if (behavior == null)
        {
            return false;
        }

        SuperFakePlayer player = SuperFakePlayer.get(world);

        if (player == null)
        {
            return false;
        }

        CrowdBehavior.Kind kind = behavior.getKind();
        CrowdBehaviorActionClip clip = BEHAVIOR_CLIPS.computeIfAbsent(crowd.crowdTag.get(), (tag) ->
        {
            CrowdBehaviorActionClip fresh = new CrowdBehaviorActionClip();

            /* No ease-in: the move-ease blend is measured from a clip's start tick, which a
             * keyframe-driven clip does not have. Anchor on spawn, since there is no replay to chase. */
            fresh.moveEase.set(0);
            fresh.target.set(CrowdBehaviorActionClip.TARGET_NONE);
            /* No look target: members should face where they walk (body, legs and head), which
             * moveEntity handles, rather than being turned to look at something. */
            fresh.lookAtTarget.set(false);

            return fresh;
        });

        clip.crowdTag.set(crowd.crowdTag.get());
        clip.mode.set(kind.mode.ordinal());
        clip.sprint.set(kind.sprint);
        clip.speed.set(behavior.effectiveSpeed());

        /* Freak out is meant to leap about, so it jumps by default when the keyframe leaves the
         * rate at 0; the calmer modes stay grounded unless a rate is dialled in. */
        float jumpRate = behavior.jumpRate > 0F ? behavior.jumpRate : (kind == CrowdBehavior.Kind.FREAK_OUT ? 1.5F : 0F);

        clip.randomJump.set(jumpRate > 0F);
        clip.jumpRate.set(jumpRate);

        clip.applyAction(null, player, film, replay, tick);

        return true;
    }

    /**
     * Walk the crowd along its waypoints.
     *
     * <p>Members are placed rather than steered. The waypoints already say where everyone is at
     * this tick, down to the stagger that makes the near edge leave first, so asking the
     * navigator to make its own way there would fight the curve the shot was authored on.</p>
     */
    private static boolean applyWalk(ServerWorld world, Film film, Replay replay, Crowd crowd,
        List<LivingEntity> members, int tick)
    {
        CrowdWalkEvaluator.Frame frame = CrowdWalkEvaluator.frame(replay, tick);

        if (frame == null)
        {
            return false;
        }

        Vec3d anchor = crowdAnchor(film, crowd);
        CrowdFormation formation = crowd.getFormation();
        CrowdPaintArea paint = formation == CrowdFormation.PAINT ? new CrowdPaintArea(crowd.getCells()) : null;
        int count = crowd.count.get();
        double spacing = crowd.spacing.get();
        double[] position = new double[3];

        /* Spread pushes members away from the middle of their own arrangement, so the middle has
         * to be worked out rather than assumed to be the origin. A painted crowd stands at world
         * coordinates; spreading those about the origin is what threw members a hundred blocks
         * out and dragged them back as the walk finished. */
        Vec3d centre = crowdCentre(crowd, paint, formation, anchor, count, spacing);
        CrowdJumpEvaluator.Frame jump = CrowdJumpEvaluator.frame(replay, tick);

        for (LivingEntity member : members)
        {
            int index = CrowdUtils.entityIndex(member);
            Vec3d base = memberBase(crowd, paint, formation, anchor, index, count, spacing);

            if (base == null)
            {
                continue;
            }

            /* The local formation position is immutable for this playback. Feeding the current
             * entity position back in here made every frame's offset become the next frame's
             * starting offset, which compounded into launches across the map. */
            CrowdWalkEvaluator.memberPosition(frame, index, base.x, base.y, base.z,
                centre.x, centre.y, centre.z, position);

            if (frame.path().terrainFollow)
            {
                position[1] = groundY(world, position[0], position[2], position[1]);
            }

            /* Jumping while walking is a lift off the route's own ground height, worked out from
             * the tick rather than from where the member is. Handing the jump to the physics
             * cannot work while the route is writing the position every tick, and adding a height
             * to the member's current height is how a crowd climbs into the sky. */
            if (jump != null)
            {
                position[1] += jump.walkingHeight(index);
            }

            /* A route that has produced a number that is not a number places nobody. Interpolation
             * with degenerate handles can do it, and a NaN reaching setPos does not misplace an
             * entity so much as remove it from the world. */
            if (!Double.isFinite(position[0]) || !Double.isFinite(position[1]) || !Double.isFinite(position[2]))
            {
                continue;
            }

            /* setPos lets the normal entity tracker interpolate the short per-tick steps. A
             * teleport-style refresh here is both visibly harsh and can create fall damage
             * after the entity briefly believes it has travelled vertically. */
            member.setPos(position[0], position[1], position[2]);

            /* Nothing left in the velocity. It is tempting to report the step here so the client
             * has something to extrapolate from, and that is exactly what threw the crowd into
             * the sky: velocity is not a report, the entity's own movement tick applies it. The
             * step got walked twice, the second one lifted the member off the route, and the
             * route - which decided whether it was airborne by comparing heights - then read
             * that as a jump and stopped correcting the height at all.
             *
             * The route is the whole answer while it is driving, so nothing else may move a
             * member and nothing about where it should be is read back off where it is. */
            member.setVelocity(Vec3d.ZERO);
            member.velocityDirty = true;
            member.fallDistance = 0F;
            member.setOnGround(true);

            /* Facing follows travel unless a look keyframe overrides it below, so a walking
             * crowd does not moonwalk to its destination.
             *
             * The direction is read off the route ahead of the member rather than from how far
             * it moved this tick: a tick of movement is a very short line, and one near a
             * waypoint points almost anywhere, which is what had members spinning on the spot.
             * A member with nowhere to be - before the route starts, after it ends - gets no
             * direction back and keeps the facing it spawned with. */
            Vec3d heading = frame.path().faceTravel ? CrowdWalkEvaluator.memberFacing(frame, index) : null;

            if (heading != null)
            {
                float target = (float) (MathHelper.atan2(heading.z, heading.x) * (180D / Math.PI)) - 90F;
                /* Turned toward, not set to. Setting it outright is a snap, and a crowd of them
                 * snapping together is the single most mechanical thing a crowd can do. */
                float yaw = turnToward(member.getYaw(), target, TURN_DEGREES_PER_TICK);

                member.setYaw(yaw);
                member.setBodyYaw(yaw);
                member.setHeadYaw(yaw);

                /* Say that this facing is the truth. Left to itself the client works body yaw
                 * out from which way the entity appears to be travelling, and against positions
                 * we are setting ourselves it can settle on exactly backwards - which is the
                 * crowd walking its route while facing the way it came. */
                if (member instanceof CrowdDrivenEntity driven)
                {
                    driven.bbs$driveBodyYaw();
                }
            }
        }

        return true;
    }

    /** Rotate toward an angle by at most {@code maxStep}, the short way round. */
    private static float turnToward(float current, float target, float maxStep)
    {
        float delta = MathHelper.wrapDegrees(target - current);

        return MathHelper.wrapDegrees(current + MathHelper.clamp(delta, -maxStep, maxStep));
    }

    private static Vec3d crowdAnchor(Film film, Crowd crowd)
    {
        Replay anchor = CrowdUtils.getReplay(film, crowd.anchor.get());

        return anchor == null ? Vec3d.ZERO : CrowdUtils.replayPosition(anchor, crowd.start.get());
    }

    /**
     * The middle of the crowd's arrangement, for spread to push members away from.
     *
     * <p>A formation is built around its anchor, so that is the middle by construction. Painted
     * ground has whatever shape it was painted, so its middle is the average of the places
     * members actually stand - sampled rather than summed over every cell, since the count is
     * routinely in the thousands and this is wanted every tick.</p>
     */
    private static Vec3d crowdCentre(Crowd crowd, CrowdPaintArea paint, CrowdFormation formation,
        Vec3d anchor, int count, double spacing)
    {
        if (paint == null)
        {
            return anchor;
        }

        int samples = Math.min(count, 64);
        double x = 0D;
        double y = 0D;
        double z = 0D;
        int taken = 0;

        for (int i = 0; i < samples; i++)
        {
            Vec3d point = paint.point((int) ((long) i * count / samples), count, 0);

            if (point == null)
            {
                continue;
            }

            x += point.x;
            y += point.y;
            z += point.z;
            taken += 1;
        }

        return taken == 0 ? anchor : new Vec3d(x / taken, y / taken, z / taken);
    }

    private static Vec3d memberBase(Crowd crowd, CrowdPaintArea paint, CrowdFormation formation,
        Vec3d anchor, int index, int count, double spacing)
    {
        if (paint != null)
        {
            return paint.point(index, count, 0);
        }

        Vec3d offset = CrowdUtils.formationPoint(formation, index, count, spacing, crowd.holeRadius.get());

        return anchor.add(offset);
    }

    /** Keep the crowd's feet on the surface without loading or generating a new chunk. */
    private static double groundY(ServerWorld world, double x, double z, double fallback)
    {
        int blockX = MathHelper.floor(x);
        int blockZ = MathHelper.floor(z);

        if (world.getChunk(blockX >> 4, blockZ >> 4, ChunkStatus.FULL, false) == null)
        {
            return fallback;
        }

        return world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, blockX, blockZ);
    }

    /**
     * Make the jumping part of the crowd jump, the way the behaviour clip always has.
     *
     * <p>A real jump: upward velocity, and gravity to bring it back. The previous attempt placed
     * members along an arc of its own, which meant owning their vertical position outright - and
     * an authored height that disagrees with the floor by any amount puts them through it. Left
     * to the physics, a member lands on whatever is under it.</p>
     */
    private static void applyJump(ServerWorld world, Replay replay, List<LivingEntity> members, int tick)
    {
        CrowdJumpEvaluator.Frame frame = CrowdJumpEvaluator.frame(replay, tick);

        if (frame == null)
        {
            return;
        }

        for (LivingEntity member : members)
        {
            if (member.isTouchingWater())
            {
                continue;
            }

            /* Standing on the ground is measured, not asked for. A walked crowd is placed with
             * setPos, which goes around the movement code that maintains isOnGround - so the
             * flag reads false forever and every member was skipped here, which is a crowd that
             * never jumps at all. */
            double ground = groundY(world, member.getX(), member.getZ(), member.getY());

            if (member.getY() > ground + AIRBORNE_EPSILON)
            {
                continue;
            }

            int index = CrowdUtils.entityIndex(member);

            if (frame.jumps(index))
            {
                jump(member, frame.power(index));
            }
        }
    }

    /**
     * Push a member off the ground and let gravity have it back.
     *
     * <p>Done by velocity rather than through the mob's jump control, which only acts when the
     * mob's AI is ticking - and a crowd with No mob AI on has none, so that route would jump
     * some crowds and not others for a reason nobody could see from the outside.</p>
     */
    private static void jump(LivingEntity entity, double power)
    {
        Vec3d velocity = entity.getVelocity();

        entity.setVelocity(velocity.x, Math.max(velocity.y, JUMP_VELOCITY * power), velocity.z);
        entity.velocityModified = true;
        entity.velocityDirty = true;
        entity.setOnGround(false);
        entity.setJumping(true);
    }

    /**
     * How far a head turns before the shoulders have to come with it. Vanilla allows a mob about
     * this much, and it is the angle that separates a glance from a turn.
     */
    private static final float MAX_HEAD_TURN = 60F;

    /** How fast a head turns toward what it is watching. Brisk, but not instant. */
    private static final float HEAD_DEGREES_PER_TICK = 25F;

    private static void applyLook(Film film, Replay replay, List<LivingEntity> members, int tick,
        Map<String, LivingEntity> actors)
    {
        /* Look at where the target actually is, not where its position keyframes say. An actor
         * moved by its entity rather than by x/y/z keyframes reads back as the origin, so the whole
         * crowd stared off at (0, 0, 0) - which looks like not looking at the target at all. The
         * live actor's eye wins; the keyframe position is the fallback for a target with no actor. */
        CrowdLookEvaluator.TargetResolver resolver = actors == null ? null : (target, filmTick) ->
        {
            LivingEntity actor = actors.get(target.getId());

            return actor == null ? null : actor.getEyePos();
        };

        CrowdLookEvaluator.Sample sample = CrowdLookEvaluator.sample(film, replay, tick, resolver);

        if (sample == null)
        {
            return;
        }

        for (LivingEntity member : members)
        {
            if (!CrowdLookEvaluator.rotation(member.getX(), member.getEyeY(), member.getZ(), sample, ROTATION))
            {
                continue;
            }

            /* Only the axes the keyframe asks for. All four were driven regardless, which made
             * the switches on the look keyframe decoration: turning pitch off still pitched the
             * crowd, and a crowd meant to turn its head without turning its body could not. */
            CrowdLookTarget control = sample.control();
            float look = ROTATION[0];

            if (control.pitch())
            {
                member.setPitch(ROTATION[1]);
            }

            /* Eased, not snapped. A head that arrives the instant the target moves reads as a
             * turret; this is the same rate limit the walk facing uses, so a scrubbed frame shows
             * the turn part way through rather than already over. */
            float head = control.headYaw()
                ? turnToward(member.getHeadYaw(), look, HEAD_DEGREES_PER_TICK)
                : member.getHeadYaw();

            if (control.headYaw())
            {
                member.setHeadYaw(head);
            }

            /* The body is not put where the head is. A BBS model turns its head by
             * query.head_yaw, which is the head's angle RELATIVE to the body (see
             * MolangHelper#setMolangVariables) - so setting both to the same angle, as this used
             * to, leaves that difference at zero and the head never turns at all. The whole
             * figure swivelled instead, which on a model with a head bone reads as nobody looking
             * at anything.
             *
             * The body only moves by however far the head has gone past what a neck allows, so a
             * glance is head alone and a look over the shoulder drags the torso round after it. */
            float body = member.getBodyYaw();
            float turned = MathHelper.wrapDegrees(head - body);
            float overturned = turned - MathHelper.clamp(turned, -MAX_HEAD_TURN, MAX_HEAD_TURN);
            float following = body + overturned;

            if (control.bodyYaw())
            {
                member.setBodyYaw(following);
            }

            if (control.yaw())
            {
                /* The facing follows the body, not the head: it is where the figure stands, and a
                 * head turned aside does not move it. */
                member.setYaw(control.bodyYaw() ? following : head);
            }

            /* Tell the client this facing is the truth, or it derives body yaw from movement
             * and the torsos lag behind the heads. Only worth saying when the body is ours to
             * turn - left undriven, the client's own guess is the right one. */
            if (control.bodyYaw() && member instanceof CrowdDrivenEntity driven)
            {
                driven.bbs$driveBodyYaw();
            }
        }
    }

    /**
     * Push a member's changed form to everyone watching it.
     *
     * <p>A member's form is sent once, when a client starts tracking it, and never again - so a
     * texture or colour written into it on a later tick stayed on the server and the crowd on
     * screen never changed. Re-sent only on the tick the value actually changes (the callers
     * guard on that), so a settled crowd costs nothing; a member's form goes out once per change,
     * not per tick.</p>
     *
     * <p>ponytail: whole-form resend per changed member. Fine at the scale a keyframed texture
     * change happens (once, on the keyframe); a diff packet would matter only if this ran per tick.</p>
     */
    private static void resyncForm(ActorEntity actor)
    {
        for (ServerPlayerEntity player : PlayerLookup.tracking(actor))
        {
            ServerNetwork.sendEntityForm(player, actor);
        }
    }

    /**
     * Dress the crowd.
     *
     * <p>One texture for everyone unless the keyframe asks for random, which is the only thing
     * in here that makes members differ from each other. Textures live on BBS model forms, so
     * members spawned as plain mobs have nothing to set and are left alone.</p>
     */
    private static void applyTexture(Replay replay, List<LivingEntity> members, int tick)
    {
        if (replay.keyframes.crowdTexture.isEmpty())
        {
            return;
        }

        CrowdTexture texture = replay.keyframes.crowdTexture.interpolate(replay.getTick(tick));

        if (texture == null)
        {
            return;
        }

        List<Link> folder = texture.random ? CrowdTextures.list(texture.folder, texture.recursive) : List.of();

        if (texture.random && folder.isEmpty())
        {
            return;
        }

        for (LivingEntity member : members)
        {
            if (!(member instanceof ActorEntity actor))
            {
                continue;
            }

            Form form = actor.getForm();

            if (form == null)
            {
                continue;
            }

            Link link = texture.random
                ? folder.get(Math.floorMod(CrowdUtils.entityIndex(member), folder.size()))
                : texture.texture;

            if (link == null)
            {
                continue;
            }

            BaseValue property = FormUtils.getProperty(form, "texture");

            if (property instanceof ValueLink value && !link.equals(value.get()))
            {
                value.set(link);
                actor.setForm(form);
                resyncForm(actor);
            }
        }
    }

    /** Tints the whole crowd together; there is no per-member colour. */
    private static void applyColor(Replay replay, List<LivingEntity> members, int tick)
    {
        if (replay.keyframes.crowdColor.isEmpty())
        {
            return;
        }

        Color color = replay.keyframes.crowdColor.interpolate(replay.getTick(tick));

        if (color == null)
        {
            return;
        }

        for (LivingEntity member : members)
        {
            if (!(member instanceof ActorEntity actor))
            {
                continue;
            }

            Form form = actor.getForm();

            if (form == null)
            {
                continue;
            }

            /* Colour is a property of the individual form types rather than of Form itself, so
             * it is looked up by name the same way the texture is. A form that has none - an
             * anchor, say - simply is not tinted. */
            BaseValue property = FormUtils.getProperty(form, "color");

            if (property instanceof ValueColor value && !value.get().equals(color))
            {
                value.set(color);
                actor.setForm(form);
                resyncForm(actor);
            }
        }
    }
}
