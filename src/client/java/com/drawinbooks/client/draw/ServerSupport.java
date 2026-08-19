package com.drawinbooks.client.draw;

import com.drawinbooks.DrawInBooks;
import com.drawinbooks.client.config.DrawConfig;
import com.drawinbooks.net.DrawingSyncPayload;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Whether drawings can actually be stored where the player currently is.
 *
 * <p>The question is answerable without asking: on joining, a server announces
 * every plugin-message channel it listens on, so
 * {@link ClientPlayNetworking#canSend} already tells us whether the other side
 * runs this mod or its plugin - and, because the channel name carries the
 * protocol version, whether it runs a compatible one.
 *
 * <p>Three situations can store a drawing:
 * <ul>
 *   <li><b>Singleplayer or hosting a LAN world</b> - the authoritative copy of
 *       the item is in this JVM.</li>
 *   <li><b>A server with the mod or the plugin</b> - the channel is there.</li>
 *   <li><b>Creative on any server</b> - the creative set-slot packet carries
 *       arbitrary item data and vanilla accepts it from creative players. This
 *       one is easy to forget and is why "no channel" alone is not the
 *       test.</li>
 * </ul>
 *
 * <p>Anywhere else the drawing would live on this client until the next
 * inventory sync wiped it, so by default the tools are not offered at all -
 * better than letting someone spend ten minutes on a drawing that was never
 * going to survive.
 */
public final class ServerSupport {
	/** So the notice appears once per server, not once per book opened. */
	private static boolean notified;

	private ServerSupport() {
	}

	public static void initialize() {
		// A fresh connection is a fresh answer: leaving a vanilla server for
		// one with the plugin must not stay silent, and vice versa.
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> notified = false);
	}

	/** Whether a drawing made here would actually be kept. */
	public static boolean canStoreDrawings() {
		Minecraft minecraft = Minecraft.getInstance();

		if (minecraft.getSingleplayerServer() != null) {
			return true;
		}

		if (ClientPlayNetworking.canSend(DrawingSyncPayload.TYPE)) {
			return true;
		}

		return minecraft.player != null && minecraft.player.getAbilities().instabuild;
	}

	/**
	 * Whether to offer the drawing tools. Normally that means "only where they
	 * would work", but it is a setting: someone may want to draw anyway - for a
	 * screenshot, or because their server keeps item data some other way - and
	 * the mod has no business being certain about a server it can only see one
	 * side of.
	 */
	public static boolean editingAllowed() {
		return !DrawConfig.get().hideToolsWithoutServerSupport || canStoreDrawings();
	}

	/**
	 * Says once why the tools are missing. Silence would be indistinguishable
	 * from the mod being broken.
	 *
	 * <p>Chat rather than the action bar because the HUD isn't drawn while a
	 * screen is open, and this is discovered exactly when a book screen opens -
	 * an action-bar message would be shown to nobody. In chat it is still there
	 * when the book is closed.
	 */
	public static void explainOnce() {
		if (notified) {
			return;
		}

		notified = true;

		DrawInBooks.LOGGER.info(
				"Drawing tools hidden: this server has neither the Draw In Books mod nor its plugin (1.1.0 or "
						+ "newer), so a drawing could not be saved here.");

		Minecraft minecraft = Minecraft.getInstance();

		if (minecraft.gui == null || minecraft.player == null) {
			return;
		}

		minecraft.gui.hud.getChat().addClientSystemMessage(Component.literal(
						"[Draw In Books] Drawing is off here - this server can't store drawings. "
								+ "It needs the mod or the plugin installed.")
				.withStyle(ChatFormatting.GRAY));
	}
}
