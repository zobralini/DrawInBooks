package com.drawinbooks.client.debug;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import com.drawinbooks.DrawInBooks;
import com.drawinbooks.client.config.DrawConfig;
import com.drawinbooks.component.BookDrawingStorage;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * Debug readout: how many bytes the held item actually takes when serialized,
 * shown in the action bar.
 *
 * <p>This exists because every design decision in this mod is argued in bytes -
 * "a drawn book is 1.78x the heaviest vanilla book" is only a useful claim if
 * you can check it in game. It measures any item, not just books, which makes
 * it easy to compare a drawn book against a shulker box or a maxed-out written
 * book.
 *
 * <p>The number is the item's NBT size, which is what lands in region files
 * and what a chunk packet has to carry - not the in-memory footprint. It is
 * measured by serializing the stack exactly as the game would, so it includes
 * the drawing, the text, and everything else on the item.
 *
 * <p>Off by default, and only measured when the held item changes or every
 * second - serializing a 534 KiB book every frame would be its own problem.
 */
public final class ItemSizeOverlay {
	private static final int REFRESH_TICKS = 20;

	private static ItemStack lastStack = ItemStack.EMPTY;
	private static Component lastMessage;
	private static int ticksUntilRefresh;

	private ItemSizeOverlay() {
	}

	public static void initialize() {
		ClientTickEvents.END_CLIENT_TICK.register(ItemSizeOverlay::onClientTick);
	}

	private static void onClientTick(Minecraft minecraft) {
		if (!DrawConfig.get().debugItemSize) {
			lastMessage = null;
			lastStack = ItemStack.EMPTY;
			return;
		}

		LocalPlayer player = minecraft.player;

		if (player == null || minecraft.gui == null) {
			return;
		}

		ItemStack held = player.getMainHandItem();

		if (held.isEmpty()) {
			lastMessage = null;
			lastStack = ItemStack.EMPTY;
			return;
		}

		// Re-measure when the item changed, or once a second in case something
		// edited it in place - which is exactly what this mod does.
		if (held != lastStack || --ticksUntilRefresh <= 0) {
			lastStack = held;
			ticksUntilRefresh = REFRESH_TICKS;
			lastMessage = describe(player, held);
		}

		if (lastMessage != null) {
			// 26.2 split the old in-game HUD out of Gui, so the action bar
			// lives on gui.hud now rather than on gui itself.
			minecraft.gui.hud.setOverlayMessage(lastMessage, false);
		}
	}

	private static Component describe(LocalPlayer player, ItemStack stack) {
		int total = serializedSize(player, stack);

		if (total < 0) {
			return Component.literal("size unavailable").withStyle(ChatFormatting.DARK_GRAY);
		}

		StringBuilder text = new StringBuilder(stack.getItem().toString())
				.append("  ")
				.append(format(total));

		// For a book, call out how much of that is the drawing - the whole
		// point of the readout.
		BookDrawingStorage.readBlob(stack).ifPresent(blob ->
				text.append("  (drawing ").append(format(blob.length)).append(')'));

		return Component.literal(text.toString()).withStyle(color(total));
	}

	/** Green under 8 KiB, yellow under 64 KiB, red past that. */
	private static ChatFormatting color(int bytes) {
		if (bytes < 8 * 1024) {
			return ChatFormatting.GREEN;
		}

		return bytes < 64 * 1024 ? ChatFormatting.YELLOW : ChatFormatting.RED;
	}

	private static String format(int bytes) {
		if (bytes < 1024) {
			return bytes + " B";
		}

		return String.format(java.util.Locale.ROOT, "%.1f KiB", bytes / 1024.0);
	}

	/**
	 * @return the item's serialized NBT size in bytes, or -1 if it could not
	 *         be measured
	 */
	private static int serializedSize(LocalPlayer player, ItemStack stack) {
		try {
			Tag tag = ItemStack.CODEC
					.encodeStart(player.registryAccess().createSerializationContext(NbtOps.INSTANCE), stack)
					.result()
					.orElse(null);

			if (!(tag instanceof CompoundTag compound)) {
				return -1;
			}

			ByteArrayOutputStream bytes = new ByteArrayOutputStream();

			try (DataOutputStream out = new DataOutputStream(bytes)) {
				NbtIo.write(compound, out);
			}

			return bytes.size();
		} catch (IOException | RuntimeException e) {
			DrawInBooks.LOGGER.debug("Could not measure item size", e);
			return -1;
		}
	}
}
