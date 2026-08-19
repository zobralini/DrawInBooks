package com.drawinbooks.component;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The blob format is what crosses trust boundaries - it arrives from disk,
 * from another player's client over the network, and from a Paper server -
 * so its validation is tested as adversarially as its encoding.
 */
class DrawingBlobTest {

	private static List<byte[]> pages(int count) {
		List<byte[]> pages = new ArrayList<>();

		for (int i = 0; i < count; i++) {
			byte[] page = PageBitmaps.blankPage();
			PageBitmaps.setColor(page, i % PageBitmaps.WIDTH, 0, 1 + i % PageBitmaps.COLOR_COUNT);
			pages.add(page);
		}

		return pages;
	}

	@Test
	void roundTripPreservesEveryPageAndTheColor() {
		List<byte[]> original = pages(4);
		byte[] blob = DrawingBlob.encode(original, 2);

		assertNotNull(blob);
		assertEquals(DrawingBlob.HEADER_BYTES + 4 * PageBitmaps.BYTES_PER_PAGE, blob.length);
		assertTrue(DrawingBlob.isValid(blob));

		DrawingBlob.Decoded decoded = DrawingBlob.decode(blob);

		assertNotNull(decoded);
		assertEquals(2, decoded.colorIndex());
		assertEquals(4, decoded.pages().size());

		for (int i = 0; i < original.size(); i++) {
			assertArrayEquals(original.get(i), decoded.pages().get(i), "page " + i);
		}
	}

	@Test
	void maximumBookIsAcceptedAndBounded() {
		byte[] blob = DrawingBlob.encode(pages(PageBitmaps.MAX_PAGES), 0);

		assertTrue(DrawingBlob.isValid(blob));
		assertEquals(DrawingBlob.MAX_BYTES, blob.length);
		assertEquals(547_202, blob.length);
	}

	@Test
	void version1BlobsAreStillReadAndComeBackUpgraded() {
		// A book drawn before green and yellow existed: two pages in the old
		// 2-bit layout, with red on the first pixel of each.
		byte[] legacy = new byte[DrawingBlob.HEADER_BYTES + 2 * PageBitmaps.LEGACY_BYTES_PER_PAGE];
		legacy[0] = DrawingBlob.VERSION_LEGACY;
		legacy[1] = 2; // blue was the last pen used
		legacy[DrawingBlob.HEADER_BYTES] = 0b01_00_00_00;
		legacy[DrawingBlob.HEADER_BYTES + PageBitmaps.LEGACY_BYTES_PER_PAGE] = 0b01_00_00_00;

		assertTrue(DrawingBlob.isValid(legacy));
		assertEquals(2, DrawingBlob.pageCount(legacy));

		DrawingBlob.Decoded decoded = DrawingBlob.decode(legacy);

		assertNotNull(decoded);
		assertEquals(2, decoded.colorIndex());
		assertEquals(2, decoded.pages().size());

		// Upgraded in place: current-size pages, colors unchanged.
		for (byte[] page : decoded.pages()) {
			assertEquals(PageBitmaps.BYTES_PER_PAGE, page.length);
			assertEquals(1, PageBitmaps.getColor(page, 0, 0));
		}

		// Re-encoding stores the current version, so a book upgrades itself
		// the first time it is saved.
		byte[] reencoded = DrawingBlob.encode(decoded.pages(), decoded.colorIndex());
		assertEquals(DrawingBlob.VERSION, reencoded[0]);

		// A legacy blob may only name one of the three inks it knew about.
		byte[] impossibleColor = legacy.clone();
		impossibleColor[1] = 4; // yellow, which version 1 could not store
		assertFalse(DrawingBlob.isValid(impossibleColor));

		// The two layouts must not be confusable: legacy page bytes in a
		// version 2 blob is the wrong length and is rejected.
		byte[] mismatched = legacy.clone();
		mismatched[0] = DrawingBlob.VERSION;
		assertFalse(DrawingBlob.isValid(mismatched));
	}

	@Test
	void nothingBiggerThanTheMaximumIsAccepted() {
		assertNull(DrawingBlob.encode(pages(PageBitmaps.MAX_PAGES + 1), 0));
		assertFalse(DrawingBlob.isValid(new byte[DrawingBlob.MAX_BYTES + 1]));
		assertFalse(DrawingBlob.isValid(new byte[10_000_000]));
	}

	@Test
	void malformedBlobsAreRejectedWhole() {
		byte[] good = DrawingBlob.encode(pages(2), 1);

		// Truncated, extended, and off-by-one lengths.
		assertFalse(DrawingBlob.isValid(java.util.Arrays.copyOf(good, good.length - 1)));
		assertFalse(DrawingBlob.isValid(java.util.Arrays.copyOf(good, good.length + 1)));

		// Empty and header-only.
		assertFalse(DrawingBlob.isValid(new byte[0]));
		assertFalse(DrawingBlob.isValid(new byte[DrawingBlob.HEADER_BYTES]));
		assertFalse(DrawingBlob.isValid(null));

		// Wrong format version.
		byte[] wrongVersion = good.clone();
		wrongVersion[0] = 99;
		assertFalse(DrawingBlob.isValid(wrongVersion));

		// Impossible color index.
		byte[] wrongColor = good.clone();
		wrongColor[1] = (byte) PageBitmaps.COLOR_COUNT;
		assertFalse(DrawingBlob.isValid(wrongColor));

		byte[] negativeColor = good.clone();
		negativeColor[1] = -1;
		assertFalse(DrawingBlob.isValid(negativeColor));

		// A rejected blob decodes to nothing rather than to something partial.
		assertNull(DrawingBlob.decode(wrongVersion));
	}

	@Test
	void emptyDrawingsAreNotEncoded() {
		assertNull(DrawingBlob.encode(List.of(), 0));
		assertNull(DrawingBlob.encode(null, 0));
	}

	@Test
	void colorIndexIsClampedOnEncode() {
		byte[] blob = DrawingBlob.encode(pages(1), 99);

		assertTrue(DrawingBlob.isValid(blob));
		assertEquals(0, DrawingBlob.decode(blob).colorIndex());
	}
}
