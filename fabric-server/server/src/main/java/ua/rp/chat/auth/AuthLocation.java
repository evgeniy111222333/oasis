package ua.rp.chat.auth;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

public record AuthLocation(ServerLevel level, double x, double y, double z, float yaw, float pitch) {
    public Vec3 position() {
        return new Vec3(x, y, z);
    }
}
