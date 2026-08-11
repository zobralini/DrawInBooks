package com.drawinbooks.paper;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.Material;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

/**
 * Server side of Draw In Books for Paper.
 *
 * <p>Why this exists: no vanilla packet lets a survival player attach data to
 * an item, so a drawing made on a server would live only on the drawer's
 * client until the next inventory sync wiped it. This plugin receives the
 * drawing on a plugin message channel and stores it on the book, which is all
 * the server needs to do - it never renders anything and never sends anything
 * back. The data rides along in the item's persistent data container, which
 * ends up inside vanilla {@code custom_data}, exactly where the client reads
 * it. Players without the mod are unaffected: to them it is an ordinary book.
 *
 * <p>Nothing here trusts the client. The message must be well formed, the blob
 * must match the fixed format byte for byte, the target must be a book the
 * player is actually holding, and each player is rate limited.
 */
public final class DrawInBooksPlugin extends JavaPlugin implements PluginMessageListener {
	/** Must match DrawingSyncPayload.ID on the mod side. */
	private static final String CHANNEL = "drawinbooks:draw";

	/** Must match BookDrawingStorage.KEY - Bukkit renders this as "drawinbooks:pages". */
	private static final String KEY = "pages";

	private static final long COOLDOWN_MS = 500;

	private final Map<UUID, Long> lastAccepted = new HashMap<>();
	private NamespacedKey dataKey;

	@Override
	public void onEnable() {
		this.dataKey = new NamespacedKey(this, KEY);
		getServer().getMessenger().registerIncomingPluginChannel(this, CHANNEL, this);
		getLogger().info("Ready - clients with Draw In Books can now store drawings on this server.");
	}

	@Override
	public void onDisable() {
		getServer().getMessenger().unregisterIncomingPluginChannel(this, CHANNEL, this);
		this.lastAccepted.clear();
	}

	@Override
	public void onPluginMessageReceived(String channel, Player player, byte[] message) {
		if (!CHANNEL.equals(channel) || isRateLimited(player)) {
			return;
		}

		Reader reader = new Reader(message);
		int hand = reader.readVarInt();
		byte[] blob = reader.readByteArray(DrawingBlob.MAX_BYTES);

		if (reader.failed() || !DrawingBlob.isValid(blob)) {
			return;
		}

		EquipmentSlot slot = hand == 1 ? EquipmentSlot.OFF_HAND : EquipmentSlot.HAND;
		ItemStack stack = player.getInventory().getItem(slot);

		// A drawing may only ever be attached to a book the player is holding.
		if (stack == null || !isBook(stack.getType())) {
			return;
		}

		ItemMeta meta = stack.getItemMeta();

		if (meta == null) {
			return;
		}

		meta.getPersistentDataContainer().set(this.dataKey, PersistentDataType.BYTE_ARRAY, blob);
		stack.setItemMeta(meta);
		player.getInventory().setItem(slot, stack);
	}

	private static boolean isBook(Material material) {
		return material == Material.WRITABLE_BOOK || material == Material.WRITTEN_BOOK;
	}

	private boolean isRateLimited(Player player) {
		long now = System.currentTimeMillis();
		Long previous = this.lastAccepted.get(player.getUniqueId());

		if (previous != null && now - previous < COOLDOWN_MS) {
			return true;
		}

		this.lastAccepted.put(player.getUniqueId(), now);
		return false;
	}

	/**
	 * Minimal reader for the Minecraft wire format the mod's payload codec
	 * produces: a VarInt, then a VarInt length followed by that many bytes.
	 * Written by hand so this plugin needs no Minecraft classes. Any
	 * malformed input sets {@link #failed()} instead of throwing.
	 */
	private static final class Reader {
		private final byte[] data;
		private int position;
		private boolean failed;

		private Reader(byte[] data) {
			this.data = data;
		}

		private boolean failed() {
			return this.failed;
		}

		private int readVarInt() {
			int result = 0;

			for (int shift = 0; shift < 35; shift += 7) {
				if (this.position >= this.data.length) {
					this.failed = true;
					return 0;
				}

				byte current = this.data[this.position++];
				result |= (current & 0x7F) << shift;

				if ((current & 0x80) == 0) {
					return result;
				}
			}

			this.failed = true;
			return 0;
		}

		private byte[] readByteArray(int maxLength) {
			int length = readVarInt();

			if (this.failed || length < 0 || length > maxLength
					|| this.position + length > this.data.length) {
				this.failed = true;
				return new byte[0];
			}

			byte[] out = new byte[length];
			System.arraycopy(this.data, this.position, out, 0, length);
			this.position += length;

			return out;
		}
	}
}
