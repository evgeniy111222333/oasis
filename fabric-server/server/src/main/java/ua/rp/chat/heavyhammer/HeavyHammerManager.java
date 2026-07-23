package ua.rp.chat.heavyhammer;

import net.minecraft.server.level.ServerLevel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import ua.rp.chat.RPChat;
import ua.rp.chat.microvoxel.MicrovoxelManager;
import ua.rp.chat.microvoxel.MicrovoxelVolume;

public final class HeavyHammerManager  {
    public static final String ITEM_ID = "heavy_builder_hammer";
    public static final String CLIENT_MODEL = "eclipseclient:heavy_hammer";

    private final RPChat plugin;
    private final MicrovoxelManager microvoxels;
    private final Map<UUID, PendingStrike> strikes = new HashMap<>();
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private final Map<UUID, CarryState> carryStates = new HashMap<>();
    private final Map<UUID, Long> readyAt = new HashMap<>();
    private int nextSequence = 1;
    private int carryRevision = 1;
    private int carryPoll;
    private long serverTick;

    public HeavyHammerManager(RPChat plugin, MicrovoxelManager microvoxels) {
        this.plugin = plugin;
        this.microvoxels = microvoxels;
    }

    public void start() {
    }

    public void shutdown() {
        strikes.clear();
        cooldowns.clear();
        carryStates.clear();
        readyAt.clear();
    }

    public ItemStack createItem() {
        ItemStack hammer = new ItemStack(Items.IRON_AXE);
        hammer.set(DataComponents.CUSTOM_NAME, Component.literal("Тяжёлый рабочий молот"));
        List<Component> loreList = List.of(
                Component.literal("Простой кузнечный инструмент для грубой каменной работы."),
                Component.literal("Требует обеих рук и расходует выносливость."),
                Component.literal("ЛКМ по микровокселям — тяжёлый круговой удар.")
        );
        hammer.set(DataComponents.LORE, new net.minecraft.world.item.component.ItemLore(loreList));
        hammer.set(DataComponents.CUSTOM_MODEL_DATA, new net.minecraft.world.item.component.CustomModelData(List.of(1401.0f), List.of(), List.of(), List.of()));
        hammer.set(DataComponents.ITEM_MODEL, Identifier.parse(CLIENT_MODEL));
        
        net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
        tag.putString("item_id", ITEM_ID);
        net.minecraft.world.item.component.CustomData.set(DataComponents.CUSTOM_DATA, hammer, tag);
        hammer.setDamageValue(0);
        return hammer;
    }

    public boolean isHeavyHammer(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        net.minecraft.world.item.component.CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return false;
        net.minecraft.nbt.CompoundTag tag = data.copyTag();
        return tag.getString("item_id").map(ITEM_ID::equals).orElse(false);
    }

    public void handleAction(ServerPlayer player, int x, int y, int z, int cell, int revision, int clientSequence) {
        if (player == null || !isHeavyHammer(player.getMainHandItem())) return;
        beginStrike(player, x, y, z, cell, revision, clientSequence);
    }

    private void beginStrike(ServerPlayer player, int x, int y, int z, int cell, int revision, int clientSequence) {
        UUID id = player.getUUID();
        long now = System.currentTimeMillis();
        if (strikes.containsKey(id) || cooldowns.getOrDefault(id, 0L) > now) {
            sendCancel(player, clientSequence);
            return;
        }
        if (now < readyAt.getOrDefault(id, Long.MAX_VALUE)) {
            player.sendSystemMessage(Component.literal("Сначала надёжно возьмите тяжёлый молот обеими руками."), true);
            sendCancel(player, clientSequence);
            return;
        }
        if (!player.getOffhandItem().isEmpty()) {
            player.sendSystemMessage(Component.literal("Для тяжёлого молота нужно освободить вторую руку."), true);
            sendCancel(player, clientSequence);
            return;
        }
        MicrovoxelManager.HammerTarget target = microvoxels.prepareHammerTarget(player, x, y, z, cell, revision);
        if (target == null) {
            sendCancel(player, clientSequence);
            return;
        }
        if (!physicallyReachable(player, target)) {
            player.sendSystemMessage(Component.literal("Подойдите ближе: тяжёлый молот не достаёт до точки удара."), true);
            sendCancel(player, clientSequence);
            return;
        }
        if (!plugin.getStaminaManager().consumeWorkEffort(
                player, HeavyHammerRules.STAMINA_COST, HeavyHammerRules.FATIGUE_GAIN)) {
            player.sendSystemMessage(Component.literal("Не хватает выносливости для тяжёлого замаха."), true);
            sendCancel(player, clientSequence);
            return;
        }

        int sequence = nextSequence++;
        if (nextSequence <= 0) nextSequence = 1;
        PendingStrike strike = new PendingStrike(sequence, target, serverTick + HeavyHammerRules.IMPACT_TICK, serverTick + HeavyHammerRules.DURATION_TICKS, false);
        strikes.put(id, strike);
        cooldowns.put(id, now + HeavyHammerRules.COOLDOWN_TICKS * 50L);
        player.setSprinting(false);
        broadcast(player, HeavyHammerProtocol.start(id, sequence,
                HeavyHammerRules.DURATION_TICKS, HeavyHammerRules.IMPACT_TICK,
                target.key().x(), target.key().y(), target.key().z(), target.anchorCell(),
                HeavyHammerProtocol.Face.valueOf(target.face().name())));


    }

