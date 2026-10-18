package ua.rp.chat.carry;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.renderer.block.BuiltInBlockModels;
import net.minecraft.client.renderer.block.model.SpecialBlockModelWrapper;
import net.minecraft.client.renderer.blockentity.ChestRenderer;
import net.minecraft.client.renderer.blockentity.ShulkerBoxRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/** Real native special-model orientation and survival checks, without any world/client process. */
public final class CarryBlockVisualTest {
    public static void main(String[] args) {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        var models = BuiltInBlockModels.createBlockModels(BlockColors.createDefault());
        int cases = 0;
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            var chest = Blocks.CHEST.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, direction);
            for (int turn = 0; turn < 4; turn++) {
                var rotated = CarryPlacement.rotated(chest, turn);
                var model = models.get(rotated);
                var transforms = specialTransforms(model);
                require(!transforms.isEmpty(), "Native display has no state-aware chest model");
                for (var transform : transforms) require(transform.equals(ChestRenderer.modelTransformation(rotated.getValue(BlockStateProperties.HORIZONTAL_FACING)).getMatrix(), .00001f),
                        "Chest preview loses saved facing");
                cases++;
            }
        }
        for (var block : new net.minecraft.world.level.block.Block[]{Blocks.SHULKER_BOX, Blocks.RED_SHULKER_BOX})
            for (Direction direction : Direction.values()) for (int turn = 0; turn < 4; turn++) {
                var state = block.defaultBlockState().setValue(BlockStateProperties.FACING, direction);
                var rotated = CarryPlacement.rotated(state, turn);
                var model = models.get(rotated);
                var transforms = specialTransforms(model);
                require(!transforms.isEmpty(), "Native display has no directional shulker model");
                for (var transform : transforms) require(transform.equals(ShulkerBoxRenderer.modelTransform(rotated.getValue(BlockStateProperties.FACING)).getMatrix(), .00001f),
                        "Shulker preview loses horizontal or vertical facing");
                cases++;
            }
        Map<BlockPos, BlockState> blocks = new HashMap<>();
        LevelReader world = (LevelReader) Proxy.newProxyInstance(CarryBlockVisualTest.class.getClassLoader(), new Class<?>[]{LevelReader.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getBlockState" -> blocks.getOrDefault(arguments[0], Blocks.AIR.defaultBlockState());
                    case "getFluidState" -> blocks.getOrDefault(arguments[0], Blocks.AIR.defaultBlockState()).getFluidState();
                    case "toString" -> "read-only carry survival fixture";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        BlockPos destination = new BlockPos(0, 70, 0);
        var hanging = Blocks.LANTERN.defaultBlockState().setValue(BlockStateProperties.HANGING, true);
        require(!CarryPlacement.snapshotDestination(world, destination, hanging, 0), "Unsupported hanging lantern is green");
        blocks.put(destination.above(), Blocks.STONE.defaultBlockState());
        require(CarryPlacement.snapshotDestination(world, destination, hanging, 0), "Supported hanging lantern is rejected");
        blocks.put(destination, Blocks.SHORT_GRASS.defaultBlockState());
        require(!CarryPlacement.snapshotDestination(world, destination, hanging, 0), "Non-air grass destination is green");
        blocks.put(destination, Blocks.WATER.defaultBlockState());
        require(!CarryPlacement.snapshotDestination(world, destination, null, 0), "Water-filled voxel destination is green");
        System.out.println("CarryBlockVisualTest: " + cases + " native chest/shulker orientations and real lantern/grass/fluid placement rules passed");
    }
    private static java.util.List<org.joml.Matrix4f> specialTransforms(net.minecraft.client.renderer.block.model.BlockModel.Unbaked model) {
        var result = new java.util.ArrayList<org.joml.Matrix4f>();
        collect(model, new org.joml.Matrix4f(), result); return result;
    }
    private static void collect(net.minecraft.client.renderer.block.model.BlockModel.Unbaked model,
                                org.joml.Matrix4f parent, java.util.List<org.joml.Matrix4f> result) {
        if (model instanceof SpecialBlockModelWrapper.Unbaked<?> special) {
            result.add(new org.joml.Matrix4f(parent).mul(special.transformation().map(com.mojang.math.Transformation::getMatrix).orElseGet(org.joml.Matrix4f::new)));
        } else if (model instanceof net.minecraft.client.renderer.block.model.CompositeBlockModel.Unbaked composite) {
            var transform = new org.joml.Matrix4f(parent).mul(composite.transformation().map(com.mojang.math.Transformation::getMatrix).orElseGet(org.joml.Matrix4f::new));
            collect(composite.normal(), transform, result); collect(composite.custom(), transform, result);
        } else if (model instanceof net.minecraft.client.renderer.block.model.ConditionalBlockModel.Unbaked conditional) {
            var transform = new org.joml.Matrix4f(parent).mul(conditional.transformation().map(com.mojang.math.Transformation::getMatrix).orElseGet(org.joml.Matrix4f::new));
            collect(conditional.onTrue(), transform, result); collect(conditional.onFalse(), transform, result);
        }
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
