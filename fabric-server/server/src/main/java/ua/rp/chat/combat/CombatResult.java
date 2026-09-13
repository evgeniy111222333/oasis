package ua.rp.chat.combat;

import net.minecraft.server.level.ServerPlayer;

public record CombatResult(
        ServerPlayer attacker,
        ServerPlayer victim,
        WeaponProfile weapon,
        CombatBodyZone zone,
        CombatOutcome outcome,
        int naturalRoll,
        int attackTotal,
        int defenseTotal,
        double healthDamage,
        double medicalDamage,
        double hitRatio,
        double lateral,
        String meLine,
        String doLine
) {
    public boolean landed() {
        return outcome.landed() && medicalDamage > 0.0;
    }
}
