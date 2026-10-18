package ua.rp.chat;

import ua.rp.chat.stonemason.DraftEstimate;
import ua.rp.chat.stonemason.DraftMask;
import ua.rp.chat.stonemason.DraftTemplates;

/**
 * Guards the client mirrors of the stonemason pure logic: the drafting screen prices
 * and previews from these copies, so any divergence from the server computation would
 * silently desynchronize the estimate line.
 */
public final class StonemasonMirrorTest {
    public static void main(String[] args) {
        DraftMask bath = new DraftMask();
        require(DraftTemplates.apply(bath, DraftTemplates.BATH) == 644,
                "Client bath template must carve 644 cells");
        DraftMask mask = new DraftMask();
        mask.set(DraftMask.index(3, 4, 5));
        require(DraftMask.decode(mask.encode()).equals(mask),
                "Client mask codec must round-trip");
        require(DraftEstimate.workTicks(640) == 300
                        && Math.abs(DraftEstimate.staminaCost(640) - 35.0) < 1.0e-9,
                "Client estimate must price 640 cells at 300 ticks / 35% stamina");
        System.out.println("StonemasonMirrorTest passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException("StonemasonMirrorTest: " + message);
    }
}
