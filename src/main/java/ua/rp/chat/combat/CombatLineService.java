package ua.rp.chat.combat;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

final class CombatLineService {
    private static final Map<String, Map<CombatOutcome, List<String>>> ME_LINES = Map.of(
            "sword", Map.of(
                    CombatOutcome.CRITICAL_MISS, List.of(
                            "слишком резко замахивается мечом и теряет темп.",
                            "ведет меч слишком широко, на мгновение раскрываясь.",
                            "срывает замах мечом и вынужден резко возвращать равновесие.",
                            "пытается ударить мечом с хода, но движение выходит рваным."
                    ),
                    CombatOutcome.MISS, List.of(
                            "проводит мечом мимо {target}, не доставая цель.",
                            "рубит мечом в сторону {target}, но удар уходит в пустоту.",
                            "делает выпад мечом, однако {target} успевает сместиться.",
                            "пытается достать {target} клинком, но лезвие проходит рядом."
                    ),
                    CombatOutcome.GRAZE, List.of(
                            "цепляет {target} мечом по {zone}, оставляя неглубокий след.",
                            "скользит клинком по {zone} {target}, лишь задевая кожу.",
                            "коротко достает {target} лезвием по {zone}.",
                            "задевает {zone} {target} краем клинка."
                    ),
                    CombatOutcome.HIT, List.of(
                            "точно проводит мечом по {zone} {target}.",
                            "вкладывает рубящий удар мечом в {zone} {target}.",
                            "достает {target} клинком по {zone}.",
                            "резко переводит меч и попадает по {zone} {target}."
                    ),
                    CombatOutcome.STRONG_HIT, List.of(
                            "вкладывает силу в удар мечом и рубит {target} по {zone}.",
                            "пробивает защиту и глубоко проводит мечом по {zone} {target}.",
                            "сильно сечет {target} мечом по {zone}.",
                            "ловит удобный угол и жестко бьет мечом по {zone} {target}."
                    ),
                    CombatOutcome.CRITICAL_HIT, List.of(
                            "ловит момент и наносит резкий удар мечом в {zone} {target}.",
                            "проводит опасный удар мечом точно в {zone} {target}.",
                            "врывается в темп {target} и жестко рассекает {zone}.",
                            "наносит тяжелый, почти безошибочный удар мечом по {zone} {target}."
                    )
            ),
            "axe", Map.of(
                    CombatOutcome.CRITICAL_MISS, List.of(
                            "слишком широко ведет топор, открываясь перед {target}.",
                            "не удерживает инерцию топора и теряет позицию.",
                            "срывает тяжелый замах топором и проседает в шаге.",
                            "перекладывает вес в удар, но топор уводит в сторону."
                    ),
                    CombatOutcome.MISS, List.of(
                            "размашисто бьет топором, но {target} уходит из-под удара.",
                            "обрушивает топор рядом с {target}, не попадая.",
                            "пытается достать {target} широким ударом, но промахивается.",
                            "ведет топор по дуге, однако {target} успевает отступить."
                    ),
                    CombatOutcome.GRAZE, List.of(
                            "краем топора задевает {target} по {zone}.",
                            "цепляет {zone} {target} скользящим ударом топора.",
                            "задевает {target} топором вскользь.",
                            "достает {target} не полной силой, лишь краем лезвия."
                    ),
                    CombatOutcome.HIT, List.of(
                            "тяжело опускает топор на {zone} {target}.",
                            "попадает топором по {zone} {target}.",
                            "вбивает удар топора в {zone} {target}.",
                            "резко сокращает дистанцию и достает {target} топором."
                    ),
                    CombatOutcome.STRONG_HIT, List.of(
                            "срывает дистанцию и сильно рубит топором по {zone} {target}.",
                            "вкладывает вес корпуса в тяжелый удар топором по {zone} {target}.",
                            "жестко пробивает топором по {zone} {target}.",
                            "с силой обрушивает топор на {zone} {target}."
                    ),
                    CombatOutcome.CRITICAL_HIT, List.of(
                            "вкладывает весь вес в сокрушительный удар топором по {zone} {target}.",
                            "ловит {target} на движении и обрушивает топор в {zone}.",
                            "наносит крайне опасный удар топором по {zone} {target}.",
                            "проламывает защиту мощным ударом топора по {zone} {target}."
                    )
            ),
            "mace", Map.of(
                    CombatOutcome.CRITICAL_MISS, List.of(
                            "не удерживает инерцию булавы и проседает в замахе.",
                            "заносит булаву слишком далеко и теряет равновесие.",
                            "пытается ударить слишком резко, но булава утягивает движение.",
                            "срывает тяжелый замах булавой перед {target}."
                    ),
                    CombatOutcome.MISS, List.of(
                            "бьет булавой, но удар проходит мимо {target}.",
                            "опускает булаву рядом с {target}, не доставая.",
                            "ведет булаву в сторону {target}, но промахивается.",
                            "пытается продавить дистанцию булавой, но {target} уходит."
                    ),
                    CombatOutcome.GRAZE, List.of(
                            "скользит ударом булавы по {zone} {target}.",
                            "задевает {target} булавой по {zone}.",
                            "лишь краем удара цепляет {zone} {target}.",
                            "касается {target} булавой вскользь."
                    ),
                    CombatOutcome.HIT, List.of(
                            "глухо попадает булавой в {zone} {target}.",
                            "вбивает удар булавой в {zone} {target}.",
                            "точно достает {target} булавой по {zone}.",
                            "коротко и тяжело бьет булавой по {zone} {target}."
                    ),
                    CombatOutcome.STRONG_HIT, List.of(
                            "мощно вбивает удар булавой в {zone} {target}.",
                            "продавливает защиту тяжелым ударом булавы по {zone} {target}.",
                            "сильно бьет булавой по {zone} {target}.",
                            "резким тяжелым движением попадает булавой в {zone} {target}."
                    ),
                    CombatOutcome.CRITICAL_HIT, List.of(
                            "пробивает защиту тяжелым ударом булавы по {zone} {target}.",
                            "наносит сокрушительный удар булавой в {zone} {target}.",
                            "ловит {target} на ошибке и тяжело бьет булавой по {zone}.",
                            "вкладывает всю массу в критический удар булавой по {zone} {target}."
                    )
            ),
            "unarmed", Map.of(
                    CombatOutcome.CRITICAL_MISS, List.of(
                            "неудачно рвется вперед и теряет равновесие.",
                            "слишком резко идет в удар и сбивается с темпа.",
                            "пытается ударить с рывка, но движение разваливается.",
                            "переносит вес вперед слишком рано и открывается."
                    ),
                    CombatOutcome.MISS, List.of(
                            "бьет кулаком, но {target} уклоняется.",
                            "пытается достать {target} прямым ударом, но промахивается.",
                            "проводит удар рядом с {target}.",
                            "делает короткий выпад, но не достает {target}."
                    ),
                    CombatOutcome.GRAZE, List.of(
                            "лишь скользит ударом по {zone} {target}.",
                            "задевает {target} кулаком по {zone}.",
                            "коротко цепляет {zone} {target}.",
                            "касается {target} ударом вскользь."
                    ),
                    CombatOutcome.HIT, List.of(
                            "попадает кулаком в {zone} {target}.",
                            "коротко бьет {target} кулаком по {zone}.",
                            "достает {target} плотным ударом по {zone}.",
                            "резко пробивает кулаком в {zone} {target}."
                    ),
                    CombatOutcome.STRONG_HIT, List.of(
                            "резко пробивает кулаком в {zone} {target}.",
                            "сильно бьет {target} по {zone}.",
                            "вкладывает корпус в удар кулаком по {zone} {target}.",
                            "ловит {target} на движении и жестко попадает в {zone}."
                    ),
                    CombatOutcome.CRITICAL_HIT, List.of(
                            "ловит {target} на движении и сильно бьет в {zone}.",
                            "точно вкладывает удар кулаком в {zone} {target}.",
                            "резко пробивает защиту и попадает в {zone} {target}.",
                            "наносит тяжелый удар кулаком по {zone} {target}."
                    )
            )
    );

