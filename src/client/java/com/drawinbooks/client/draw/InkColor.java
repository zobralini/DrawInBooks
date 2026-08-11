package com.drawinbooks.client.draw;

/**
 * The ink color a drawing renders in.
 *
 * <p>Colors are stored per pixel: each pixel holds two bits, so one page can
 * mix all three inks. The color kept alongside the pages is only the pen
 * color the player last used, so reopening a book resumes where they left
 * off.
 */
public enum InkColor {
	// Order defines the stored pixel values: RED is 1, BLACK 2, BLUE 3.
	// Must stay in sync with PageBitmaps.COLOR_COUNT.
	RED(0xFFAA0000),
	BLACK(0xFF1F1F23),
	BLUE(0xFF2244BB);

	private final int argb;

	InkColor(int argb) {
		this.argb = argb;
	}

	/** Opaque ARGB, for GUI fills. */
	public int argb() {
		return this.argb;
	}

	/** RGB only, for text styling. */
	public int rgb() {
		return this.argb & 0xFFFFFF;
	}

	/** The value this color is stored as in the bitmap (0 is blank). */
	public int pixelValue() {
		return ordinal() + 1;
	}

	/** @param value a stored pixel value; 0 (blank) has no color */
	public static InkColor byPixelValue(int value) {
		return value >= 1 && value <= values().length ? values()[value - 1] : RED;
	}

	public InkColor next() {
		return values()[(ordinal() + 1) % values().length];
	}

	/** Lenient lookup: anything out of range falls back to the default. */
	public static InkColor byIndex(int index) {
		return index >= 0 && index < values().length ? values()[index] : RED;
	}

	/** Clamps a stored index into a valid one, for decode-time validation. */
	public static int clampIndex(int index) {
		return byIndex(index).ordinal();
	}
}
