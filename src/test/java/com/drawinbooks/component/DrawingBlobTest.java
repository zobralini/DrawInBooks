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
		assertEquals(364_802, blob.length);
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
