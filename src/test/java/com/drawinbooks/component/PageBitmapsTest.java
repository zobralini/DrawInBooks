package com.drawinbooks.component;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Validation tests for the fixed page-bitmap format. These are pure-Java
 * tests (no Minecraft bootstrap): the strict format rules live in
 * {@link PageBitmaps} and the storage codec delegates to them, so the
 * accept/reject behavior of the single point of defense is what is being
 * exercised here.
 */
class PageBitmapsTest {

	private static final int GOOD = PageBitmaps.BYTES_PER_PAGE;

	private static List<byte[]> pagesOf(int count, int size) {
		List<byte[]> pages = new ArrayList<>();

		for (int i = 0; i < count; i++) {
			pages.add(new byte[size]);
		}

		return pages;
	}

	@Test
	void formatConstantsAreTheHardContract() {
		assertEquals(114, PageBitmaps.WIDTH);
		assertEquals(128, PageBitmaps.HEIGHT);
		assertEquals(3, PageBitmaps.BITS_PER_PIXEL);
		assertEquals(5, PageBitmaps.COLOR_COUNT);
		assertEquals(5472, PageBitmaps.BYTES_PER_PAGE);
		assertEquals(100, PageBitmaps.MAX_PAGES);
		assertEquals(547200, PageBitmaps.MAX_TOTAL_BYTES);

		// The bitmap must divide evenly into bytes - no padding, no ambiguity.
		assertEquals(0, PageBitmaps.WIDTH * PageBitmaps.HEIGHT * PageBitmaps.BITS_PER_PIXEL % 8);

		// The bits per pixel must hold blank plus every ink color.
		assertTrue(PageBitmaps.COLOR_COUNT < (1 << PageBitmaps.BITS_PER_PIXEL));

		// The layout this build can still read, but no longer writes.
		assertEquals(2, PageBitmaps.LEGACY_BITS_PER_PIXEL);
		assertEquals(3, PageBitmaps.LEGACY_COLOR_COUNT);
		assertEquals(3648, PageBitmaps.LEGACY_BYTES_PER_PAGE);
	}

	@Test
	void validDataIsAccepted() {
		assertTrue(PageBitmaps.isValid(List.of()));
		assertTrue(PageBitmaps.isValid(pagesOf(1, GOOD)));
		assertTrue(PageBitmaps.isValid(pagesOf(PageBitmaps.MAX_PAGES, GOOD)));
	}

	@Test
	void wrongPageSizeIsRejectedAsAWhole() {
		assertFalse(PageBitmaps.isValid(pagesOf(1, GOOD - 1)));
		assertFalse(PageBitmaps.isValid(pagesOf(1, GOOD + 1)));
		assertFalse(PageBitmaps.isValid(pagesOf(1, 0)));
		assertFalse(PageBitmaps.isValid(pagesOf(1, 1_000_000)));

		// One bad page poisons the whole drawing - no partial parsing.
		List<byte[]> mixed = pagesOf(5, GOOD);
		mixed.set(3, new byte[100]);
		assertFalse(PageBitmaps.isValid(mixed));
	}

	@Test
	void tooManyPagesAreRejected() {
		assertFalse(PageBitmaps.isValid(pagesOf(PageBitmaps.MAX_PAGES + 1, GOOD)));
		assertFalse(PageBitmaps.isValid(pagesOf(1000, GOOD)));
	}

	@Test
	void nullsAreRejected() {
		assertFalse(PageBitmaps.isValid(null));

		List<byte[]> withNull = pagesOf(2, GOOD);
		withNull.add(null);
		assertFalse(PageBitmaps.isValid(withNull));
	}

