package ua.rp.chat.animation;

import java.util.Map;

/** Immutable authored offsets: rotation in degrees, translation in model pixels. */
public final class AnimationPose {
    public record Vector(float x, float y, float z) { }

    private static final Vector ZERO = new Vector(0, 0, 0);
    public static final AnimationPose IDENTITY = new AnimationPose(Map.of(), new float[0]);
    private final Map<String, Integer> boneIndices;
    private final float[] values;

    /** Takes ownership of a freshly sampled array; no mutable data escapes the package. */
    AnimationPose(Map<String, Integer> boneIndices, float[] values) {
        this.boneIndices = boneIndices;
        this.values = values;
    }

    public Vector rotation(String bone) { return vector(bone, 0); }
    public Vector position(String bone) { return vector(bone, 3); }

    private Vector vector(String bone, int channel) {
        Integer index = boneIndices.get(bone);
        if (index == null) return ZERO;
        int offset = index * 6 + channel;
        float x = values[offset], y = values[offset + 1], z = values[offset + 2];
        return x == 0 && y == 0 && z == 0 ? ZERO : new Vector(x, y, z);
    }

    public Editor edit() { return new Editor(); }

    /** Copy once while fitting the sampled pose to a render rig. The source stays immutable. */
    public final class Editor {
        private float[] edited = values.clone();
        private Editor() {}
        public Editor rotation(String bone, float x, float y, float z) { return set(bone, 0, x, y, z); }
        public Editor position(String bone, float x, float y, float z) { return set(bone, 3, x, y, z); }
        private Editor set(String bone, int channel, float x, float y, float z) {
            if (edited == null) throw new IllegalStateException("The pose editor has already been built");
            Integer index = boneIndices.get(bone);
            if (index == null) throw new IllegalArgumentException("Unknown animation bone: " + bone);
            if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z))
                throw new IllegalArgumentException("Non-finite animation override: " + bone);
            int offset = index * 6 + channel;
            edited[offset] = x; edited[offset + 1] = y; edited[offset + 2] = z;
            return this;
        }
        public AnimationPose build() {
            if (edited == null) throw new IllegalStateException("The pose editor has already been built");
            AnimationPose result = new AnimationPose(boneIndices, edited);
            edited = null;
            return result;
        }
    }

    public static AnimationPose blend(AnimationPose from, AnimationPose to, double amount) {
        if (amount <= 0) return from;
        if (amount >= 1) return to;
        float[] result = new float[to.values.length];
        for (int i = 0; i < result.length; i++) {
            float start = from.values.length == 0 ? 0 : from.values[i];
            result[i] = (float) (start + (to.values[i] - start) * amount);
        }
        return new AnimationPose(to.boneIndices, result);
    }
}
