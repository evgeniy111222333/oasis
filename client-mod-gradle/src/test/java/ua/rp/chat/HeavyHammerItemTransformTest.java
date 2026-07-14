package ua.rp.chat;

import org.joml.Quaternionf;
import org.joml.Vector3f;

public final class HeavyHammerItemTransformTest {
    public static void main(String[] args) {
        for (int index = 0; index <= 680; index++) {
            HeavyHammerAnimation.Sample sample = HeavyHammerAnimation.strike(index / 20.0f);
            HeavyHammerItemTransform.Result result = HeavyHammerItemTransform.solve(sample,
                    sample.rightX(), sample.rightY(), sample.rightZ(),
                    -sample.rightLower(), 0.0f, 0.0f);

            Quaternionf rendered = new Quaternionf(result.current())
                    .mul(result.correction()).mul(result.layerRotation());
            verifyAxis(rendered, new Vector3f(1.0f, 0.0f, 0.0f),
                    sample.headAxisX(), sample.headAxisY(), sample.headAxisZ(), "боёк", index);
            verifyAxis(rendered, new Vector3f(0.0f, 1.0f, 0.0f),
                    sample.shaftX(), sample.shaftY(), sample.shaftZ(), "древко", index);
            verifyAxis(rendered, new Vector3f(0.0f, 0.0f, 1.0f),
                    sample.depthAxisX(), sample.depthAxisY(), sample.depthAxisZ(), "глубина", index);

            Vector3f layerOffset = HeavyHammerItemTransform.itemLayerHandOffset()
                    .rotate(result.layerRotation());
            Vector3f oldGrip = HeavyHammerItemTransform.articulatedHandOffset().rotate(result.current());
            Vector3f newGrip = new Vector3f(result.compensation()).add(layerOffset)
                    .rotate(result.desiredBeforeLayer());
            require(oldGrip.distance(newGrip) < 0.0001f,
                    "Смена ориентации не должна сдвигать нижний хват: sample=" + index);
        }
        System.out.println("HeavyHammerItemTransformTest passed");
    }

    private static void verifyAxis(Quaternionf rotation, Vector3f local,
                                   float x, float y, float z, String name, int sample) {
        Vector3f rendered = local.rotate(rotation);
        require(rendered.distance(new Vector3f(x, y, z)) < 0.0002f,
                "Процедурная ось '" + name + "' не совпала с рендером: sample=" + sample);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
