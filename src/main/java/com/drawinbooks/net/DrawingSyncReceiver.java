package com.drawinbooks.net;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.drawinbooks.DrawInBooks;
import com.drawinbooks.component.DrawingBlob;
import com.drawinbooks.component.BookDrawingStorage;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

/**
 * Server side of drawing sync. Present whenever the mod is installed on a
 * server; does nothing at all if no client ever sends anything.
 *
 * <p>A drawing arrives as a run of {@link DrawingSyncPayload} chunks, because
 * one payload cannot exceed vanilla's 32 767-byte cap. Chunks are buffered per
 * player and only applied once the last one lands.
 *
 * <p>Everything a client sends is treated as hostile until proven otherwise:
 * the payload codec caps each chunk, a transfer may not exceed the format's
 * own maximum, chunks must arrive in order, {@link DrawingBlob#isValid}
 * rejects any assembled blob that isn't exactly the fixed format, the target
 * must actually be a book in the player's hand, and completed syncs are rate
 * limited. A client cannot use this to attach data to arbitrary items, to
 * exceed the size cap, or to make the server hold more than one partial
 * drawing per player.
 */
public final class DrawingSyncReceiver {
	/** Minimum gap between accepted (completed) syncs from one player. */
	private static final long COOLDOWN_MS = 500;

	/** A half-finished transfer this old is abandoned. */
	private static final long TRANSFER_TIMEOUT_MS = 10_000;

	private static final Map<UUID, Long> lastAccepted = new HashMap<>();
	private static final Map<UUID, Transfer> transfers = new HashMap<>();

	/** One in-flight drawing: the chunks received so far, in order. */
	private static final class Transfer {
		private final byte[] buffer;
		private final int chunkCount;
		private int nextIndex;
		private int length;
		private long updatedAt;

		private Transfer(int chunkCount) {
			this.chunkCount = chunkCount;
			this.buffer = new byte[chunkCount * DrawingSyncPayload.MAX_CHUNK_BYTES];
			this.updatedAt = System.currentTimeMillis();
		}
	}

	private DrawingSyncReceiver() {
	}

	public static void initialize() {
		PayloadTypeRegistry.serverboundPlay().register(DrawingSyncPayload.TYPE, DrawingSyncPayload.CODEC);

		ServerPlayNetworking.registerGlobalReceiver(DrawingSyncPayload.TYPE, (payload, context) ->
				accept(context.player(), payload));

		// Don't leak state for players who have left.
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			UUID id = handler.getPlayer().getUUID();
			lastAccepted.remove(id);
			transfers.remove(id);
		});
	}

	private static void accept(ServerPlayer player, DrawingSyncPayload payload) {
		byte[] blob = collect(player.getUUID(), payload);

		if (blob == null) {
			return; // more chunks to come, or the transfer was rejected
		}

		if (isRateLimited(player)) {
			return;
		}

		// An empty blob is how a client says "this book has no drawing any
		// more"; anything else has to be exactly the fixed format.
		if (blob.length > 0 && !DrawingBlob.isValid(blob)) {
			DrawInBooks.LOGGER.debug("Rejected malformed drawing from {}", player.getName().getString());
			return;
		}

		InteractionHand hand = payload.hand() == 1 ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
		ItemStack stack = player.getItemInHand(hand);

		// The only thing a client may attach a drawing to is a book it is
		// actually holding.
		if (!BookDrawingStorage.isBook(stack)) {
			return;
		}

		if (blob.length == 0) {
			BookDrawingStorage.clear(stack);
		} else {
			BookDrawingStorage.writeBlob(stack, blob);
		}

		player.containerMenu.broadcastChanges();
	}

	/**
	 * Adds one chunk to the player's in-flight transfer.
	 *
	 * @return the assembled blob once the final chunk has arrived, otherwise
	 *         null - which also covers every rejection, since a client that
	 *         sends nonsense simply gets nothing stored
	 */
	private static byte[] collect(UUID playerId, DrawingSyncPayload payload) {
		int count = payload.chunkCount();
		int index = payload.chunkIndex();
		byte[] chunk = payload.chunk();

		if (count < 1 || count > DrawingSyncPayload.MAX_CHUNKS || index < 0 || index >= count) {
			transfers.remove(playerId);
			return null;
		}

		// Every chunk but the last must be full. Without this a client could
		// describe a 34-chunk transfer and dribble it out one byte at a time.
		if (index < count - 1 && chunk.length != DrawingSyncPayload.MAX_CHUNK_BYTES) {
			transfers.remove(playerId);
			return null;
		}

		long now = System.currentTimeMillis();
		Transfer transfer = transfers.get(playerId);

		if (index == 0) {
			transfer = new Transfer(count); // a restart is normal after a failed send
			transfers.put(playerId, transfer);
		} else if (transfer == null || transfer.chunkCount != count || transfer.nextIndex != index
				|| now - transfer.updatedAt > TRANSFER_TIMEOUT_MS) {
			transfers.remove(playerId);
			return null;
		}

		if (transfer.length + chunk.length > DrawingBlob.MAX_BYTES) {
			transfers.remove(playerId);
			return null;
		}

		System.arraycopy(chunk, 0, transfer.buffer, transfer.length, chunk.length);
		transfer.length += chunk.length;
		transfer.nextIndex = index + 1;
		transfer.updatedAt = now;

		if (transfer.nextIndex < count) {
			return null;
		}

		transfers.remove(playerId);
		return Arrays.copyOf(transfer.buffer, transfer.length);
	}

	private static boolean isRateLimited(ServerPlayer player) {
		long now = System.currentTimeMillis();
		Long previous = lastAccepted.get(player.getUUID());

		if (previous != null && now - previous < COOLDOWN_MS) {
			return true;
		}

		lastAccepted.put(player.getUUID(), now);
		return false;
	}
}