	@Test
	void colorsRoundTripPerPixel() {
		byte[] page = PageBitmaps.blankPage();
		assertTrue(PageBitmaps.isBlank(page));

		int maxX = PageBitmaps.WIDTH - 1;
		int maxY = PageBitmaps.HEIGHT - 1;

		// Pixel 2 straddles the first and second byte (bits 6..8), which is the
		// case three bits per pixel makes possible and two never did.
		assertTrue(PageBitmaps.setColor(page, 0, 0, 1));
		assertTrue(PageBitmaps.setColor(page, 1, 0, 2));
		assertTrue(PageBitmaps.setColor(page, 2, 0, 5));
		assertTrue(PageBitmaps.setColor(page, 3, 0, 4));
		assertTrue(PageBitmaps.setColor(page, maxX, maxY, 3));

		// Neighboring pixels share a byte - they must not bleed into each other.
		assertEquals(1, PageBitmaps.getColor(page, 0, 0));
		assertEquals(2, PageBitmaps.getColor(page, 1, 0));
		assertEquals(5, PageBitmaps.getColor(page, 2, 0));
		assertEquals(4, PageBitmaps.getColor(page, 3, 0));
		assertEquals(PageBitmaps.BLANK, PageBitmaps.getColor(page, 4, 0));
		assertEquals(3, PageBitmaps.getColor(page, maxX, maxY));
		assertFalse(PageBitmaps.isBlank(page));

		// Setting the same value again reports "no change".
		assertFalse(PageBitmaps.setColor(page, 1, 0, 2));
		assertFalse(PageBitmaps.setColor(page, 2, 0, 5));

		// Recoloring and erasing, on a straddling pixel too.
		assertTrue(PageBitmaps.setColor(page, 1, 0, 3));
		assertEquals(3, PageBitmaps.getColor(page, 1, 0));
		assertTrue(PageBitmaps.setColor(page, 1, 0, PageBitmaps.BLANK));
		assertEquals(PageBitmaps.BLANK, PageBitmaps.getColor(page, 1, 0));

		assertTrue(PageBitmaps.setColor(page, 2, 0, PageBitmaps.BLANK));
		assertEquals(PageBitmaps.BLANK, PageBitmaps.getColor(page, 2, 0));
		assertEquals(4, PageBitmaps.getColor(page, 3, 0), "erasing must not disturb its neighbor");
	}

	@Test
	void everyPixelIsIndependentOfItsNeighbors() {
		byte[] page = PageBitmaps.blankPage();

		// A different color on every consecutive pixel, so any bleed between
		// the bit fields shows up as a mismatch rather than by luck.
		for (int y = 0; y < PageBitmaps.HEIGHT; y++) {
			for (int x = 0; x < PageBitmaps.WIDTH; x++) {
				PageBitmaps.setColor(page, x, y, 1 + (y * PageBitmaps.WIDTH + x) % PageBitmaps.COLOR_COUNT);
			}
		}

		for (int y = 0; y < PageBitmaps.HEIGHT; y++) {
			for (int x = 0; x < PageBitmaps.WIDTH; x++) {
				assertEquals(1 + (y * PageBitmaps.WIDTH + x) % PageBitmaps.COLOR_COUNT,
						PageBitmaps.getColor(page, x, y), x + "," + y);
			}
		}
	}

	@Test
	void unusedBitPatternsReadAsBlank() {
		byte[] page = PageBitmaps.blankPage();

		// Three bits can hold 6 and 7, which name no color. Only hand-written
		// NBT can produce them, and they must read as blank rather than as
		// some arbitrary ink.
		java.util.Arrays.fill(page, (byte) 0xFF); // every pixel is 7

		assertEquals(PageBitmaps.BLANK, PageBitmaps.getColor(page, 0, 0));
		assertEquals(PageBitmaps.BLANK, PageBitmaps.getColor(page, 1, 0));
		assertEquals(PageBitmaps.BLANK, PageBitmaps.getColor(page, 2, 0));
		assertEquals(PageBitmaps.BLANK,
				PageBitmaps.getColor(page, PageBitmaps.WIDTH - 1, PageBitmaps.HEIGHT - 1));
	}

	@Test
	void legacyPagesUpgradeWithTheirColorsIntact() {
		byte[] legacy = new byte[PageBitmaps.LEGACY_BYTES_PER_PAGE];

		// Version 1 layout: 4 pixels per byte, 2 bits each, MSB first.
		legacy[0] = (byte) 0b01_10_11_00; // pixels 0..3 = red, black, blue, blank

		byte[] upgraded = PageBitmaps.upgradeLegacyPage(legacy);

		assertEquals(PageBitmaps.BYTES_PER_PAGE, upgraded.length);
		assertEquals(1, PageBitmaps.getColor(upgraded, 0, 0));
		assertEquals(2, PageBitmaps.getColor(upgraded, 1, 0));
		assertEquals(3, PageBitmaps.getColor(upgraded, 2, 0));
		assertEquals(PageBitmaps.BLANK, PageBitmaps.getColor(upgraded, 3, 0));

		// Anything that isn't a legacy page is refused rather than guessed at.
		assertNull(PageBitmaps.upgradeLegacyPage(null));
		assertNull(PageBitmaps.upgradeLegacyPage(PageBitmaps.blankPage()));
		assertNull(PageBitmaps.upgradeLegacyPage(new byte[7]));
	}

