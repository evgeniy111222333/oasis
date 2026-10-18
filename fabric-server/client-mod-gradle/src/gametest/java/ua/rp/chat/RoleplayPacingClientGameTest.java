package ua.rp.chat;

import com.mojang.authlib.GameProfile;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import ua.rp.chat.locomotion.RoleplayPaceAttributes;
import ua.rp.chat.locomotion.RoleplayPacing;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Native movement attributes and real LocalPlayer.aiStep physics, including same-tick sprint changes. */
public final class RoleplayPacingClientGameTest implements FabricClientGameTest {
    private static final double WALK_METERS_PER_SECOND =
            RoleplayPacing.WALK_STRIDE_METERS / RoleplayPacing.WALK_CYCLE_SECONDS;
    private static final double CROUCH_METERS_PER_SECOND =
            RoleplayPacing.CROUCH_STRIDE_METERS / RoleplayPacing.CROUCH_CYCLE_SECONDS;
    private static final double SPRINT_METERS_PER_SECOND =
            RoleplayPacing.SPRINT_STRIDE_METERS / RoleplayPacing.SPRINT_CYCLE_SECONDS;

    @Override public void runTest(ClientGameTestContext context) {
        context.waitTicks(3);
        context.waitFor(client -> com.cinemamod.mcef.MCEF.isInitialized(), 3600);
        try (var world = context.worldBuilder().create()) {
            world.getClientLevel().waitForChunksRender();
            context.waitFor(client -> client.player != null && client.player.onGround(), 300);
            context.runOnClient(client -> {
                verifyNativeAttributes(client);
                verifyNativeFieldOfView(client);
                verifyClientPhysicsHook(client);
            });
        }
    }

