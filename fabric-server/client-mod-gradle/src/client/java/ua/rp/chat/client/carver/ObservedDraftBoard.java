package ua.rp.chat.client.carver;

import net.minecraft.core.BlockPos;
import ua.rp.chat.carver.CarverChalkQuads;
import ua.rp.chat.carver.DraftMask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Drafts of nearby artisans, mirrored for observers. Entries arrive through the
 * regular draft channel, leave on session close or work start, and expire on
 * silence so a lost packet never pins a stale outline.
 */
public final class ObservedDraftBoard {
    public record Entry(BlockPos focus, DraftMask mask, long fingerprint, long lastTick) {
    }

    private final Map<BlockPos, Entry> entries = new HashMap<>();

    public void put(BlockPos focus, DraftMask mask, long tick) {
        if (focus == null || mask == null) return;
        BlockPos key = focus.immutable();
        DraftMask snapshot = mask.copy();
        entries.put(key, new Entry(key, snapshot,
                CarverChalkQuads.draftFingerprint(snapshot), tick));
    }

    public void remove(BlockPos focus) {
        if (focus == null) return;
        entries.remove(focus.immutable());
    }

    public List<Entry> snapshot() {
        return List.copyOf(new ArrayList<>(entries.values()));
    }

    public void expire(long nowTick, long ttlTicks) {
        if (entries.isEmpty()) return;
        var iterator = entries.entrySet().iterator();
        while (iterator.hasNext()) {
            if (nowTick - iterator.next().getValue().lastTick() > ttlTicks) {
                iterator.remove();
            }
        }
    }

    public void clear() {
        entries.clear();
    }

    public int size() {
        return entries.size();
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }
}
