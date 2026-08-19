package com.drawinbooks.net;

import com.drawinbooks.DrawInBooks;
import com.drawinbooks.component.DrawingBlob;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Client to server: one slice of "this is the drawing on the book in my hand".
 *
 * <p>Needed because no vanilla packet can carry arbitrary item data from a
 * survival player - the book edit packet only carries text, and the creative
 * set-slot packet is refused unless the player is in creative. Without this,
 * a drawing made on a server would live only on the sender's client and be
 * wiped by the next inventory sync.
 *
 * <p><b>Why it is sliced.</b> Vanilla caps a serverbound custom payload at
 * 32 767 bytes and <em>disconnects</em> the sender when a packet exceeds it. A
 * full drawing is up to 534 KiB, so anything past a handful of drawn pages
 * used to kick the player - and, because this is sent from the book screen's
 * save path, it took the unsent text with it. The blob is therefore cut into
 * {@value #MAX_CHUNK_BYTES}-byte pieces that are reassembled server side.
 *
 * <p>The wire format stays deliberately trivial - three VarInts and a byte
 * array - so that a Paper plugin, which receives this as a plain plugin
 * message on the {@code drawinbooks:draw2} channel, can parse it without any
 * Minecraft classes. Do not add structure here without updating that plugin.
 *
 * <p>The channel id gained a {@code 2} when slicing was introduced. That is
 * what makes a version mismatch harmless: an older server never announces this
 * channel, so a newer client sees {@code canSend} return false and says so,
 * instead of sending something the other side would misread.
 */
public record DrawingSyncPayload(int hand, int chunkIndex, int chunkCount, byte[] chunk)
		implements CustomPacketPayload {
	public static final Identifier ID = Identifier.fromNamespaceAndPath(DrawInBooks.MOD_ID, "draw2");
	public static final Type<DrawingSyncPayload> TYPE = new Type<>(ID);

	/**
	 * Comfortably under vanilla's 32 767-byte serverbound payload cap, with
	 * room to spare for the channel id and the three VarInts.
	 */
	public static final int MAX_CHUNK_BYTES = 16384;

	/** How many chunks the largest possible drawing needs. */
	public static final int MAX_CHUNKS =
			(DrawingBlob.MAX_BYTES + MAX_CHUNK_BYTES - 1) / MAX_CHUNK_BYTES;

	public static final StreamCodec<RegistryFriendlyByteBuf, DrawingSyncPayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, DrawingSyncPayload::hand,
			ByteBufCodecs.VAR_INT, DrawingSyncPayload::chunkIndex,
			ByteBufCodecs.VAR_INT, DrawingSyncPayload::chunkCount,
			ByteBufCodecs.byteArray(MAX_CHUNK_BYTES), DrawingSyncPayload::chunk,
			DrawingSyncPayload::new);

	/** Number of chunks a blob of this length will be sent as (at least one). */
	public static int chunkCountFor(int blobLength) {
		return Math.max(1, (blobLength + MAX_CHUNK_BYTES - 1) / MAX_CHUNK_BYTES);
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
