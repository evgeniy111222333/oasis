package ua.rp.chat.client.tab;

/** English labels from the server's overworld clock, also in other dimensions. */
public record TabClock(long day, String phase) {
    public static TabClock of(long ticks) {
        long time = Math.floorMod(ticks, 24000L);
        String phase = time < 3000 || time >= 23000 ? "Dawn" : time < 11000 ? "High Noon"
                : time < 14000 ? "Eventide" : "Midnight";
        return new TabClock(Math.max(1, Math.floorDiv(ticks, 24000L) + 1), phase);
    }
    public String label() { return "Day " + day + " - " + phase; }
}
