package ua.rp.chat;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class FabricParityContractTest {
    public static void main(String[] args) throws Exception {
        Path configFile = Files.createTempFile("eclipse-fabric-config-", ".yml");
        try {
            Files.writeString(configFile, "active-style: 4\ncombat:\n  enabled: false\n", StandardCharsets.UTF_8);
            SimpleConfig config = new SimpleConfig(configFile.toFile());
            require(config.getInt("active-style", 1) == 4, "Fabric must reload the configured chat style");
            require(!config.getBoolean("combat.enabled", true), "Fabric must honor combat.enabled=false");
        } finally {
            Files.deleteIfExists(configFile);
        }

        String defaults = resource("config.yml");
        require(defaults.contains("combat:\n  enabled: true"), "Default production config must declare RP combat");

        String mixins = resource("eclipseserver.mixins.json");
        require(mixins.contains("AuthContainerMixin\""),
                "Pending authentication must prevent server-side container access");
        require(mixins.contains("PlayerActionMixin\""),
                "Pending authentication must prevent inventory packet actions");

        System.out.println("FabricParityContractTest passed");
    }

    private static String resource(String name) throws Exception {
        try (InputStream input = FabricParityContractTest.class.getClassLoader().getResourceAsStream(name)) {
            if (input == null) throw new AssertionError("Missing runtime resource: " + name);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
