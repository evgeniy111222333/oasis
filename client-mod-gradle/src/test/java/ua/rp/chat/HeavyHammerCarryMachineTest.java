package ua.rp.chat;

public final class HeavyHammerCarryMachineTest {
    public static void main(String[] args) {
        HeavyHammerCarryMachine machine = new HeavyHammerCarryMachine();
        machine.reset(true, false);
        require(machine.sample(1.0f).phase() == HeavyHammerCarryMachine.Phase.STOWED,
                "Молот обязан начинать в подвесе");

        float previous = 0.0f;
        for (int tick = 0; tick < 30; tick++) {
            machine.tick(true, true, 1.0f);
            float current = machine.sample(1.0f).position();
            require(current >= previous, "Извлечение не может двигаться назад");
            require(current - previous <= 1.0f / HeavyHammerCarryMachine.DRAW_DURATION_TICKS + 0.0001f,
                    "Обнаружен скачок извлечения");
            previous = current;
        }
        require(machine.isReady(), "После полного извлечения молот должен быть готов");

        for (int tick = 0; tick < 9; tick++) machine.tick(true, false, 1.0f);
        float interrupted = machine.sample(1.0f).position();
        machine.tick(true, true, 1.0f);
        float reversed = machine.sample(1.0f).position();
        require(reversed > interrupted, "Повторный выбор должен бесшовно развернуть возврат");
        require(reversed - interrupted <= 1.0f / HeavyHammerCarryMachine.DRAW_DURATION_TICKS + 0.0001f,
                "Реверс не должен телепортировать инструмент");

        for (int tick = 0; tick < 80; tick++) machine.tick(true, false, 1.0f);
        HeavyHammerCarryMachine.Sample stowed = machine.sample(1.0f);
        require(stowed.phase() == HeavyHammerCarryMachine.Phase.STOWED,
                "Возврат обязан завершаться закрытым подвесом");
        require(stowed.latchClosed() > 0.999f, "Защёлка должна закрыться после возврата");

        machine.tick(false, false, 1.0f);
        require(!machine.sample(1.0f).present(), "Удалённый из инвентаря молот не должен оставаться видимым");
        System.out.println("HeavyHammerCarryMachineTest passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