    private static final Map<CombatOutcome, List<String>> DEFAULT_ME_LINES = Map.of(
            CombatOutcome.CRITICAL_MISS, List.of(
                    "неудачно атакует {target}, теряя темп.",
                    "срывает атаку и вынужден восстанавливать равновесие.",
                    "ошибается в моменте атаки и открывается.",
                    "делает слишком резкое движение и сбивается."
            ),
            CombatOutcome.MISS, List.of(
                    "атакует {target}, но не достает цель.",
                    "проводит удар рядом с {target}.",
                    "пытается достать {target}, но промахивается.",
                    "не успевает поймать движение {target}."
            ),
            CombatOutcome.GRAZE, List.of(
                    "задевает {target} по {zone}.",
                    "цепляет {zone} {target} вскользь.",
                    "достает {target} по {zone} не полной силой.",
                    "проводит скользящий удар по {zone} {target}."
            ),
            CombatOutcome.HIT, List.of(
                    "попадает {weapon} по {zone} {target}.",
                    "точно достает {target} ударом по {zone}.",
                    "проводит удар {weapon} в {zone} {target}.",
                    "ловит дистанцию и попадает по {zone} {target}."
            ),
            CombatOutcome.STRONG_HIT, List.of(
                    "сильно попадает {weapon} по {zone} {target}.",
                    "вкладывает силу в удар по {zone} {target}.",
                    "жестко пробивает ударом в {zone} {target}.",
                    "резко усиливает атаку и попадает по {zone} {target}."
            ),
            CombatOutcome.CRITICAL_HIT, List.of(
                    "наносит критический удар {weapon} по {zone} {target}.",
                    "ловит идеальный момент и тяжело попадает по {zone} {target}.",
                    "пробивает защиту опасным ударом по {zone} {target}.",
                    "наносит крайне точный удар в {zone} {target}."
            )
    );

