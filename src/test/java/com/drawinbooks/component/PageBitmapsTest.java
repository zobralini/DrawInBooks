package com.drawinbooks.component;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
		assertEquals(2, PageBitmaps.BITS_PER_PIXEL);
		assertEquals(3, PageBitmaps.COLOR_COUNT);
		assertEquals(3648, PageBitmaps.BYTES_PER_PAGE);
		assertEquals(100, PageBitmaps.MAX_PAGES);
		assertEquals(364800, PageBitmaps.MAX_TOTAL_BYTES);

		// The bitmap must divide evenly into bytes - no padding, no ambiguity.
		assertEquals(0, PageBitmaps.WIDTH * PageBitmaps.HEIGHT * PageBitmaps.BITS_PER_PIXEL % 8);

		// Two bits must be able to hold blank plus every ink color.
		assertTrue(PageBitmaps.COLOR_COUNT < (1 << PageBitmaps.BITS_PER_PIXEL));
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

		assertTrue(PageBitmaps.setColor(page, 0, 0, 1));
		assertTrue(PageBitmaps.setColor(page, 1, 0, 2));
		assertTrue(PageBitmaps.setColor(page, 2, 0, 3));
		assertTrue(PageBitmaps.setColor(page, maxX, maxY, 3));

		// Neighboring pixels share a byte - they must not bleed into each other.
		assertEquals(1, PageBitmaps.getColor(page, 0, 0));
		assertEquals(2, PageBitmaps.getColor(page, 1, 0));
		assertEquals(3, PageBitmaps.getColor(page, 2, 0));
		assertEquals(PageBitmaps.BLANK, PageBitmaps.getColor(page, 3, 0));
		assertEquals(3, PageBitmaps.getColor(page, maxX, maxY));
		assertFalse(PageBitmaps.isBlank(page));

		// Setting the same value again reports "no change".
		assertFalse(PageBitmaps.setColor(page, 1, 0, 2));

		// Recoloring and erasing.
		assertTrue(PageBitmaps.setColor(page, 1, 0, 3));
		assertEquals(3, PageBitmaps.getColor(page, 1, 0));
		assertTrue(PageBitmaps.setColor(page, 1, 0, PageBitmaps.BLANK));
		assertEquals(PageBitmaps.BLANK, PageBitmaps.getColor(page, 1, 0));
	}

	@Test
	void everyPixelIsAddressableAndIndependent() {
		byte[] page = PageBitmaps.blankPage();

		for (int y = 0; y < PageBitmaps.HEIGHT; y++) {
			for (int x = 0; x < PageBitmaps.WIDTH; x++) {
				assertTrue(PageBitmaps.setColor(page, x, y, PageBitmaps.COLOR_COUNT), "set " + x + "," + y);
			}
		}

		// Filling every pixel with the highest color must set every bit -
		// proof that the layout covers the array exactly, nothing left over.
		for (byte b : page) {
			assertEquals((byte) 0xFF, b);
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