    private void impact(ServerPlayer player, PendingStrike expected) {
        if (!expected.equals(strikes.get(player.getUUID()))) return;
        boolean validGrip = player.connection != null && player.isAlive()
                && isHeavyHammer(player.getMainHandItem())
                && player.getOffhandItem().isEmpty()
                && physicallyReachable(player, expected.target());
        int removed = validGrip ? microvoxels.commitHammerImpact(player, expected.target()) : 0;
        boolean success = removed > 0;
        broadcast(player, HeavyHammerProtocol.impact(player.getUUID(), expected.sequence(), success));
        if (!success) return;

        ItemStack hammer = player.getMainHandItem();
        // Damage item in main hand (1 point)
        hammer.hurtAndBreak(1, player, net.minecraft.world.entity.EquipmentSlot.MAINHAND);
        
        TargetPoint point = targetPoint(expected.target());
        ((ServerLevel) player.level()).sendParticles(
                net.minecraft.core.particles.ParticleTypes.SMOKE,
                point.x(), point.y(), point.z(),
                Math.min(20, 5 + removed / 4), 0.22, 0.22, 0.22, 0.015
        );
        ((ServerLevel) player.level()).playSound(null, point.x(), point.y(), point.z(),
                SoundEvents.ANVIL_LAND, SoundSource.BLOCKS, 0.72f, 0.62f);
        ((ServerLevel) player.level()).playSound(null, point.x(), point.y(), point.z(),
                SoundEvents.STONE_BREAK, SoundSource.BLOCKS, 0.85f, 0.72f);
    }

    private void finish(ServerPlayer player, PendingStrike expected) {
        strikes.remove(player.getUUID(), expected);
    }

    private boolean physicallyReachable(ServerPlayer player, MicrovoxelManager.HammerTarget target) {
        if (player == null || target == null || player.connection == null || !player.isAlive()) return false;
        TargetPoint point = targetPoint(target);
        double dx = point.x() - player.getX();
        double dy = point.y() - player.getY();
        double dz = point.z() - player.getZ();
        double horizontalSquared = dx * dx + dz * dz;
        return horizontalSquared <= HeavyHammerRules.MAX_HORIZONTAL_TARGET_DISTANCE
                * HeavyHammerRules.MAX_HORIZONTAL_TARGET_DISTANCE
                && dy >= HeavyHammerRules.MIN_TARGET_HEIGHT
                && dy <= HeavyHammerRules.MAX_TARGET_HEIGHT
                && horizontalSquared + dy * dy <= HeavyHammerRules.MAX_TARGET_DISTANCE
                * HeavyHammerRules.MAX_TARGET_DISTANCE;
    }

    private static TargetPoint targetPoint(MicrovoxelManager.HammerTarget target) {
        double scale = 1.0 / MicrovoxelVolume.RESOLUTION;
        int cell = target.anchorCell();
        return new TargetPoint(
                target.key().x() + (MicrovoxelVolume.x(cell) + 0.5 + target.face().dx() * 0.5) * scale,
                target.key().y() + (MicrovoxelVolume.y(cell) + 0.5 + target.face().dy() * 0.5) * scale,
                target.key().z() + (MicrovoxelVolume.z(cell) + 0.5 + target.face().dz() * 0.5) * scale);
    }