	@Test
	void everyPixelIsAddressableAndTheLayoutCoversTheArrayExactly() {
		byte[] page = PageBitmaps.blankPage();

		for (int y = 0; y < PageBitmaps.HEIGHT; y++) {
			for (int x = 0; x < PageBitmaps.WIDTH; x++) {
				assertTrue(PageBitmaps.setColor(page, x, y, PageBitmaps.COLOR_COUNT), "set " + x + "," + y);
			}
		}

		// Every pixel set to 5 (0b101) makes the whole array the bit pattern
		// 101101101... which lands on a 3-byte cycle. If a single pixel were
		// mislocated, or the array were one byte too long or short, this would
		// not hold anywhere but at the start.
		byte[] cycle = { (byte) 0xB6, (byte) 0xDB, (byte) 0x6D };
		assertEquals(0, PageBitmaps.BYTES_PER_PAGE % cycle.length);

		for (int i = 0; i < page.length; i++) {
			assertEquals(cycle[i % cycle.length], page[i], "byte " + i);
		}

		// And every pixel reads back as that color, not just the bytes.
		assertEquals(PageBitmaps.COLOR_COUNT, PageBitmaps.getColor(page, 0, 0));
		assertEquals(PageBitmaps.COLOR_COUNT,
				PageBitmaps.getColor(page, PageBitmaps.WIDTH - 1, PageBitmaps.HEIGHT - 1));
	}

	@Test
	void fillMatchesPixelByPixelPainting() {
		byte[] filled = PageBitmaps.blankPage();
		PageBitmaps.fill(filled, 2);

		byte[] painted = PageBitmaps.blankPage();

		for (int y = 0; y < PageBitmaps.HEIGHT; y++) {
			for (int x = 0; x < PageBitmaps.WIDTH; x++) {
				PageBitmaps.setColor(painted, x, y, 2);
			}
		}

		org.junit.jupiter.api.Assertions.assertArrayEquals(painted, filled);

		PageBitmaps.fill(filled, PageBitmaps.BLANK);
		assertTrue(PageBitmaps.isBlank(filled));
	}

	@Test
	void outOfRangeInputsAreIgnored() {
		byte[] page = PageBitmaps.blankPage();

		assertFalse(PageBitmaps.setColor(page, -1, 0, 1));
		assertFalse(PageBitmaps.setColor(page, PageBitmaps.WIDTH, 0, 1));
		assertFalse(PageBitmaps.setColor(page, 0, -1, 1));
		assertFalse(PageBitmaps.setColor(page, 0, PageBitmaps.HEIGHT, 1));

		// An impossible color value must never be written.
		assertFalse(PageBitmaps.setColor(page, 0, 0, -1));
		assertFalse(PageBitmaps.setColor(page, 0, 0, PageBitmaps.COLOR_COUNT + 1));

		assertTrue(PageBitmaps.isBlank(page));
		assertEquals(PageBitmaps.BLANK,
				PageBitmaps.getColor(page, PageBitmaps.WIDTH, PageBitmaps.HEIGHT));
	}

	@Test
	void trailingBlankPagesAreTrimmed() {
		List<byte[]> pages = pagesOf(5, GOOD);
		PageBitmaps.setColor(pages.get(2), 5, 5, 1);

		List<byte[]> trimmed = PageBitmaps.trimTrailingBlank(pages);
		assertEquals(3, trimmed.size());

		// Blank pages before a drawn page are preserved.
		assertTrue(PageBitmaps.isBlank(trimmed.get(0)));
		assertTrue(PageBitmaps.isBlank(trimmed.get(1)));
		assertFalse(PageBitmaps.isBlank(trimmed.get(2)));

		assertEquals(0, PageBitmaps.trimTrailingBlank(pagesOf(3, GOOD)).size());
	}
}
