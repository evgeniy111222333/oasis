package ua.rp.chat.client.tab;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Stores only presentation-safe server labels; never resolves account names or skins. */
public final class TabRoster {
    private static final Comparator<Entry> ORDER = Comparator.comparing(Entry::self).reversed()
            .thenComparing(Entry::label, String.CASE_INSENSITIVE_ORDER).thenComparing(Entry::id);
    private Map<UUID, Entry> byId = Map.of();
    private List<Entry> entries = List.of();
    private boolean authoritative;
    private long revision;

    public boolean replace(Collection<Entry> incoming, boolean fromServer) {
        Map<UUID, Entry> next = new HashMap<>();
        for (Entry entry : incoming) next.put(entry.id(), entry);
        authoritative = fromServer;
        if (byId.equals(next)) return false;
        ArrayList<Entry> sorted = new ArrayList<>(next.values());
        sorted.sort(ORDER);
        byId = Map.copyOf(next);
        entries = List.copyOf(sorted);
        revision++;
        return true;
    }

    public void clear() {
        if (byId.isEmpty()) { authoritative = false; return; }
        replace(List.of(), false);
    }

    public List<Entry> entries() { return entries; }
    public long revision() { return revision; }
    public boolean authoritative() { return authoritative; }

    public record Entry(UUID id, String label, boolean self) {
        public Entry {
            if (id == null) throw new IllegalArgumentException("Missing player UUID");
            label = TabText.clean(label);
            if (label.isBlank()) label = "Незнакомец";
        }
    }
}
