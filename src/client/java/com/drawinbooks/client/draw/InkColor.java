package com.drawinbooks.client.draw;

/**
 * The ink color a drawing renders in.
 *
 * <p>Colors are stored per pixel: each pixel holds three bits, so one page can
 * mix all five inks. The color kept alongside the pages is only the pen color
 * the player last used, so reopening a book resumes where they left off.
 *
 * <p>The shades are picked against the actual page, which is #FFFAEE - very
 * nearly white. Four of the five clear a 5:1 contrast ratio there. Yellow
 * cannot: anything still recognisable as yellow sits around 2:1 on a white
 * page, which is a property of yellow rather than of this mod. It is therefore
 * as saturated as possible instead, since the alternative - darkening it until
 * it contrasts - is what makes a yellow look like mud.
 */
public enum InkColor {
	// Order defines the stored pixel values: RED is 1, BLACK 2, BLUE 3,
	// GREEN 4, YELLOW 5. Never reorder or insert: those numbers are what is
	// written into every drawn book. Must stay in sync with
	// PageBitmaps.COLOR_COUNT.
	RED(0xFFCC2222),
	BLACK(0xFF1F1F23),
	BLUE(0xFF2244BB),
	GREEN(0xFF2A7A2A),
	YELLOW(0xFFE0A800);

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
