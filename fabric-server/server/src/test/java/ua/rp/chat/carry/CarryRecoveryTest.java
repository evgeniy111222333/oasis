package ua.rp.chat.carry;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.world.phys.Vec3;

public final class CarryRecoveryTest {
    public static void main(String[] args) throws Exception {
        Vec3 player = new Vec3(.5, 64, .5);
        Vec3 wall = player.add(0, .006, 1.1);
        Vec3 clear = new Vec3(-.5, 64.006, .5);
        Vec3 chosen = CarryRecovery.find(player, 0, point -> point.distanceToSqr(clear) < 1e-12);
        require(chosen.distanceToSqr(clear) < 1e-12 && chosen.distanceToSqr(wall) > .1,
                "Recovery still uses the forward display anchor inside a wall");

        var store = new CarryLedger.Store() {
            public java.util.List<CarryLedger.Entry> load() { return java.util.List.of(); }
            public void save(java.util.Collection<CarryLedger.Entry> entries) { }
        };
        CarryLedger ledger = new CarryLedger(store);
        UUID owner = UUID.randomUUID();
        var object = new CarryLedger.Snapshot("item", "Chest", "{}", "", new byte[0], true);
        ledger.introduce(object, owner, location(chosen), CarryLedger.NONE);
        ledger.recoverAll();
        require(ledger.entries().getFirst().owner() == null && ledger.entries().getFirst().location().equals(location(clear)),
                "Immediate restart loses the validated recovery point");

        AtomicInteger probes = new AtomicInteger();
        try {
            CarryRecovery.find(player, 0, point -> { probes.incrementAndGet(); return false; });
            throw new AssertionError("A cramped room produces an uncollectable fallback instead of rejecting");
        } catch (IllegalStateException expected) {
            require(probes.get() == 25, "Recovery search is no longer bounded");
        }
        System.out.println("CarryRecoveryTest: blocked forward anchor, reachable fallback, immediate restart and cramped-room rejection passed");
    }
    private static CarryLedger.Location location(Vec3 point) {
        return new CarryLedger.Location("minecraft:overworld", point.x, point.y, point.z, 0);
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
