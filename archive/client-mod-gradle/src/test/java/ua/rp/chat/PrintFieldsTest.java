package ua.rp.chat;

import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import java.lang.reflect.Field;

public final class PrintFieldsTest {
    public static void main(String[] args) {
        System.out.println("=== FIELDS of AvatarRenderState ===");
        Class<?> clazz = AvatarRenderState.class;
        while (clazz != null) {
            System.out.println("Class: " + clazz.getName());
            for (Field field : clazz.getDeclaredFields()) {
                System.out.println("  " + field.getType().getName() + " " + field.getName());
            }
            clazz = clazz.getSuperclass();
        }
    }
}
