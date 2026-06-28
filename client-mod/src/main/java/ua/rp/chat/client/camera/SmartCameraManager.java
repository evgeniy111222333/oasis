package ua.rp.chat.client.camera;

import net.minecraft.client.Minecraft;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

public class SmartCameraManager {
    private static final SmartCameraManager INSTANCE = new SmartCameraManager();

    public static SmartCameraManager getInstance() {
        return INSTANCE;
    }

    private static final double SPRING_STIFFNESS = 180.0;
    private static final double SPRING_DAMPING = 22.0;
    private static final double Y_SMOOTH_FACTOR = 0.25;
    private static final double MAX_LANDING_COMPRESS = 0.4;

    private double landingDisplacement = 0.0;
    private double landingVelocity = 0.0;
    private boolean wasOnGround = true;

    private double lastSmoothY = -1.0;
    private double stridePhase = 0.0;
    private double strideStrength = 0.0;
    private double idlePhase = 0.0;
    private double lean = 0.0;
    private double weaponLag = 0.0;
    private double weaponOffsetX = 0.0;
    private double weaponOffsetY = 0.0;
    private double weaponVelocityX = 0.0;
    private double weaponVelocityY = 0.0;
    private double recoil = 0.0;
    private double proximity = 0.0;
    private float lastYaw = Float.NaN;
    private float lastYawDelta = 0.0f;
    private float lastPitch = Float.NaN;
    private boolean wasUsingRanged = false;
    private int armorStepCooldown = 0;
    private boolean renderingFirstPersonPlayer = false;
    private boolean enabled = true;

    private SmartCameraManager() {}

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isRenderingFirstPersonPlayer() {
        return renderingFirstPersonPlayer;
    }

    public void setRenderingFirstPersonPlayer(boolean state) {
        this.renderingFirstPersonPlayer = state;
    }

    public boolean isActive() {
        return isCameraMotionActive();
    }

    public boolean isFirstPersonBodyEnabled() {
        Minecraft client = Minecraft.getInstance();
        return enabled
                && client != null
                && client.player != null
                && client.level != null
                && client.options.getCameraType().isFirstPerson();
    }

    public boolean isCameraMotionActive() {
        Minecraft client = Minecraft.getInstance();
        return isFirstPersonBodyEnabled()
                && client != null
                && client.screen == null;
    }

    public boolean isActiveFor(Player player) {
        return isCameraMotionActiveFor(player);
    }

    public boolean isCameraMotionActiveFor(Player player) {
        Minecraft client = Minecraft.getInstance();
        return isCameraMotionActive() && player == client.player;
    }

    public void clientTick(Minecraft client) {
        if (client == null || client.player == null || client.level == null) {
            resetTransientState();
            return;
        }

        LocalPlayer player = client.player;
        if (!isCameraMotionActiveFor(player)) {
            armorStepCooldown = Math.max(0, armorStepCooldown - 1);
            return;
        }

        if (armorStepCooldown > 0) {
            armorStepCooldown--;
        }

        Vec3 velocity = player.getDeltaMovement();
        double horizontalSpeed = Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);
        if (player.onGround() && horizontalSpeed > 0.075 && player.getArmorValue() >= 12 && armorStepCooldown == 0) {
            float volume = (float) Math.min(0.42, 0.12 + player.getArmorValue() * 0.015);
            float pitch = player.isSprinting() ? 1.08f : 0.92f;
            player.playSound(SoundEvents.CHAIN_STEP, volume, pitch);
            armorStepCooldown = player.isSprinting() ? 9 : 13;
        }

