package com.drawinbooks.component;

import java.util.ArrayList;
import java.util.List;

/**
 * The one wire and storage format for a book's drawing: a single flat byte
 * array.
 *
 * <pre>
 *   [0]      format version (currently 2)
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
 * version byte matches a version this build understands. Anything else is
 * rejected whole - never partially parsed, never clamped.
 *
 * <p><b>Version 1</b> (2 bits per pixel, three inks) is still read, so books
 * drawn before green and yellow existed keep their drawings. It is never
 * written: decoding upgrades those pages in place, and the next save stores
 * version 2.
 */
public final class DrawingBlob {
	public static final byte VERSION = 2;

	/** The 2-bits-per-pixel layout this mod shipped with. Read-only now. */
	public static final byte VERSION_LEGACY = 1;

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
	 * @return the decoded drawing, or null if the blob is malformed in any way.
	 *         Version 1 blobs come back already upgraded to the current pixel
	 *         layout, so callers never see more than one format
	 */
	public static Decoded decode(byte[] blob) {
		if (!isValid(blob)) {
			return null;
		}

		boolean legacy = blob[0] == VERSION_LEGACY;
		int pageBytes = legacy ? PageBitmaps.LEGACY_BYTES_PER_PAGE : PageBitmaps.BYTES_PER_PAGE;
		int pageCount = (blob.length - HEADER_BYTES) / pageBytes;

		List<byte[]> pages = new ArrayList<>(pageCount);

		for (int i = 0; i < pageCount; i++) {
			byte[] page = new byte[pageBytes];
			System.arraycopy(blob, HEADER_BYTES + i * pageBytes, page, 0, pageBytes);
			pages.add(legacy ? PageBitmaps.upgradeLegacyPage(page) : page);
		}

		return new Decoded(pages, blob[1]);
	}

	/**
	 * The single point of defense. Used identically by the client, by the
	 * Fabric server receiving a packet, and by the Paper plugin - a blob that
	 * fails here is never stored and never rendered.
	 */
	public static boolean isValid(byte[] blob) {
		if (blob == null || blob.length <= HEADER_BYTES || blob.length > MAX_BYTES) {
			return false;
		}

		int pageBytes = switch (blob[0]) {
			case VERSION -> PageBitmaps.BYTES_PER_PAGE;
			case VERSION_LEGACY -> PageBitmaps.LEGACY_BYTES_PER_PAGE;
			default -> 0;
		};

		if (pageBytes == 0) {
			return false;
		}

		int body = blob.length - HEADER_BYTES;

		if (body % pageBytes != 0 || body / pageBytes > PageBitmaps.MAX_PAGES) {
			return false;
		}

		int color = blob[1];

		// A version 1 blob can only name one of the three inks it knew about.
		int colorCount = blob[0] == VERSION_LEGACY
				? PageBitmaps.LEGACY_COLOR_COUNT
				: PageBitmaps.COLOR_COUNT;

		return color >= 0 && color < colorCount;
	}

	/** Only meaningful for a blob that {@link #isValid} has accepted. */
	public static int pageCount(byte[] blob) {
		int pageBytes = blob[0] == VERSION_LEGACY
				? PageBitmaps.LEGACY_BYTES_PER_PAGE
				: PageBitmaps.BYTES_PER_PAGE;

		return (blob.length - HEADER_BYTES) / pageBytes;
	}
}