    private void sendCancel(ServerPlayer player, int clientSequence) {
        if (player == null || player.connection == null) return;
        byte[] payload = HeavyHammerProtocol.cancel(player.getUUID(), clientSequence);
        net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(
                player, new ua.rp.chat.client.heavyhammer.HeavyHammerSyncPayload(payload));
    }

    private void broadcast(ServerPlayer source, byte[] payload) {
        for (ServerPlayer observer : ((ServerLevel) source.level()).players()) {
            if (observer.position().distanceToSqr(source.position()) <= 96.0 * 96.0) {
                net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(
                        observer, new ua.rp.chat.client.heavyhammer.HeavyHammerSyncPayload(payload));
            }
        }
    }

    public void tick() {
        serverTick++;
        for (Map.Entry<UUID, PendingStrike> entry : new ArrayList<>(strikes.entrySet())) {
            UUID playerId = entry.getKey();
            PendingStrike strike = entry.getValue();
            ServerPlayer player = plugin.getServer().getPlayerList().getPlayer(playerId);
            if (player == null || player.connection == null) {
                strikes.remove(playerId, strike);
                continue;
            }
            if (!strike.impacted() && serverTick >= strike.impactAt()) {
                PendingStrike impacted = strike.withImpacted();
                if (strikes.replace(playerId, strike, impacted)) {
                    impact(player, impacted);
                    strike = impacted;
                }
            }
            if (serverTick >= strike.finishAt()) {
                finish(player, strike);
            }
        }
        pollCarryStates();
    }
    public void pollCarryStates() {
        carryPoll++;
        boolean refresh = carryPoll % 20 == 0;
        for (ServerPlayer player : plugin.getServer().getPlayerList().getPlayers()) {
            boolean present = false;
            for (ItemStack stack : player.getInventory().getNonEquipmentItems()) {
                if (isHeavyHammer(stack)) {
                    present = true;
                    break;
                }
            }
            boolean selected = isHeavyHammer(player.getMainHandItem());
            CarryState previous = carryStates.get(player.getUUID());
            CarryState current = new CarryState(present, selected);
            if (!current.equals(previous)) {
                carryStates.put(player.getUUID(), current);
                if (selected && (previous == null || !previous.selected())) {
                    readyAt.put(player.getUUID(), System.currentTimeMillis() + 1500L);
                } else if (!selected) {
                    readyAt.remove(player.getUUID());
                }
                broadcast(player, HeavyHammerProtocol.carry(player.getUUID(), nextCarryRevision(),
                        present, selected));
            } else if (refresh) {
                broadcast(player, HeavyHammerProtocol.carry(player.getUUID(), nextCarryRevision(),
                        present, selected));
            }
        }
    }

    private int nextCarryRevision() {
        int revision = carryRevision++;
        if (carryRevision <= 0) carryRevision = 1;
        return revision;
    }

    public void onJoin(ServerPlayer observer) {
        if (observer.connection == null) return;
        for (ServerPlayer source : ((ServerLevel) observer.level()).players()) {
            CarryState state = carryStates.get(source.getUUID());
            if (state != null && observer.position().distanceToSqr(source.position()) <= 96.0 * 96.0) {
                byte[] payload = HeavyHammerProtocol.carry(source.getUUID(), nextCarryRevision(),
                        state.present(), state.selected());
                net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(
                        observer, new ua.rp.chat.client.heavyhammer.HeavyHammerSyncPayload(payload));
            }
        }
    }

    public void onQuit(ServerPlayer player) {
        strikes.remove(player.getUUID());
        cooldowns.remove(player.getUUID());
        carryStates.remove(player.getUUID());
        readyAt.remove(player.getUUID());
    }

    public void giveHammer(ServerPlayer target, ServerPlayer sender) {
        target.getInventory().placeItemBackInInventory(createItem());
        if (sender != null) {
            sender.sendSystemMessage(Component.literal("Тяжёлый рабочий молот выдан игроку " + target.getScoreboardName()));
        }
    }

    private record PendingStrike(int sequence, MicrovoxelManager.HammerTarget target,
                                 long impactAt, long finishAt, boolean impacted) {
        private PendingStrike withImpacted() {
            return new PendingStrike(sequence, target, impactAt, finishAt, true);
        }
    }

    private record TargetPoint(double x, double y, double z) {
    }

    private record CarryState(boolean present, boolean selected) {
    }
}
