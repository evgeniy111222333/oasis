package ua.rp.chat.carver;

import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Resolved work-sound kit for one carving session: every slot comes from the live
 * block's vanilla sound type, with a tag-driven instrumental layer on top. Slots are
 * resolved once per session and never re-queried, so a missing or null sound degrades
 * to the stone fallback exactly once instead of glitching mid-work.
 */
public final class CarverSoundKit {
    private final SoundEvent strike;
    private final SoundEvent scrape;
    private final SoundEvent crack;
    private final SoundEvent finish;
    private final SoundEvent layer;
    private final float scrapePitch;
    private final float scrapeVolume;
    private final boolean invertBalance;

    private CarverSoundKit(SoundEvent strike, SoundEvent scrape, SoundEvent crack,
                           SoundEvent finish, SoundEvent layer,
                           float scrapePitch, float scrapeVolume, boolean invertBalance) {
        this.strike = strike;
        this.scrape = scrape;
        this.crack = crack;
        this.finish = finish;
        this.layer = layer;
        this.scrapePitch = scrapePitch;
        this.scrapeVolume = scrapeVolume;
        this.invertBalance = invertBalance;
    }

    public static CarverSoundKit forState(BlockState state) {
        if (state == null) return stoneFallback();
        net.minecraft.world.level.block.SoundType type;
        try {
            type = state.getSoundType();
        } catch (RuntimeException broken) {
            return stoneFallback();
        }
        if (type == null) return stoneFallback();
        SoundEvent strike = orStone(type.getHitSound(), SoundEvents.STONE_HIT);
        SoundEvent scrape = orStone(type.getStepSound(), SoundEvents.STONE_STEP);
        SoundEvent crack = orStone(type.getBreakSound(), SoundEvents.STONE_BREAK);
        SoundEvent finish = orStone(type.getBreakSound(), SoundEvents.STONE_BREAK);
        CarverSoundTable.Kind kind = CarverSoundTable.classify(state);
        SoundEvent layer = switch (kind) {
            case SNIP -> SoundEvents.BEEHIVE_SHEAR;
            case STRIP -> SoundEvents.AXE_STRIP;
            default -> null;
        };
        boolean invert = kind == CarverSoundTable.Kind.SOFT;
        return new CarverSoundKit(strike, scrape, crack, finish, layer,
                0.8f, invert ? 1.0f : 0.6f, invert);
    }

    public static CarverSoundKit stoneFallback() {
        return new CarverSoundKit(SoundEvents.STONE_HIT, SoundEvents.STONE_STEP,
                SoundEvents.STONE_BREAK, SoundEvents.STONE_BREAK,
                null, 0.8f, 0.6f, false);
    }

    private static SoundEvent orStone(SoundEvent candidate, SoundEvent fallback) {
        return candidate == null ? fallback : candidate;
    }

    public SoundEvent strike() {
        return strike;
    }

    public SoundEvent scrape() {
        return scrape;
    }

    public SoundEvent crack() {
        return crack;
    }

    public SoundEvent finish() {
        return finish;
    }

    /** Instrumental overlay (shears/axe) or null for plain materials. */
    public SoundEvent layer() {
        return layer;
    }

    public float scrapePitch() {
        return scrapePitch;
    }

    public float scrapeVolume() {
        return scrapeVolume;
    }

    public boolean invertBalance() {
        return invertBalance;
    }
}
