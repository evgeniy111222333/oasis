package ua.rp.chat.interaction;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import ua.rp.chat.RPChat;
import ua.rp.chat.mixin.ItemEntityAccessor;

import java.util.UUID;

public final class ItemPickupManager {
    public static final String ACTION_CHANNEL = "rpchat:item_pickup";
    private static final double ITEM_RAY_MARGIN = 0.14;
    private final RPChat plugin;

    public ItemPickupManager(RPChat plugin) {
        this.plugin = plugin;
    }

    public void start() {
    }

    public InteractionResult onUseEntity(ServerPlayer player, ItemEntity item, InteractionHand hand) {
        if (hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
        take(player, item);
        return InteractionResult.SUCCESS;
    }

    public void handlePickup(ServerPlayer player, UUID itemId) {
        if (player == null) return;
        net.minecraft.world.entity.Entity entity = ((ServerLevel) player.level()).getEntity(itemId);
        if (entity instanceof ItemEntity item) {
            take(player, item);
        } else {
            deny(player, "Предмет уже недоступен.");
        }
    }

    private void take(ServerPlayer player, ItemEntity item) {
        if (plugin.getAuthManager().isPendingAuth(player.getUUID())) {
            deny(player, "Сначала завершите авторизацию.");
            return;
        }
        if (!item.isAlive()) {
            deny(player, "Предмет уже недоступен.");
            return;
        }

        double distanceSquared = distanceSquaredToBox(
                player.getEyePosition(), item.getBoundingBox().inflate(ITEM_RAY_MARGIN));
        UUID pickupTarget = ((ItemEntityAccessor) (Object) item).eclipse$getPickupTarget();
        if (!ItemPickupRules.mayPickUp(true, item.hasPickUpDelay() ? 1 : 0,
                pickupTarget, player.getUUID(), distanceSquared)) {
            if (item.hasPickUpDelay()) {
                deny(player, "Предмет пока нельзя поднять.");
            } else if (pickupTarget != null && !pickupTarget.equals(player.getUUID())) {
                deny(player, "Этот предмет пока предназначен другому игроку.");
            } else {
                deny(player, "Подойдите ближе к предмету.");
            }
            return;
        }
        if (!isLookingAt(player, item)) {
            deny(player, "Наведитесь на предмет точнее.");
            return;
        }

        ItemStack offered = item.getItem();
        if (offered.isEmpty()) return;
        
        ItemStack toAdd = offered.copy();
        int originalCount = toAdd.getCount();
        player.getInventory().add(toAdd);
        int remaining = toAdd.getCount();
        
        if (remaining == originalCount) {
            deny(player, "Недостаточно места в инвентаре.");
            return;
        }

        int pickedUp = originalCount - remaining;
        player.take(item, pickedUp);
        if (remaining == 0) {
            item.discard();
        } else {
            item.setItem(toAdd.copy());
        }
        player.awardStat(Stats.ITEM_PICKED_UP.get(offered.getItem()), pickedUp);
        player.onItemPickup(item);
        player.containerMenu.broadcastChanges();
        ((ServerLevel) player.level()).playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.2f, 1.0f);
    }

    private static boolean isLookingAt(ServerPlayer player, ItemEntity expected) {
        ServerLevel level = (ServerLevel) player.level();
        Vec3 eyePosition = player.getEyePosition();
        Vec3 lookAngle = player.getLookAngle().normalize();
        Vec3 endPos = eyePosition.add(lookAngle.scale(ItemPickupRules.MAX_INTERACTION_DISTANCE));
        BlockHitResult blockHit = level.clip(new net.minecraft.world.level.ClipContext(
                eyePosition, endPos,
                net.minecraft.world.level.ClipContext.Block.COLLIDER,
                net.minecraft.world.level.ClipContext.Fluid.NONE,
                player
        ));
        double maxDistSqr = ItemPickupRules.MAX_INTERACTION_DISTANCE * ItemPickupRules.MAX_INTERACTION_DISTANCE;
        if (blockHit.getType() != HitResult.Type.MISS) {
            maxDistSqr = blockHit.getLocation().distanceToSqr(eyePosition);
        }

        AABB searchBounds = player.getBoundingBox()
                .expandTowards(lookAngle.scale(ItemPickupRules.MAX_INTERACTION_DISTANCE))
                .inflate(ITEM_RAY_MARGIN);
        ItemEntity nearest = null;
        double nearestDistanceSquared = maxDistSqr;
        for (Entity entity : level.getEntities(player, searchBounds,
                candidate -> candidate instanceof ItemEntity && candidate.isAlive())) {
            ItemEntity item = (ItemEntity) entity;
            var intersection = item.getBoundingBox().inflate(ITEM_RAY_MARGIN).clip(eyePosition, endPos);
            if (intersection.isEmpty()) continue;
            double hitDistanceSquared = eyePosition.distanceToSqr(intersection.get());
            if (hitDistanceSquared < nearestDistanceSquared) {
                nearest = item;
                nearestDistanceSquared = hitDistanceSquared;
            }
        }
        return expected.equals(nearest);
    }

    public static double distanceSquaredToBox(Vec3 point, AABB box) {
        double dx = Math.max(Math.max(box.minX - point.x, 0.0), point.x - box.maxX);
        double dy = Math.max(Math.max(box.minY - point.y, 0.0), point.y - box.maxY);
        double dz = Math.max(Math.max(box.minZ - point.z, 0.0), point.z - box.maxZ);
        return dx * dx + dy * dy + dz * dz;
    }

    private static void deny(ServerPlayer player, String message) {
        player.sendSystemMessage(Component.literal(message), true);
    }
}
