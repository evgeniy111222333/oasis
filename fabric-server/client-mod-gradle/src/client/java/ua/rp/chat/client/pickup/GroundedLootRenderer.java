package ua.rp.chat.client.pickup;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.ItemEntityRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Replaces Minecraft's decorative item bob/spin with a grounded, deterministic loot presentation.
 * The server still owns motion, collision and pickup; this class only changes how that state is shown.
 */
public final class GroundedLootRenderer {
    private static final float CONTACT_CLEARANCE = 0.010f;
    private static final long SETTLE_DURATION_NANOS = 240_000_000L;
    private static final Map<UUID, LandingTrack> LANDINGS = new HashMap<>();
    private static final Map<ItemEntityRenderState, ItemEntity> STATE_ENTITIES = new IdentityHashMap<>();

    private GroundedLootRenderer() {
    }

    public static void submit(ItemEntity entity, ItemEntityRenderState state, PoseStack poseStack,
                              SubmitNodeCollector collector, int light) {
        if (state.item.isEmpty()) return;

        ItemStack stack = entity.getItem();
        if (stack.isEmpty()) return;
        AABB bounds = state.item.getModelBoundingBox();
        Profile profile = Profile.forStack(stack, bounds);
        LandingSample landing = sampleLanding(entity);

        poseStack.pushPose();
        // The model's bottom, not its logical entity centre, touches the support surface.
        float contactY = profile.liesFlat
                ? (float) bounds.maxZ * profile.scaleXZ
                : -((float) bounds.minY) * profile.scaleY;
        poseStack.translate(0.0f, contactY + CONTACT_CLEARANCE + landing.bounce(), 0.0f);
        poseStack.mulPose(Axis.YP.rotationDegrees(landing.yawDegrees()));
        if (profile.liesFlat) {
            // Generated 2D item models are upright in the vanilla ground context.
            // Rotate them onto their broad face before the short landing settle begins.
            poseStack.mulPose(Axis.XP.rotationDegrees(90.0f));
        }
        if (landing.pitchDegrees() != 0.0f) {
            poseStack.mulPose(Axis.XP.rotationDegrees(landing.pitchDegrees()));
        }
        if (landing.rollDegrees() != 0.0f) {
            poseStack.mulPose(Axis.ZP.rotationDegrees(landing.rollDegrees()));
        }
        poseStack.scale(profile.scaleXZ, profile.scaleY, profile.scaleXZ);

        int visibleCopies = Math.min(state.count, profile.maxVisibleCopies);
        for (int copy = 0; copy < visibleCopies; copy++) {
            poseStack.pushPose();
            if (copy > 0) {
                float direction = seeded01(entity.getUUID(), copy * 17L) * 360.0f;
                float radius = profile.copySpread * copy;
                float dx = Mth.cos((float) Math.toRadians(direction)) * radius;
                float dz = Mth.sin((float) Math.toRadians(direction)) * radius;
                poseStack.translate(dx, profile.copyLift * copy, dz);
                poseStack.mulPose(Axis.YP.rotationDegrees((seeded01(entity.getUUID(), copy * 31L) - 0.5f) * 8.0f));
            }
            state.item.submit(poseStack, collector, light, OverlayTexture.NO_OVERLAY, 0);
            poseStack.popPose();
        }
        poseStack.popPose();
    }

    /** ItemEntityRenderer reuses render-state objects, so this association is refreshed every frame. */
    public static void captureEntity(ItemEntityRenderState state, ItemEntity entity) {
        STATE_ENTITIES.put(state, entity);
    }

    public static ItemEntity entityFor(ItemEntityRenderState state) {
        return STATE_ENTITIES.get(state);
    }

    /** Reverses the transform already applied by ItemEntityRenderer before the redirected submit call. */
    public static void undoVanillaHoverAndSpin(ItemEntityRenderState state, PoseStack poseStack, AABB modelBounds) {
        float modelFloor = -((float) modelBounds.minY) + 0.0625f;
        float hover = Mth.sin(state.ageInTicks / 10.0f + state.bobOffset) * 0.1f + 0.1f;
        float spin = ItemEntity.getSpin(state.ageInTicks, state.bobOffset);
        poseStack.mulPose(Axis.YP.rotation(-spin));
        poseStack.translate(0.0f, -(hover + modelFloor), 0.0f);
    }

    public static void clientTick(Minecraft client) {
        if (client.level == null) {
            LANDINGS.clear();
            STATE_ENTITIES.clear();
            return;
        }
        long cutoff = System.nanoTime() - 10_000_000_000L;
        LANDINGS.entrySet().removeIf(entry -> entry.getValue().lastSeenNanos < cutoff);
    }

