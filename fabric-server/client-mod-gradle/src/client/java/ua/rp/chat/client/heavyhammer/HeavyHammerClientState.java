package ua.rp.chat.client.heavyhammer;

import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import ua.rp.chat.HeavyHammerAnimation;
import ua.rp.chat.HeavyHammerCarryMachine;
import ua.rp.chat.HeavyHammerProceduralMotion;
import ua.rp.chat.client.EclipseClientMod;
import ua.rp.chat.client.debug.HammerRenderQaController;
import ua.rp.chat.microvoxel.MicrovoxelRaycaster;
import ua.rp.chat.microvoxel.MicrovoxelVolume;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class HeavyHammerClientState {
    private static final String MODEL_ID = "eclipseclient:heavy_hammer";
    private static final String DISPLAY_NAME = "Тяжёлый рабочий молот";
    private static final Map<UUID, Strike> STRIKES = new HashMap<>();
    private static final Map<UUID, CarryTrack> CARRY_TRACKS = new HashMap<>();
    private static final Map<UUID, RemoteCarry> REMOTE_CARRY = new HashMap<>();
    private static int nextPrediction = -1;

    private HeavyHammerClientState() {
    }

    public static int startPrediction(Player player, MicrovoxelRaycaster.Hit hit) {
        int sequence = nextPrediction--;
        if (nextPrediction >= 0) nextPrediction = -1;
        STRIKES.put(player.getUUID(), new Strike(sequence, System.nanoTime(),
                (int) HeavyHammerAnimation.DURATION_TICKS, (int) HeavyHammerAnimation.IMPACT_TICK, false,
                new TargetSnapshot(hit.entry().x(), hit.entry().y(), hit.entry().z(),
                        hit.cell(), hit.face().ordinal())));
        return sequence;
    }

    public static void handle(HeavyHammerSyncPayload payload) {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload.data()))) {
            int type = input.readUnsignedByte();
            UUID playerId = new UUID(input.readLong(), input.readLong());
            int sequence = input.readInt();
            if (type == 1) {
                int duration = input.readUnsignedShort();
                int impact = input.readUnsignedShort();
                int blockX = input.readInt();
                int blockY = input.readInt();
                int blockZ = input.readInt();
                int cell = input.readUnsignedShort();
                int face = input.readUnsignedByte();
                if (input.available() == 0 && duration >= 1 && duration <= 200 && impact < duration
                        && cell < MicrovoxelVolume.CELL_COUNT && face < 6) {
                    STRIKES.put(playerId, new Strike(sequence, System.nanoTime(), duration, impact, true,
                            new TargetSnapshot(blockX, blockY, blockZ, cell, face)));
                }
            } else if (type == 2) {
                boolean success = input.readBoolean();
                Strike strike = STRIKES.get(playerId);
                if (input.available() == 0 && strike != null && strike.sequence == sequence) {
                    strike.confirmedImpact = success;
                }
            } else if (type == 3 && input.available() == 0) {
                Strike strike = STRIKES.get(playerId);
                if (strike != null && strike.sequence == sequence) STRIKES.remove(playerId);
            } else if (type == 4) {
                boolean present = input.readBoolean();
                boolean selected = input.readBoolean();
                if (input.available() == 0) {
                    RemoteCarry previous = REMOTE_CARRY.get(playerId);
                    if (previous == null || Integer.compareUnsigned(sequence, previous.revision) > 0) {
                        REMOTE_CARRY.put(playerId, new RemoteCarry(sequence, present, selected,
                                System.nanoTime()));
                    }
                }
            }
        } catch (IOException | RuntimeException error) {
            EclipseClientMod.LOGGER.warn("Отклонён повреждённый пакет анимации тяжёлого молота.", error);
        }
    }

    public static void clientTick(Minecraft client) {
        long now = System.nanoTime();
        STRIKES.entrySet().removeIf(entry -> elapsedTicks(entry.getValue(), now) > entry.getValue().durationTicks + 3.0f);
        if (client.player == null || client.level == null) {
            STRIKES.clear();
            CARRY_TRACKS.clear();
            REMOTE_CARRY.clear();
            return;
        }
        for (Player player : client.level.players()) {
            ItemStack hammer = findHammer(player);
            boolean local = player == client.player;
            RemoteCarry remote = local ? null : REMOTE_CARRY.get(player.getUUID());
            boolean present = remote != null ? remote.present : hammer != null && !hammer.isEmpty();
            boolean selected = remote != null ? remote.selected : isHolding(player.getMainHandItem());
            if (present && (hammer == null || hammer.isEmpty())) hammer = createRemoteHammerStack();
            CarryTrack track = CARRY_TRACKS.get(player.getUUID());
            if (track == null && (present || selected)) {
                track = new CarryTrack();
                track.machine.reset(present || selected, false);
                CARRY_TRACKS.put(player.getUUID(), track);
            }
            if (track == null) continue;
            if (present) track.hammer = hammer.copyWithCount(1);
            track.machine.tick(present || selected, selected, 1.0f);
            track.lastSeenNanos = now;
        }
        CARRY_TRACKS.entrySet().removeIf(entry -> now - entry.getValue().lastSeenNanos > 5_000_000_000L);
        REMOTE_CARRY.entrySet().removeIf(entry -> now - entry.getValue().lastSeenNanos > 5_000_000_000L);
    }

    public static HeavyHammerAnimation.Sample poseFor(Player player, float ageTicks) {
        if (player == null) return null;
        long now = System.nanoTime();
        boolean holding = isHolding(player.getMainHandItem());
        CarryTrack carry = CARRY_TRACKS.get(player.getUUID());
        if (carry == null) return null;

        HeavyHammerAnimation.Sample diagnosticPose = HammerRenderQaController.poseOverride(player, ageTicks);
        if (holding && diagnosticPose != null) return diagnosticPose;
        Strike strike = STRIKES.get(player.getUUID());
        float locomotion = (float) player.getDeltaMovement().horizontalDistance();
        if (holding && carry.machine.isReady() && strike != null) {
            return HeavyHammerAnimation.strike(elapsedTicks(strike, now), targetFor(player, strike.target));
        }
        return HeavyHammerAnimation.draw(carry.machine.sample(partialTick(ageTicks)).position(),
                ageTicks, locomotion);
    }

    public static boolean striking(Player player) {
        return player != null && STRIKES.containsKey(player.getUUID());
    }

    public static boolean ready(Player player) {
        if (player == null || !isHolding(player.getMainHandItem())) return false;
        CarryTrack carry = CARRY_TRACKS.get(player.getUUID());
        return carry != null && carry.machine.isReady();
    }

    public static Visual visualFor(Player player, float ageTicks) {
        if (player == null) return null;
        CarryTrack track = CARRY_TRACKS.get(player.getUUID());
        if (track == null || track.hammer == null || track.hammer.isEmpty()) return null;
        HeavyHammerCarryMachine.Sample carry = track.machine.sample(partialTick(ageTicks));
        if (!carry.present() && carry.position() <= 0.001f) return null;
        float locomotion = (float) player.getDeltaMovement().horizontalDistance();
        HeavyHammerProceduralMotion.Frame frame;
        HeavyHammerProceduralMotion.Frame diagnosticFrame =
                HammerRenderQaController.frameOverride(player, ageTicks);
        Strike strike = STRIKES.get(player.getUUID());
        if (diagnosticFrame != null) {
            frame = diagnosticFrame;
        } else if (track.machine.isReady() && strike != null) {
            frame = HeavyHammerProceduralMotion.strike(
                    Math.min(1.0f, elapsedTicks(strike, System.nanoTime()) / strike.durationTicks),
                    targetFor(player, strike.target));
        } else {
            frame = HeavyHammerProceduralMotion.draw(carry.position(), ageTicks, locomotion);
        }
        return new Visual(track.hammer, carry, frame);
    }

    public static boolean suppressVanillaHammer(ItemStack stack) {
        return isHolding(stack);
    }

    public static boolean suppressSelectedItem(Player player) {
        CarryTrack track = player == null ? null : CARRY_TRACKS.get(player.getUUID());
        return track != null && track.machine.blocksSelectedItem();
    }

    public static boolean isHolding(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        Identifier model = stack.get(DataComponents.ITEM_MODEL);
        if (model != null && MODEL_ID.equals(model.toString())) return true;
        // Старые уже выданные предметы могли потерять клиентский компонент при обновлении Paper.
        // Узкий резервный признак сохраняет стойку, не превращая обычные переименованные топоры в молот.
        return stack.is(Items.IRON_AXE) && DISPLAY_NAME.equals(stack.getHoverName().getString());
    }

    private static float elapsedTicks(Strike strike, long now) {
        return Math.max(0.0f, (now - strike.startedNanos) / 50_000_000.0f);
    }

    private static float partialTick(float ageTicks) {
        float floor = (float) Math.floor(ageTicks);
        return Math.max(0.0f, Math.min(1.0f, ageTicks - floor));
    }

    private static ItemStack findHammer(Player player) {
        if (player == null) return ItemStack.EMPTY;
        ItemStack selected = player.getMainHandItem();
        if (isHolding(selected)) return selected;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (isHolding(stack)) return stack;
        }
        return ItemStack.EMPTY;
    }

    private static ItemStack createRemoteHammerStack() {
        ItemStack stack = new ItemStack(Items.IRON_AXE);
        stack.set(DataComponents.ITEM_MODEL, Identifier.parse(MODEL_ID));
        return stack;
    }

    private static HeavyHammerProceduralMotion.Target targetFor(Player player, TargetSnapshot target) {
        int cellX = MicrovoxelVolume.x(target.cell);
        int cellY = MicrovoxelVolume.y(target.cell);
        int cellZ = MicrovoxelVolume.z(target.cell);
        int faceX = target.face == 4 ? -1 : target.face == 5 ? 1 : 0;
        int faceY = target.face == 0 ? -1 : target.face == 1 ? 1 : 0;
        int faceZ = target.face == 2 ? -1 : target.face == 3 ? 1 : 0;
        double worldX = target.blockX + (cellX + 0.5 + faceX * 0.5) / 16.0;
        double worldY = target.blockY + (cellY + 0.5 + faceY * 0.5) / 16.0;
        double worldZ = target.blockZ + (cellZ + 0.5 + faceZ * 0.5) / 16.0;
        double dx = worldX - player.getX();
        double dz = worldZ - player.getZ();
        double yaw = Math.toRadians(player.getYRot());
        float localX = (float) ((dx * Math.cos(yaw) + dz * Math.sin(yaw)) * 16.0);
        float localZ = (float) ((dx * Math.sin(yaw) - dz * Math.cos(yaw)) * 16.0);
        float localY = (float) ((player.getY() - worldY) * 16.0 + 24.0);
        HeavyHammerProceduralMotion.Surface surface = target.face == 1
                ? HeavyHammerProceduralMotion.Surface.UP
                : target.face == 0 ? HeavyHammerProceduralMotion.Surface.DOWN
                : HeavyHammerProceduralMotion.Surface.SIDE;
        float normalX = (float) (faceX * Math.cos(yaw) + faceZ * Math.sin(yaw));
        float normalZ = (float) (faceX * Math.sin(yaw) - faceZ * Math.cos(yaw));
        return new HeavyHammerProceduralMotion.Target(localX, localY, localZ, surface,
                normalX, faceY, normalZ);
    }

    private static final class Strike {
        private final int sequence;
        private final long startedNanos;
        private final int durationTicks;
        @SuppressWarnings("unused")
        private final int impactTick;
        @SuppressWarnings("unused")
        private final boolean authoritative;
        private final TargetSnapshot target;
        private boolean confirmedImpact;

        private Strike(int sequence, long startedNanos, int durationTicks, int impactTick,
                       boolean authoritative, TargetSnapshot target) {
            this.sequence = sequence;
            this.startedNanos = startedNanos;
            this.durationTicks = durationTicks;
            this.impactTick = impactTick;
            this.authoritative = authoritative;
            this.target = target;
        }
    }

    private static final class CarryTrack {
        private final HeavyHammerCarryMachine machine = new HeavyHammerCarryMachine();
        private ItemStack hammer = ItemStack.EMPTY;
        private long lastSeenNanos;
    }

    private static final class RemoteCarry {
        private final int revision;
        private final boolean present;
        private final boolean selected;
        private final long lastSeenNanos;

        private RemoteCarry(int revision, boolean present, boolean selected, long lastSeenNanos) {
            this.revision = revision;
            this.present = present;
            this.selected = selected;
            this.lastSeenNanos = lastSeenNanos;
        }
    }

    public record Visual(ItemStack hammer, HeavyHammerCarryMachine.Sample carry,
                         HeavyHammerProceduralMotion.Frame frame) {
    }

    private record TargetSnapshot(int blockX, int blockY, int blockZ, int cell, int face) {
    }
}
