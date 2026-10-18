package ua.rp.chat.client.carry;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.*;
import java.util.UUID;

/** A target hint only; the server independently validates the nearest visible object. */
public final class CarryTargeting {
    public static final double REACH = 3;
    private CarryTargeting() {}

    public static Target find(Minecraft client, boolean includeBlocks) {
        if (client.player == null || client.level == null) return null;
        Vec3 eye = client.player.getEyePosition(), look = client.player.getLookAngle().normalize();
        BlockHitResult hit = blockHit(client);
        double reach = hit.getType() == HitResult.Type.BLOCK ? eye.distanceTo(hit.getLocation()) : REACH;
        Target nearest = null;
        for (var prop : CarryClientState.props()) {
            if (!prop.world().equals(client.level.dimension().identifier().toString())) continue;
            double distance = ray(eye, look, prop.bounds(), reach);
            if (distance < reach) { reach = distance; nearest = new Target(prop.id(), null, prop.entity(), prop.name()); }
        }
        AABB search = client.player.getBoundingBox().expandTowards(look.scale(REACH)).inflate(.3);
        for (Entity entity : client.level.getEntities(client.player, search, candidate -> candidate instanceof ItemEntity)) {
            ItemEntity item = (ItemEntity) entity;
            if (!item.isAlive() || item.hasPickUpDelay() || item.getItem().isEmpty()) continue;
            double distance = ray(eye, look, item.getBoundingBox().inflate(.14), reach);
            if (distance < reach) {
                reach = distance; nearest = new Target(item.getUUID(), null, item.getId(), item.getItem().getHoverName().getString());
            }
        }
        if (nearest != null) return nearest;
        if (!includeBlocks || hit.getType() != HitResult.Type.BLOCK) return null;
        BlockPos pos = hit.getBlockPos();
        var state = client.level.getBlockState(pos);
        if (!CarryClientState.liftable(state) || state.isAir() || !state.getFluidState().isEmpty()
                || state.getDestroySpeed(client.level, pos) < 0) return null;
        return new Target(null, pos.immutable(), -1, state.getBlock().getName().getString());
    }
    public static BlockHitResult blockHit(Minecraft client) {
        Vec3 eye = client.player.getEyePosition();
        return client.level.clip(new ClipContext(eye, eye.add(client.player.getLookAngle().normalize().scale(REACH)),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, client.player));
    }
    public static Player player(Minecraft client) {
        if (client.player == null || client.level == null) return null;
        Vec3 eye = client.player.getEyePosition(), look = client.player.getLookAngle().normalize();
        BlockHitResult block = blockHit(client);
        double reach = block.getType() == HitResult.Type.BLOCK ? eye.distanceTo(block.getLocation()) : REACH;
        Player nearest = null;
        for (Player player : client.level.players()) {
            if (player == client.player || !player.isAlive() || player.isSpectator()) continue;
            double distance = ray(eye, look, player.getBoundingBox().inflate(.08), reach);
            if (distance < reach) { reach = distance; nearest = player; }
        }
        return nearest;
    }
    private static double ray(Vec3 eye, Vec3 look, AABB bounds, double limit) {
        return CarryMath.rayBox(eye.x, eye.y, eye.z, look.x, look.y, look.z,
                bounds.minX, bounds.minY, bounds.minZ, bounds.maxX, bounds.maxY, bounds.maxZ, limit);
    }
    public record Target(UUID id, BlockPos block, int entity, String name) {}
}