    private static LandingSample sampleLanding(ItemEntity entity) {
        UUID id = entity.getUUID();
        long now = System.nanoTime();
        boolean onGround = entity.onGround();
        Vec3 velocity = entity.getDeltaMovement();
        LandingTrack track = LANDINGS.get(id);
        if (track == null) {
            track = new LandingTrack(onGround, 0L, now);
            if (!onGround) beginFlight(track, id, velocity, now);
            LANDINGS.put(id, track);
        } else if (onGround && !track.wasOnGround) {
            track.landedAtNanos = now;
            track.wasOnGround = true;
        } else if (!onGround) {
            if (track.wasOnGround) beginFlight(track, id, velocity, now);
            track.wasOnGround = false;
            track.landedAtNanos = 0L;
        }
        track.lastSeenNanos = now;

        float landingPhase = track.landedAtNanos == 0L ? 1.0f
                : Math.min(1.0f, (now - track.landedAtNanos) / (float) SETTLE_DURATION_NANOS);
        float settleFade = 1.0f - landingPhase;
        float baseYaw = seeded01(id, 7L) * 360.0f;
        if (!onGround) {
            float airPhase = Math.min(1.0f, (now - track.flightStartedNanos) / 240_000_000.0f);
            float eased = 1.0f - (1.0f - airPhase) * (1.0f - airPhase);
            return new LandingSample(baseYaw + track.flightYawDegrees * eased, 0.0f,
                    track.flightPitchDegrees * eased, track.flightRollDegrees * eased);
        }

        float bounce = (float) (Math.sin(landingPhase * Math.PI) * settleFade * 0.045f);
        float restTilt = (seeded01(id, 91L) - 0.5f) * 10.0f * settleFade;
        return new LandingSample(baseYaw + track.flightYawDegrees * settleFade, bounce,
                track.flightPitchDegrees * settleFade + restTilt, track.flightRollDegrees * settleFade);
    }

    private static void beginFlight(LandingTrack track, UUID id, Vec3 velocity, long now) {
        track.flightStartedNanos = now;
        float horizontalSpeed = (float) Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);
        float energy = Mth.clamp(horizontalSpeed * 2.7f + (float) Math.abs(velocity.y) * 1.6f, 0.35f, 1.0f);
        float sign = seeded01(id, 43L) < 0.5f ? -1.0f : 1.0f;
        // A single, velocity-weighted quarter/half turn reads as a thrown object.
        // It is held only until impact, then the landing phase damps it to the resting pose.
        track.flightYawDegrees = sign * (70.0f + 80.0f * energy);
        track.flightPitchDegrees = Mth.clamp((float) -velocity.y * 90.0f, -32.0f, 32.0f)
                + sign * (8.0f + 10.0f * energy);
        track.flightRollDegrees = sign * (10.0f + 18.0f * energy);
    }

    private static float seeded01(UUID id, long salt) {
        long value = id.getMostSignificantBits() ^ Long.rotateLeft(id.getLeastSignificantBits(), 19) ^ salt;
        value ^= value >>> 33;
        value *= 0xff51afd7ed558ccdl;
        value ^= value >>> 33;
        return (float) ((value >>> 40) & 0xFFFFFFL) / 0x1000000L;
    }

    private enum Profile {
        // The vanilla GROUND transform has already reduced a block to ~50%.
        // 0.92 here produces a readable physical block at about 46% of placed-block size.
        BLOCK_SAMPLE(0.92f, 0.92f, 3, 0.026f, 0.022f),
        FLAT(0.92f, 0.92f, 2, 0.008f, 0.012f, true),
        TOOL(0.88f, 0.88f, 2, 0.018f, 0.014f),
        GENERAL(0.92f, 0.92f, 2, 0.014f, 0.012f);

        private final float scaleXZ;
        private final float scaleY;
        private final int maxVisibleCopies;
        private final float copyLift;
        private final float copySpread;
        private final boolean liesFlat;

        Profile(float scaleXZ, float scaleY, int maxVisibleCopies, float copyLift, float copySpread) {
            this(scaleXZ, scaleY, maxVisibleCopies, copyLift, copySpread, false);
        }

        Profile(float scaleXZ, float scaleY, int maxVisibleCopies, float copyLift, float copySpread,
                boolean liesFlat) {
            this.scaleXZ = scaleXZ;
            this.scaleY = scaleY;
            this.maxVisibleCopies = maxVisibleCopies;
            this.copyLift = copyLift;
            this.copySpread = copySpread;
            this.liesFlat = liesFlat;
        }

        private static Profile forStack(ItemStack stack, AABB bounds) {
            // A dropped building block must keep its cubic volume. The previous special
            // plank profile compressed it into a slab, which contradicted the item itself.
            if (stack.getItem() instanceof BlockItem) return BLOCK_SAMPLE;
            if (bounds.getZsize() <= 0.0625d) return FLAT;
            return stack.isDamageableItem() ? TOOL : GENERAL;
        }
    }

    private static final class LandingTrack {
        private boolean wasOnGround;
        private long landedAtNanos;
        private long flightStartedNanos;
        private float flightYawDegrees;
        private float flightPitchDegrees;
        private float flightRollDegrees;
        private long lastSeenNanos;

        private LandingTrack(boolean wasOnGround, long landedAtNanos, long lastSeenNanos) {
            this.wasOnGround = wasOnGround;
            this.landedAtNanos = landedAtNanos;
            this.lastSeenNanos = lastSeenNanos;
        }
    }

    private record LandingSample(float yawDegrees, float bounce, float pitchDegrees, float rollDegrees) {
    }
}
