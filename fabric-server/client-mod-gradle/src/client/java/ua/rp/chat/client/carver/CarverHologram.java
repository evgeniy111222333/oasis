package ua.rp.chat.client.carver;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import ua.rp.chat.carver.CarverHologramMotion;
import ua.rp.chat.carver.CarverHologramOffset;

/**
 * Client-only hologram of the focused block. On design entry the real block is
 * hidden locally (the server and other players keep seeing it untouched) and a
 * copy rises straight up out of the socket, drawn by {@link CarverHologramRenderer}
 * with exact socket-corner geometry and explicit biome tint.
 *
 * <p>Painting, chalk and the camera all track the same anchor, so marks land on the
 * same cells they preview. On approval the copy falls like a stone: impact bursts
 * block chips plus the custom work storm, shakes the camera, restores the real
 * block, and hands over to the work cloud carving the clicked block itself.
 * Nothing here ever touches the network.</p>
 */
public final class CarverHologram {
    static final double REST_LIFT = 0.75;
    private static final double BOB_AMPLITUDE = 0.02;

    private enum Phase { RISING, HOVER, FALLING, GONE }

    private static boolean active;
    private static BlockPos focus;
    private static BlockState hiddenState;
    private static BlockState displayState;
    private static String materialKey = "";
    private static BlockState dustState;
    private static double lift;
    private static double prevLift;
    private static long lastTickNanos;
    private static double offsetX;
    private static double offsetZ;
    private static int flightTick;
    private static int ageTicks;
    private static Phase phase = Phase.GONE;
    /**
     * Impact effects fire only for a server-confirmed work start. The fall animation
     * itself begins optimistically on SPACE so the copy drops without a network
     * round-trip of delay; a rejected draft restores silently before touchdown.
     */
    private static boolean impactArmed;
    /**
     * Touchdown that landed before the server confirmation arrived (slow link): the
     * position and dust wait here so the work start can replay the burst on the same
     * tick instead of losing the impact entirely.
     */
    private static BlockPos silentLanding;
    private static BlockState silentDust;

    private CarverHologram() {
    }

    /** True while a hologram copy is lifted (or lifting) for the session. */
    public static boolean active() {
        return active && focus != null && phase != Phase.GONE;
    }

    /** Socket position of the active hologram, null when parked. */
    public static BlockPos focus() {
        return focus;
    }

    /** Material string the copy renders; drives the renderer model cache. */
    public static String materialKey() {
        return materialKey;
    }

    /** Resolved copy state for the renderer, null when parked. */
    public static BlockState displayState() {
        return active() ? displayState : null;
    }

    /** Current visual lift in blocks; 0 when parked. */
    public static double lift() {
        return active() ? lift : 0.0;
    }

    /** Sideways nudge in blocks along X; always 0, kept for anchor exactness. */
    public static double offsetX() {
        return active() ? effectiveOffsetX() : 0.0;
    }

    /** Sideways nudge in blocks along Z; always 0, kept for anchor exactness. */
    public static double offsetZ() {
        return active() ? effectiveOffsetZ() : 0.0;
    }

    public static void begin(Minecraft minecraft, BlockPos focusBlock, String materialId) {
        if (minecraft == null || minecraft.level == null || focusBlock == null) return;
        // Re-clicking the same socket reuses the hidden copy instead of paying for
        // a restore plus a second hide (two synchronous chunk rebuilds in one tick).
        if (active && focus != null && focus.equals(focusBlock) && phase != Phase.GONE) {
            return;
        }
        clear();
        BlockState state = stateFor(materialId);
        if (state == null) {
            state = minecraft.level.getBlockState(focusBlock);
        }
        try {
            hiddenState = minecraft.level.getBlockState(focusBlock);
            // The copy renders the live socket state (or the parsed parent
            // for carved volumes), so biome tints and properties like snow stay exact.
            BlockState live = hiddenState;
            if (live != null && state != null
                    && live.getBlock() == state.getBlock()) {
                state = live;
            }
            displayState = state;
            materialKey = materialId == null ? "" : materialId;
            dustState = state;
            // No sideways drift by design: the copy always rises straight up out of
            // the socket. The anchor plumbing keeps zero offsets for exactness.
            offsetX = 0.0;
            offsetZ = 0.0;
            minecraft.level.setBlock(focusBlock, Blocks.AIR.defaultBlockState(), 3);
            focus = focusBlock.immutable();
            lift = 0.0;
            prevLift = 0.0;
            lastTickNanos = System.nanoTime();
            flightTick = 0;
            ageTicks = 0;
            impactArmed = false;
            active = true;
            phase = Phase.RISING;
        } catch (RuntimeException failed) {
            restore(minecraft);
            clear();
        }
    }

