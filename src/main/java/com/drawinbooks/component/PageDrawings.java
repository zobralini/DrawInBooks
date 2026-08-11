package com.drawinbooks.component;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.mojang.serialization.Codec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Immutable value stored in the {@code drawinbooks:page_drawings} data
 * component, attached directly to the book {@code ItemStack}.
 *
 * <p>Validation on decode is the single point of defense: data may come from
 * disk, network, {@code /give}, hand-edited component data or another mod.
 * If anything does not match the exact format (each page exactly
 * {@link PageBitmaps#BYTES_PER_PAGE} bytes, at most
 * {@link PageBitmaps#MAX_PAGES} pages), the ENTIRE component decodes to
 * {@link #EMPTY} - it is
 * treated as absent and renders blank. We deliberately do not return a decode
 * error: a hard {@code DataResult.error} inside an item component codec can
 * make the whole ItemStack fail to parse, which would destroy the book on
 * chunk load. Lenient-to-empty gives the required behavior (no crash, no
 * partial parse, no clamping) without risking item loss.
 *
 * <p>No compression is used; every page is a fixed-size array, so there is no
 * decompression-bomb surface and the worst case size per item is a hard
 * constant ({@link PageBitmaps#MAX_TOTAL_BYTES} bytes for a maxed-out book).
 */
public final class PageDrawings {
	private static final Logger LOGGER = LoggerFactory.getLogger("drawinbooks");

	public static final PageDrawings EMPTY = new PageDrawings(List.of());

	/**
	 * Decode-time clamp for the "last used color" hint stored next to the
	 * pages. Pixel colors themselves need no clamping - they are two bits
	 * wide and cannot encode an invalid value.
	 */
	public static int clampColorIndex(int index) {
		return index >= 0 && index < PageBitmaps.COLOR_COUNT ? index : 0;
	}

	/**
	 * Serialized form: a plain list of byte arrays (one per page, in page
	 * order). In NBT this is a list of byte-array tags; blank trailing pages
	 * are trimmed before saving. Decoding runs strict validation and falls
	 * back to {@link #EMPTY} (with a warning) on any mismatch.
	 */
	public static final Codec<PageDrawings> CODEC = Codec.BYTE_BUFFER.listOf()
			.xmap(PageDrawings::fromBuffersLenient, PageDrawings::toBuffers);

	/** Defensively-copied, each entry exactly {@link PageBitmaps#BYTES_PER_PAGE} bytes. */
	private final List<byte[]> pages;

	private PageDrawings(List<byte[]> validatedPages) {
		this.pages = validatedPages;
	}

	/**
	 * Creates an instance from raw page data, or {@code null} if the data is
	 * invalid. Input arrays are defensively copied.
	 */
	public static PageDrawings tryCreate(List<byte[]> rawPages) {
		if (!PageBitmaps.isValid(rawPages)) {
			return null;
		}

		List<byte[]> copy = new ArrayList<>(rawPages.size());

		for (byte[] page : rawPages) {
			copy.add(page.clone());
		}

		return new PageDrawings(List.copyOf(copy));
	}

	private static PageDrawings fromBuffersLenient(List<ByteBuffer> buffers) {
		List<byte[]> raw = new ArrayList<>(Math.min(buffers.size(), PageBitmaps.MAX_PAGES + 1));

		for (ByteBuffer buffer : buffers) {
			ByteBuffer dup = buffer.duplicate();
			byte[] bytes = new byte[dup.remaining()];
			dup.get(bytes);
			raw.add(bytes);

			if (raw.size() > PageBitmaps.MAX_PAGES) {
				break; // already invalid, no need to copy more
			}
		}

		PageDrawings result = tryCreate(raw);

		if (result == null) {
			LOGGER.warn(
					"Rejected malformed drawinbooks:page_drawings component ({} pages); treating as absent",
					buffers.size());
			return EMPTY;
		}

		return result;
	}

	private List<ByteBuffer> toBuffers() {
		List<ByteBuffer> buffers = new ArrayList<>(this.pages.size());

		for (byte[] page : this.pages) {
			buffers.add(ByteBuffer.wrap(page.clone()));
		}

		return buffers;
	}

	public int pageCount() {
		return this.pages.size();
	}

	public boolean isEmpty() {
		return this.pages.isEmpty();
	}

	/**
	 * Returns a copy of the bitmap for the given page, or a blank bitmap if
	 * the page index is out of range (pages past the last drawn page are
	 * simply blank).
	 */
	public byte[] copyPage(int index) {
		if (index < 0 || index >= this.pages.size()) {
			return PageBitmaps.blankPage();
		}

		return this.pages.get(index).clone();
	}

	/**
	 * Value equality (content-based), required for vanilla stack merging:
	 * written books only stack when their component data is equal, and lists
	 * of arrays compare by identity by default.
	 */
	@Override
	public boolean equals(Object obj) {
		if (this == obj) {
			return true;
		}

		if (!(obj instanceof PageDrawings other) || other.pages.size() != this.pages.size()) {
			return false;
		}

		for (int i = 0; i < this.pages.size(); i++) {
			if (!Arrays.equals(this.pages.get(i), other.pages.get(i))) {
				return false;
			}
		}

		return true;
	}

	@Override
	public int hashCode() {
		int hash = 1;

		for (byte[] page : this.pages) {
			hash = 31 * hash + Arrays.hashCode(page);
		}

		return hash;
	}

	@Override
	public String toString() {
		return "PageDrawings[" + this.pages.size() + " pages]";
	}
}