    String meLine(String target, WeaponProfile weapon, CombatBodyZone zone, CombatOutcome outcome, String previousLine) {
        Map<CombatOutcome, List<String>> byOutcome = ME_LINES.getOrDefault(weapon.id(), DEFAULT_ME_LINES);
        List<String> lines = byOutcome.getOrDefault(outcome, DEFAULT_ME_LINES.get(outcome));
        return fill(pick(lines, previousLine), target, weapon, zone);
    }

    String doLine(WeaponProfile weapon, CombatBodyZone zone, CombatOutcome outcome, double damage) {
        String detail = switch (outcome) {
            case GRAZE -> woundPhrase(weapon.damageProfile(), "легкая рана", "неглубокий прокол", "слабый ушиб");
            case HIT -> woundPhrase(weapon.damageProfile(), "заметная рана", "заметный прокол", "ощутимый ушиб");
            case STRONG_HIT -> woundPhrase(weapon.damageProfile(), "глубокая рана", "глубокий прокол", "сильный ушиб");
            case CRITICAL_HIT -> woundPhrase(weapon.damageProfile(), "критическая рана", "тяжелый прокол", "тяжелый ушиб");
            default -> "";
        };
        String extra = damage >= 6.5 ? " движения резко сковывает." : damage >= 3.5 ? " боль быстро нарастает." : "";
        String comma = extra.isBlank() ? "." : ",";
        return zone.locative().substring(0, 1).toUpperCase() + zone.locative().substring(1)
                + " заметна травма: " + detail + comma + extra;
    }

    private String woundPhrase(CombatDamageProfile profile, String sharp, String projectile, String blunt) {
        return switch (profile) {
            case SHARP -> sharp;
            case PROJECTILE -> projectile;
            case BLUNT -> blunt;
        };
    }

    private String pick(List<String> lines, String previousLine) {
        if (lines == null || lines.isEmpty()) {
            return "";
        }
        if (lines.size() == 1) {
            return lines.get(0);
        }
        String selected;
        int guard = 0;
        do {
            selected = lines.get(ThreadLocalRandom.current().nextInt(lines.size()));
            guard++;
        } while (selected.equals(previousLine) && guard < 8);
        return selected;
    }

    private String fill(String template, String target, WeaponProfile weapon, CombatBodyZone zone) {
        return template
                .replace("{target}", target)
                .replace("{weapon}", weapon.displayName())
                .replace("{zone}", zone.accusative());
    }
}
