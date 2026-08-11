package com.drawinbooks.client.draw;

import java.util.function.IntSupplier;

import com.drawinbooks.component.PageBitmaps;

import net.minecraft.client.Minecraft;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import org.lwjgl.glfw.GLFW;

/**
 * Invisible widget laid over the vanilla page area. It renders the current
 * page's drawing and paints while a mouse button is held.
 *
 * <p><b>Input is polled from GLFW, not taken from click events.</b> The
 * vanilla book screen handles clicks on the page area itself (text cursor
 * placement) and consumes them, so a child widget never reliably sees them.
 * Polling the button state each frame sidesteps event-dispatch ordering
 * entirely, and it also gives continuous painting while dragging. Consecutive
 * frames are joined with a Bresenham line so fast strokes don't leave gaps.
 *
 * <p>The widget itself never consumes mouse events; suppressing the vanilla
 * text layer while draw mode is on is handled at screen level in
 * {@code BookEditScreenMixin}.
 */
public final class DrawCanvasWidget extends AbstractWidget {
	/** Faint tint of the brush preview under the cursor. */
	private static final int CURSOR_ALPHA = 0x50000000;

	private final DrawingSession session;
	private final IntSupplier currentPage;

	/** True while a stroke that began inside the canvas is still held down. */
	private boolean strokeActive;
	private boolean strokeErasing;
	private int lastPx = -1;
	private int lastPy = -1;

	public DrawCanvasWidget(int x, int y, DrawingSession session, IntSupplier currentPage) {
		super(x, y, BookLayout.CANVAS_WIDTH, BookLayout.CANVAS_HEIGHT, Component.empty());
		this.session = session;
		this.currentPage = currentPage;
	}

	@Override
	protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		int px = BookLayout.pixelX(mouseX - getX());
		int py = BookLayout.pixelY(mouseY - getY());
		boolean overCanvas = px >= 0 && py >= 0;

		handleInput(px, py, overCanvas);
		CanvasRenderer.renderInk(
				graphics, getX(), getY(),
				this.session.peekPage(this.currentPage.getAsInt()));

		// Cursor preview in the pen's color, showing exactly which pixels the
		// brush would hit.
		if (overCanvas && this.session.isDrawMode()) {
			int size = this.session.brushSize(this.session.tool());
			int before = (size - 1) / 2;
			int after = size / 2;

			graphics.fill(
					getX() + BookLayout.cellX(px - before),
					getY() + BookLayout.cellY(py - before),
					getX() + BookLayout.cellX(px + after + 1),
					getY() + BookLayout.cellY(py + after + 1),
					CURSOR_ALPHA | this.session.inkColor().rgb());
		}
	}

	private void handleInput(int px, int py, boolean overCanvas) {
		long window = Minecraft.getInstance().getWindow().handle();
		boolean left = GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
		boolean right = GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_RIGHT) == GLFW.GLFW_PRESS;

		if (!left && !right) {
			this.strokeActive = false;
			this.lastPx = -1;
			this.lastPy = -1;
			return;
		}

		if (!this.session.isDrawMode()) {
			return;
		}

		// A stroke may only begin inside the canvas; dragging in from a
		// toolbar button must not start painting.
		if (!this.strokeActive) {
			if (!overCanvas) {
				return;
			}

			this.strokeActive = true;
			// Right button always erases, regardless of the selected tool.
			this.strokeErasing = right && !left;
			// One undo entry per stroke, not per pixel.
			this.session.beginEdit(this.currentPage.getAsInt());
		}

		if (!overCanvas) {
			// Left the canvas mid-stroke: drop the trail so re-entering
			// doesn't draw a line across the page.
			this.lastPx = -1;
			this.lastPy = -1;
			return;
		}

		boolean erasing = this.strokeErasing || this.session.tool() == Tool.ERASER;
		int value = erasing ? PageBitmaps.BLANK : this.session.inkColor().pixelValue();
		int size = this.session.brushSize(erasing ? Tool.ERASER : Tool.PEN);
		int page = this.currentPage.getAsInt();

		if (this.lastPx >= 0) {
			this.session.strokeLine(page, this.lastPx, this.lastPy, px, py, value, size);
		} else {
			this.session.paint(page, px, py, value, size);
		}

		this.lastPx = px;
		this.lastPy = py;
	}

	/** Never consume mouse events - screen-level blocking handles draw mode. */
	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		return false;
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput output) {
		// Intentionally silent; the canvas has no narration in the MVP.
	}
}
