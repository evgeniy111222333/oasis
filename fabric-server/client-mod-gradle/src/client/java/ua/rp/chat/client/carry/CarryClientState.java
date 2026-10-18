package ua.rp.chat.client.carry;

import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import net.minecraft.client.Minecraft;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import ua.rp.chat.client.EclipseClientMod;

import java.util.*;

/** Full-replacement, connection-scoped visual state. No item ownership lives on the client. */
public final class CarryClientState {
    private static Map<UUID, Held> held = Map.of();
    private static Map<Integer, UUID> displayOwners = Map.of();
    private static List<Prop> props = List.of();
    private static List<Offer> offers = List.of();
    private static final Map<UUID, Retired> retired = new HashMap<>();
    private static long revision = -1;
    private static String pending;
    private static Set<String> liftable = Set.of("minecraft:chest", "minecraft:barrel", "minecraft:shulker_box",
            "minecraft:crafting_table", "minecraft:bookshelf", "minecraft:chiseled_bookshelf", "minecraft:decorated_pot",
            "minecraft:flower_pot", "minecraft:lantern", "minecraft:soul_lantern", "minecraft:carved_pumpkin");
    private CarryClientState() {}

    public static void accept(String json) {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null) { pending = json; return; }
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            if (!string(root, "type", "state").equals("state")) return;
            long nextRevision = root.has("revision") ? root.get("revision").getAsLong() : revision + 1;
            if (nextRevision < revision) return;
            RegistryAccess registries = client.level.registryAccess();
            var ops = RegistryOps.create(JsonOps.INSTANCE, registries);
            Map<UUID, Held> next = new HashMap<>();
            Map<Integer, UUID> nextOwners = new HashMap<>();
            for (JsonElement entry : array(root, "players")) {
                JsonObject object = entry.getAsJsonObject();
                UUID player = UUID.fromString(object.get("player").getAsString());
                ItemStack visual = object.has("item") ? ItemStack.CODEC.parse(ops, object.get("item")).result().orElse(ItemStack.EMPTY) : ItemStack.EMPTY;
                BlockState block = object.has("blockState") ? BlockState.CODEC.parse(ops, object.get("blockState")).result().orElse(null) : null;
                Held previous = held.get(player);
                String mode = string(object, "mode", "palm");
                String objectId = string(object, "object", "");
                double since = previous != null && previous.mode.equals(mode) && previous.object.equals(objectId)
                        ? previous.since : CarryClient.seconds();
                next.put(player, new Held(player, mode, string(object, "name", "Предмет"), visual, block,
                        object.has("slot") ? object.get("slot").getAsInt() : 0, objectId,
                        object.has("shownUntil") ? object.get("shownUntil").getAsLong() : 0, since,
                        string(object, "kind", block == null ? "item" : "block"),
                        object.has("stowable") ? object.get("stowable").getAsBoolean() : block == null,
                        object.has("entity") ? object.get("entity").getAsInt() : -1));
                retired.remove(player);
                if (object.has("entity") && object.get("entity").getAsInt() >= 0)
                    nextOwners.put(object.get("entity").getAsInt(), player);
            }
            List<Prop> nextProps = new ArrayList<>();
            for (JsonElement entry : array(root, "props")) {
                JsonObject object = entry.getAsJsonObject();
                double x = object.get("x").getAsDouble(), y = object.get("y").getAsDouble(), z = object.get("z").getAsDouble();
                if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) continue;
                nextProps.add(new Prop(UUID.fromString(object.get("id").getAsString()),
                        object.has("entity") ? object.get("entity").getAsInt() : -1,
                        string(object, "world", "minecraft:overworld"), x, y, z,
                        string(object, "mode", "palm"), string(object, "name", "Предмет")));
            }
            List<Offer> nextOffers = new ArrayList<>();
            for (JsonElement entry : array(root, "offers")) {
                JsonObject object = entry.getAsJsonObject();
                nextOffers.add(new Offer(UUID.fromString(object.get("id").getAsString()),
                        UUID.fromString(object.get("from").getAsString()), UUID.fromString(object.get("to").getAsString()),
                        string(object, "name", "Предмет"), object.get("expires").getAsLong()));
            }
            double now = CarryClient.seconds();
            held.forEach((player, old) -> { if (!next.containsKey(player)) retired.put(player, new Retired(old, now)); });
            held = Map.copyOf(next); displayOwners = Map.copyOf(nextOwners);
            props = List.copyOf(nextProps); offers = List.copyOf(nextOffers);
            if (root.has("liftable")) {
                Set<String> configured = new HashSet<>();
                for (JsonElement id : root.getAsJsonArray("liftable")) configured.add(id.getAsString());
                liftable = Set.copyOf(configured);
            }
            revision = nextRevision; pending = null;
        } catch (RuntimeException invalid) {
            EclipseClientMod.LOGGER.warn("[CARRY] Ignored invalid visual state", invalid);
        }
    }
    public static void tick() {
        if (pending != null && Minecraft.getInstance().level != null) accept(pending);
        retired.values().removeIf(value -> CarryClient.seconds() - value.at > .5);
    }
    public static void clear() {
        held = Map.of(); displayOwners = Map.of(); props = List.of(); offers = List.of(); retired.clear(); revision = -1; pending = null;
    }
    public static Held local() {
        Player player = Minecraft.getInstance().player;
        return player == null ? null : held.get(player.getUUID());
    }
    public static boolean twoHands() { Held local = local(); return local != null && local.large(); }
    public static boolean holdingLarge(UUID player) { Held value = held.get(player); return value != null && value.large(); }
    public static UUID displayOwner(int entity) { return displayOwners.get(entity); }
    public static List<Prop> props() { return props; }
    public static boolean liftable(BlockState block) {
        return ua.rp.chat.microvoxel.MicrovoxelBlocks.isMarker(block)
                || liftable.contains(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block.getBlock()).toString());
    }
    public static List<Offer> offers() { return offers; }
    public static Offer incoming() {
        Player player = Minecraft.getInstance().player;
        return player == null ? null : offers.stream().filter(o -> o.to.equals(player.getUUID()) && o.expires > System.currentTimeMillis()).findFirst().orElse(null);
    }
    public static PoseSample pose(Player player) {
        Held value = held.get(player.getUUID());
        double now = CarryClient.seconds();
        float weight;
        if (value != null) {
            if (!value.large() && (player.getMainHandItem().isEmpty()
                    || (player == Minecraft.getInstance().player && player.getInventory().getSelectedSlot() != value.slot))) return null;
            weight = (float) (1 - Math.exp(-18 * Math.max(0, now - value.since)));
        } else {
            Retired old = retired.get(player.getUUID());
            if (old == null) return null;
            value = old.held;
            weight = (float) Math.exp(-18 * Math.max(0, now - old.at));
        }
        return weight < .001 ? null : new PoseSample(value, weight);
    }
    private static JsonArray array(JsonObject object, String key) { return object.has(key) ? object.getAsJsonArray(key) : new JsonArray(); }
    private static String string(JsonObject object, String key, String fallback) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : fallback;
    }
    public record Held(UUID player, String mode, String name, ItemStack item, BlockState block,
                       int slot, String object, long shownUntil, double since, String kind, boolean stowable, int entity) {
        public boolean large() { return mode.equals("two_hands"); }
        public boolean showing() { return shownUntil > System.currentTimeMillis(); }
    }
    public record Prop(UUID id, int entity, String world, double x, double y, double z, String mode, String name) {
        public boolean large() { return mode.equals("two_hands"); }
        public AABB bounds() {
            double half = large() ? .5 : .18, height = large() ? 1 : .36;
            return new AABB(x - half, y, z - half, x + half, y + height, z + half);
        }
    }
    public record Offer(UUID id, UUID from, UUID to, String name, long expires) {}
    public record PoseSample(Held held, float weight) {}
    private record Retired(Held held, double at) {}
}
