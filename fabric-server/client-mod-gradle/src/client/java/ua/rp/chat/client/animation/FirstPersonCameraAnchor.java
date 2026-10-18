package ua.rp.chat.client.animation;

import org.joml.Vector3f;
import org.joml.Vector3fc;

/** Converts the rendered neck pivot to an eye offset without adding authored vertical bob. */
public final class FirstPersonCameraAnchor {
    private static final double MODEL_PIXEL = .9375 / 16;

    private FirstPersonCameraAnchor() {}

    public static Vector3f offset(Vector3fc neck, double yaw, double pitch,
                                  boolean crouching, double entityScale) {
        if (!Double.isFinite(yaw)) yaw = 0;
        if (!Double.isFinite(pitch)) pitch = 0;
        if (!Double.isFinite(entityScale) || entityScale <= 0) entityScale = 1;
        boolean animated = neck != null && Float.isFinite(neck.x()) && Float.isFinite(neck.z());
        double inspect = Math.max(0, Math.min(1, (pitch - 20) / 60));
        inspect = inspect * inspect * (3 - 2 * inspect);
        double forward = .20 + inspect * .05;
        // The authored crouch contains its own forward lean. Native poses still need the old offset.
        if (crouching && !animated) forward += .34;
        double down = .02 + inspect * .03 + (crouching ? .02 : 0);
        double radians = Math.toRadians(yaw), sin = Math.sin(radians), cos = Math.cos(radians);
        double x = -sin * forward, z = cos * forward;
        if (animated) {
            // AvatarRenderer: .9375 scale, native model Y down and model -Z facing forward.
            double pixel = MODEL_PIXEL * entityScale;
            // Preserve the stable eye position when a stop/turn leans the neck behind the torso.
            double forwardNeckZ = Math.min(0, neck.z());
            x += (neck.x() * cos + forwardNeckZ * sin) * pixel;
            z += (neck.x() * sin - forwardNeckZ * cos) * pixel;
        }
        return new Vector3f((float)x, (float)-down, (float)z);
    }
}
