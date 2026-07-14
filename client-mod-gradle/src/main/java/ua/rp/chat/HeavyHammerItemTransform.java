package ua.rp.chat;

import org.joml.Matrix3f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Общая математика, связывающая процедурный базис молота с ItemInHandLayer. */
public final class HeavyHammerItemTransform {
    private static final float SIXTEENTH = 1.0f / 16.0f;

    private HeavyHammerItemTransform() {
    }

    public static Result solve(HeavyHammerAnimation.Sample hammer,
                               float armX, float armY, float armZ,
                               float forearmX, float forearmY, float forearmZ) {
        Quaternionf armRotation = new Quaternionf().rotationZYX(armZ, armY, armX);
        Quaternionf forearmRotation = new Quaternionf().rotationZYX(forearmZ, forearmY, forearmX);
        Quaternionf current = new Quaternionf(armRotation).mul(forearmRotation);
        Quaternionf layer = layerRotation();

        Matrix3f hammerBasis = new Matrix3f()
                .setColumn(0, hammer.headAxisX(), hammer.headAxisY(), hammer.headAxisZ())
                .setColumn(1, hammer.shaftX(), hammer.shaftY(), hammer.shaftZ())
                .setColumn(2, hammer.depthAxisX(), hammer.depthAxisY(), hammer.depthAxisZ());
        Quaternionf hammerRotation = new Quaternionf().setFromNormalized(hammerBasis);
        Quaternionf desiredBeforeLayer = new Quaternionf(hammerRotation)
                .mul(new Quaternionf(layer).conjugate());

        Vector3f layerHandOffset = itemLayerHandOffset().rotate(layer);
        // Для тяжёлого молота центр ItemTransform является реальной точкой хвата.
        // Ванильные боковые смещения (-1, -2 px) заменяются концом 10px цепи руки.
        Vector3f articulatedHandOffset = articulatedHandOffset().rotate(current);
        Vector3f compensation = articulatedHandOffset
                .rotate(new Quaternionf(desiredBeforeLayer).conjugate())
                .sub(layerHandOffset);
        Quaternionf correction = new Quaternionf(current).conjugate().mul(desiredBeforeLayer);
        return new Result(correction, compensation, current, desiredBeforeLayer, hammerRotation, layer);
    }

    public static Quaternionf layerRotation() {
        return new Quaternionf().rotationX(-(float) Math.PI * 0.5f)
                .mul(new Quaternionf().rotationY((float) Math.PI));
    }

    public static Vector3f itemLayerHandOffset() {
        return new Vector3f(SIXTEENTH, 2.0f * SIXTEENTH, -10.0f * SIXTEENTH);
    }

    public static Vector3f articulatedHandOffset() {
        return new Vector3f(0.0f, 10.0f * SIXTEENTH, 0.0f);
    }

    public record Result(Quaternionf correction, Vector3f compensation,
                         Quaternionf current, Quaternionf desiredBeforeLayer,
                         Quaternionf hammerRotation, Quaternionf layerRotation) {
    }
}
