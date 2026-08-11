package com.drawinbooks.client.draw;

import com.drawinbooks.component.PageBitmaps;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Draws a page bitmap as flat GUI fills, batched per horizontal run of
 * same-colored ink so a full page costs a few hundred quads rather than one
 * per pixel.
 *
 * <p>Shared by the editing canvas (which owns it as a widget) and the reading
 * screen (which draws it last, on top of the text).
 */
public final class CanvasRenderer {
	private CanvasRenderer() {
	}

	public static void renderInk(GuiGraphicsExtractor graphics, int originX, int originY, byte[] page) {
		if (page == null) {
			return;
		}

		for (int py = 0; py < PageBitmaps.HEIGHT; py++) {
			int y1 = originY + BookLayout.cellY(py);
			int y2 = originY + BookLayout.cellY(py + 1);

			int px = 0;

			while (px < PageBitmaps.WIDTH) {
				int value = PageBitmaps.getColor(page, px, py);

				if (value == PageBitmaps.BLANK) {
					px++;
					continue;
				}

				// Merge a run of same-colored pixels into one quad.
				int runStart = px;

				while (px < PageBitmaps.WIDTH && PageBitmaps.getColor(page, px, py) == value) {
					px++;
				}

				graphics.fill(
						originX + BookLayout.cellX(runStart), y1,
						originX + BookLayout.cellX(px), y2,
						InkColor.byPixelValue(value).argb());
			}
		}
	}
}
