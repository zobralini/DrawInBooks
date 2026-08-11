package com.drawinbooks.component;

import java.util.Optional;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/**
 * Reads and writes the drawing on an ItemStack.
 *
 * <p>The data lives inside vanilla's {@code minecraft:custom_data} component,
 * under a single {@code drawinbooks} key - <b>not</b> in a data component
 * registered by this mod. That choice is what makes the mod genuinely
 * optional:
 * <ul>
 *   <li>A vanilla server stores and forwards {@code custom_data} like any
 *       other item data, without knowing what is in it.</li>
 *   <li>A player without the mod simply never sees the drawing. They cannot
 *       be disconnected by an unknown component id, because there isn't
 *       one.</li>
 *   <li>Two players who both have the mod see the same drawing on the same
 *       book, because the data travels with the item itself.</li>
 * </ul>
 * A mod-registered component would have been cleaner to read, but it would
 * add an entry to a synced registry and would be sent over the wire as an id
 * that vanilla clients cannot decode.
 *
 * <p>Everything still goes through {@link PageDrawings#CODEC}, so the strict
 * size and page-count validation applies exactly as before - now also to
 * whatever arbitrary NBT anyone else may have put in {@code custom_data}.
 */
public final class BookDrawingStorage {
	/** Our single key inside the shared custom_data compound. */
	public static final String ROOT_KEY = "drawinbooks";

	private BookDrawingStorage() {
	}

	/** What a book carries: the page bitmaps plus which color they render in. */
	public record Stored(PageDrawings drawings, int colorIndex) {
		public static final Codec<Stored> CODEC = RecordCodecBuilder.create(instance -> instance.group(
				PageDrawings.CODEC.fieldOf("pages").forGetter(Stored::drawings),
				Codec.INT.optionalFieldOf("color", 0).forGetter(Stored::colorIndex)
		).apply(instance, Stored::new));

		public Stored {
			colorIndex = PageDrawings.clampColorIndex(colorIndex);
		}
	}

	/** Codec anchored at the custom_data root, so no raw tag lookups are needed. */
	private static final Codec<Stored> ROOT_CODEC = Stored.CODEC.fieldOf(ROOT_KEY).codec();

	/**
	 * @return the drawing on this stack, or empty if it has none (or if what
	 *         it has fails validation - malformed data reads as absent)
	 */
	public static Optional<Stored> read(ItemStack stack) {
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);

		if (data == null) {
			return Optional.empty();
		}

		return ROOT_CODEC.parse(NbtOps.INSTANCE, data.copyTag())
				.result()
				.filter(stored -> !stored.drawings().isEmpty());
	}

	/** Writes the drawing, replacing any previous one. Other custom_data keys are left alone. */
	public static void write(ItemStack stack, PageDrawings drawings, int colorIndex) {
		if (drawings == null || drawings.isEmpty()) {
			clear(stack);
			return;
		}

		CompoundTag root = currentTag(stack);

		Stored.CODEC.encodeStart(NbtOps.INSTANCE, new Stored(drawings, colorIndex))
				.result()
				.ifPresent(encoded -> {
					root.put(ROOT_KEY, encoded);
					stack.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
				});
	}

	/** Removes only our key; if nothing else was in custom_data, drops the component too. */
	public static void clear(ItemStack stack) {
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);

		if (data == null) {
			return;
		}

		CompoundTag root = data.copyTag();
		root.remove(ROOT_KEY);

		if (root.isEmpty()) {
			stack.remove(DataComponents.CUSTOM_DATA);
		} else {
			stack.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
		}
	}

	private static CompoundTag currentTag(ItemStack stack) {
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		return data == null ? new CompoundTag() : data.copyTag();
	}
}
