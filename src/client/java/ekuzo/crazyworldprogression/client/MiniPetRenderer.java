package ekuzo.crazyworldprogression.client;

import com.mojang.blaze3d.vertex.PoseStack;
import ekuzo.crazyworldprogression.collectibles.LookMount;
import ekuzo.crazyworldprogression.collectibles.MiniPet;
import ekuzo.crazyworldprogression.collectibles.MobLook;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Draws a {@link MiniPet} or a {@link LookMount} as its vanilla mob (pets smaller): every pet has an invisible copy of that mob on the client that
 * follows it (position, turning) and takes over its walk cycle and age; the vanilla renderer draws the copy scaled down
 * at the pet's place. The copy is never ticked itself: a ticking mob would push the player and play its own movement.
 * Flying looks bob a little.
 */
public final class MiniPetRenderer<T extends LivingEntity & MobLook> extends EntityRenderer<T, MiniPetRenderer.State> {
    private static final Map<LivingEntity, Entity> COPIES = new WeakHashMap<>();
    private static int nextCopyId = -2_000_000;

    public MiniPetRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    /** Gives every registered mini pet type this renderer and keeps the copies animated. */
    public static void registerAll() {
        for (EntityType<?> type : MiniPet.types()) {
            @SuppressWarnings("unchecked") EntityType<MiniPet> pet = (EntityType<MiniPet>) type;
            EntityRendererRegistry.register(pet, MiniPetRenderer::new);
        }
        for (EntityType<?> type : LookMount.types()) {
            @SuppressWarnings("unchecked") EntityType<LookMount> mount = (EntityType<LookMount>) type;
            EntityRendererRegistry.register(mount, MiniPetRenderer::new);
        }
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.level == null || client.isPaused()) return;
            COPIES.entrySet().removeIf(entry -> entry.getKey().isRemoved() || entry.getKey().level() != client.level);
            for (Map.Entry<LivingEntity, Entity> entry : COPIES.entrySet()) {
                Entity copy = entry.getValue();
                copy.setOldPosAndRot();
                follow(copy, entry.getKey());
                animate(copy, entry.getKey());
            }
        });
    }

    public static final class State extends EntityRenderState {
        EntityRenderState mob;
        float scale = 1, hover;
        /** The item model around the mob (a bubble), its size and height of its centre. */
        final ItemStackRenderState shell = new ItemStackRenderState();
        float shellSize, shellY;
    }

    @Override
    public State createRenderState() { return new State(); }

    @Override
    public void extractRenderState(T pet, State state, float partialTick) {
        super.extractRenderState(pet, state, partialTick);
        MiniPet.Look look = pet.look();
        state.mob = null;
        if (look == null) return;
        Entity copy = COPIES.computeIfAbsent(pet, key -> create(key, look));
        if (copy == null) return;
        follow(copy, pet);
        state.mob = entityRenderDispatcher.extractEntity(copy, partialTick);
        state.scale = look.scale();
        state.hover = look.hovers() ? (float) Math.sin((pet.tickCount + partialTick) * 0.12) * 0.1F : 0;
        shadowRadius = Math.max(0.15F, look.vanilla().getWidth() * look.scale() * 0.5F);
        state.shell.clear();
        if (look.shell() != null) {
            ItemStack stack = new ItemStack(Items.PAPER);
            stack.set(DataComponents.ITEM_MODEL, look.shell());
            Minecraft.getInstance().getItemModelResolver().updateForTopItem(state.shell, stack, ItemDisplayContext.NONE,
                    pet.level(), null, pet.getId());
            state.shellSize = look.shellSize();
            state.shellY = look.vanilla().getHeight() * look.scale() * 0.5F;
            shadowRadius = Math.max(shadowRadius, look.shellSize() * 0.4F);
        }
    }

    @Override
    public void submit(State state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
        if (state.mob != null) {
            poseStack.pushPose();
            poseStack.translate(0, state.hover, 0);
            poseStack.scale(state.scale, state.scale, state.scale);
            submitMob(entityRenderDispatcher, state.mob, poseStack, collector, camera);
            poseStack.popPose();
        }
        if (state.mob != null && !state.shell.isEmpty()) {
            poseStack.pushPose();
            poseStack.translate(0, state.hover + state.shellY, 0);
            poseStack.scale(state.shellSize, state.shellSize, state.shellSize);
            state.shell.submit(poseStack, collector, state.lightCoords, OverlayTexture.NO_OVERLAY, 0);
            poseStack.popPose();
        }
        super.submit(state, poseStack, collector, camera);
    }

    private static <S extends EntityRenderState> void submitMob(EntityRenderDispatcher dispatcher, S mob, PoseStack poseStack,
                                                                SubmitNodeCollector collector, CameraRenderState camera) {
        dispatcher.getRenderer(mob).submit(mob, poseStack, collector, camera);
    }

    private static Entity create(LivingEntity pet, MiniPet.Look look) {
        Entity copy = look.vanilla().create(pet.level(), EntitySpawnReason.LOAD);
        if (copy == null) return null;
        // Only the client knows the copy, so it gets an id the server never hands out.
        copy.setId(nextCopyId--);
        copy.setSilent(true);
        if (copy instanceof net.minecraft.world.entity.ambient.Bat bat) bat.setResting(false);
        if (look.baby() && copy instanceof net.minecraft.world.entity.AgeableMob ageable) ageable.setBaby(true);
        // Mounts wear a saddle (drawn by mobs that have a saddle layer).
        if (pet instanceof ekuzo.crazyworldprogression.collectibles.CwpMount && copy instanceof net.minecraft.world.entity.Mob mob)
            mob.setItemSlot(net.minecraft.world.entity.EquipmentSlot.SADDLE, new ItemStack(Items.SADDLE));
        if (copy instanceof net.minecraft.world.entity.animal.fish.TropicalFish fish) {
            // Clownfish colours: orange with white stripes.
            ((ekuzo.crazyworldprogression.mixin.client.TropicalFishVariantInvoker) fish).cwp$setPackedVariant(
                    new net.minecraft.world.entity.animal.fish.TropicalFish.Variant(
                            net.minecraft.world.entity.animal.fish.TropicalFish.Pattern.KOB,
                            net.minecraft.world.item.DyeColor.ORANGE, net.minecraft.world.item.DyeColor.WHITE).getPackedId());
        }
        follow(copy, pet);
        copy.setOldPosAndRot();
        return copy;
    }

    /** The pet's walk cycle and age (for idle animations such as tails and wings), and flapping for bats. */
    private static void animate(Entity copy, LivingEntity pet) {
        copy.tickCount = pet.tickCount;
        if (copy instanceof LivingEntity living) {
            var from = (ekuzo.crazyworldprogression.mixin.client.WalkAnimationStateAccessor) pet.walkAnimation;
            var to = (ekuzo.crazyworldprogression.mixin.client.WalkAnimationStateAccessor) living.walkAnimation;
            to.cwp$setSpeedOld(from.cwp$speedOld());
            to.cwp$setSpeed(from.cwp$speed());
            to.cwp$setPosition(from.cwp$position());
        }
        if (copy instanceof net.minecraft.world.entity.ambient.Bat bat) bat.flyAnimationState.startIfStopped(copy.tickCount);
    }

    /** Puts the copy where the pet is, turned the same way. */
    private static void follow(Entity copy, LivingEntity pet) {
        MiniPet.Look look = ((MobLook) pet).look();
        copy.setPos(pet.getX(), pet.getY(), pet.getZ());
        if (look != null && look.submerged())
            ((ekuzo.crazyworldprogression.mixin.client.EntityWaterAccessor) copy).cwp$setTouchingWater(true);
        copy.setOnGround(pet.onGround() || look != null && look.hovers());
        copy.setYRot(pet.getYRot());
        copy.setXRot(pet.getXRot());
        copy.yRotO = pet.yRotO;
        copy.xRotO = pet.xRotO;
        if (copy instanceof LivingEntity living) {
            living.yBodyRot = pet.yBodyRot;
            living.yBodyRotO = pet.yBodyRotO;
            living.yHeadRot = pet.yHeadRot;
            living.yHeadRotO = pet.yHeadRotO;
        }
    }
}
