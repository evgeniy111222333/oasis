package ua.rp.chat;

/**
 * Реверсивный автомат ношения тяжёлого молота. Положение инструмента хранится
 * одной непрерывной координатой, поэтому смена выбранного слота не создаёт
 * скачка даже посреди извлечения или возврата в подвес.
 */
public final class HeavyHammerCarryMachine {
    public static final float DRAW_DURATION_TICKS = 30.0f;
    public static final float HOLSTER_DURATION_TICKS = 28.0f;

    private float position;
    private float previousPosition;
    private boolean selected;
    private boolean present;

    public void reset(boolean present, boolean selected) {
        this.present = present;
        this.selected = present && selected;
        this.position = this.selected ? 1.0f : 0.0f;
        this.previousPosition = this.position;
    }

    public void tick(boolean present, boolean selected, float deltaTicks) {
        previousPosition = position;
        this.present = present;
        this.selected = present && selected;
        float delta = Math.max(0.0f, Math.min(deltaTicks, 4.0f));
        if (!present) {
            position = Math.max(0.0f, position - delta / HOLSTER_DURATION_TICKS);
        } else if (this.selected) {
            position = Math.min(1.0f, position + delta / DRAW_DURATION_TICKS);
        } else {
            position = Math.max(0.0f, position - delta / HOLSTER_DURATION_TICKS);
        }
    }

    public Sample sample(float partialTick) {
        float p = lerp(previousPosition, position, clamp(partialTick, 0.0f, 1.0f));
        Direction direction = position > previousPosition + 0.00001f
                ? Direction.DRAWING
                : position < previousPosition - 0.00001f ? Direction.HOLSTERING : Direction.STILL;
        return new Sample(phase(p, direction), direction, p,
                smootherStep(clamp((p - 0.24f) / 0.58f, 0.0f, 1.0f)),
                smootherStep(clamp((p - 0.04f) / 0.24f, 0.0f, 1.0f)),
                smootherStep(clamp((p - 0.60f) / 0.22f, 0.0f, 1.0f)),
                1.0f - smootherStep(clamp((p - 0.16f) / 0.20f, 0.0f, 1.0f)),
                present, selected);
    }

    public boolean isReady() {
        return present && selected && position >= 0.999f;
    }

    public boolean blocksSelectedItem() {
        return position > 0.001f && (!selected || position < 0.999f);
    }

    private static Phase phase(float position, Direction direction) {
        if (position <= 0.001f) return Phase.STOWED;
        if (position >= 0.999f) return Phase.CARRY;
        if (direction == Direction.HOLSTERING) {
            if (position > 0.82f) return Phase.RELEASING_SUPPORT;
            if (position > 0.64f) return Phase.GUIDING_BACK;
            if (position > 0.30f) return Phase.RETURNING;
            if (position > 0.18f) return Phase.LATCHING;
            return Phase.WITHDRAWING_HAND;
        }
        if (position < 0.18f) return Phase.REACHING;
        if (position < 0.30f) return Phase.UNLATCHING;
        if (position < 0.64f) return Phase.DRAWING_CLEAR;
        if (position < 0.82f) return Phase.CATCHING;
        return Phase.SETTLING;
    }

    private static float smootherStep(float value) {
        return value * value * value * (value * (value * 6.0f - 15.0f) + 10.0f);
    }

    private static float lerp(float from, float to, float amount) {
        return from + (to - from) * amount;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    public enum Phase {
        STOWED,
        REACHING,
        UNLATCHING,
        DRAWING_CLEAR,
        CATCHING,
        SETTLING,
        CARRY,
        RELEASING_SUPPORT,
        GUIDING_BACK,
        RETURNING,
        LATCHING,
        WITHDRAWING_HAND
    }

    public enum Direction {
        STILL,
        DRAWING,
        HOLSTERING
    }

    public record Sample(Phase phase, Direction direction, float position,
                         float toolTravel, float mainHandWeight, float offhandWeight,
                         float latchClosed, boolean present, boolean selected) {
    }
}
