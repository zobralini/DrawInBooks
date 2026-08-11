package com.drawinbooks.client.draw;

import java.util.UUID;

import com.drawinbooks.DrawInBooks;
import com.drawinbooks.component.BookDrawingStorage;
import com.drawinbooks.component.PageDrawings;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Writes the finished drawing (and its ink color) into the book's components.
 *
 * <p>This is a pure client mod, so the honest reach of persistence is:
 * <ul>
 *   <li><b>Singleplayer</b>: the integrated server runs in this JVM with the
 *       mod loaded, so we also set the component on the server-side copy of
 *       the held item; it then saves with the world like any component.</li>
 *   <li><b>Multiplayer, creative</b>: the creative set-item packet carries
 *       full component data and vanilla servers accept it.</li>
 *   <li><b>Multiplayer, survival</b>: the vanilla book-edit packet only
 *       carries text pages, so the drawing stays local.</li>
 * </ul>
 *
 * <p>Signing complicates this: vanilla replaces the {@code writable_book}
 * with a freshly built {@code written_book} <em>after</em> the screen closes,
 * which drops any component we set beforehand. So instead of writing once, we
 * re-apply for a short window of client ticks afterwards.
 *
 * <p>That retry is deliberately narrow, because a drawing must never land on
 * the wrong book: identical-looking books are still separate items. The retry
 * is locked to the exact inventory slot the book was edited in, and only
 * touches either the very same {@link ItemStack} instance or a
 * {@code written_book} that replaced it in that slot - never some other book
 * the player happens to select in the meantime.
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

	private static PageDrawings pendingDrawings;
	private static int pendingColorIndex;
	private static InteractionHand pendingHand;
	private static int pendingSlot = -1;
	private static ItemStack pendingStack;
	private static int ticksLeft;

	private DrawingPersistence() {
	}

	public static void initialize() {
		ClientTickEvents.END_CLIENT_TICK.register(DrawingPersistence::onClientTick);
	}

	private static boolean isBook(ItemStack stack) {
		return stack.is(Items.WRITABLE_BOOK) || stack.is(Items.WRITTEN_BOOK);
	}

	/**
	 * Applies the drawing to the book in the given hand and keeps re-applying
	 * briefly so the signed copy inherits it.
	 *
	 * @param drawings snapshot to store, or {@code null} to remove the component
	 */
	public static void persist(Minecraft minecraft, InteractionHand hand, PageDrawings drawings, InkColor inkColor) {
		LocalPlayer player = minecraft.player;

		if (player == null) {
			return;
		}

		ItemStack stack = player.getItemInHand(hand);

		if (!isBook(stack)) {
			return;
		}

		pendingDrawings = drawings;
		pendingColorIndex = inkColor == null ? 0 : inkColor.ordinal();
		pendingHand = hand;
		pendingStack = stack;
		pendingSlot = hand == InteractionHand.OFF_HAND ? -1 : player.getInventory().getSelectedSlot();
		ticksLeft = REAPPLY_TICKS;

		if (!applyOnce(minecraft) && drawings != null) {
			// Remote survival server: the vanilla book-edit packet carries only
			// text pages, so the drawing cannot legitimately reach the server.
			DrawInBooks.LOGGER.info("Drawing stored on the client-side stack only (remote survival server)");
		}
	}

	private static void onClientTick(Minecraft minecraft) {
		if (ticksLeft <= 0) {
			return;
		}

		ticksLeft--;
		applyOnce(minecraft);

		if (ticksLeft == 0) {
			pendingDrawings = null;
			pendingStack = null;
			pendingSlot = -1;
		}
	}

	/**
	 * @return true if the component reached an authoritative copy -
	 *         singleplayer server or creative packet
	 */
	private static boolean applyOnce(Minecraft minecraft) {
		LocalPlayer player = minecraft.player;

		if (player == null || pendingHand == null) {
			return false;
		}

		ItemStack stack = targetStack(player);

		if (stack == null) {
			return false;
		}

		BookDrawingStorage.Stored current = BookDrawingStorage.read(stack).orElse(null);

		boolean alreadyApplied = pendingDrawings == null
				? current == null
				: current != null
						&& pendingDrawings.equals(current.drawings())
						&& pendingColorIndex == current.colorIndex();

		apply(stack, pendingDrawings, pendingColorIndex);

		MinecraftServer server = minecraft.getSingleplayerServer();

		if (server != null) {
			// Singleplayer / LAN host: write through to the authoritative copy,
			// matched by the same slot rule.
			UUID playerId = player.getUUID();
			PageDrawings drawings = pendingDrawings;
			int colorIndex = pendingColorIndex;
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

				if (serverStack != null && isBook(serverStack)) {
					apply(serverStack, drawings, colorIndex);
				}
			});

			return true;
		}

		if (player.getAbilities().instabuild) {
			// Remote server, creative mode: the creative set-item packet
			// carries component data verbatim. Send it only when something
			// actually changed, to avoid spamming a packet every tick.
			if (!alreadyApplied) {
				int slot = pendingHand == InteractionHand.OFF_HAND
						? OFFHAND_PACKET_SLOT
						: 36 + pendingSlot;
				player.connection.send(new ServerboundSetCreativeModeSlotPacket(slot, stack.copy()));
			}

			return true;
		}

		return false;
	}

	/**
	 * The book this drawing belongs to, or null if it is no longer there.
	 * Accepts only the original stack instance, or a written book that took
	 * its place in the same slot (that is exactly what signing does). Any
	 * other book - a different copy, a same-named duplicate, whatever the
	 * player selected a moment later - is explicitly not a match.
	 */
	private static ItemStack targetStack(LocalPlayer player) {
		ItemStack stack = pendingHand == InteractionHand.OFF_HAND
				? player.getOffhandItem()
				: player.getInventory().getItem(pendingSlot);

		if (stack == null || !isBook(stack)) {
			return null;
		}

		if (stack == pendingStack || stack.is(Items.WRITTEN_BOOK)) {
			return stack;
		}

		return null;
	}

	private static void apply(ItemStack stack, PageDrawings drawings, int colorIndex) {
		BookDrawingStorage.write(stack, drawings, colorIndex);
	}
}