    /**
     * Drops the copy like a stone; impact lands exactly {@link CarverHologramMotion#FALL_TICKS}
     * later with a dust burst, a camera kick and a thud on that same touchdown tick.
     */
    public static void beginFall() {
        if (active && (phase == Phase.RISING || phase == Phase.HOVER)) {
            phase = Phase.FALLING;
            flightTick = 0;
        }
    }

    /** Arms the touchdown effects once the server confirms the work start. */
    public static void setImpactArmed(boolean armed) {
        impactArmed = armed;
    }

    public static void clear() {
        CarverHologramRenderer.resetDiag();
        Minecraft minecraft = Minecraft.getInstance();
        restore(minecraft);
        active = false;
        focus = null;
        hiddenState = null;
        displayState = null;
        materialKey = "";
        dustState = null;
        lift = 0.0;
        offsetX = 0.0;
        offsetZ = 0.0;
        impactArmed = false;
        silentLanding = null;
        silentDust = null;
        phase = Phase.GONE;
    }

    public static void tick(Minecraft minecraft) {
        if (!active || focus == null) return;
        if (minecraft == null || minecraft.level == null || minecraft.player == null) {
            clear();
            return;
        }
        // The server never removes the socket block, so any authoritative resync
        // (neighbour edit, chunk reload) resurrects it under the hologram. Re-assert
        // the local hide on sight: one cached read per tick, a rebuild only when a
        // resurrection actually happened.
        if ((phase == Phase.RISING || phase == Phase.HOVER) && !isHidden(minecraft)) {
            try {
                minecraft.level.setBlock(focus, Blocks.AIR.defaultBlockState(), 3);
            } catch (RuntimeException failed) {
                clear();
                return;
            }
        }
        ageTicks++;
        prevLift = lift;
        lastTickNanos = System.nanoTime();
        if (phase == Phase.FALLING) {
            flightTick++;
            double t = Math.min(1.0, flightTick / (double) CarverHologramMotion.FALL_TICKS);
            lift = REST_LIFT * (1.0 - CarverHologramMotion.ease(t));
            if (t >= 1.0) {
                impact(minecraft);
            }
        } else if (phase == Phase.RISING) {
            flightTick++;
            double t = Math.min(1.0, flightTick / (double) CarverHologramMotion.RISE_TICKS);
            lift = REST_LIFT * CarverHologramMotion.ease(t);
            if (t >= 1.0) phase = Phase.HOVER;
        }
    }

    private static boolean isHidden(Minecraft minecraft) {
        try {
            return minecraft.level.getBlockState(focus).isAir();
        } catch (RuntimeException unreadable) {
            return true;
        }
    }

    private static void impact(Minecraft minecraft) {
        lift = 0.0;
        BlockPos landedAt = focus;
        BlockState dust = dustState;
        boolean effects = impactArmed;
        restore(minecraft);
        active = false;
        focus = null;
        hiddenState = null;
        displayState = null;
        materialKey = "";
        dustState = null;
        offsetX = 0.0;
        offsetZ = 0.0;
        impactArmed = false;
        phase = Phase.GONE;
        if (landedAt == null || minecraft.level == null) return;
        if (!effects) {
            silentLanding = landedAt;
            silentDust = dust;
            return;
        }
        playLandingFx(minecraft, landedAt, dust);
    }

    /** Dust burst, camera kick and thud for one touchdown. */
    public static void playLandingFx(Minecraft minecraft, BlockPos landedAt, BlockState dust) {
        if (minecraft == null || minecraft.level == null || landedAt == null) return;
        CarverCameraRig.addShake(0.6);
        burstChips(minecraft, landedAt, dust);
        int tint = CarverDustStorm.tintFor(minecraft, landedAt, dust);
        CarverDustStorm.burst(minecraft, landedAt, tint);
        try {
            minecraft.level.playLocalSound(
                    landedAt.getX() + 0.5, landedAt.getY() + 0.5, landedAt.getZ() + 0.5,
                    net.minecraft.sounds.SoundEvents.STONE_FALL, net.minecraft.sounds.SoundSource.BLOCKS,
                    1.0f, 0.8f, false);
        } catch (RuntimeException ignored) {
        }
    }

