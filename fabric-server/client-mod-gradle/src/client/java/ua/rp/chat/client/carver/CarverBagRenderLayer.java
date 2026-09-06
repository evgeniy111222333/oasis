package ua.rp.chat.client.carver;

import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.fabric.api.client.rendering.v1.LivingEntityRenderLayerRegistrationCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/**
 * Mason's bag worn on the back and tools held during carving:
 * 1. The chest slot holds the bag, riding the body bone on the back.
 * 2. When the artisan starts carving, tools dynamically pull out of the bag:
 *    - Phase 1 (tick 0..11): Left hand reaches back, grabs Point Chisel. At tick 11,
 *      it vanishes from the bag loop and appears in the left hand.
 *    - Phase 2 (tick 12..23): Right hand reaches back, grabs Flat Chisel. At tick 23,
 *      it vanishes from the right bag loop and appears in the right hand.
 *    - Phase 3 (tick 24+): Both tools held in hands for stone-cutting strikes,
 *      while both loops on the backpack remain empty until work completes.
 */
public final class CarverBagRenderLayer extends RenderLayer<AvatarRenderState, PlayerModel> {
    /** Scale fitting the bag onto the torso. */
    private static final float BAG_SCALE = 0.72f;
    /** Anchor on the mid-upper back in body-bone space (Y down from neck, Z back from spine). */
    private static final float ANCHOR_Y = 6.0f / 16.0f;
    private static final float ANCHOR_Z = 2.80f / 16.0f;

    private final ItemModelResolver resolver;

