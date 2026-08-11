package com.drawinbooks.net;

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
 * <p>Everything a client sends is treated as hostile until proven otherwise:
 * the payload codec caps the length, {@link DrawingBlob#isValid} rejects any
 * blob that isn't exactly the fixed format, the target must actually be a book
 * in the player's hand, and repeated sends are rate limited. A client cannot
 * use this to attach data to arbitrary items, to exceed the size cap, or to
 * spam the server with 356 KiB payloads.
 */
public final class DrawingSyncReceiver {
	/** Minimum gap between accepted syncs from one player, in milliseconds. */
	private static final long COOLDOWN_MS = 500;

	private static final Map<UUID, Long> lastAccepted = new HashMap<>();

	private DrawingSyncReceiver() {
	}

	public static void initialize() {
		PayloadTypeRegistry.serverboundPlay().register(DrawingSyncPayload.TYPE, DrawingSyncPayload.CODEC);

		ServerPlayNetworking.registerGlobalReceiver(DrawingSyncPayload.TYPE, (payload, context) ->
				accept(context.player(), payload));

		// Don't leak cooldown entries for players who have left.
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
				lastAccepted.remove(handler.getPlayer().getUUID()));
	}

	private static void accept(ServerPlayer player, DrawingSyncPayload payload) {
		if (isRateLimited(player)) {
			return;
		}

		if (!DrawingBlob.isValid(payload.blob())) {
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

		BookDrawingStorage.writeBlob(stack, payload.blob());
		player.containerMenu.broadcastChanges();
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
