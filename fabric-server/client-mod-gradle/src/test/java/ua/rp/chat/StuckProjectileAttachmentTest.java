package ua.rp.chat;

import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.util.RandomSource;
import ua.rp.chat.client.render.StuckProjectileAttachment;

import java.util.List;
import java.util.Map;

public final class StuckProjectileAttachmentTest {
    private StuckProjectileAttachmentTest() {
    }

    public static void main(String[] args) {
        MeshDefinition mesh = new MeshDefinition();
        mesh.getRoot().addOrReplaceChild(
                "attachment",
                StuckProjectileAttachment.invisibleCube(-4.0f, 0.0f, -2.0f, 8.0f, 12.0f, 4.0f),
                PartPose.ZERO);
        ModelPart attachment = mesh.getRoot().bake(64, 64).getChild("attachment");

        require(!attachment.isEmpty(), "Invisible attachment must still own a cube");
        ModelPart.Cube cube = attachment.getRandomCube(RandomSource.create(7L));
        require(cube.polygons.length == 0, "Attachment cube must not emit visible polygons");
        require(cube.minX == -4.0f && cube.maxX == 4.0f,
                "Attachment cube must preserve horizontal bounds");
        require(cube.minY == 0.0f && cube.maxY == 12.0f,
                "Attachment cube must preserve vertical bounds");
        require(cube.minZ == -2.0f && cube.maxZ == 2.0f,
                "Attachment cube must preserve depth bounds");

        ModelPart empty = new ModelPart(List.of(), Map.of());
        ModelPart recovered = StuckProjectileAttachment.safeBodyPart(
                RandomSource.create(11L), empty, List.of(empty, attachment));
        require(recovered == attachment, "Empty selected body part must fall back to populated geometry");
        recovered.getRandomCube(RandomSource.create(13L));

        ModelPart emergency = StuckProjectileAttachment.safeBodyPart(
                RandomSource.create(17L), empty, List.of(empty));
        require(!emergency.isEmpty(), "All-empty third-party models require a non-empty emergency part");
        require(emergency.getRandomCube(RandomSource.create(19L)).polygons.length == 0,
                "Emergency fallback must also remain invisible");

        System.out.println("Stuck projectile attachments are non-empty, invisible and crash-safe");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