        updateActionImpulses(player);
    }

    public void updatePhysics(Player player, float partialTick) {
        if (player == null) return;

        boolean onGround = player.onGround();
        if (onGround && !wasOnGround) {
            double fallDist = player.fallDistance;
            if (fallDist > 1.5) {
                double impactVelocity = Math.min(2.0, Math.sqrt(2 * 0.08 * fallDist * 20));
                landingVelocity = -impactVelocity * 0.15;
            }
        }
        wasOnGround = onGround;

        double deltaTime = 0.05;
        double force = -SPRING_STIFFNESS * landingDisplacement - SPRING_DAMPING * landingVelocity;
        landingVelocity += force * deltaTime;
        landingDisplacement += landingVelocity * deltaTime;

        if (landingDisplacement < -MAX_LANDING_COMPRESS) {
            landingDisplacement = -MAX_LANDING_COMPRESS;
            landingVelocity = 0;
        } else if (landingDisplacement > 0.05) {
            landingDisplacement = 0.05;
        }

        Vec3 velocity = player.getDeltaMovement();
        double horizontalSpeed = Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);
        double targetStride = player.onGround() ? Math.min(1.0, horizontalSpeed * (player.isSprinting() ? 8.0 : 6.0)) : 0.0;
        strideStrength += (targetStride - strideStrength) * 0.18;
        stridePhase += (0.18 + horizontalSpeed * 1.55) * Math.max(0.2, strideStrength);
        idlePhase += 0.012;

        updateTurnInertia(player);
        updateWeaponSpring(player);
        updateProximity(player);

        double lateral = getLocalLateralVelocity(player, velocity);
        double targetLean = clamp(lateral * 2.4, -0.28, 0.28);
        if (!player.onGround() && Math.abs(lateral) > 0.09) {
            targetLean *= 1.35;
        }
        lean += (targetLean - lean) * 0.18;

        double weaponWeight = getWeaponWeight(player);
        weaponLag += ((weaponWeight * Math.abs(getYawDelta(player)) / 70.0) - weaponLag) * (0.08 + (1.0 - weaponWeight) * 0.08);
        weaponLag *= 0.92;
    }

    public Vec3 getCameraOffset(double yaw, double pitch) {
        if (!enabled) {
            return Vec3.ZERO;
        }
        // Forward offset виносить обличчя попереду шиї, тулуб менше видно в кадрі.
        // Невеликий down-offset імітує положення очей у черепі, не на маківці.
        double yawRad = Math.toRadians(yaw);
        double pitchDown = clamp(pitch, 0.0, 90.0);
        double inspect = smoothStep(20.0, 80.0, pitchDown);
        double forwardAmount = 0.20 + inspect * 0.05;
        double downAmount = 0.02 + inspect * 0.03;
        return new Vec3(
                -Math.sin(yawRad) * forwardAmount,
                -downAmount,
                Math.cos(yawRad) * forwardAmount
        );
    }

    public Vec3 getEyeOffset(double yaw, double pitch) {
        // Eye offset == camera offset => курсор (raycast з Camera.getPosition)
        // автоматично співпадає зі зором гравця. Моби теж "бачать" гравця
        // у новій позиції обличчя, а не старій позиції шиї.
        return getCameraOffset(yaw, pitch);
    }

    public void applyFirstPersonBodyPose(PlayerModel model) {
        Minecraft client = Minecraft.getInstance();
        if (model == null || client == null || client.player == null) {
            return;
        }

        LocalPlayer player = client.player;
        model.head.visible = false;
        model.hat.visible = false;

        model.body.visible = true;
        model.jacket.visible = true;
    }

    public boolean shouldCullTorso() {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.player == null) {
            return false;
        }
        return false;
    }

    private void syncWearableLayers(PlayerModel model) {
        // head/hat синхронізуємо також — раніше це пропускалось,
        // через що кастомні зміни head призводили до "летючої" шапки.
        copyPose(model.head, model.hat);
        copyPose(model.rightArm, model.rightSleeve);
        copyPose(model.leftArm, model.leftSleeve);
        copyPose(model.rightLeg, model.rightPants);
        copyPose(model.leftLeg, model.leftPants);
        copyPose(model.body, model.jacket);
    }

    private void copyPose(net.minecraft.client.model.geom.ModelPart from, net.minecraft.client.model.geom.ModelPart to) {
        if (from == null || to == null) {
            return;
        }
        to.x = from.x;
        to.y = from.y;
        to.z = from.z;
        to.xRot = from.xRot;
        to.yRot = from.yRot;
        to.zRot = from.zRot;
    }

    public boolean shouldRenderHelmetVisor() {
        Minecraft client = Minecraft.getInstance();
        if (!isFirstPersonBodyEnabled() || client.player == null) {
            return false;
        }
        ItemStack helmet = client.player.getItemBySlot(EquipmentSlot.HEAD);
        return !helmet.isEmpty() && client.player.getArmorValue() >= 10;
    }

    public float getHelmetVisorAlpha() {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.player == null) {
            return 0.0f;
        }
        return clampFloat(0.12f + client.player.getArmorValue() * 0.012f, 0.14f, 0.32f);
    }

    public double getStabilizedY(Player player, double rawY, float partialTick) {
        return rawY;
    }

    private void updateTurnInertia(Player player) {
        if (Float.isNaN(lastYaw)) {
            lastYaw = player.getYRot();
            lastYawDelta = 0.0f;
            return;
        }
        float delta = player.getYRot() - lastYaw;
        while (delta > 180.0f) delta -= 360.0f;
        while (delta < -180.0f) delta += 360.0f;
        lastYawDelta = delta;
        lastYaw = player.getYRot();
    }

    private float getYawDelta(Player player) {
        return lastYawDelta;
    }

    private float getPitchDelta(Player player) {
        if (Float.isNaN(lastPitch)) {
            lastPitch = player.getXRot();
            return 0.0f;
        }
        float delta = player.getXRot() - lastPitch;
        lastPitch = player.getXRot();
        return delta;
    }

    private void updateWeaponSpring(Player player) {
        double weight = getWeaponWeight(player);
        double damping = 0.70 - weight * 0.16;
        double stiffness = 0.18 - weight * 0.06;
        double yawImpulse = clamp(getYawDelta(player) / 34.0, -1.6, 1.6);
        double pitchImpulse = clamp(getPitchDelta(player) / 42.0, -1.2, 1.2);
        double bobX = Math.sin(stridePhase) * strideStrength * (player.isSprinting() ? 0.34 : 0.22);
        double bobY = Math.sin(stridePhase * 2.0) * strideStrength * (player.isSprinting() ? 0.20 : 0.12);
        double idle = Math.sin(idlePhase) * (1.0 - strideStrength) * 0.012;
        double targetX = -yawImpulse * (0.28 + weight * 0.58) + bobX;
        double targetY = pitchImpulse * (0.18 + weight * 0.32) + bobY + idle - proximity * 0.32 + recoil * 0.55;

        weaponVelocityX += (targetX - weaponOffsetX) * stiffness;
        weaponVelocityY += (targetY - weaponOffsetY) * stiffness;
        weaponVelocityX *= damping;
        weaponVelocityY *= damping;
        weaponOffsetX = clamp(weaponOffsetX + weaponVelocityX, -1.05, 1.05);
        weaponOffsetY = clamp(weaponOffsetY + weaponVelocityY, -0.85, 0.85);
        recoil *= 0.82;
    }

    private void updateProximity(Player player) {
        Minecraft client = Minecraft.getInstance();
        double target = 0.0;
        if (client != null && client.hitResult != null && client.hitResult.getType() == HitResult.Type.BLOCK) {
            double distance = client.hitResult.distanceTo(player);
            target = clamp((1.35 - distance) / 0.85, 0.0, 1.0);
        }
        proximity += (target - proximity) * 0.22;
    }

    private void updateActionImpulses(Player player) {
        if (player.swinging && player.swingTime <= 2) {
            recoil = Math.max(recoil, 0.48 + getWeaponWeight(player) * 0.34);
            weaponVelocityX += player.swingingArm == net.minecraft.world.InteractionHand.OFF_HAND ? 0.22 : -0.22;
            weaponVelocityY += 0.22;
        }

        boolean usingRanged = player.isUsingItem() && isRangedUse(player.getUseItem());
        if (wasUsingRanged && !usingRanged) {
            recoil = Math.max(recoil, 0.62);
            weaponVelocityY += 0.32;
        }
        wasUsingRanged = usingRanged;
    }

    private double getLocalLateralVelocity(Player player, Vec3 velocity) {
        double yawRad = Math.toRadians(player.getYRot());
        return velocity.x * Math.cos(yawRad) + velocity.z * Math.sin(yawRad);
    }

    private double getWeaponWeight(Player player) {
        ItemStack main = player.getMainHandItem();
        ItemStack off = player.getOffhandItem();
        return Math.max(getItemWeight(main), getItemWeight(off) * 0.7);
    }

    private double getItemWeight(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return 0.0;
        }

        String id = stack.getItem().toString().toLowerCase();
        if (id.contains("shield")) return 0.75;
        if (id.contains("mace")) return 0.95;
        if (id.contains("axe")) return 0.85;
        if (id.contains("sword")) return 0.72;
        if (id.contains("trident") || id.contains("spear")) return 0.68;
        return 0.0;
    }

    private boolean isUsingShield(Player player) {
        if (!player.isUsingItem()) {
            return false;
        }
        ItemStack useItem = player.getUseItem();
        return useItem != null && useItem.getUseAnimation() == ItemUseAnimation.BLOCK;
    }

    private void resetTransientState() {
        landingDisplacement = 0.0;
        landingVelocity = 0.0;
        lastSmoothY = -1.0;
        strideStrength = 0.0;
        idlePhase = 0.0;
        lean = 0.0;
        weaponLag = 0.0;
        weaponOffsetX = 0.0;
        weaponOffsetY = 0.0;
        weaponVelocityX = 0.0;
        weaponVelocityY = 0.0;
        recoil = 0.0;
        proximity = 0.0;
        lastYaw = Float.NaN;
        lastYawDelta = 0.0f;
        lastPitch = Float.NaN;
        wasUsingRanged = false;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double smoothStep(double edge0, double edge1, double value) {
        double x = clamp((value - edge0) / (edge1 - edge0), 0.0, 1.0);
        return x * x * (3.0 - 2.0 * x);
    }

    private boolean isRangedUse(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        ItemUseAnimation animation = stack.getUseAnimation();
        return animation == ItemUseAnimation.BOW
                || animation == ItemUseAnimation.CROSSBOW
                || animation == ItemUseAnimation.TRIDENT
                || animation == ItemUseAnimation.SPEAR;
    }

    private static float clampFloat(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
