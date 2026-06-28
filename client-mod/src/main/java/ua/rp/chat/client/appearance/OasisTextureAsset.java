package ua.rp.chat.client.appearance;

import net.minecraft.core.ClientAsset;
import net.minecraft.resources.Identifier;

public record OasisTextureAsset(Identifier id) implements ClientAsset.Texture {
    @Override
    public Identifier texturePath() {
        return id;
    }
}
