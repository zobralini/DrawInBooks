package com.drawinbooks.component;

import java.util.ArrayList;
import java.util.List;

/**
 * The one wire and storage format for a book's drawing: a single flat byte
 * array.
 *
 * <pre>
 *   [0]      format version (currently 1)
 *   [1]      pen color index (0..COLOR_COUNT-1), only a "last used" hint
 *   [2..]    N pages, each exactly PageBitmaps.BYTES_PER_PAGE bytes
 * </pre>
 *
 * <p>A flat array rather than a structured tag on purpose: it is the only
 * shape that every side of this mod can read and write with its own plain
 * API - a Fabric client, a Fabric server, and a Paper plugin using nothing
 * but Bukkit's persistent data container (which supports byte arrays and
 * nothing structured). One format means there is exactly one parser to trust,
 * and it is this one.
 *
 * <p>Validation is total: a blob is valid only if its length is exactly
 * {@code 2 + N * BYTES_PER_PAGE} for some {@code 0 < N <= MAX_PAGES}, and the
 * version byte matches. Anything else is rejected whole - never partially
 * parsed, never clamped.
 */
public final class DrawingBlob {
	public static final byte VERSION = 1;

	/** version byte + color byte */
	public static final int HEADER_BYTES = 2;

	public static final int MAX_BYTES = HEADER_BYTES + PageBitmaps.MAX_TOTAL_BYTES;

	private DrawingBlob() {
	}

	/** What a decoded blob contains. */
	public record Decoded(List<byte[]> pages, int colorIndex) {
	}

	public static byte[] encode(List<byte[]> pages, int colorIndex) {
		if (!PageBitmaps.isValid(pages) || pages.isEmpty()) {
			return null;
		}

		byte[] blob = new byte[HEADER_BYTES + pages.size() * PageBitmaps.BYTES_PER_PAGE];
		blob[0] = VERSION;
		blob[1] = (byte) PageBitmaps.clampColorIndex(colorIndex);

		int offset = HEADER_BYTES;

		for (byte[] page : pages) {
			System.arraycopy(page, 0, blob, offset, PageBitmaps.BYTES_PER_PAGE);
			offset += PageBitmaps.BYTES_PER_PAGE;
		}

		return blob;
	}

	/**
	 * @return the decoded drawing, or null if the blob is malformed in any way
	 */
	public static Decoded decode(byte[] blob) {
		if (!isValid(blob)) {
			return null;
		}

		int pageCount = pageCount(blob);
		List<byte[]> pages = new ArrayList<>(pageCount);

		for (int i = 0; i < pageCount; i++) {
			byte[] page = new byte[PageBitmaps.BYTES_PER_PAGE];
			System.arraycopy(blob, HEADER_BYTES + i * PageBitmaps.BYTES_PER_PAGE,
					page, 0, PageBitmaps.BYTES_PER_PAGE);
			pages.add(page);
		}

		return new Decoded(pages, blob[1]);
	}

	/**
	 * The single point of defense. Used identically by the client, by the
	 * Fabric server receiving a packet, and by the Paper plugin - a blob that
	 * fails here is never stored and never rendered.
	 */
	public static boolean isValid(byte[] blob) {
		if (blob == null || blob.length < HEADER_BYTES + PageBitmaps.BYTES_PER_PAGE) {
			return false;
		}

		if (blob.length > MAX_BYTES || blob[0] != VERSION) {
			return false;
		}

		int body = blob.length - HEADER_BYTES;

		if (body % PageBitmaps.BYTES_PER_PAGE != 0) {
			return false;
		}

		int pages = body / PageBitmaps.BYTES_PER_PAGE;

		if (pages > PageBitmaps.MAX_PAGES) {
			return false;
		}

		int color = blob[1];

		return color >= 0 && color < PageBitmaps.COLOR_COUNT;
	}

	public static int pageCount(byte[] blob) {
		return (blob.length - HEADER_BYTES) / PageBitmaps.BYTES_PER_PAGE;
	}
}
