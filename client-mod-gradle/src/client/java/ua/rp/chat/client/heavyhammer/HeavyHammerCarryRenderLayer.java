package ua.rp.chat.client.heavyhammer;

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
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix3f;
import org.joml.Quaternionf;
import ua.rp.chat.HeavyHammerHolsterLayout;
import ua.rp.chat.HeavyHammerProceduralMotion;
import ua.rp.chat.BreathingTorsoLayout;
import ua.rp.chat.client.render.BreathingPoseState;

/** Рисует один экземпляр молота во всём цикле: подвес, передача и рабочий хват. */
public final class HeavyHammerCarryRenderLayer extends RenderLayer<AvatarRenderState, PlayerModel> {
    private final ItemModelResolver itemModelResolver;
    private ItemStack holsterStack;

    private HeavyHammerCarryRenderLayer(RenderLayerParent<AvatarRenderState, PlayerModel> parent,
                                        ItemModelResolver itemModelResolver) {
        super(parent);
        this.itemModelResolver = itemModelResolver;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static void register() {
        LivingEntityRenderLayerRegistrationCallback.EVENT.register((type, renderer, helper, context) -> {
            if (type != EntityType.PLAYER || !(renderer.getModel() instanceof PlayerModel)) return;
            helper.register((RenderLayer) new HeavyHammerCarryRenderLayer(
                    (RenderLayerParent<AvatarRenderState, PlayerModel>) (RenderLayerParent) renderer,
                    context.getItemModelResolver()));
        });
    }

    @Override
    public void submit(PoseStack poseStack, SubmitNodeCollector collector, int light,
                       AvatarRenderState state, float yRot, float xRot) {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null) return;
        Entity entity = client.level.getEntity(state.id);
        if (!(entity instanceof Player player)) return;
        boolean holsterEquipped = HammerHolsterClientState.isEquipped(player);
        HeavyHammerClientState.Visual visual = HeavyHammerClientState.visualFor(player, state.ageInTicks);
        if (!holsterEquipped && visual == null) return;

        if (holsterEquipped) {
            submitHolster(player, poseStack, collector, light, state.outlineColor,
                    visual == null ? 1.0f : visual.carry().latchClosed(), state);
        }
        if (visual == null) return;
        if (!holsterEquipped && !HeavyHammerClientState.isHolding(player.getMainHandItem())) return;
        submitHammer(player, visual.hammer(), visual.frame(), poseStack, collector,
                light, state.outlineColor);
    }

    private void submitHammer(Player player, ItemStack stack, HeavyHammerProceduralMotion.Frame frame,
                              PoseStack poseStack, SubmitNodeCollector collector, int light, int outline) {
        ItemStackRenderState renderState = new ItemStackRenderState();
        itemModelResolver.updateForLiving(renderState, stack, ItemDisplayContext.THIRD_PERSON_RIGHT_HAND, player);
        if (renderState.isEmpty()) return;

        Matrix3f basis = new Matrix3f()
                .setColumn(0, frame.headAxis().x(), frame.headAxis().y(), frame.headAxis().z())
                .setColumn(1, frame.shaft().x(), frame.shaft().y(), frame.shaft().z())
                .setColumn(2, frame.depthAxis().x(), frame.depthAxis().y(), frame.depthAxis().z());
        Quaternionf rotation = new Quaternionf().setFromNormalized(basis);

        poseStack.pushPose();
        poseStack.translate(frame.mainGrip().x() / 16.0f,
                frame.mainGrip().y() / 16.0f, frame.mainGrip().z() / 16.0f);
        poseStack.mulPose(rotation);
        renderState.submit(poseStack, collector, light, 0, outline);
        poseStack.popPose();
    }

    private void submitHolster(Player player, PoseStack poseStack, SubmitNodeCollector collector,
                               int light, int outline, float latchClosed, AvatarRenderState state) {
        // ItemStack нельзя создавать при загрузке класса: в этот момент Minecraft
        // ещё не привязал компоненты реестра. Первый submit выполняется уже после
        // полной инициализации клиента и является безопасной точкой.
        if (holsterStack == null) holsterStack = createHolsterStack();
        ItemStackRenderState holster = new ItemStackRenderState();
        itemModelResolver.updateForLiving(holster, holsterStack, ItemDisplayContext.FIXED, player);
        if (holster.isEmpty()) return;
        poseStack.pushPose();
        // Центр портупеи совмещён с торсом; fixed-поворот самой модели зеркалит
        // капелу к правому бедру. Защёлка даёт только едва заметную посадку по Z.
        // The item layer starts at the player root while the shirt is animated
        // from PlayerModel.body. Apply that exact body bone first: otherwise a
        // leaning, breathing or sprinting torso moves through a static holster.
        PlayerModel model = getParentModel();
        if (model == null || model.body == null) {
            poseStack.popPose();
            return;
        }
        model.body.translateAndRotate(poseStack);

        // Apply Left-Shoulder Pivot Yaw Compensation to prevent thigh clipping
        float yaw = model.body.yRot;
        float xShoulder = 2.0f / 16.0f; // left shoulder pivot
        poseStack.translate(xShoulder, 0.0f, 0.0f);
        poseStack.mulPose(new Quaternionf().rotationY(-0.45f * yaw));
        poseStack.translate(-xShoulder, 0.0f, 0.0f);

        // Apply Dynamic Breathing and Jacket scaling to prevent chest/back clipping
        BreathingPoseState.Sample breath = BreathingPoseState.sample(state);
        float calm = breath.calm();
        boolean firstPerson = breath.firstPerson();
        var respiration = breath.respiration();

        float grow = state.showJacket ? BreathingTorsoLayout.OUTER_LAYER_GROW : 0.0f;

        // Calculate breathing expansion at Y = 6.0 (middle of the torso)
        float height01 = 0.5f;
        float regionalBreath = BreathingTorsoLayout.regionalBreath(respiration.phase(), height01);
        float amplitude = BreathingTorsoLayout.amplitude(respiration.intensity(), calm, firstPerson);
        float weighted = amplitude * BreathingTorsoLayout.profile(3) * regionalBreath; // profile at ring 3 is 0.78f

        float side = weighted * 0.72f;
        float front = weighted;
        float back = weighted * (0.62f + height01 * 0.18f);

        float baseWidth = 8.0f + 2.0f * grow;
        float baseDepth = 4.0f + 2.0f * grow;

        float dynamicWidth = baseWidth + 2.0f * side;
        float dynamicDepth = baseDepth + front + back;

        // Holster model is designed for jacket thickness (grow = 0.25)
        float refWidth = 8.0f + 2.0f * BreathingTorsoLayout.OUTER_LAYER_GROW; // 8.5f
        float refDepth = 4.0f + 2.0f * BreathingTorsoLayout.OUTER_LAYER_GROW; // 4.5f

        float scaleX = dynamicWidth / refWidth;
        float scaleZ = dynamicDepth / refDepth;

        poseStack.scale(scaleX, 1.0f, scaleZ);

        HeavyHammerHolsterLayout.Point attachment = HeavyHammerHolsterLayout.bodyAttachment(latchClosed);
        poseStack.translate(attachment.x() / 16.0f, attachment.y() / 16.0f, attachment.z() / 16.0f);
        holster.submit(poseStack, collector, light, 0, outline);
        poseStack.popPose();
    }

    private static ItemStack createHolsterStack() {
        return HammerHolsterClientState.createStack();
    }
}
