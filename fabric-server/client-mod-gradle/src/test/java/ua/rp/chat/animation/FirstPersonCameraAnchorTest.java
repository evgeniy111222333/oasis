package ua.rp.chat.animation;

import org.joml.Vector3f;
import ua.rp.chat.client.animation.FirstPersonCameraAnchor;
import ua.rp.chat.client.animation.PlayerTorsoSkeleton;

/** Reconstructs the actual animated torso and the downward view without starting Minecraft. */
public final class FirstPersonCameraAnchorTest {
    private static final float PIXEL = .9375f / 16;
    private static int frames;

    public static void main(String[] args) throws Exception {
        nativeFallback();
        rotationsAndScale();
        invalidInputs();
        try (var stream = FirstPersonCameraAnchorTest.class.getResourceAsStream(
                "/assets/eclipseclient/animations/rp_character_v3.animation.json")) {
            AnimationLibrary library = AnimationLibrary.load(stream);
            int oldIntersections = 0;
            for (String clip : library.clipNames()) {
                for (int i = 0; i <= 120; i++) {
                    var pose = library.sample(clip, library.length(clip) * i / 120.0);
                    boolean crouch = clip.startsWith("sneak");
                    var skeleton = new PlayerTorsoSkeleton(pose);
                    skeleton.groundFootContacts(pose, crouch);
                    Vector3f originalNeck = new Vector3f(skeleton.neck);
                    for (double yaw : new double[]{0, 90, 180, -90, 37}) {
                        for (double scale : new double[]{.5, 1, 1.8}) {
                            for (double pitch : new double[]{0, 45, 80, 90}) {
                                Vector3f camera = FirstPersonCameraAnchor.offset(
                                        skeleton.neck, yaw, pitch, crouch, scale);
                                double angle = Math.toRadians(yaw);
                                double cameraForward = -camera.x * Math.sin(angle) + camera.z * Math.cos(angle);
                                double neckForward = -skeleton.neck.z * PIXEL * scale;
                                require(cameraForward - neckForward >= .19999,
                                        clip + " camera fell behind the animated neck at frame " + i);
                                double lateral = camera.x * Math.cos(angle) + camera.z * Math.sin(angle);
                                require(Math.abs(lateral - skeleton.neck.x * PIXEL * scale) < .00001,
                                        clip + " lateral neck sway lost the camera/body alignment");
                                require(Math.abs(camera.y) < .071,
                                        "Authored root bob must not be injected into camera height");
                                frames++;
                            }
                        }
                    }
                    require(skeleton.neck.equals(originalNeck), "Camera calculation mutated the shared skin/armor skeleton");
                    if (clip.startsWith("sprint")) {
                        Vector3f camera = FirstPersonCameraAnchor.offset(skeleton.neck, 0, 90, false, 1);
                        double eyeY = 1.62 + camera.y;
                        require(!hitsTorso(skeleton, camera.x, eyeY, camera.z),
                                clip + " torso still blocks the downward crosshair at frame " + i);
                        if (hitsTorso(skeleton, 0, 1.57, .25)) oldIntersections++;
                    }
                }
            }
            require(oldIntersections > 0, "The regression fixture did not reproduce the screenshot with the previous fixed camera");
            currentFrameClearance(library);
            frameInvalidation(library);
            System.out.println("FirstPersonCameraAnchorTest: " + frames
                    + " animated view samples passed; reproduced " + oldIntersections
                    + " torso intersections with the old fixed camera and zero with the animated anchor");
        }
    }

    /** Camera prepares the frame before positioning; the later avatar extraction reuses it. */
    private static void currentFrameClearance(AnimationLibrary library) {
        for (int fps : new int[]{15, 30, 60, 144}) {
            var sampler = new ua.rp.chat.client.animation.PlayerAnimationSampler(library);
            var oncePerFrame = new ua.rp.chat.client.animation.PlayerAnimationSampler(library);
            double z = 0;
            for (int i = 0; i < fps * 5; i++) {
                double time = i / (double)fps;
                double speed = time >= .3 && time < 3 ? 3.6 : 0;
                z += speed / fps;
                var motion = new LocomotionController.Motion(speed, speed > 0 ? 1 : 0, 0,
                        true, false, true, 0, speed > 0 ? LocomotionController.MoveIntent.MOVING
                                : LocomotionController.MoveIntent.STOPPED);
                var frame = sampler.sample(time, motion, 0, 0, z, 1, false, null);
                var skeleton = new PlayerTorsoSkeleton(frame.pose());
                skeleton.groundFootContacts(frame.pose(), false);
                Vector3f camera = FirstPersonCameraAnchor.offset(skeleton.neck, 0, 90, false, 1);
                require(!hitsTorso(skeleton, camera.x, 1.62 + camera.y, camera.z),
                        "Current-frame camera clips at " + fps + " FPS, time " + time);
                for (int pass = 0; pass < 4; pass++) {
                    var renderFrame = sampler.sample(time, motion, 0, 0, z, 1, false, null);
                    require(renderFrame == frame, "Camera and later extraction did not share their exact immutable frame");
                }
                var reference = oncePerFrame.sample(time, motion, 0, 0, z, 1, false, null);
                for (String bone : library.boneNames()) {
                    require(frame.pose().position(bone).equals(reference.pose().position(bone))
                                    && frame.pose().rotation(bone).equals(reference.pose().rotation(bone)),
                            "Additional camera/armor extraction advanced contacts for " + bone);
                }
            }
        }
    }

