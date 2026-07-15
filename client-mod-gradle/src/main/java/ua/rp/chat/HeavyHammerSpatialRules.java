package ua.rp.chat;

/** Пространственные ограничения молота относительно тела персонажа. */
public final class HeavyHammerSpatialRules {
    public static final float HEAD_BOUND_CENTER_DISTANCE = 24.55f * HeavyHammerProceduralMotion.ITEM_SCALE;
    public static final float HEAD_HALF_WIDTH = 12.65f * HeavyHammerProceduralMotion.ITEM_SCALE;
    public static final float HEAD_HALF_HEIGHT = 4.20f * HeavyHammerProceduralMotion.ITEM_SCALE;
    public static final float HEAD_HALF_DEPTH = 3.65f * HeavyHammerProceduralMotion.ITEM_SCALE;

    public static final Aabb PLAYER_HEAD = new Aabb(
            new HeavyHammerProceduralMotion.Vec3(0.0f, -4.0f, 0.0f),
            new HeavyHammerProceduralMotion.Vec3(4.0f, 4.0f, 4.0f));
    public static final Aabb PLAYER_TORSO = new Aabb(
            new HeavyHammerProceduralMotion.Vec3(0.0f, 6.0f, 0.0f),
            new HeavyHammerProceduralMotion.Vec3(4.0f, 6.0f, 2.0f));

    private HeavyHammerSpatialRules() {
    }

    public static OrientedBox headBox(HeavyHammerProceduralMotion.Frame frame) {
        HeavyHammerProceduralMotion.Vec3 center = frame.mainGrip()
                .add(frame.shaft().scale(HEAD_BOUND_CENTER_DISTANCE));
        return new OrientedBox(center, frame.headAxis(), frame.shaft(), frame.depthAxis(),
                HEAD_HALF_WIDTH, HEAD_HALF_HEIGHT, HEAD_HALF_DEPTH);
    }

    public static boolean intersectsPlayerHead(HeavyHammerProceduralMotion.Frame frame) {
        return intersects(headBox(frame), PLAYER_HEAD);
    }

    public static boolean intersectsPlayerTorso(HeavyHammerProceduralMotion.Frame frame) {
        return intersects(headBox(frame), PLAYER_TORSO);
    }

    /** Видимая длина длинной оси бойка в ортографической проекции камеры. */
    public static float projectedLongAxisLength(HeavyHammerProceduralMotion.Frame frame,
                                                HeavyHammerProceduralMotion.Vec3 cameraForward) {
        HeavyHammerProceduralMotion.Vec3 view = cameraForward.normalized();
        float alongView = frame.headAxis().dot(view);
        return 2.0f * HEAD_HALF_WIDTH
                * (float) Math.sqrt(Math.max(0.0f, 1.0f - alongView * alongView));
    }

    /** SAT-проверка ориентированного параллелепипеда против осевого AABB. */
    public static boolean intersects(OrientedBox box, Aabb aabb) {
        HeavyHammerProceduralMotion.Vec3[] axes = {box.axisX, box.axisY, box.axisZ};
        float[] obbExtent = {box.extentX, box.extentY, box.extentZ};
        float[] aabbExtent = {aabb.extent.x(), aabb.extent.y(), aabb.extent.z()};
        float[][] rotation = new float[3][3];
        float[][] absolute = new float[3][3];
        for (int i = 0; i < 3; i++) {
            rotation[i][0] = axes[i].x();
            rotation[i][1] = axes[i].y();
            rotation[i][2] = axes[i].z();
            for (int j = 0; j < 3; j++) {
                absolute[i][j] = Math.abs(rotation[i][j]) + 1.0e-5f;
            }
        }

        HeavyHammerProceduralMotion.Vec3 delta = aabb.center.subtract(box.center);
        float[] translated = {delta.dot(axes[0]), delta.dot(axes[1]), delta.dot(axes[2])};
        float[] deltaWorld = {delta.x(), delta.y(), delta.z()};

        for (int i = 0; i < 3; i++) {
            float radius = aabbExtent[0] * absolute[i][0]
                    + aabbExtent[1] * absolute[i][1]
                    + aabbExtent[2] * absolute[i][2];
            if (Math.abs(translated[i]) > obbExtent[i] + radius) return false;
        }
        for (int j = 0; j < 3; j++) {
            float radius = obbExtent[0] * absolute[0][j]
                    + obbExtent[1] * absolute[1][j]
                    + obbExtent[2] * absolute[2][j];
            if (Math.abs(deltaWorld[j]) > aabbExtent[j] + radius) return false;
        }

        for (int i = 0; i < 3; i++) {
            int i1 = (i + 1) % 3;
            int i2 = (i + 2) % 3;
            for (int j = 0; j < 3; j++) {
                int j1 = (j + 1) % 3;
                int j2 = (j + 2) % 3;
                float value = Math.abs(translated[i2] * rotation[i1][j]
                        - translated[i1] * rotation[i2][j]);
                float radiusObb = obbExtent[i1] * absolute[i2][j]
                        + obbExtent[i2] * absolute[i1][j];
                float radiusAabb = aabbExtent[j1] * absolute[i][j2]
                        + aabbExtent[j2] * absolute[i][j1];
                if (value > radiusObb + radiusAabb) return false;
            }
        }
        return true;
    }

    public record OrientedBox(HeavyHammerProceduralMotion.Vec3 center,
                              HeavyHammerProceduralMotion.Vec3 axisX,
                              HeavyHammerProceduralMotion.Vec3 axisY,
                              HeavyHammerProceduralMotion.Vec3 axisZ,
                              float extentX, float extentY, float extentZ) {
    }

    public record Aabb(HeavyHammerProceduralMotion.Vec3 center,
                       HeavyHammerProceduralMotion.Vec3 extent) {
    }
}
