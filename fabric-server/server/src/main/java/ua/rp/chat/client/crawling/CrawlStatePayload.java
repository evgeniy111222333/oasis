package ua.rp.chat.client.crawling;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record CrawlStatePayload(boolean crawling, boolean stealth, float progress) implements CustomPacketPayload {
    public static final Type<CrawlStatePayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("rpchat", "crawl_state"));
    public static final StreamCodec<RegistryFriendlyByteBuf, CrawlStatePayload> CODEC = StreamCodec.ofMember(
            CrawlStatePayload::write,
            CrawlStatePayload::read
    );

    private void write(RegistryFriendlyByteBuf buf) {
        buf.writeBoolean(crawling);
        buf.writeBoolean(stealth);
        buf.writeFloat(progress);
    }

    private static CrawlStatePayload read(RegistryFriendlyByteBuf buf) {
        boolean crawling = buf.readBoolean();
        boolean stealth = buf.readBoolean();
        float progress = buf.readFloat();
        return new CrawlStatePayload(crawling, stealth, progress);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
