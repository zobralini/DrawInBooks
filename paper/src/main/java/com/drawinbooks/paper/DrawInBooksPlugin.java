package com.drawinbooks.paper;

import java.util.Arrays;
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
 * Server side of Draw In Books for Bukkit-family servers (Bukkit, Spigot,
 * Paper and its forks - nothing here uses a Paper-only API).
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
 * <p>A drawing arrives as a run of chunks rather than one message, because a
 * client cannot send a custom payload larger than 32 767 bytes without being
 * disconnected, and a full drawing is many times that. Chunks are buffered per
 * player and only applied once the last one lands.
 *
 * <p>Nothing here trusts the client. The message must be well formed, chunks
 * must arrive in order and within the size cap, the blob must match the fixed
 * format byte for byte, the target must be a book the player is actually
 * holding, and each player is rate limited.
 */
public final class DrawInBooksPlugin extends JavaPlugin implements PluginMessageListener {
	/**
	 * Must match DrawingSyncPayload.ID on the mod side. The trailing 2 marks
	 * the chunked protocol: an older client announces only "drawinbooks:draw"
	 * and simply finds nobody listening, instead of sending a message this
	 * plugin would misread.
	 */
	private static final String CHANNEL = "drawinbooks:draw2";

	/** Must match BookDrawingStorage.KEY - Bukkit renders this as "drawinbooks:pages". */
	private static final String KEY = "pages";

	/** Must match DrawingSyncPayload.MAX_CHUNK_BYTES. */
	private static final int MAX_CHUNK_BYTES = 16384;

	private static final int MAX_CHUNKS = (DrawingBlob.MAX_BYTES + MAX_CHUNK_BYTES - 1) / MAX_CHUNK_BYTES;

	private static final long COOLDOWN_MS = 500;

	/** A half-finished transfer this old is abandoned. */
	private static final long TRANSFER_TIMEOUT_MS = 10_000;

	private final Map<UUID, Long> lastAccepted = new HashMap<>();
	private final Map<UUID, Transfer> transfers = new HashMap<>();
	private NamespacedKey dataKey;

	/** One in-flight drawing: the chunks received so far, in order. */
	private static final class Transfer {
		private final byte[] buffer;
		private final int chunkCount;
		private int nextIndex;
		private int length;
		private long updatedAt;

		private Transfer(int chunkCount) {
			this.chunkCount = chunkCount;
			this.buffer = new byte[chunkCount * MAX_CHUNK_BYTES];
			this.updatedAt = System.currentTimeMillis();
		}
	}

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
		this.transfers.clear();
	}

	@Override
	public void onPluginMessageReceived(String channel, Player player, byte[] message) {
		if (!CHANNEL.equals(channel)) {
			return;
		}

		Reader reader = new Reader(message);
		int hand = reader.readVarInt();
		int chunkIndex = reader.readVarInt();
		int chunkCount = reader.readVarInt();
		byte[] chunk = reader.readByteArray(MAX_CHUNK_BYTES);

		if (reader.failed()) {
			this.transfers.remove(player.getUniqueId());
			return;
		}

		byte[] blob = collect(player.getUniqueId(), chunkIndex, chunkCount, chunk);

		if (blob == null || isRateLimited(player)) {
			return;
		}

		// An empty blob means "this book has no drawing any more".
		if (blob.length > 0 && !DrawingBlob.isValid(blob)) {
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

		if (blob.length == 0) {
			meta.getPersistentDataContainer().remove(this.dataKey);
		} else {
			meta.getPersistentDataContainer().set(this.dataKey, PersistentDataType.BYTE_ARRAY, blob);
		}

		stack.setItemMeta(meta);
		player.getInventory().setItem(slot, stack);
	}

	/**
	 * Adds one chunk to the player's in-flight transfer.
	 *
	 * @return the assembled blob once the final chunk has arrived, otherwise
	 *         null - which also covers every rejection
	 */
	private byte[] collect(UUID playerId, int index, int count, byte[] chunk) {
		if (count < 1 || count > MAX_CHUNKS || index < 0 || index >= count) {
			this.transfers.remove(playerId);
			return null;
		}

		// Every chunk but the last must be full, so a client cannot describe a
		// long transfer and then dribble it out a byte at a time.
		if (index < count - 1 && chunk.length != MAX_CHUNK_BYTES) {
			this.transfers.remove(playerId);
			return null;
		}

		long now = System.currentTimeMillis();
		Transfer transfer = this.transfers.get(playerId);

		if (index == 0) {
			transfer = new Transfer(count); // a restart is normal after a failed send
			this.transfers.put(playerId, transfer);
		} else if (transfer == null || transfer.chunkCount != count || transfer.nextIndex != index
				|| now - transfer.updatedAt > TRANSFER_TIMEOUT_MS) {
			this.transfers.remove(playerId);
			return null;
		}

		if (transfer.length + chunk.length > DrawingBlob.MAX_BYTES) {
			this.transfers.remove(playerId);
			return null;
		}

		System.arraycopy(chunk, 0, transfer.buffer, transfer.length, chunk.length);
		transfer.length += chunk.length;
		transfer.nextIndex = index + 1;
		transfer.updatedAt = now;

		if (transfer.nextIndex < count) {
			return null;
		}

		this.transfers.remove(playerId);
		return Arrays.copyOf(transfer.buffer, transfer.length);
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
	 * produces: VarInts, then a VarInt length followed by that many bytes.
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
