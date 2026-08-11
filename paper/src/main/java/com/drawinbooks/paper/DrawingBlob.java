package com.drawinbooks.paper;

/**
 * Validation of the drawing blob, duplicated from the mod's
 * {@code com.drawinbooks.component.DrawingBlob}.
 *
 * <p>It is duplicated rather than shared on purpose: this plugin must build
 * against nothing but the Bukkit API, so that it loads on any Paper server
 * without dragging in Minecraft or Fabric classes. The cost is that these
 * constants have to be changed in both places at once - which is why they are
 * few, and why the layout is deliberately trivial.
 *
 * <pre>
 *   [0]      format version
 *   [1]      pen color index
 *   [2..]    N pages, each exactly BYTES_PER_PAGE bytes
 * </pre>
 */
final class DrawingBlob {
	static final byte VERSION = 1;
	static final int HEADER_BYTES = 2;

	static final int WIDTH = 114;
	static final int HEIGHT = 128;
	static final int BITS_PER_PIXEL = 2;
	static final int COLOR_COUNT = 3;

	static final int BYTES_PER_PAGE = WIDTH * HEIGHT * BITS_PER_PIXEL / 8; // 3648
	static final int MAX_PAGES = 100;
	static final int MAX_BYTES = HEADER_BYTES + BYTES_PER_PAGE * MAX_PAGES;

	private DrawingBlob() {
	}

	/**
	 * The single point of defense on this side. A client can send anything;
	 * only blobs that are exactly the fixed format are ever stored.
	 */
	static boolean isValid(byte[] blob) {
		if (blob == null || blob.length < HEADER_BYTES + BYTES_PER_PAGE) {
			return false;
		}

		if (blob.length > MAX_BYTES || blob[0] != VERSION) {
			return false;
		}

		int body = blob.length - HEADER_BYTES;

		if (body % BYTES_PER_PAGE != 0 || body / BYTES_PER_PAGE > MAX_PAGES) {
			return false;
		}

		int color = blob[1];

		return color >= 0 && color < COLOR_COUNT;
	}
}
