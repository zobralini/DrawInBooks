package com.drawinbooks.client.draw;

/**
 * The drawing tools and their brush size limits. There is no separate
 * "pointer" idle tool: the draw/text mode toggle already covers that role, and
 * in text mode the canvas takes no input at all.
 *
 * <p>Sizes are square side lengths in bitmap pixels, adjustable in-place with
 * Ctrl-click (bigger) and Alt-click (smaller) on the tool's button.
 */
public enum Tool {
	PEN(1, 5, 1),
	ERASER(1, 7, 3);

	private final int minSize;
	private final int maxSize;
	private final int defaultSize;

	Tool(int minSize, int maxSize, int defaultSize) {
		this.minSize = minSize;
		this.maxSize = maxSize;
		this.defaultSize = defaultSize;
	}

	public int minSize() {
		return this.minSize;
	}

	public int maxSize() {
		return this.maxSize;
	}

	public int defaultSize() {
		return this.defaultSize;
	}
}
