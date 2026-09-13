package ua.rp.chat.projectile;

public final class ArrowImpactPhysicsTest {
    public static void main(String[] args) {
        var weak = ArrowImpactPhysics.resolve(
                new ArrowImpactPhysics.Input(0.35, 1.0, 0.0, 1, 1L));
        require(weak.outcome() == ArrowImpactPhysics.Outcome.DEFLECTED,
                "A barely released arrow must not penetrate a torso");

        var shallow = ArrowImpactPhysics.resolve(
                new ArrowImpactPhysics.Input(1.2, 1.0, 0.0, 1, 2L));
        require(shallow.outcome() == ArrowImpactPhysics.Outcome.SHALLOW,
                "A low-energy direct torso hit should remain shallow");
        require(shallow.penetrationDepthBlocks() >= 0.025
                        && shallow.penetrationDepthBlocks() < 0.075,
                "Shallow depth left its calibrated interval");

        var armoredGlance = ArrowImpactPhysics.resolve(
                new ArrowImpactPhysics.Input(3.0, 0.30, 65.0, 1, 3L));
        require(armoredGlance.outcome() == ArrowImpactPhysics.Outcome.DEFLECTED,
                "A grazing full draw must deflect from iron armour");

        ArrowImpactPhysics.Result limbThrough = null;
        for (long seed = 0; seed < 10_000; seed++) {
            var candidate = ArrowImpactPhysics.resolve(
                    new ArrowImpactPhysics.Input(3.0, 1.0, 0.0, 2, seed));
            if (!candidate.boneContact()) {
                limbThrough = candidate;
                break;
            }
        }
        require(limbThrough != null && limbThrough.outcome() == ArrowImpactPhysics.Outcome.THROUGH,
                "A straight full-energy soft-tissue limb hit should pass through");
        require(limbThrough.residualSpeed() > 0.0 && limbThrough.residualSpeed() < 3.0,
                "A through arrow must continue slower than it arrived");

        var first = ArrowImpactPhysics.resolve(
                new ArrowImpactPhysics.Input(2.55, 0.82, 28.0, 4, 987654321L));
        var second = ArrowImpactPhysics.resolve(
                new ArrowImpactPhysics.Input(2.55, 0.82, 28.0, 4, 987654321L));
        require(first.equals(second), "Identical projectile impacts must resolve deterministically");
        require(first.penetrationDepthBlocks() <= 0.275,
                "Embedded depth exceeded the authored leg thickness");

        require(ArrowImpactPhysics.armorResistance("minecraft:netherite_chestplate")
                        > ArrowImpactPhysics.armorResistance("minecraft:iron_chestplate"),
                "Armour material ordering is inverted");
        System.out.println("ArrowImpactPhysicsTest passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
