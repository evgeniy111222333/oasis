package ua.rp.chat.combat;

public enum CombatOutcome {
    CRITICAL_MISS(false, "критический промах"),
    MISS(false, "промах"),
    GRAZE(true, "касание"),
    HIT(true, "попадание"),
    STRONG_HIT(true, "сильное попадание"),
    CRITICAL_HIT(true, "критическое попадание");

    private final boolean landed;
    private final String label;

    CombatOutcome(boolean landed, String label) {
        this.landed = landed;
        this.label = label;
    }

    public boolean landed() {
        return landed;
    }

    public String label() {
        return label;
    }
}
