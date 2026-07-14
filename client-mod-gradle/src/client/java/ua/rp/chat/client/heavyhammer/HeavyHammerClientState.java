package ua.rp.chat.client.heavyhammer;

import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import ua.rp.chat.HeavyHammerAnimation;
import ua.rp.chat.client.EclipseClientMod;

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
    private static int nextPrediction = -1;

    private HeavyHammerClientState() {
    }

    public static int startPrediction(Player player) {
        int sequence = nextPrediction--;
        if (nextPrediction >= 0) nextPrediction = -1;
        STRIKES.put(player.getUUID(), new Strike(sequence, System.nanoTime(),
                (int) HeavyHammerAnimation.DURATION_TICKS, (int) HeavyHammerAnimation.IMPACT_TICK, false));
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
                if (input.available() == 0 && duration >= 1 && duration <= 200 && impact < duration) {
                    STRIKES.put(playerId, new Strike(sequence, System.nanoTime(), duration, impact, true));
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
            }
        } catch (IOException | RuntimeException error) {
            EclipseClientMod.LOGGER.warn("Отклонён повреждённый пакет анимации тяжёлого молота.", error);
        }
    }

    public static void clientTick(Minecraft client) {
        long now = System.nanoTime();
        STRIKES.entrySet().removeIf(entry -> elapsedTicks(entry.getValue(), now) > entry.getValue().durationTicks + 3.0f);
        if (client.player == null || client.level == null) STRIKES.clear();
    }

    public static HeavyHammerAnimation.Sample poseFor(Player player, float ageTicks) {
        if (player == null || !isHolding(player.getMainHandItem())) return null;
        Strike strike = STRIKES.get(player.getUUID());
        float locomotion = (float) player.getDeltaMovement().horizontalDistance();
        return strike == null ? HeavyHammerAnimation.idle(ageTicks, locomotion)
                : HeavyHammerAnimation.strike(elapsedTicks(strike, System.nanoTime()));
    }

    public static boolean striking(Player player) {
        return player != null && STRIKES.containsKey(player.getUUID());
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

    private static final class Strike {
        private final int sequence;
        private final long startedNanos;
        private final int durationTicks;
        @SuppressWarnings("unused")
        private final int impactTick;
        @SuppressWarnings("unused")
        private final boolean authoritative;
        private boolean confirmedImpact;

        private Strike(int sequence, long startedNanos, int durationTicks, int impactTick, boolean authoritative) {
            this.sequence = sequence;
            this.startedNanos = startedNanos;
            this.durationTicks = durationTicks;
            this.impactTick = impactTick;
            this.authoritative = authoritative;
        }
    }
}
