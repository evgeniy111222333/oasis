package ua.rp.chat.microvoxel;

/**
 * Three-tier distance LOD for carved volumes. Bands follow voxel angular size at
 * 1080p/70deg: a 1/16 cell reads ~2px at 24 m (last distance full detail matters),
 * a 1/8 block reads ~3.4px at 32 m, and past ~72 m only the silhouette survives, so
 * stride-4 dilation is visually free there.
 *
 * <p>Every boundary carries a 15% hysteresis dead band, so camera jitter on the edge
 * never thrashes between background mesh builds. Pure: unit-tested without Minecraft.</p>
 */
public final class MicrovoxelLodTiers {
    public enum Tier {
        NEAR(1), MID(2), FAR(4);

        public final int stride;

        Tier(int stride) {
            this.stride = stride;
        }
    }

    /** Full detail inside this range in meters. */
    public static final double NEAR_M = 24.0;
    /** Silhouette-only past this range in meters. */
    public static final double FAR_M = 72.0;
    /** Hysteresis dead band as a fraction of each boundary. */
    public static final double HYSTERESIS = 0.15;

    static final double NEAR_ENTER_SQ = NEAR_M * NEAR_M;
    static final double NEAR_EXIT_SQ;
    static final double FAR_ENTER_SQ = FAR_M * FAR_M;
    static final double FAR_EXIT_SQ;

    static {
        double nearExit = NEAR_M * (1.0 - HYSTERESIS);
        double farExit = FAR_M * (1.0 - HYSTERESIS);
        NEAR_EXIT_SQ = nearExit * nearExit;
        FAR_EXIT_SQ = farExit * farExit;
    }

    private MicrovoxelLodTiers() {
    }

    /**
     * Hysteresis tier decision: NEAR flips to MID only past 24 m and back only inside
     * 20.4 m; MID flips to FAR only past 72 m and back only inside 61.2 m.
     */
    public static Tier wantTier(double distanceSquared, Tier current) {
        if (current == null) current = Tier.NEAR;
        return switch (current) {
            case NEAR -> distanceSquared > NEAR_ENTER_SQ ? Tier.MID : Tier.NEAR;
            case MID -> {
                if (distanceSquared > FAR_ENTER_SQ) yield Tier.FAR;
                if (distanceSquared < NEAR_EXIT_SQ) yield Tier.NEAR;
                yield Tier.MID;
            }
            case FAR -> distanceSquared < FAR_EXIT_SQ ? Tier.MID : Tier.FAR;
        };
    }

    public static int strideFor(Tier tier) {
        return tier == null ? 1 : tier.stride;
    }
}
