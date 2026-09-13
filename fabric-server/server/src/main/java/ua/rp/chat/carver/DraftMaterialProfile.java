package ua.rp.chat.carver;

/**
 * Work pricing for any carvable material. The drafting system is not stone-only:
 * wool, wood, clay and every other eligible full cube carves with the same tools,
 * only the pace changes. Pace follows vanilla hardness, so instincts learned from
 * hand-mining transfer directly: wool flies, deepslate drags, bedrock refuses.
 *
 * <p>Pure and dependency-free: the manager resolves live block states to hardness,
 * this table turns hardness into a time multiplier.</p>
 */
public final class DraftMaterialProfile {
    /** Reference hardness the estimate baseline (640 cells / 15 s) is tuned against. */
    public static final float REFERENCE_HARDNESS = 1.5f;
    public static final double MIN_MULTIPLIER = 0.5;
    public static final double MAX_MULTIPLIER = 8.0;

    private DraftMaterialProfile() {
    }

    /** True when the hardness value describes a carvable block (unbreakable is negative). */
    public static boolean isCarvableHardness(float hardness) {
        return hardness >= 0.0f && Float.isFinite(hardness);
    }

    /**
     * Work-time multiplier: {@code 0.25 + hardness * 0.5} clamped to
     * {@code [0.5, 8.0]}. Stone (1.5) paces at 1.0, wool (0.8) at 0.65,
     * deepslate (3.0) at 1.75, obsidian (50) clamps to 8.0.
     */
    public static double timeMultiplier(float hardness) {
        if (!isCarvableHardness(hardness)) return MAX_MULTIPLIER;
        return Math.min(MAX_MULTIPLIER, Math.max(MIN_MULTIPLIER, 0.25 + hardness * 0.5));
    }
}
