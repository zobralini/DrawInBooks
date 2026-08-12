package com.drawinbooks.client.draw;

import java.util.Arrays;

import com.drawinbooks.component.PageBitmaps;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Draws a page bitmap as flat GUI fills, batched per horizontal run of
 * same-colored ink.
 *
 * <p>Scanning 14 592 pixels every frame to produce the same few hundred quads
 * is wasted work, so runs are computed once and replayed until the drawing
 * actually changes - which is what {@link DrawingSession#revision()} tracks. A
 * page that isn't being drawn on costs one integer comparison per frame.
 *
 * <p>Runs are kept in a flat int array rather than objects: a busy page can
 * have a few thousand of them, and this way redrawing allocates nothing at
 * all.
 */
public final class CanvasRenderer {
	/** Ints per run: x1, x2, y, color. */
	private static final int STRIDE = 4;

	private CanvasRenderer() {
	}

	/**
	 * One canvas's worth of cached geometry. Held by whatever renders a page,
	 * so two visible pages keep separate caches.
	 */
	public static final class RunCache {
		private int[] runs = new int[256 * STRIDE];
		private int count;

		private int cachedPage = Integer.MIN_VALUE;
		private int cachedRevision = Integer.MIN_VALUE;
		private boolean cachedBlank = true;

		public void render(GuiGraphicsExtractor graphics, int originX, int originY,
				byte[] page, int pageIndex, int revision) {
			if (pageIndex != this.cachedPage || revision != this.cachedRevision) {
				rebuild(page);
				this.cachedPage = pageIndex;
				this.cachedRevision = revision;
			}

			if (this.cachedBlank) {
				return;
			}

			for (int i = 0; i < this.count; i++) {
				int base = i * STRIDE;
				int y = this.runs[base + 2];

				graphics.fill(
						originX + BookLayout.cellX(this.runs[base]), originY + BookLayout.cellY(y),
						originX + BookLayout.cellX(this.runs[base + 1]), originY + BookLayout.cellY(y + 1),
						InkColor.byPixelValue(this.runs[base + 3]).argb());
			}
		}

		private void rebuild(byte[] page) {
			this.count = 0;
			this.cachedBlank = page == null || PageBitmaps.isBlank(page);

			// A blank page is the common case for a book being written in, and
			// checking 3 648 bytes is far cheaper than decoding every pixel.
			if (this.cachedBlank) {
				return;
			}

			for (int y = 0; y < PageBitmaps.HEIGHT; y++) {
				int x = 0;

				while (x < PageBitmaps.WIDTH) {
					int value = PageBitmaps.getColor(page, x, y);

					if (value == PageBitmaps.BLANK) {
						x++;
						continue;
					}

					int start = x;

					// Each pixel is decoded once: the run ends at the first
					// pixel whose color differs, which becomes the next run's
					// first pixel without re-reading it.
					do {
						x++;
					} while (x < PageBitmaps.WIDTH && PageBitmaps.getColor(page, x, y) == value);

					add(start, x, y, value);
				}
			}
		}

		private void add(int x1, int x2, int y, int value) {
			int base = this.count * STRIDE;

			if (base + STRIDE > this.runs.length) {
				this.runs = Arrays.copyOf(this.runs, this.runs.length * 2);
			}

			this.runs[base] = x1;
			this.runs[base + 1] = x2;
			this.runs[base + 2] = y;
			this.runs[base + 3] = value;
			this.count++;
		}
	}
}
