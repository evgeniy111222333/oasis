package ua.rp.chat.client.blood;

import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.fabric.api.client.rendering.v1.LivingEntityRenderLayerRegistrationCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import ua.rp.chat.projectile.DirectArrowGeometry;

/** Renders a detailed voxel arrow on the exact animated body bone owning the wound. */
public final class EmbeddedArrowRenderLayer extends RenderLayer<AvatarRenderState, PlayerModel> {
    private static final Identifier MODEL = Identifier.fromNamespaceAndPath("eclipseclient", "embedded_arrow");
    private final ItemModelResolver resolver;
    private ItemStack arrow;

    private EmbeddedArrowRenderLayer(RenderLayerParent<AvatarRenderState, PlayerModel> parent,
                                     ItemModelResolver resolver) {
        super(parent);
        this.resolver = resolver;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static void register() {
        LivingEntityRenderLayerRegistrationCallback.EVENT.register((type, renderer, helper, context) -> {
            if (type != EntityType.PLAYER || !(renderer.getModel() instanceof PlayerModel)) return;
            helper.register((RenderLayer) new EmbeddedArrowRenderLayer(
                    (RenderLayerParent<AvatarRenderState, PlayerModel>) (RenderLayerParent) renderer,
                    context.getItemModelResolver()));
        });
    }

    @Override
    public void submit(PoseStack stack, SubmitNodeCollector collector, int light,
                       AvatarRenderState state, float yRot, float xRot) {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null || !(client.level.getEntity(state.id) instanceof Player player)) return;
        var arrows = BloodFxClientState.skinWounds(player.getUUID()).stream()
                .filter(wound -> (wound.flags() & BloodFxPayload.FLAG_EMBEDDED_PROJECTILE) != 0
                        && wound.profile() == 1)
                .limit(6)
                .toList();
        if (arrows.isEmpty()) return;
        if (arrow == null) {
            arrow = new ItemStack(Items.ARROW);
            arrow.set(DataComponents.ITEM_MODEL, MODEL);
        }
        ItemStackRenderState render = new ItemStackRenderState();
        resolver.updateForLiving(render, arrow, ItemDisplayContext.NONE, player);
        if (render.isEmpty()) return;

        for (BloodFxClientState.SkinWound wound : arrows) {
            stack.pushPose();
            attachToBone(stack, getParentModel(), wound);
            Vec3 direction = localOutward(player, wound);
            stack.mulPose(new Quaternionf().rotationTo(new Vector3f(0, 0, 1),
                    new Vector3f((float) direction.x, (float) direction.y, (float) direction.z)));
            float wobbleAge = Math.max(0.0f, wound.ageTicks() + state.ageInTicks % 1.0f);
            float wobble = (float) (Math.sin(wobbleAge * 1.55 + (wound.seed() & 31) * 0.17)
                    * Math.toRadians(4.5) * Math.exp(-wobbleAge / 8.0));
            stack.mulPose(new Quaternionf().rotationX(wobble));
            float penetration = Math.max(0.025f, Math.min(0.50f, wound.penetrationDepth()));
            stack.translate(0.0, 0.0, -penetration);
            stack.scale(DirectArrowGeometry.MODEL_SCALE, DirectArrowGeometry.MODEL_SCALE,
                    DirectArrowGeometry.MODEL_SCALE);
            stack.translate(-DirectArrowGeometry.SOURCE_CENTER_X,
                    -DirectArrowGeometry.SOURCE_CENTER_Y,
                    -DirectArrowGeometry.SOURCE_TIP_Z);
            render.submit(stack, collector, light, 0, state.outlineColor);
            stack.popPose();
        }
    }

    private static void attachToBone(PoseStack stack, PlayerModel model,
                                     BloodFxClientState.SkinWound wound) {
        ModelPart bone = switch (wound.zone()) {
            case 0 -> model.head;
            case 1 -> model.body;
            case 2 -> model.leftArm;
            case 3 -> model.rightArm;
            case 4 -> model.leftLeg;
            default -> model.rightLeg;
        };
        bone.translateAndRotate(stack);
        boolean limb = wound.zone() >= 2;
        float globalY = (1.0f - wound.height()) * (wound.zone() == 0 ? 8.0f : 12.0f);
        if (limb && globalY >= 6.0f) {
            ModelPart lower = child(bone, wound.zone() <= 3 ? "eclipse_forearm" : "eclipse_shin");
            if (lower != null) {
                lower.translateAndRotate(stack);
                globalY -= 6.0f;
            }
        }
        float halfWidth = wound.zone() == 1 ? 4.0f : wound.zone() == 0 ? 4.0f : 2.0f;
        float halfDepth = wound.zone() == 0 ? 4.0f : 2.0f;
        float x = wound.side() * Math.max(0.5f, halfWidth - 0.45f);
        float z = 0.0f;
        switch (wound.face()) {
            case 1 -> z = halfDepth;
            case 2 -> x = -halfWidth;
            case 3 -> x = halfWidth;
            default -> z = -halfDepth;
        }
        stack.translate(x / 16.0f, globalY / 16.0f, z / 16.0f);
    }

    private static Vec3 localOutward(Player player, BloodFxClientState.SkinWound wound) {
        Vec3 incoming = wound.direction();
        if (incoming == null || incoming.lengthSqr() < 1.0e-5) {
            return switch (wound.face()) {
                case 1 -> new Vec3(0, 0, 1);
                case 2 -> new Vec3(-1, 0, 0);
                case 3 -> new Vec3(1, 0, 0);
                default -> new Vec3(0, 0, -1);
            };
        }
        Vec3 outward = incoming.normalize().scale(-1.0);
        double yaw = Math.toRadians(player.yBodyRot);
        double cos = Math.cos(-yaw);
        double sin = Math.sin(-yaw);
        Vec3 local = new Vec3(outward.x * cos - outward.z * sin, outward.y,
                outward.x * sin + outward.z * cos);
        return local.lengthSqr() < 1.0e-5 ? new Vec3(0, 0, -1) : local.normalize();
    }

    private static ModelPart child(ModelPart parent, String name) {
        try {
            return parent.getChild(name);
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}
