package ua.rp.chat.combat;

import org.bukkit.entity.Player;

public record CombatResult(
        Player attacker,
        Player victim,
        WeaponProfile weapon,
        CombatBodyZone zone,
        CombatOutcome outcome,
        int naturalRoll,
        int attackTotal,
        int defenseTotal,
        double healthDamage,
        double medicalDamage,
        String meLine,
        String doLine
) {
    public boolean landed() {
        return outcome.landed() && medicalDamage > 0.0;
    }
}
