package com.drawinbooks.component;

import java.nio.ByteBuffer;
import java.util.Optional;

import com.mojang.serialization.Codec;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;

/**
 * Reads and writes the drawing on an ItemStack.
 *
 * <p>The data is a single byte array (see {@link DrawingBlob}) kept inside
 * vanilla's {@code minecraft:custom_data}, at the path Bukkit's persistent
 * data container uses:
 *
 * <pre>
 *   custom_data: { PublicBukkitValues: { "drawinbooks:pages": [B; ... ] } }
 * </pre>
 *
 * <p>Two decisions are baked into that path, and both matter:
 *
 * <ul>
 *   <li><b>custom_data, not a component this mod registers.</b> A vanilla
 *       server stores and forwards it without knowing what it is, and a
 *       player without the mod sees an ordinary book - they cannot be
 *       disconnected by an unknown component id, because there isn't one.</li>
 *   <li><b>The Bukkit PDC path specifically.</b> A Paper plugin can only
 *       write item data through the persistent data container, which lands
 *       exactly here. Using that path from the Fabric side too means the
 *       plugin and the mod are reading and writing the same bytes in the same
 *       place, instead of two formats that have to be kept in sync.</li>
 * </ul>
 */
public final class BookDrawingStorage {
	/** Bukkit puts every plugin's persistent data under this compound. */
	public static final String BUKKIT_ROOT = "PublicBukkitValues";

	/** Namespaced like a Bukkit NamespacedKey, because that is what writes it. */
	public static final String KEY = "drawinbooks:pages";

	/**
	 * Reading goes through a codec rather than raw tag lookups so that a tag
	 * of the wrong type where we expect ours is simply "no drawing" instead of
	 * an exception.
	 */
	private static final Codec<byte[]> BLOB_CODEC = Codec.BYTE_BUFFER.xmap(
			buffer -> {
				ByteBuffer copy = buffer.duplicate();
				byte[] bytes = new byte[copy.remaining()];
				copy.get(bytes);
				return bytes;
			},
			ByteBuffer::wrap);

	private static final Codec<byte[]> PATH_CODEC =
			BLOB_CODEC.fieldOf(KEY).codec().fieldOf(BUKKIT_ROOT).codec();

	private BookDrawingStorage() {
	}

	public static boolean isBook(ItemStack stack) {
		return stack != null && (stack.is(Items.WRITABLE_BOOK) || stack.is(Items.WRITTEN_BOOK));
	}

	/**
	 * @return the drawing on this stack, or empty if it has none or if what it
	 *         has fails validation - malformed data reads as absent
	 */
	public static Optional<DrawingBlob.Decoded> read(ItemStack stack) {
		return readBlob(stack).map(DrawingBlob::decode).filter(decoded -> decoded != null);
	}

	/** The raw blob, still validated, for callers that only pass it along. */
	public static Optional<byte[]> readBlob(ItemStack stack) {
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);

		if (data == null) {
			return Optional.empty();
		}

		return PATH_CODEC.parse(NbtOps.INSTANCE, data.copyTag())
				.result()
				.filter(DrawingBlob::isValid);
	}

	/**
	 * Writes a validated blob, leaving every other key in custom_data - and
	 * every other plugin's persistent data - untouched.
	 *
	 * @return false if the blob was rejected, in which case nothing was written
	 */
	public static boolean writeBlob(ItemStack stack, byte[] blob) {
		if (!DrawingBlob.isValid(blob)) {
			return false;
		}

		CustomData existing = stack.get(DataComponents.CUSTOM_DATA);
		CompoundTag root = existing == null ? new CompoundTag() : existing.copyTag();
		CompoundTag bukkit = root.getCompound(BUKKIT_ROOT).orElseGet(CompoundTag::new);

		bukkit.putByteArray(KEY, blob);
		root.put(BUKKIT_ROOT, bukkit);
		stack.set(DataComponents.CUSTOM_DATA, CustomData.of(root));

		return true;
	}

	public static void write(ItemStack stack, java.util.List<byte[]> pages, int colorIndex) {
		byte[] blob = DrawingBlob.encode(pages, colorIndex);

		if (blob == null) {
			clear(stack);
		} else {
			writeBlob(stack, blob);
		}
	}

	/** Removes only our key, and any now-empty container above it. */
	public static void clear(ItemStack stack) {
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);

		if (data == null) {
			return;
		}

		CompoundTag root = data.copyTag();
		CompoundTag bukkit = root.getCompound(BUKKIT_ROOT).orElse(null);

		if (bukkit == null) {
			return;
		}

		bukkit.remove(KEY);

		if (bukkit.isEmpty()) {
			root.remove(BUKKIT_ROOT);
		} else {
			root.put(BUKKIT_ROOT, bukkit);
		}

		if (root.isEmpty()) {
			stack.remove(DataComponents.CUSTOM_DATA);
		} else {
			stack.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
		}
	}
}
