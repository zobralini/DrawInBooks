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
 *       = {@value #MAX_TOTAL_BYTES} bytes (~534 KiB), a hard constant
 *       regardless of how the data was produced.</li>
 * </ul>
 *
 * <p>Bit layout: row-major, most significant bits first, packed continuously
 * across byte boundaries. Pixel (x, y) is at pixel index
 * {@code i = y * WIDTH + x} and occupies bits {@code i*3 .. i*3+2} of the page.
 * Three bits do not tile a byte, so a pixel can straddle two bytes; all access
 * goes through {@link #getColor} and {@link #setColor}, which handle that.
 *
 * <p>Value 0 means blank, 1..{@value #COLOR_COUNT} are ink colors, and 6 and 7
 * are unused. Unused values can only come from hand-written NBT, and
 * {@link #getColor} reports them as blank, so no caller has to consider them.
 */
public final class PageBitmaps {
	public static final int WIDTH = 114;
	public static final int HEIGHT = 128;
	public static final int BITS_PER_PIXEL = 3;

	/** Ink colors, i.e. stored values 1..COLOR_COUNT. 0 is always "blank". */
	public static final int COLOR_COUNT = 5;

	public static final int BLANK = 0;

	public static final int PIXELS_PER_PAGE = WIDTH * HEIGHT; // 14592
	public static final int BYTES_PER_PAGE = PIXELS_PER_PAGE * BITS_PER_PIXEL / 8; // 5472
	public static final int MAX_PAGES = 100;
	public static final int MAX_TOTAL_BYTES = BYTES_PER_PAGE * MAX_PAGES; // 547200

	private static final int VALUE_MASK = (1 << BITS_PER_PIXEL) - 1; // 0b111

	/**
	 * The version 1 layout: 2 bits per pixel, 3 colors. Only needed to read
	 * books drawn before green and yellow existed - see
	 * {@link #upgradeLegacyPage}. Nothing writes it any more.
	 */
	public static final int LEGACY_BITS_PER_PIXEL = 2;
	public static final int LEGACY_COLOR_COUNT = 3;
	public static final int LEGACY_BYTES_PER_PAGE = PIXELS_PER_PAGE * LEGACY_BITS_PER_PIXEL / 8; // 3648
	public static final int LEGACY_MAX_TOTAL_BYTES = LEGACY_BYTES_PER_PAGE * MAX_PAGES;

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
	 * <p>Pixel <em>values</em> are deliberately not validated here: that would
	 * mean decoding 1.4 million pixels to reject data that is already harmless,
	 * because {@link #getColor} reads the two unused values as blank.
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
	 * colors themselves need no clamping - {@link #getColor} already reports
	 * anything unrecognised as blank.
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

	/**
	 * @return 0 for blank, or 1..{@link #COLOR_COUNT} for an ink color. The two
	 *         unused bit patterns read as blank, so every possible byte array
	 *         describes a drawable page
	 */
	public static int getColor(byte[] page, int x, int y) {
		if (x < 0 || x >= WIDTH || y < 0 || y >= HEIGHT) {
			return BLANK;
		}

		int bit = (y * WIDTH + x) * BITS_PER_PIXEL;
		int byteIndex = bit >> 3;
		int used = 8 - (bit & 7); // bits of this pixel living in the first byte
		int value;

		if (used >= BITS_PER_PIXEL) {
			value = (page[byteIndex] & 0xFF) >>> (used - BITS_PER_PIXEL);
		} else {
			int spill = BITS_PER_PIXEL - used;
			value = ((page[byteIndex] & 0xFF) << spill) | ((page[byteIndex + 1] & 0xFF) >>> (8 - spill));
		}

		value &= VALUE_MASK;

		return value <= COLOR_COUNT ? value : BLANK;
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

		int bit = (y * WIDTH + x) * BITS_PER_PIXEL;
		int byteIndex = bit >> 3;
		int used = 8 - (bit & 7);

		if (used >= BITS_PER_PIXEL) {
			int shift = used - BITS_PER_PIXEL;
			byte old = page[byteIndex];
			byte updated = (byte) ((old & ~(VALUE_MASK << shift)) | (value << shift));

			if (old == updated) {
				return false;
			}

			page[byteIndex] = updated;
			return true;
		}

		// Straddles two bytes: the high bits finish the current byte, the rest
		// start the next one.
		int spill = BITS_PER_PIXEL - used;

		byte oldHigh = page[byteIndex];
		byte oldLow = page[byteIndex + 1];

		byte newHigh = (byte) ((oldHigh & ~((1 << used) - 1)) | (value >>> spill));
		byte newLow = (byte) ((oldLow & (0xFF >>> spill)) | ((value & ((1 << spill) - 1)) << (8 - spill)));

		if (oldHigh == newHigh && oldLow == newLow) {
			return false;
		}

		page[byteIndex] = newHigh;
		page[byteIndex + 1] = newLow;
		return true;
	}

	/** Fills the entire page with one ink color (or blanks it, for value 0). */
	public static void fill(byte[] page, int value) {
		if (value == BLANK) {
			java.util.Arrays.fill(page, (byte) 0);
			return;
		}

		// Three bits do not tile a byte, so there is no single byte to repeat.
		// Writing pixel by pixel is a few thousand shifts on one click, which
		// is not worth being clever about.
		for (int y = 0; y < HEIGHT; y++) {
			for (int x = 0; x < WIDTH; x++) {
				setColor(page, x, y, value);
			}
		}
	}

	/**
	 * Rewrites a version 1 page (2 bits per pixel) in the current layout. The
	 * three original inks keep their stored values, so red stays red.
	 *
	 * @return a new page, or null if the input isn't a legacy page
	 */
	public static byte[] upgradeLegacyPage(byte[] legacy) {
		if (legacy == null || legacy.length != LEGACY_BYTES_PER_PAGE) {
			return null;
		}

		byte[] page = blankPage();
		int pixelsPerByte = 8 / LEGACY_BITS_PER_PIXEL; // 4
		int mask = (1 << LEGACY_BITS_PER_PIXEL) - 1;

		for (int y = 0; y < HEIGHT; y++) {
			for (int x = 0; x < WIDTH; x++) {
				int index = y * WIDTH + x;
				int shift = (pixelsPerByte - 1 - index % pixelsPerByte) * LEGACY_BITS_PER_PIXEL;
				int value = (legacy[index / pixelsPerByte] >> shift) & mask;

				if (value != BLANK && value <= LEGACY_COLOR_COUNT) {
					setColor(page, x, y, value);
				}
			}
		}

		return page;
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