    /** Pace is a locomotion choice; native sprint, vitals, external modifiers and bow still affect FOV. */
    private static void verifyNativeFieldOfView(Minecraft client) {
        ProbePlayer player = new ProbePlayer(client);
        AttributeInstance speed = player.getAttribute(Attributes.MOVEMENT_SPEED);
        require(speed != null, "FOV regression requires native movement-speed attributes");
        for (boolean reducedBase : List.of(false, true)) {
            speed.setBaseValue(reducedBase ? .071 : .1);
            for (boolean externalSlowness : List.of(false, true)) {
                AttributeModifier external = new AttributeModifier(Identifier.parse("eclipserp_test:fov_slowness"),
                        -.2, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
                if (externalSlowness) speed.addTransientModifier(external);
                for (boolean crouch : List.of(false, true)) {
                    player.setPose(crouch ? Pose.CROUCHING : Pose.STANDING);
                    for (boolean sprint : List.of(false, true)) {
                        player.setSprinting(sprint);
                        for (boolean drawnBow : List.of(false, true)) {
                            player.drawnBow = drawnBow;
                            RoleplayPaceAttributes.clear(player);
                            float ordinary = player.getFieldOfViewModifier(false, 1);
                            RoleplayPaceAttributes.apply(player);
                            float paced = player.getFieldOfViewModifier(false, 1);
                            near(paced, ordinary, 2e-6,
                                    "RP pacing must preserve native FOV (reducedBase=" + reducedBase
                                            + ", slowness=" + externalSlowness + ", crouch=" + crouch
                                            + ", sprint=" + sprint + ", bow=" + drawnBow + ")");
                        }
                    }
                }
                RoleplayPaceAttributes.clear(player);
                player.setSprinting(false);
                speed.removeModifier(external.id());
            }
        }
        System.out.println("[ROLEPLAY-PACING-TEST] Native FOV unchanged by RP pace across standing/crouch/sprint, vitals, slowness and drawn-bow combinations");
    }

    private static void verifyNativeAttributes(Minecraft client) {
        ProbePlayer player = new ProbePlayer(client);
        AttributeInstance speed = player.getAttribute(Attributes.MOVEMENT_SPEED);
        require(speed != null, "Native player must provide the movement-speed attribute");
        speed.setBaseValue(.071); // A vitals system has already lowered the ordinary .1 base.
        AttributeModifier external = new AttributeModifier(Identifier.parse("eclipserp_test:external_speed"),
                .11, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
        speed.addTransientModifier(external);
        double ordinary = speed.getValue();
        for (int capture = 0; capture < 100; capture++) RoleplayPaceAttributes.apply(player);
        near(speed.getValue(), ordinary * RoleplayPacing.WALK_FACTOR, 1e-10,
                "Repeated client/server pace application must not compound modifiers");
        require(speed.getModifiers().size() == 2 && speed.getPermanentModifiers().isEmpty(),
                "Pacing must add exactly one transient modifier beside the existing modifier");
        near(speed.getBaseValue(), .071, 1e-12, "Pacing must preserve the vitals-owned base speed");
        require(speed.getModifier(external.id()) == external,
                "Pacing must preserve unrelated speed modifiers");

        player.setSprinting(true);
        near(RoleplayPaceAttributes.apply(player), RoleplayPacing.SPRINT_FACTOR, 1e-12,
                "Sprint must use the calibrated RP sprint factor");
        require(speed.hasModifier(RoleplayPaceAttributes.MODIFIER_ID),
                "Entering sprint must update the RP pace modifier in the same update");
        near(speed.getValue(), ordinary * 1.3 * RoleplayPacing.SPRINT_FACTOR, 1e-7,
                "Native sprint's modifier and external vitals modifiers must compose with RP sprint pace");
        player.setSprinting(false);
        RoleplayPaceAttributes.apply(player);
        near(speed.getValue(), ordinary * RoleplayPacing.WALK_FACTOR, 1e-10,
                "Leaving sprint must restore the RP walk pace immediately");
        player.setShiftKeyDown(true);
        player.setPose(Pose.CROUCHING);
        near(RoleplayPaceAttributes.apply(player), RoleplayPacing.CROUCH_FACTOR, 1e-12,
                "Crouching must use the calibrated 1.15-second cadence factor");
        near(speed.getValue(), ordinary * RoleplayPacing.CROUCH_FACTOR, 1e-10,
                "Crouch pace must replace the walk modifier");
        player.setShiftKeyDown(false);
        player.setPose(Pose.STANDING);
        player.setOnGround(false);
        near(RoleplayPaceAttributes.apply(player), RoleplayPacing.WALK_FACTOR, 1e-12,
                "An ordinary jump must retain its takeoff pace");
        player.setOnGround(true);

        for (Exemption exemption : Exemption.values()) {
            RoleplayPaceAttributes.apply(player);
            player.exemption = exemption;
            player.getAbilities().flying = exemption == Exemption.FLIGHT;
            near(RoleplayPaceAttributes.apply(player), 1, 1e-12,
                    "Unsupported movement must retain native speed: " + exemption);
            require(!speed.hasModifier(RoleplayPaceAttributes.MODIFIER_ID),
                    "Unsupported movement must clear stale RP pace: " + exemption);
            near(speed.getValue(), ordinary, 1e-10,
                    "Unsupported movement must preserve external modifiers: " + exemption);
            player.exemption = null;
            player.getAbilities().flying = false;
        }
        RoleplayPaceAttributes.apply(player);
        RoleplayPaceAttributes.clear(player);
        RoleplayPaceAttributes.clear(player);
        near(speed.getValue(), ordinary, 1e-10, "Clearing pace must be idempotent");
        System.out.println("[ROLEPLAY-PACING-TEST] Native transient pace is idempotent; vitals base, external modifiers, sprint and all movement exemptions verified");
    }

    /** No helper applies pacing here: LocalPlayer.aiStep must invoke the production mixin before travel. */
    private static void verifyClientPhysicsHook(Minecraft client) {
        LocalPlayer player = client.player;
        AttributeInstance attribute = player.getAttribute(Attributes.MOVEMENT_SPEED);
        require(attribute != null, "Local player must provide native speed attributes");
        Snapshot snapshot = new Snapshot(player, attribute);
        try {
            attribute.removeModifiers();
            attribute.setBaseValue(.1);
            player.getAbilities().flying = false;
            player.setSprinting(false);
            player.setPose(Pose.STANDING);
            player.setYRot(0); player.yRotO = 0;
            player.setXRot(0); player.xRotO = 0;
            player.yBodyRot = player.yBodyRotO = player.yHeadRot = player.yHeadRotO = 0;
            player.setOnGround(true);
            FixedInput input = new FixedInput();
            player.input = input;

            input.set(false, false);
            player.setSpeed(.1f); // Deliberately leave the previous native speed cached.
            double firstWalk = stepFromRest(player);
            require(firstWalk > .048 && firstWalk < .068,
                    "First walking physics tick must already use RP pace, not stale native speed: " + firstWalk);
            double walk = steadySpeed(player);
            near(walk, WALK_METERS_PER_SECOND, .045,
                    "Real client walk displacement must match the calibrated 0.90-second stride");

            input.set(true, false);
            double firstSprint = stepFromRest(player);
            require(player.isSprinting() && attribute.hasModifier(RoleplayPaceAttributes.MODIFIER_ID),
                    "Native sprint input must apply RP sprint pacing before that tick's physics");
            require(firstSprint > .072 && firstSprint < .098,
                    "First sprint physics tick must use calibrated RP sprint acceleration: " + firstSprint);
            double sprint = steadySpeed(player);
            near(sprint, SPRINT_METERS_PER_SECOND, .055,
                    "Real client sprint displacement must match the calibrated 0.85-second stride");
            require(sprint > walk * 1.38,
                    "Sprint must remain noticeably faster than RP walk: " + sprint + " m/s vs RP walk " + walk);

            input.set(false, false);
            player.setSprinting(false);
            double firstReturn = stepFromRest(player);
            require(firstReturn > .048 && firstReturn < .068,
                    "Leaving sprint must restore RP acceleration before the first walk tick: " + firstReturn);

            input.set(false, true);
            double firstCrouch = stepFromRest(player);
            require(player.isCrouching() && firstCrouch > .018 && firstCrouch < .028,
                    "Crouch input must immediately use its own RP acceleration: " + firstCrouch);
            double crouch = steadySpeed(player);
            near(crouch, CROUCH_METERS_PER_SECOND, .035,
                    "Real crouching displacement must match the calibrated 1.15-second stride");
            System.out.println("[ROLEPLAY-PACING-TEST] Real LocalPlayer.aiStep physics: walk=" + walk
                    + ", sprint=" + sprint + ", crouch=" + crouch
                    + " m/s; first-tick walk/sprint/walk/crouch acceleration verified");
        } finally {
            snapshot.restore(player, attribute);
        }
    }

    private static double stepFromRest(LocalPlayer player) {
        // A grounded native tick carries downward gravity into the collision solve. Erasing Y
        // makes move(dy=0) clear its onGround flag despite remaining at the floor's exact height.
        player.setDeltaMovement(0, -.08, 0);
        return step(player);
    }

    private static double step(LocalPlayer player) {
        player.setOldPosAndRot();
        Vec3 from = player.position();
        player.tickCount++;
        player.aiStep();
        require(player.onGround(), "Native pace physics must stay on the flat test-world floor: y="
                + player.getY() + ", fromY=" + from.y + ", velocity=" + player.getDeltaMovement());
        return Math.hypot(player.getX() - from.x, player.getZ() - from.z);
    }

    private static double steadySpeed(LocalPlayer player) {
        double distance = 0;
        for (int tick = 0; tick < 60; tick++) {
            double moved = step(player);
            if (tick >= 30) distance += moved;
        }
        return distance / 30 * 20;
    }

    private static final class FixedInput extends ClientInput {
        void set(boolean sprint, boolean crouch) {
            keyPresses = new Input(true, false, false, false, false, crouch, sprint);
            moveVector = new Vec2(0, 1);
        }
        @Override public void tick() {}
    }

    private enum Exemption { FLIGHT, ELYTRA, WATER, SWIMMING, LADDER, PASSENGER, SPECTATOR, DEAD }

    private static final class ProbePlayer extends RemotePlayer {
        private Exemption exemption;
        private boolean drawnBow;
        private final ItemStack bow = new ItemStack(Items.BOW);
        ProbePlayer(Minecraft client) {
            super(client.level, new GameProfile(new UUID(0xEC, 0x9ACE), "PacingAudit"));
            setPos(client.player.position());
            setOnGround(true);
        }
        @Override public boolean isFallFlying() { return exemption == Exemption.ELYTRA; }
        @Override public boolean isInWater() { return exemption == Exemption.WATER; }
        @Override public boolean isSwimming() { return exemption == Exemption.SWIMMING; }
        @Override public boolean onClimbable() { return exemption == Exemption.LADDER; }
        @Override public boolean isPassenger() { return exemption == Exemption.PASSENGER; }
        @Override public boolean isSpectator() { return exemption == Exemption.SPECTATOR; }
        @Override public boolean isAlive() { return exemption != Exemption.DEAD; }
        @Override public boolean isUsingItem() { return drawnBow; }
        @Override public ItemStack getUseItem() { return drawnBow ? bow : ItemStack.EMPTY; }
        @Override public int getTicksUsingItem() { return drawnBow ? 20 : 0; }
    }

    private static final class Snapshot {
        final ClientInput input;
        final Vec3 position, oldPosition, oldInterpolationPosition, velocity;
        final double base;
        final List<AttributeModifier> modifiers;
        final Set<AttributeModifier> permanent;
        final Pose pose;
        final int tickCount;
        final boolean flying, sprinting, grounded;
        final float cachedSpeed, yaw, oldYaw, pitch, oldPitch, body, oldBody, head, oldHead;
        Snapshot(LocalPlayer player, AttributeInstance attribute) {
            input = player.input; position = player.position(); velocity = player.getDeltaMovement();
            oldPosition = new Vec3(player.xOld, player.yOld, player.zOld);
            oldInterpolationPosition = new Vec3(player.xo, player.yo, player.zo);
            base = attribute.getBaseValue(); modifiers = new ArrayList<>(attribute.getModifiers());
            permanent = new HashSet<>(attribute.getPermanentModifiers());
            pose = player.getPose(); tickCount = player.tickCount;
            flying = player.getAbilities().flying; sprinting = player.isSprinting(); grounded = player.onGround();
            cachedSpeed = player.getSpeed(); yaw = player.getYRot(); oldYaw = player.yRotO;
            pitch = player.getXRot(); oldPitch = player.xRotO;
            body = player.yBodyRot; oldBody = player.yBodyRotO; head = player.yHeadRot; oldHead = player.yHeadRotO;
        }
        void restore(LocalPlayer player, AttributeInstance attribute) {
            player.input = input; player.setPos(position); player.setDeltaMovement(velocity);
            player.xOld = oldPosition.x; player.yOld = oldPosition.y; player.zOld = oldPosition.z;
            player.xo = oldInterpolationPosition.x; player.yo = oldInterpolationPosition.y; player.zo = oldInterpolationPosition.z;
            player.setPose(pose); player.tickCount = tickCount; player.getAbilities().flying = flying;
            player.setSprinting(sprinting); player.setOnGround(grounded);
            player.setYRot(yaw); player.yRotO = oldYaw; player.setXRot(pitch); player.xRotO = oldPitch;
            player.yBodyRot = body; player.yBodyRotO = oldBody; player.yHeadRot = head; player.yHeadRotO = oldHead;
            attribute.removeModifiers(); attribute.setBaseValue(base);
            for (AttributeModifier modifier : modifiers) {
                if (permanent.contains(modifier)) attribute.addPermanentModifier(modifier);
                else attribute.addTransientModifier(modifier);
            }
            player.setSpeed(cachedSpeed);
        }
    }

    private static void near(double actual, double expected, double tolerance, String message) {
        require(Math.abs(actual - expected) <= tolerance, message + ": " + actual + " vs " + expected);
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
