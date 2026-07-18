package ua.rp.chat.client.mixin;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ua.rp.chat.client.heavyhammer.HammerEquipmentScreen;

@Mixin(InventoryScreen.class)
public abstract class InventoryScreenMixin extends Screen {
    protected InventoryScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void eclipse$addEquipmentSubmenu(CallbackInfo ci) {
        int inventoryRight = width / 2 + 88;
        int inventoryTop = height / 2 - 83;
        addRenderableWidget(Button.builder(Component.literal("Снаряжение"), button ->
                        minecraft.setScreen(new HammerEquipmentScreen((Screen) (Object) this)))
                .bounds(Math.min(width - 92, inventoryRight + 6), inventoryTop + 7, 86, 20)
                .build());
    }
}