    private CarverBagRenderLayer(RenderLayerParent<AvatarRenderState, PlayerModel> parent,
                                 ItemModelResolver resolver) {
        super(parent);
        this.resolver = resolver;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static void register() {
        LivingEntityRenderLayerRegistrationCallback.EVENT.register((type, renderer, helper, context) -> {
            if (type != EntityType.PLAYER || !(renderer.getModel() instanceof PlayerModel)) return;
            helper.register((RenderLayer) new CarverBagRenderLayer(
                    (RenderLayerParent<AvatarRenderState, PlayerModel>) (RenderLayerParent) renderer,
                    context.getItemModelResolver()));
        });
    }

    /** Work clock in smooth client ticks, or -1.0 when idle. */
    public static double getWorkTicks(Player player, Minecraft client) {
        if (player == null || client == null) return -1.0;
        try {
            if (player == client.player) {
                if (CarverClientState.working()) {
                    return CarverClientState.smoothWorkTicks();
                }
            } else {
                CarverClientState.ObservedWork observed =
                        CarverClientState.observedWork(player.getUUID());
                if (observed != null) {
                    return CarverClientState.smoothSince(observed.startClientTick());
                }
            }
        } catch (RuntimeException ignored) {
        }
        return -1.0;
    }

    private static double strikeCycle(Player player, Minecraft client, double workTicks) {
        try {
            int total;
            if (player == client.player) {
                total = Math.max(1, CarverClientState.workTotalTicks());
            } else {
                CarverClientState.ObservedWork observed =
                        CarverClientState.observedWork(player.getUUID());
                if (observed == null) return 0.0;
                total = Math.max(1, observed.totalTicks());
            }
            return ua.rp.chat.carver.CarverWorkStroke.cycleOf(workTicks, total);
        } catch (RuntimeException unreadable) {
            return 0.0;
        }
    }

    private static double strikeLift(Player player, Minecraft client, double workTicks) {
        try {
            return ua.rp.chat.carver.CarverWorkStroke.lift(strikeCycle(player, client, workTicks));
        } catch (RuntimeException unreadable) {
            return 0.0;
        }
    }

    private static double strikeContact(Player player, Minecraft client, double workTicks) {
        try {
            return ua.rp.chat.carver.CarverWorkStroke.contact(strikeCycle(player, client, workTicks));
        } catch (RuntimeException unreadable) {
            return 0.0;
        }
    }

    @Override
    public void submit(PoseStack stack, SubmitNodeCollector collector, int light,
                       AvatarRenderState state, float yRot, float xRot) {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null || !(client.level.getEntity(state.id) instanceof Player player)) {
            return;
        }
        ItemStack bag;
        try {
            bag = player.getItemBySlot(EquipmentSlot.CHEST);
            if (bag == null || bag.isEmpty()
                    || !bag.is(ua.rp.chat.carver.CarverItems.BAG)) {
                return;
            }
        } catch (RuntimeException unreadable) {
            return;
        }

        double workTicks = getWorkTicks(player, client);

        // 1. Render backpack on the spine (with dynamic tool disappearance)
        ItemStack displayBag;
        if (workTicks >= 23.0) {
            displayBag = new ItemStack(ua.rp.chat.carver.CarverItems.BAG_EMPTY);
        } else if (workTicks >= 11.0) {
            displayBag = new ItemStack(ua.rp.chat.carver.CarverItems.BAG_NO_POINT);
        } else {
            displayBag = bag;
        }

        ItemStackRenderState bagRender = new ItemStackRenderState();
        try {
            resolver.updateForLiving(bagRender, displayBag, ItemDisplayContext.NONE, player);
        } catch (RuntimeException unreadable) {
            return;
        }
        if (!bagRender.isEmpty()) {
            stack.pushPose();
            try {
                getParentModel().body.translateAndRotate(stack);
                stack.translate(0.0, ANCHOR_Y, ANCHOR_Z);
                stack.mulPose(com.mojang.math.Axis.ZP.rotationDegrees(180.0f));
                stack.scale(BAG_SCALE, BAG_SCALE, BAG_SCALE);
                bagRender.submit(stack, collector, light, 0, state.outlineColor);
            } catch (RuntimeException unreadable) {
            } finally {
                stack.popPose();
            }
        }

        // 2. Render Point Chisel in left hand once drawn (workTicks >= 11.0)
        if (workTicks >= 11.0) {
            ItemStackRenderState leftToolRender = new ItemStackRenderState();
            try {
                resolver.updateForLiving(leftToolRender,
                        new ItemStack(ua.rp.chat.carver.CarverItems.CHISEL_POINT),
                        ItemDisplayContext.THIRD_PERSON_LEFT_HAND, player);
            } catch (RuntimeException unreadable) {
                leftToolRender = null;
            }
            if (leftToolRender != null && !leftToolRender.isEmpty()) {
                stack.pushPose();
                try {
                    double contact = strikeContact(player, client, workTicks);
                    getParentModel().translateToHand(state, HumanoidArm.LEFT, stack);
                    stack.mulPose(com.mojang.math.Axis.XP.rotationDegrees((float) (-90.0f + contact * 8.0f)));
                    stack.mulPose(com.mojang.math.Axis.YP.rotationDegrees(180.0f));
                    stack.mulPose(com.mojang.math.Axis.ZP.rotationDegrees(180.0f));
                    stack.translate(-1.3f / 16.0f, 2.0f / 16.0f, -10.0f / 16.0f);
                    leftToolRender.submit(stack, collector, light, 0, state.outlineColor);
                } catch (RuntimeException unreadable) {
                } finally {
                    stack.popPose();
                }
            }
        }

        // 3. Render Flat Chisel / Striker in right hand once drawn (workTicks >= 23.0)
        if (workTicks >= 23.0) {
            ItemStackRenderState rightToolRender = new ItemStackRenderState();
            try {
                resolver.updateForLiving(rightToolRender,
                        new ItemStack(ua.rp.chat.carver.CarverItems.CHISEL_FLAT),
                        ItemDisplayContext.THIRD_PERSON_RIGHT_HAND, player);
            } catch (RuntimeException unreadable) {
                rightToolRender = null;
            }
            if (rightToolRender != null && !rightToolRender.isEmpty()) {
                stack.pushPose();
                try {
                    double lift = strikeLift(player, client, workTicks);
                    double contact = strikeContact(player, client, workTicks);
                    getParentModel().translateToHand(state, HumanoidArm.RIGHT, stack);
                    stack.mulPose(com.mojang.math.Axis.XP.rotationDegrees((float) (-90.0f - lift * 55.0f + contact * 14.0f)));
                    stack.mulPose(com.mojang.math.Axis.YP.rotationDegrees(180.0f));
                    stack.mulPose(com.mojang.math.Axis.ZP.rotationDegrees(180.0f));
                    stack.translate(1.3f / 16.0f, (float) (2.0f / 16.0f + lift * 4.0f / 16.0f), -10.0f / 16.0f);
                    rightToolRender.submit(stack, collector, light, 0, state.outlineColor);
                } catch (RuntimeException unreadable) {
                } finally {
                    stack.popPose();
                }
            }
        }
    }
}
