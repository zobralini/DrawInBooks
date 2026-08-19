package com.drawinbooks.client.draw;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import com.drawinbooks.DrawInBooks;
import com.drawinbooks.component.BookDrawingStorage;
import com.drawinbooks.component.DrawingBlob;
import com.drawinbooks.net.DrawingSyncPayload;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Writes the finished drawing onto the book.
 *
 * <p>Getting it to stick depends entirely on what is on the other end:
 * <ul>
 *   <li><b>Singleplayer / LAN host</b> - the integrated server is in this JVM,
 *       so the drawing is written straight to the authoritative copy.</li>
 *   <li><b>Server running this mod (or the Paper plugin)</b> - the drawing is
 *       sent as a {@link DrawingSyncPayload}, validated server side, and
 *       stored. This is the only path that works in survival.</li>
 *   <li><b>Vanilla server, creative</b> - the creative set-slot packet carries
 *       arbitrary item data and vanilla accepts it from creative players.</li>
 *   <li><b>Vanilla server, survival</b> - nothing can be done. The vanilla
 *       book packet carries only text, so the drawing exists on this client
 *       until the next inventory sync overwrites it. The player is told once,
 *       rather than left wondering why their drawing vanished.</li>
 * </ul>
 *
 * <p>Signing complicates all of the above: vanilla builds a <em>new</em>
 * written book after the screen closes, so the write is repeated for a couple
 * of seconds, locked to the exact slot and item it started on so it can never
 * land on a different book.
 */
public final class DrawingPersistence {
	/**
	 * How long to keep re-applying after a save, in client ticks (~2s at 20
	 * tps). It has to outlast the sign round-trip - the server replaces the
	 * writable book with a freshly built written one, and the new stack only
	 * reaches the client a few ticks later - while staying short enough that
	 * it cannot follow the player into some unrelated book.
	 */
	private static final int REAPPLY_TICKS = 40;

	/** Inventory slot index of the offhand, as used by creative set-slot packets. */
	private static final int OFFHAND_PACKET_SLOT = 45;

	/** "This book has no drawing any more", as the server reads it. */
	private static final byte[] EMPTY_BLOB = new byte[0];

	private static byte[] pendingBlob;
	private static InteractionHand pendingHand;
	private static int pendingSlot = -1;
	private static ItemStack pendingStack;
	private static int ticksLeft;

	/** So the "this server can't store it" notice is logged once per session. */
	private static boolean warnedAboutServer;

	private DrawingPersistence() {
	}

	public static void initialize() {
		ClientTickEvents.END_CLIENT_TICK.register(DrawingPersistence::onClientTick);
	}

	/**
	 * @param pages      page bitmaps, or null/empty to remove the drawing
	 * @param inkColor   the pen color to remember for next time
	 */
	public static void persist(Minecraft minecraft, InteractionHand hand, List<byte[]> pages, InkColor inkColor) {
		LocalPlayer player = minecraft.player;

		if (player == null) {
			return;
		}

		ItemStack stack = player.getItemInHand(hand);

		if (!BookDrawingStorage.isBook(stack)) {
			return;
		}

		pendingBlob = pages == null || pages.isEmpty()
				? null
				: DrawingBlob.encode(pages, inkColor == null ? 0 : inkColor.ordinal());
		pendingHand = hand;
		pendingStack = stack;
		pendingSlot = hand == InteractionHand.OFF_HAND ? -1 : player.getInventory().getSelectedSlot();
		ticksLeft = REAPPLY_TICKS;

		applyOnce(minecraft, true);
	}

	private static void onClientTick(Minecraft minecraft) {
		if (ticksLeft <= 0) {
			return;
		}

		ticksLeft--;
		applyOnce(minecraft, false);

		if (ticksLeft == 0) {
			pendingBlob = null;
			pendingStack = null;
			pendingSlot = -1;
		}
	}