    private static void frameInvalidation(AnimationLibrary library) {
        var sampler = new ua.rp.chat.client.animation.PlayerAnimationSampler(library);
        var moving = new LocomotionController.Motion(3.6, 1, 0, true, false, true, 0);
        var first = sampler.sample(1, moving, 0, 0, 0, 1, false, null);
        var moved = sampler.sample(1, moving, 0, 0, 1, 1, false, null);
        require(moved != first, "Position changes must invalidate a same-time frame");
        var unsupported = new LocomotionController.Motion(3.6, 1, 0, true, false, false, 0);
        require(!sampler.sample(1, unsupported, 0, 0, 1, 1, false, null).active(),
                "A same-time native/airborne pose must not reuse a grounded animation");
        var scaled = sampler.sample(1, moving, 0, 0, 1, .5, false, null);
        require(scaled != moved, "Scale changes must invalidate the old contact solve");
        require(sampler.sample(1, moving, 0, 0, 1, .5, true, null) != scaled,
                "Detached preview and world contact frames must stay separate");
    }

    private static void nativeFallback() {
        close(FirstPersonCameraAnchor.offset(null, 0, 0, false, 1), 0, -.02, .20);
        close(FirstPersonCameraAnchor.offset(null, 0, 90, false, 1), 0, -.05, .25);
        close(FirstPersonCameraAnchor.offset(null, 0, 0, true, 1), 0, -.04, .54);
        close(FirstPersonCameraAnchor.offset(null, 0, 90, true, 1), 0, -.07, .59);
        // An authored crouch already contains the lean; the native .34 offset must not be added twice.
        close(FirstPersonCameraAnchor.offset(new Vector3f(0, 7, -8), 0, 0, true, 1),
                0, -.04, .66875);
    }

    private static void rotationsAndScale() {
        Vector3f neck = new Vector3f(2, -40, -8);
        close(FirstPersonCameraAnchor.offset(neck, 0, 0, false, 1), .1171875, -.02, .66875);
        close(FirstPersonCameraAnchor.offset(neck, 90, 0, false, 1), -.66875, -.02, .1171875);
        close(FirstPersonCameraAnchor.offset(neck, -90, 0, false, 1), .66875, -.02, -.1171875);
        close(FirstPersonCameraAnchor.offset(neck, 0, 0, false, .5), .05859375, -.02, .434375);
        close(FirstPersonCameraAnchor.offset(neck, 0, 0, false, 2), .234375, -.02, 1.1375);
        // A backward braking lean must not drag the eye back above the lower torso.
        close(FirstPersonCameraAnchor.offset(new Vector3f(0, 0, 8), 0, 90, false, 1), 0, -.05, .25);
    }

    private static void invalidInputs() {
        close(FirstPersonCameraAnchor.offset(new Vector3f(Float.NaN, 0, 0), 0, 0, false, 1), 0, -.02, .20);
        close(FirstPersonCameraAnchor.offset(new Vector3f(0, 0, Float.POSITIVE_INFINITY), 0, 0, false, 1), 0, -.02, .20);
        Vector3f result = FirstPersonCameraAnchor.offset(new Vector3f(0, 0, -8), Double.NaN, Double.NaN, false, Double.NaN);
        require(Float.isFinite(result.x) && Float.isFinite(result.y) && Float.isFinite(result.z),
                "Invalid animation data must never poison the camera matrices");
    }

    private static boolean hitsTorso(PlayerTorsoSkeleton skeleton, double x, double eyeY, double z) {
        Vector3f eye = new Vector3f((float)x, (float)eyeY, (float)z);
        Vector3f direction = new Vector3f(0, -1, 0);
        for (int y = 0; y < 12; y++) {
            Vector3f[] bottom = ring(skeleton, y), top = ring(skeleton, y + 1);
            for (int side = 0; side < 4; side++) {
                int next = (side + 1) % 4;
                if (triangle(eye, direction, bottom[side], top[side], top[next])
                        || triangle(eye, direction, bottom[side], top[next], bottom[next])) return true;
            }
            if (y == 0 && (triangle(eye, direction, bottom[0], bottom[1], bottom[2])
                    || triangle(eye, direction, bottom[0], bottom[2], bottom[3]))) return true;
        }
        return false;
    }

    private static Vector3f[] ring(PlayerTorsoSkeleton s, float y) {
        return new Vector3f[]{point(s, -4, y, -2.5f), point(s, 4, y, -2.5f),
                point(s, 4, y, 2.5f), point(s, -4, y, 2.5f)};
    }

    private static Vector3f point(PlayerTorsoSkeleton s, float x, float y, float z) {
        Vector3f p = s.chestRotation.transform(s.deform(x, y, z)).add(s.neck);
        return new Vector3f(p.x * PIXEL, (PlayerTorsoSkeleton.floorPixels(false) - p.y) * PIXEL, -p.z * PIXEL);
    }

    private static boolean triangle(Vector3f eye, Vector3f ray, Vector3f a, Vector3f b, Vector3f c) {
        Vector3f edge1 = new Vector3f(b).sub(a), edge2 = new Vector3f(c).sub(a);
        Vector3f h = new Vector3f(ray).cross(edge2);
        float determinant = edge1.dot(h);
        if (Math.abs(determinant) < .0000001) return false;
        float inverse = 1 / determinant;
        Vector3f s = new Vector3f(eye).sub(a);
        float u = inverse * s.dot(h);
        if (u < 0 || u > 1) return false;
        Vector3f q = new Vector3f(s).cross(edge1);
        float v = inverse * ray.dot(q);
        return v >= 0 && u + v <= 1 && inverse * edge2.dot(q) >= 0;
    }

    private static void close(Vector3f actual, double x, double y, double z) {
        require(Math.abs(actual.x - x) < .00001 && Math.abs(actual.y - y) < .00001 && Math.abs(actual.z - z) < .00001,
                "Unexpected camera offset " + actual + "; expected " + x + ", " + y + ", " + z);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
