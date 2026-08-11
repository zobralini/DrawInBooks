package com.drawinbooks.net;

import com.drawinbooks.DrawInBooks;
import com.drawinbooks.component.DrawingBlob;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Client to server: "this is the drawing on the book in my hand".
 *
 * <p>Needed because no vanilla packet can carry arbitrary item data from a
 * survival player - the book edit packet only carries text, and the creative
 * set-slot packet is refused unless the player is in creative. Without this,
 * a drawing made on a server would live only on the sender's client and be
 * wiped by the next inventory sync.
 *
 * <p>The wire format is deliberately trivial - a hand index and a byte array -
 * so that a Paper plugin, which receives this as a plain plugin message on the
 * {@code drawinbooks:draw} channel, can parse it without any Minecraft
 * classes. Do not add structure here without updating that plugin.
 */
public record DrawingSyncPayload(int hand, byte[] blob) implements CustomPacketPayload {
	public static final Identifier ID = Identifier.fromNamespaceAndPath(DrawInBooks.MOD_ID, "draw");
	public static final Type<DrawingSyncPayload> TYPE = new Type<>(ID);

	/**
	 * The length cap is the format's own hard maximum, enforced by the codec
	 * before a single byte is buffered - so an oversized payload is dropped by
	 * the network layer rather than by our handler.
	 */
	public static final StreamCodec<RegistryFriendlyByteBuf, DrawingSyncPayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, DrawingSyncPayload::hand,
			ByteBufCodecs.byteArray(DrawingBlob.MAX_BYTES), DrawingSyncPayload::blob,
			DrawingSyncPayload::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