	/**
	 * @param firstAttempt true for the call made at save time - packets are
	 *                     only sent then, so a retry can never turn into a
	 *                     packet every tick
	 */
	private static void applyOnce(Minecraft minecraft, boolean firstAttempt) {
		LocalPlayer player = minecraft.player;

		if (player == null || pendingHand == null) {
			return;
		}

		ItemStack stack = targetStack(player);

		if (stack == null) {
			return;
		}

		apply(stack, pendingBlob);

		MinecraftServer server = minecraft.getSingleplayerServer();

		if (server != null) {
			writeThroughToIntegratedServer(server, player.getUUID());
			return;
		}

		if (!firstAttempt) {
			return;
		}

		// A server with the mod (or the Paper plugin) accepts this channel;
		// canSend is false on a plain vanilla server - and on one still running
		// an older version, which announced a different channel - so nothing is
		// sent to a server that would only reject it.
		if (ClientPlayNetworking.canSend(DrawingSyncPayload.TYPE)) {
			sendChunked(pendingBlob);
			return;
		}

		if (player.getAbilities().instabuild) {
			int slot = pendingHand == InteractionHand.OFF_HAND
					? OFFHAND_PACKET_SLOT
					: 36 + pendingSlot;
			player.connection.send(new ServerboundSetCreativeModeSlotPacket(slot, stack.copy()));
			return;
		}

		if (pendingBlob != null && !warnedAboutServer) {
			warnedAboutServer = true;
			DrawInBooks.LOGGER.warn(
					"This server has neither the Draw In Books mod nor its Paper plugin (or is running a version "
							+ "older than 1.1.0), and you are not in creative - the drawing cannot be stored server "
							+ "side and will disappear on the next inventory sync.");
		}
	}

	/**
	 * Sends the drawing as a run of chunks, because vanilla disconnects a
	 * client whose custom payload exceeds 32 767 bytes and a full drawing is
	 * many times that. The server applies nothing until the last chunk lands,
	 * so a half-sent drawing can never overwrite a whole one.
	 *
	 * @param blob the drawing, or null to clear the book's drawing - which is
	 *             sent as a single empty chunk, so erasing everything syncs
	 *             just like drawing does
	 */
	private static void sendChunked(byte[] blob) {
		byte[] data = blob == null ? EMPTY_BLOB : blob;

		if (data.length > 0 && !DrawingBlob.isValid(data)) {
			return; // never send something the server would only reject
		}

		int hand = pendingHand == InteractionHand.OFF_HAND ? 1 : 0;
		int count = DrawingSyncPayload.chunkCountFor(data.length);

		for (int i = 0; i < count; i++) {
			int from = i * DrawingSyncPayload.MAX_CHUNK_BYTES;
			int to = Math.min(from + DrawingSyncPayload.MAX_CHUNK_BYTES, data.length);

			ClientPlayNetworking.send(new DrawingSyncPayload(
					hand, i, count, Arrays.copyOfRange(data, from, to)));
		}
	}

	private static void writeThroughToIntegratedServer(MinecraftServer server, UUID playerId) {
		byte[] blob = pendingBlob;
		InteractionHand hand = pendingHand;
		int slot = pendingSlot;

		server.execute(() -> {
			ServerPlayer serverPlayer = server.getPlayerList().getPlayer(playerId);

			if (serverPlayer == null) {
				return;
			}

			ItemStack serverStack = hand == InteractionHand.OFF_HAND
					? serverPlayer.getItemInHand(hand)
					: serverPlayer.getInventory().getItem(slot);

			if (BookDrawingStorage.isBook(serverStack)) {
				apply(serverStack, blob);
			}
		});
	}

	/**
	 * The book this drawing belongs to, or null if it is no longer there.
	 * Accepts only the original stack instance, or a written book that took
	 * its place in the same slot - which is exactly what signing does. Any
	 * other book, including an identical-looking copy the player selected a
	 * moment later, is explicitly not a match.
	 */
	private static ItemStack targetStack(LocalPlayer player) {
		ItemStack stack = pendingHand == InteractionHand.OFF_HAND
				? player.getOffhandItem()
				: player.getInventory().getItem(pendingSlot);

		if (!BookDrawingStorage.isBook(stack)) {
			return null;
		}

		if (stack == pendingStack || stack.is(Items.WRITTEN_BOOK)) {
			return stack;
		}

		return null;
	}

	/**
	 * Writes only when the item doesn't already carry exactly this drawing.
	 * The re-apply window runs for two seconds, and a write means deep-copying
	 * the item's whole NBT plus the blob - up to 534 KiB - so repeating it
	 * every tick for a book that already took the change is pure garbage.
	 */
	private static void apply(ItemStack stack, byte[] blob) {
		byte[] current = BookDrawingStorage.readBlob(stack).orElse(null);

		if (blob == null) {
			if (current != null) {
				BookDrawingStorage.clear(stack);
			}

			return;
		}

		if (!Arrays.equals(current, blob)) {
			BookDrawingStorage.writeBlob(stack, blob);
		}
	}
}
