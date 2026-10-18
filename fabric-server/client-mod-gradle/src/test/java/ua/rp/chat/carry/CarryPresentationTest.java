package ua.rp.chat.carry;

import ua.rp.chat.client.carry.CarryFeedback;
import ua.rp.chat.client.carry.CarryMath;
import ua.rp.chat.client.carry.CarryPoseMath;
import ua.rp.chat.client.carry.CarryPoseRenderer;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.world.item.ItemDisplayContext;
import ua.rp.chat.microvoxel.MicrovoxelItemScale;
import ua.rp.chat.microvoxel.MicrovoxelVisualShape;
import java.util.List;
import java.util.Map;

/** Exercises the same timing, ray and pose maths used by the live client, without Minecraft. */
public final class CarryPresentationTest {
    public static void main(String[] args) {
        require(CarryFeedback.U >= 0 && CarryFeedback.V >= 0
                && CarryFeedback.U + CarryFeedback.SOURCE_WIDTH <= CarryFeedback.TEXTURE_SIZE
                && CarryFeedback.V + CarryFeedback.SOURCE_HEIGHT <= CarryFeedback.TEXTURE_SIZE,
                "Concept 01 texture region leaves the source image");
        float reference = -1;
        for (int fps : new int[]{15, 20, 30, 60, 144, 240}) {
            CarryFeedback feedback = new CarryFeedback();
            feedback.sample(true, 0);
            for (int i = 1; i <= fps; i++) feedback.sample(true, i / (double) fps);
            float alpha = feedback.sample(true, 1).alpha();
            require(alpha > .85 && alpha <= 1, "Focus did not settle");
            if (reference >= 0) require(Math.abs(alpha - reference) < .00001, "FPS-dependent fade");
            reference = alpha;
            feedback.press(1);
            var pressed = feedback.sample(true, 1.09);
            require(pressed.press() > .9 && pressed.scale() < .96, "Press feedback is static");
            require(feedback.sample(true, 1.4).press() == 0, "Press feedback never releases");
            for (int i = 1; i <= fps; i++) feedback.sample(false, 1.4 + i / (double) fps);
            require(feedback.sample(false, 2.4).alpha() < .001, "Lost focus never fades out");
            var invalid = feedback.sample(false, Double.NaN);
            require(Float.isFinite(invalid.alpha()), "Invalid clock poisoned animation");
        }
        CarryFeedback feedback = new CarryFeedback();
        feedback.sample(true, 0);
        float a = feedback.sample(true, .1).alpha();
        float b = feedback.sample(false, .11).alpha();
        require(b > 0 && b < a, "Focus interruption snaps instead of reversing smoothly");
        require(CarryMath.snapYaw(92) == 90 && CarryMath.snapYaw(-91) == 270
                && CarryMath.snapYaw(360) == 0 && CarryMath.snapYaw(Float.NaN) == 0,
                "Preview/server quarter turns disagree");
        for (int yaw : new int[]{0, 90, 180, 270}) {
            // Native fixed DisplayRenderer uses rotationYXZ(-entityYRot, entityXRot, 0).
            var original = new org.joml.Vector3f(.21f, .17f, .43f);
            var expected = new org.joml.Quaternionf().rotationY((float) Math.toRadians(-yaw))
                    .transform(new org.joml.Vector3f(original));
            var actual = CarryMath.previewRotation(yaw).transform(new org.joml.Vector3f(original));
            require(actual.distance(expected) < .000001, "Asymmetric preview turns opposite to native display at " + yaw);
            var anchor = CarryTransforms.anchor(0, 0, 0, yaw, false);
            double radians = Math.toRadians(yaw);
            require(Math.abs(anchor.x() + Math.sin(radians) * .85) < .000001
                    && Math.abs(anchor.z() - Math.cos(radians) * .85) < .000001,
                    "Carried projection detached from the interpolated player");
            require(anchor.y() + 1 < 1.62, "Standing carried object crosses eye height");
            require(CarryTransforms.anchor(0, 0, 0, yaw, true).y() + 1 < 1.27,
                    "Crouching raises carried object above the eyes");
        }
        require(Math.abs(CarryMath.rayBox(0, .2, 0, 0, 0, 1, -.2, 0, 1, .2, .4, 1.4, 3) - 1) < .000001,
                "Visible prop not targeted");
        require(Double.isInfinite(CarryMath.rayBox(0, .2, 0, 0, 0, 1, -.2, 0, 1, .2, .4, 1.4, .9)),
                "Prop targeted through wall");
        require(Double.isInfinite(CarryMath.rayBox(0, .2, 0, 0, 0, 1, 1, 0, 1, 2, .4, 1.4, 3)),
                "Parallel ray selected an adjacent prop");
        for (int i = 0; i < 300; i++) {
            var p = CarryPoseMath.sample(true, false, false, i / 60.0);
            require(p.left().pitch() < -.5 && p.right().pitch() < -.5, "Large prop leaves one hand free");
            require(Math.abs(p.left().roll() + p.right().roll()) < .00001, "Two-hand support is not symmetric");
            require(p.left().elbow() < -.2 && p.right().elbow() < -.2, "Forearms do not cradle the prop");
        }
        var right = CarryPoseMath.sample(false, false, false, 0);
        var left = CarryPoseMath.sample(false, true, false, 0);
        require(right.right().active() && !right.left().active() && left.left().active() && !left.right().active(),
                "Palm pose ignores the player's main arm");
        require(CarryPoseMath.sample(false, false, true, 0).right().pitch() < right.right().pitch(),
                "Showing gesture does not raise the held object");
        var sleeve = new ModelPart(List.of(), Map.of());
        var hand = new ModelPart(List.of(), Map.of());
        var lower = new ModelPart(List.of(), Map.of("eclipse_hand", hand, "eclipse_forearm_sleeve", sleeve));
        var arm = new ModelPart(List.of(), Map.of("eclipse_forearm", lower));
        arm.setPos(-5, 2, 0); lower.setPos(0, 4, 0); hand.setPos(0, 4, 0);
        CarryPoseRenderer.applyArm(arm, right.right(), 1);
        require(Math.abs(lower.xRot - right.right().elbow()) < .00001 && arm.x == -5 && lower.y == 4,
                "Carry overlay changes bind pivots or ignores actual forearm");
        require(sleeve.xRot == 0 && sleeve.y == 0, "Sleeve receives elbow transform twice");
        require(Math.abs(hand.yRot - right.right().wrist()) < .00001, "Actual hand misses palm pose");
        require(MicrovoxelItemScale.presentation(ItemDisplayContext.NONE,
                new MicrovoxelVisualShape.Bounds(0, 0, 0, 1, 1, 1)).scale() == 1,
                "A carried sculpture is shrunk instead of retaining its world size");
        System.out.println("CarryPresentationTest: 6 frame rates, reversible animation, asset UVs, occlusion, rotation and articulated carry poses passed");
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
