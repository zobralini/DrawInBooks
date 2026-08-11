package com.drawinbooks.client.draw;

import com.drawinbooks.component.PageBitmaps;

/**
 * Pixel geometry of the vanilla book edit screen.
 *
 * <p>The vanilla page background ({@code assets/minecraft/textures/gui/book.png},
 * 256x256 source texture) is drawn 192 px wide starting at
 * {@code (screenWidth - 192) / 2}, top at y = 2. The writable text area starts
 * at x = bookLeft + 36 and at absolute screen y = 32, and is 114x128 px. If the
 * canvas ever misaligns after a Minecraft update, re-check the constants in
 * {@code BookEditScreen} (run {@code gradlew genSources}) and adjust here -
 * nothing else needs to change.
 *
 * <p><b>One bitmap pixel is exactly one GUI pixel</b> ({@link #CELL} = 1), and
 * the bitmap resolution equals the text area in GUI pixels, so the canvas
 * fills the page edge to edge with a perfectly uniform grid. Any other
 * combination either leaves cells of differing widths (stretching) or wastes
 * page area (centering a smaller bitmap).
 */
public final class BookLayout {
	public static final int BOOK_WIDTH = 192;
	public static final int BOOK_TOP = 2;
	public static final int PAGE_TEXT_X = 36;
	public static final int PAGE_TEXT_Y = 32;
	public static final int PAGE_TEXT_WIDTH = 114;
	public static final int PAGE_TEXT_HEIGHT = 128;

	/** GUI pixels per bitmap pixel. Must stay an integer - see class docs. */
	public static final int CELL = 1;

	public static final int CANVAS_WIDTH = PageBitmaps.WIDTH * CELL;
	public static final int CANVAS_HEIGHT = PageBitmaps.HEIGHT * CELL;

	/**
	 * Fine adjustment of the canvas against the drawn page, applied on top of
	 * the computed position. The page graphic is not perfectly symmetric
	 * inside the book texture, so the canvas sits one pixel left of where the
	 * text-area constants alone would put it.
	 */
	public static final int CANVAS_NUDGE_X = -1;
	public static final int CANVAS_NUDGE_Y = 0;

	/**
	 * Canvas offset from the book's left edge / from the top of the screen.
	 * The canvas covers the text area exactly; if the bitmap is ever made
	 * smaller than the text area again, it is centered instead.
	 */
	public static final int CANVAS_X = PAGE_TEXT_X + (PAGE_TEXT_WIDTH - CANVAS_WIDTH) / 2 + CANVAS_NUDGE_X;
	public static final int CANVAS_Y = PAGE_TEXT_Y + (PAGE_TEXT_HEIGHT - CANVAS_HEIGHT) / 2 + CANVAS_NUDGE_Y;

	private BookLayout() {
	}

	public static int bookLeft(int screenWidth) {
		return (screenWidth - BOOK_WIDTH) / 2;
	}

	/** Left screen-x edge of bitmap column {@code px}, relative to canvas origin. */
	public static int cellX(int px) {
		return px * CELL;
	}

	/** Top screen-y edge of bitmap row {@code py}, relative to canvas origin. */
	public static int cellY(int py) {
		return py * CELL;
	}

	/** Bitmap column for a canvas-relative x, or -1 when outside. */
	public static int pixelX(double relX) {
		if (relX < 0 || relX >= CANVAS_WIDTH) {
			return -1;
		}

		return (int) (relX / CELL);
	}

	/** Bitmap row for a canvas-relative y, or -1 when outside. */
	public static int pixelY(double relY) {
		if (relY < 0 || relY >= CANVAS_HEIGHT) {
			return -1;
		}

		return (int) (relY / CELL);
	}
}
