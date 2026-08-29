package mchorse.bbs_mod.entity;

import mchorse.bbs_mod.forms.entities.MCEntity;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.network.ServerNetwork;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.packet.s2c.play.ItemPickupAnimationS2CPacket;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Arm;
import net.minecraft.util.math.Box;
import net.minecraft.world.World;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import mchorse.bbs_mod.BBSSettings;

import net.minecraft.block.BlockRenderType;

import net.minecraft.block.BlockState;

import net.minecraft.particle.BlockStateParticleEffect;

import net.minecraft.particle.ParticleTypes;

import net.minecraft.util.math.BlockPos;

public class ActorEntity extends LivingEntity implements IEntityFormProvider
{
    public static DefaultAttributeContainer.Builder createActorAttributes()
    {
        return LivingEntity.createLivingAttributes()
            .add(EntityAttributes.GENERIC_ATTACK_DAMAGE, 1D)
            .add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.1D)
            .add(EntityAttributes.GENERIC_ATTACK_SPEED)
            .add(EntityAttributes.GENERIC_LUCK);
    }

    private boolean despawn;
    private MCEntity entity = new MCEntity(this);
    private Form form;

    private Map<EquipmentSlot, ItemStack> equipment = new HashMap<>();

    public ActorEntity(EntityType<? extends LivingEntity> entityType, World world)
    {
        super(entityType, world);
    }

    public MCEntity getEntity()
    {
        return this.entity;
    }

    @Override
    public int getEntityId()
    {
        return this.getId();
    }

    @Override
    public Form getForm()
    {
        return this.form;
    }

    @Override
    public void setForm(Form form)
    {
        Form lastForm = this.form;

        this.form = form;

        if (!this.getWorld().isClient())
        {
            if (lastForm != null) lastForm.onDemorph(this);
            if (form != null) form.onMorph(this);
        }
    }

    @Override
    public boolean shouldRender(double distance)
    {
        double d = this.getBoundingBox().getAverageSideLength();

        if (Double.isNaN(d))
        {
            d = 1D;
        }

        return distance < (d * 256D) * (d * 256D);
    }

    @Override
    public Iterable<ItemStack> getHandItems()
    {
        return List.of(this.getEquippedStack(EquipmentSlot.MAINHAND), this.getEquippedStack(EquipmentSlot.OFFHAND));
    }

    @Override
    public Iterable<ItemStack> getArmorItems()
    {
        return List.of(this.getEquippedStack(EquipmentSlot.FEET), this.getEquippedStack(EquipmentSlot.LEGS), this.getEquippedStack(EquipmentSlot.CHEST), this.getEquippedStack(EquipmentSlot.HEAD));
    }

    @Override
    public ItemStack getEquippedStack(EquipmentSlot slot)
    {
        return this.equipment.getOrDefault(slot, ItemStack.EMPTY);
    }

    @Override
    public void equipStack(EquipmentSlot slot, ItemStack stack)
    {
        this.equipment.put(slot, stack == null ? ItemStack.EMPTY : stack);
    }

    @Override
    public Arm getMainArm()
    {
        return Arm.RIGHT;
    }

    /**
     * The scuff of blocks under a sprinting actor.
     *
     * <p>Vanilla spawns these from {@code Entity#move}, and an actor never moves: its position is
     * written straight from the keyframes each tick, so the code that would have noticed the
     * sprint never runs. The flag is set on the actor and looks right in every other way, which is
     * why the particles were the one part of sprinting that never appeared.</p>
     *
     * <p>Speed is taken from the step just travelled rather than from the velocity, for the same
     * reason - a teleported entity has none. Off by default, since a crowd of sprinting actors is a
     * great many particles.</p>
     *
     * <p>Deliberately not an override of {@code Entity#spawnSprintingParticles}: were an actor ever
     * moved rather than placed, vanilla would call that one too and the scuff would come out at
     * double rate.</p>
     */
    private void spawnSprintScuff()
    {
        if (!BBSSettings.sprintParticles.get() || !this.isSprinting() || !this.isOnGround())
        {
            return;
        }

        double dx = this.getX() - this.prevX;
        double dz = this.getZ() - this.prevZ;

        /* Sprinting on the spot is still standing still, and vanilla would not scuff either. */
        if (dx * dx + dz * dz < 0.0025D)
        {
            return;
        }

        BlockPos below = BlockPos.ofFloored(this.getX(), this.getY() - 0.2D, this.getZ());
        BlockState state = this.getWorld().getBlockState(below);

        if (state.getRenderType() == BlockRenderType.INVISIBLE)
        {
            return;
        }

        double width = this.getWidth();

        this.getWorld().addParticle(new BlockStateParticleEffect(ParticleTypes.BLOCK, state),
            this.getX() + (this.random.nextDouble() - 0.5D) * width,
            this.getY() + 0.1D,
            this.getZ() + (this.random.nextDouble() - 0.5D) * width,
            dx * -4.0D, 1.5D, dz * -4.0D);
    }

    @Override
    public void tick()
    {
        super.tick();

        this.tickHandSwing();

        if (this.form != null)
        {
            this.form.update(this.entity);
        }

        if (this.getWorld().isClient)
        {
            this.spawnSprintScuff();

            return;
        }

        /* Pickup items */
        Box box = this.getBoundingBox().expand(1D, 0.5D, 1D);
        List<Entity> list = this.getWorld().getOtherEntities(this, box);

        for (Entity entity : list)
        {
            if (entity instanceof ItemEntity itemEntity)
            {
                ItemStack itemStack = itemEntity.getStack();
                int i = itemStack.getCount();

                if (!entity.isRemoved() && !itemEntity.cannotPickup())
                {
                    ((ServerWorld) this.getWorld()).getChunkManager().sendToOtherNearbyPlayers(entity, new ItemPickupAnimationS2CPacket(entity.getId(), this.getId(), i));
                    entity.discard();
                }
            }
        }
    }

    @Override
    public void checkDespawn()
    {
        super.checkDespawn();

        if (this.despawn)
        {
            this.discard();
        }
    }

    @Override
    public void onStartedTrackingBy(ServerPlayerEntity player)
    {
        super.onStartedTrackingBy(player);

        ServerNetwork.sendEntityForm(player, this);
    }

    @Override
    public void readCustomDataFromNbt(NbtCompound nbt)
    {
        super.readCustomDataFromNbt(nbt);

        this.despawn = nbt.getBoolean("despawn");
    }

    @Override
    public void writeCustomDataToNbt(NbtCompound nbt)
    {
        super.writeCustomDataToNbt(nbt);

        nbt.putBoolean("despawn", true);
    }

    @Override
    protected int getPermissionLevel()
    {
        return 4;
    }
}