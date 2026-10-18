package ua.rp.chat.animation;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Shared, immutable numeric Bedrock animation library without game dependencies. */
public final class AnimationLibrary {
    private final Map<String, Clip> clips;
    private final Map<String, Integer> boneIndices;

    private AnimationLibrary(Map<String, Clip> clips, Map<String, Integer> boneIndices) {
        this.clips = Collections.unmodifiableMap(clips);
        this.boneIndices = Collections.unmodifiableMap(boneIndices);
    }

    /** The caller owns the stream. Expressions fail explicitly rather than becoming zero. */
    public static AnimationLibrary load(InputStream stream) throws IOException {
        Objects.requireNonNull(stream, "animation stream");
        try {
            JsonObject root = object(JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)), "root");
            JsonObject animations = object(root.get("animations"), "animations");
            Map<String, Integer> indices = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> animation : animations.entrySet()) {
                JsonObject bones = object(object(animation.getValue(), animation.getKey()).get("bones"), "bones");
                for (String bone : bones.keySet()) indices.computeIfAbsent(bone, ignored -> indices.size());
            }
            Map<String, Clip> clips = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> animation : animations.entrySet()) {
                String name = shortName(animation.getKey());
                JsonObject definition = object(animation.getValue(), name);
                double length = number(definition.get("animation_length"), name + " animation_length");
                if (length <= 0) throw new IllegalArgumentException(name + ": animation_length must be positive");
                boolean loops = readLoop(definition.get("loop"), name);
                List<Track> tracks = new ArrayList<>();
                JsonObject bones = object(definition.get("bones"), name + " bones");
                for (Map.Entry<String, JsonElement> bone : bones.entrySet()) {
                    JsonObject channels = object(bone.getValue(), name + " bone " + bone.getKey());
                    for (String channel : channels.keySet()) {
                        int offset = switch (channel) {
                            case "rotation" -> 0;
                            case "position" -> 3;
                            default -> throw new IllegalArgumentException(name + ": unsupported channel " + channel);
                        };
                        tracks.add(readTrack(channels.get(channel), indices.get(bone.getKey()) * 6 + offset,
                                length, name + "/" + bone.getKey() + "/" + channel));
                    }
                }
                if (clips.put(name, new Clip(length, loops, tracks.toArray(Track[]::new))) != null) {
                    throw new IllegalArgumentException("Duplicate short animation name: " + name);
                }
            }
            if (clips.isEmpty()) throw new IllegalArgumentException("Animation library is empty");
            return new AnimationLibrary(clips, indices);
        } catch (JsonParseException | IllegalStateException ex) {
            throw new IOException("Invalid animation JSON", ex);
        }
    }

    public int clipCount() { return clips.size(); }
    public Set<String> clipNames() { return clips.keySet(); }
    public Set<String> boneNames() { return boneIndices.keySet(); }
    public double length(String clip) { return clip(clip).length; }
    public boolean loops(String clip) { return clip(clip).loops; }

    /** Loops wrap positive time; negative time and one-shots clamp at the endpoint. */
    public AnimationPose sample(String name, double seconds) {
        if (!Double.isFinite(seconds)) throw new IllegalArgumentException("Animation time must be finite");
        Clip clip = clip(name);
        double time = Math.max(0, seconds);
        time = clip.loops ? time % clip.length : Math.min(time, clip.length);
        float[] values = new float[boneIndices.size() * 6];
        for (Track track : clip.tracks) track.sample(time, clip.loops, values);
        return new AnimationPose(boneIndices, values);
    }

    private Clip clip(String name) {
        Clip clip = clips.get(shortName(Objects.requireNonNull(name, "clip")));
        if (clip == null) throw new IllegalArgumentException("Unknown animation: " + name);
        return clip;
    }

    private static String shortName(String name) {
        return name.startsWith("animation.") ? name.substring(name.lastIndexOf('.') + 1) : name;
    }

    private static boolean readLoop(JsonElement value, String name) {
        if (value == null) return false;
        if (!value.isJsonPrimitive()) throw new IllegalArgumentException(name + ": invalid loop value");
        if (value.getAsJsonPrimitive().isBoolean()) return value.getAsBoolean();
        if (value.getAsJsonPrimitive().isString() && value.getAsString().equals("hold_on_last_frame")) return false;
        throw new IllegalArgumentException(name + ": unsupported loop value");
    }

    private static Track readTrack(JsonElement value, int offset, double length, String path) {
        if (value != null && value.isJsonArray()) {
            float[] vector = vector(value, path);
            return new Track(offset, new Key[]{new Key(0, vector, vector, false)});
        }
        JsonObject source = object(value, path);
        List<Key> keys = new ArrayList<>();
        for (Map.Entry<String, JsonElement> entry : source.entrySet()) {
            double time;
            try { time = Double.parseDouble(entry.getKey()); }
            catch (NumberFormatException ex) { throw new IllegalArgumentException(path + ": nonnumeric key time", ex); }
            if (!Double.isFinite(time) || time < 0 || time > length + 0.000001) {
                throw new IllegalArgumentException(path + ": key time outside animation length");
            }
            JsonElement keyValue = entry.getValue();
            if (keyValue.isJsonArray()) {
                float[] vector = vector(keyValue, path);
                keys.add(new Key(time, vector, vector, false));
            } else {
                JsonObject key = object(keyValue, path + " key");
                JsonElement postValue = key.has("post") ? key.get("post") : key.get("pre");
                float[] post = vector(postValue, path + " post");
                float[] pre = key.has("pre") ? vector(key.get("pre"), path + " pre") : post;
                String mode = key.has("lerp_mode") ? key.get("lerp_mode").getAsString() : "linear";
                if (!mode.equals("linear") && !mode.equals("catmullrom")) {
                    throw new IllegalArgumentException(path + ": unsupported interpolation " + mode);
                }
                keys.add(new Key(time, pre, post, mode.equals("catmullrom")));
            }
        }
        if (keys.isEmpty()) throw new IllegalArgumentException(path + ": empty channel");
        keys.sort(Comparator.comparingDouble(Key::time));
        for (int i = 1; i < keys.size(); i++) {
            if (keys.get(i - 1).time == keys.get(i).time) throw new IllegalArgumentException(path + ": duplicate key time");
        }
        return new Track(offset, keys.toArray(Key[]::new));
    }

    private static JsonObject object(JsonElement value, String path) {
        if (value == null || !value.isJsonObject()) throw new IllegalArgumentException(path + ": object required");
        return value.getAsJsonObject();
    }

    private static double number(JsonElement value, String path) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException(path + ": numeric value required; expressions are unsupported");
        }
        double result = value.getAsDouble();
        if (!Double.isFinite(result)) throw new IllegalArgumentException(path + ": finite number required");
        return result;
    }

    private static float[] vector(JsonElement value, String path) {
        if (value == null || !value.isJsonArray()) throw new IllegalArgumentException(path + ": vector required");
        JsonArray array = value.getAsJsonArray();
        if (array.size() != 3) throw new IllegalArgumentException(path + ": vector must have three components");
        float[] result = new float[3];
        for (int i = 0; i < 3; i++) {
            result[i] = (float) number(array.get(i), path);
            if (!Float.isFinite(result[i])) throw new IllegalArgumentException(path + ": float component out of range");
        }
        return result;
    }

    private record Clip(double length, boolean loops, Track[] tracks) { }
    private record Key(double time, float[] pre, float[] post, boolean curved) { }

    private record Track(int offset, Key[] keys) {
        void sample(double time, boolean loops, float[] target) {
            int last = keys.length - 1;
            if (time <= keys[0].time || last == 0) { write(keys[0].post, target); return; }
            if (time >= keys[last].time) { write(keys[last].post, target); return; }
            int low = 0, high = last;
            while (high - low > 1) {
                int middle = (low + high) >>> 1;
                if (keys[middle].time <= time) low = middle; else high = middle;
            }
            Key left = keys[low], right = keys[high];
            double u = (time - left.time) / (right.time - left.time);
            boolean curved = left.curved || right.curved;
            // Authored loops include a duplicate closing key: skip it when finding wrapped neighbours.
            Key before = keys[low > 0 ? low - 1 : loops && last > 1 ? last - 1 : 0];
            Key after = keys[high < last ? high + 1 : loops && last > 1 ? 1 : last];
            for (int axis = 0; axis < 3; axis++) {
                double p1 = left.post[axis], p2 = right.pre[axis];
                double result;
                if (curved) {
                    double p0 = before.post[axis], p3 = after.pre[axis];
                    result = .5 * (2 * p1 + (p2 - p0) * u
                            + (2 * p0 - 5 * p1 + 4 * p2 - p3) * u * u
                            + (-p0 + 3 * p1 - 3 * p2 + p3) * u * u * u);
                } else result = p1 + (p2 - p1) * u;
                target[offset + axis] = (float) result;
            }
        }

        private void write(float[] vector, float[] target) {
            System.arraycopy(vector, 0, target, offset, 3);
        }
    }
}
