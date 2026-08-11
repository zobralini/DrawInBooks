package com.drawinbooks.component;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure-Java (no Minecraft imports) definition and validation of the page
 * bitmap format. Kept dependency-free so it can be unit-tested without
 * bootstrapping the game.
 *
 * <p>Format contract (hard, provable constants):
 * <ul>
 *   <li>Fixed resolution per page: {@value #WIDTH}x{@value #HEIGHT} pixels,
 *       matching the vanilla page text area in GUI pixels exactly, so one
 *       bitmap pixel is one GUI pixel and the grid is uniform.</li>
 *   <li>{@value #BITS_PER_PIXEL} bits per pixel: one blank state plus
 *       {@value #COLOR_COUNT} ink colors, so a single page can mix colors.
 *       No alpha, no shading, no palette stored per page.</li>
 *   <li>Fixed size per page: exactly {@value #BYTES_PER_PAGE} bytes
 *       ({@value #WIDTH}*{@value #HEIGHT}*{@value #BITS_PER_PIXEL}/8, which
 *       divides evenly - no padding). Never variable-length, never
 *       compressed.</li>
 *   <li>Max pages: {@value #MAX_PAGES} (matches vanilla's book page limit).</li>
 *   <li>Worst-case size per item: {@value #MAX_PAGES}*{@value #BYTES_PER_PAGE}
 *       = {@value #MAX_TOTAL_BYTES} bytes (~356 KB), a hard constant
 *       regardless of how the data was produced.</li>
 * </ul>
 *
 * <p>Bit layout: row-major, most significant bits first. Pixel (x, y) is at
 * pixel index {@code i = y * WIDTH + x}, stored in byte {@code i / 4} at bit
 * offset {@code (3 - i % 4) * 2}. Value 0 means blank; 1..{@value #COLOR_COUNT}
 * are ink colors, mapped to actual colors on the client side only.
 */
public final class PageBitmaps {
	public static final int WIDTH = 114;
	public static final int HEIGHT = 128;
	public static final int BITS_PER_PIXEL = 2;

	/** Ink colors, i.e. stored values 1..COLOR_COUNT. 0 is always "blank". */
	public static final int COLOR_COUNT = 3;

	public static final int BLANK = 0;

	public static final int PIXELS_PER_BYTE = 8 / BITS_PER_PIXEL; // 4
	public static final int BYTES_PER_PAGE = WIDTH * HEIGHT * BITS_PER_PIXEL / 8; // 3648
	public static final int MAX_PAGES = 100;
	public static final int MAX_TOTAL_BYTES = BYTES_PER_PAGE * MAX_PAGES; // 364800

	private static final int VALUE_MASK = (1 << BITS_PER_PIXEL) - 1; // 0b11

	private PageBitmaps() {
	}

	/**
	 * A page byte array is valid iff it is non-null and exactly
	 * {@link #BYTES_PER_PAGE} bytes.
	 */
	public static boolean isValidPage(byte[] page) {
		return page != null && page.length == BYTES_PER_PAGE;
	}

	/**
	 * Strict whole-component validation: the list must be non-null, contain at
	 * most {@link #MAX_PAGES} entries, and every entry must be exactly
	 * {@link #BYTES_PER_PAGE} bytes. Anything else is invalid as a whole -
	 * callers must treat the entire drawing as absent (no partial parsing,
	 * no clamping/truncation).
	 *
	 * <p>Note that pixel <em>values</em> need no validation: two bits can only
	 * ever hold 0..3, so no byte pattern can encode an out-of-range color.
	 */
	public static boolean isValid(List<byte[]> pages) {
		if (pages == null || pages.size() > MAX_PAGES) {
			return false;
		}

		for (byte[] page : pages) {
			if (!isValidPage(page)) {
				return false;
			}
		}

		return true;
	}

	public static byte[] blankPage() {
		return new byte[BYTES_PER_PAGE];
	}

	/**
	 * Decode-time clamp for the stored "last used pen color" hint. Pixel
	 * colors themselves need no clamping - they are two bits wide and cannot
	 * encode an invalid value.
	 */
	public static int clampColorIndex(int index) {
		return index >= 0 && index < COLOR_COUNT ? index : 0;
	}

	public static boolean isBlank(byte[] page) {
		for (byte b : page) {
			if (b != 0) {
				return false;
			}
		}

		return true;
	}

	/** @return 0 for blank, or 1..{@link #COLOR_COUNT} for an ink color */
	public static int getColor(byte[] page, int x, int y) {
		if (x < 0 || x >= WIDTH || y < 0 || y >= HEIGHT) {
			return BLANK;
		}

		int index = y * WIDTH + x;
		int shift = (PIXELS_PER_BYTE - 1 - index % PIXELS_PER_BYTE) * BITS_PER_PIXEL;

		return (page[index / PIXELS_PER_BYTE] >> shift) & VALUE_MASK;
	}

	/**
	 * Sets one pixel to blank (0) or an ink color (1..{@link #COLOR_COUNT}).
	 *
	 * @return true if the pixel actually changed
	 */
	public static boolean setColor(byte[] page, int x, int y, int value) {
		if (x < 0 || x >= WIDTH || y < 0 || y >= HEIGHT || value < 0 || value > COLOR_COUNT) {
			return false;
		}

		int index = y * WIDTH + x;
		int byteIndex = index / PIXELS_PER_BYTE;
		int shift = (PIXELS_PER_BYTE - 1 - index % PIXELS_PER_BYTE) * BITS_PER_PIXEL;

		byte old = page[byteIndex];
		byte updated = (byte) ((old & ~(VALUE_MASK << shift)) | (value << shift));

		if (old == updated) {
			return false;
		}

		page[byteIndex] = updated;
		return true;
	}

	/** Fills the entire page with one ink color (or blanks it, for value 0). */
	public static void fill(byte[] page, int value) {
		int packed = 0;

		for (int i = 0; i < PIXELS_PER_BYTE; i++) {
			packed = (packed << BITS_PER_PIXEL) | (value & VALUE_MASK);
		}

		java.util.Arrays.fill(page, (byte) packed);
	}

	/**
	 * Returns a copy of the list with trailing blank pages removed, so that a
	 * book whose last drawn page is N stores only N+1 pages. Blank pages
	 * before a drawn page are kept (still full-size blank pages).
	 */
	public static List<byte[]> trimTrailingBlank(List<byte[]> pages) {
		int last = pages.size();

		while (last > 0 && isBlank(pages.get(last - 1))) {
			last--;
		}

		return new ArrayList<>(pages.subList(0, last));
	}
}