    /**
     * Replays a silent touchdown when the confirmation lands after the copy: returns
     * true and plays the burst when one was waiting.
     */
    public static boolean replaySilentLanding(Minecraft minecraft) {
        if (silentLanding == null) return false;
        BlockPos landedAt = silentLanding;
        BlockState dust = silentDust;
        silentLanding = null;
        silentDust = null;
        playLandingFx(minecraft, landedAt, dust);
        return true;
    }

    /** Material-correct block chips; the stylized cloud arrives via the dust storm. */
    private static void burstChips(Minecraft minecraft, BlockPos pos, BlockState state) {
        double x = pos.getX() + 0.5;
        double y = pos.getY() + 0.6;
        double z = pos.getZ() + 0.5;
        for (int index = 0; index < 48; index++) {
            double angle = index / 48.0 * Math.PI * 2.0;
            double speed = 0.15 + (index % 5) * 0.05;
            double vx = Math.cos(angle) * speed;
            double vz = Math.sin(angle) * speed;
            double vy = 0.25 + (index % 3) * 0.12;
            if (state != null) {
                minecraft.level.addParticle(new BlockParticleOption(ParticleTypes.BLOCK, state),
                        x, y, z, vx, vy, vz);
            }
        }
    }

    private static void restore(Minecraft minecraft) {
        if (minecraft == null || minecraft.level == null || focus == null || hiddenState == null) {
            return;
        }
        try {
            minecraft.level.setBlock(focus, hiddenState, 3);
        } catch (RuntimeException ignored) {
        }
    }

    private static BlockState stateFor(String materialId) {
        if (materialId == null || materialId.isEmpty()) return null;
        try {
            // Re-carved volumes report full state strings with properties: parse them
            // whole (biome tint, snow cover and facing survive), plain ids resolve to
            // the default state as before.
            if (materialId.indexOf('[') >= 0) {
                BlockState parsed = ua.rp.chat.client.microvoxel.MicrovoxelSectionModel
                        .parseBlockState(materialId);
                if (parsed != null) return parsed;
            }
            Identifier id = Identifier.parse(materialId);
            if (!BuiltInRegistries.BLOCK.containsKey(id)) return null;
            return BuiltInRegistries.BLOCK.getValue(id).defaultBlockState();
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    /**
     * Rendered lift with hover bob, interpolated between tick snapshots over our own
     * tick clock: the tick thread owns the motion, every frame draws between the last
     * two snapshots, so the rise glides at any refresh rate instead of stepping at
     * 20 Hz. Raycasts and overlays must use this, never raw lift.
     */
    public static double visualLift() {
        if (!active) return 0.0;
        double partial = CarverHologramMotion.renderPartial(lastTickNanos);
        double smooth = CarverHologramMotion.lerpTick(prevLift, lift, partial);
        double bob = phase == Phase.HOVER ? Math.sin(ageTicks * 0.15) * BOB_AMPLITUDE : 0.0;
        return smooth + bob;
    }

    /**
     * Live nudge relaxed by the fall progress: full strength while hovering, zero at
     * touchdown so the copy always lands back into its socket.
     */
    private static double effectiveOffsetX() {
        return CarverHologramOffset.falloff(offsetX, lift, REST_LIFT);
    }

    private static double effectiveOffsetZ() {
        return CarverHologramOffset.falloff(offsetZ, lift, REST_LIFT);
    }

    /** World-space X of the lifted cube's min corner for raycasts and overlays. */
    public static double originX() {
        if (focus == null) return 0.0;
        return focus.getX() + effectiveOffsetX();
    }

    /** World-space Y of the lifted cube's min corner for raycasts and overlays. */
    public static double originY() {
        if (focus == null) return 0.0;
        return focus.getY() + visualLift();
    }

    /** World-space Z of the lifted cube's min corner for raycasts and overlays. */
    public static double originZ() {
        if (focus == null) return 0.0;
        return focus.getZ() + effectiveOffsetZ();
    }

    /** World-space anchor of the lifted cube for raycasts and overlays. */
    public static Vec3 anchor() {
        if (focus == null) return Vec3.ZERO;
        return new Vec3(focus.getX() + 0.5 + effectiveOffsetX(),
                focus.getY() + 0.5 + visualLift(),
                focus.getZ() + 0.5 + effectiveOffsetZ());
    }
}
