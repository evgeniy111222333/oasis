package ua.rp.chat;

public final class HeavyHammerGripSolverTest {
    public static void main(String[] args) {
        verifyReachable(new HeavyHammerGripSolver.Point(0.0f, 8.0f, -2.0f));
        verifyReachable(new HeavyHammerGripSolver.Point(-4.0f, 5.0f, 4.0f));
        verifyReachable(new HeavyHammerGripSolver.Point(3.0f, -4.0f, -3.0f));

        HeavyHammerGripSolver.Point shoulder = new HeavyHammerGripSolver.Point(0.0f, 0.0f, 0.0f);
        HeavyHammerGripSolver.Solution clamped = HeavyHammerGripSolver.solve(
                shoulder, new HeavyHammerGripSolver.Point(40.0f, 0.0f, 0.0f));
        HeavyHammerGripSolver.Point hand = HeavyHammerGripSolver.hand(
                shoulder, clamped.upperX(), clamped.upperZ(), clamped.lowerX());
        require(hand.distanceTo(shoulder) < 10.01f, "Рука не должна растягиваться дальше суммы сегментов");
        require(clamped.clampDistance() > 29.0f, "Недостижимая цель должна явно отмечаться ограничением");
        System.out.println("HeavyHammerGripSolverTest passed");
    }

    private static void verifyReachable(HeavyHammerGripSolver.Point target) {
        HeavyHammerGripSolver.Point shoulder = new HeavyHammerGripSolver.Point(0.0f, 0.0f, 0.0f);
        HeavyHammerGripSolver.Solution solution = HeavyHammerGripSolver.solve(shoulder, target);
        HeavyHammerGripSolver.Point hand = HeavyHammerGripSolver.hand(
                shoulder, solution.upperX(), solution.upperZ(), solution.lowerX());
        require(solution.clampDistance() < 0.001f, "Тестовая цель должна быть достижима");
        require(hand.distanceTo(target) < 0.02f, "Решение должно приводить ладонь точно в цель");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
