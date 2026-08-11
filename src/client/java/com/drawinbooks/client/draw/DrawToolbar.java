package com.drawinbooks.client.draw;

import java.util.List;
import java.util.function.IntSupplier;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import org.lwjgl.glfw.GLFW;

/**
 * The little strip of controls next to the book: a framed draw/text toggle,
 * and under it the frameless tool icons.
 *
 * <p>Lives outside the mixin so that the vanilla book screen and Scribble's
 * replacement screen get the same toolbar from the same code - the two differ
 * only in where the book is drawn, which is passed in.
 */
public final class DrawToolbar {
	/** The framed mode toggle. */
	private static final int TOGGLE = 20;

	/** Frameless tool icons - shorter, since they carry no button frame. */
	private static final int ICON = 16;

	public static final int WIDTH = TOGGLE;

	/**
	 * Labels are glyphs from Minecraft's own font (its unifont fallback covers
	 * these), so no texture asset is needed. If one ever renders as a
	 * missing-glyph box, swap in a plain ASCII letter - nothing depends on
	 * these strings.
	 */
	private static final String GLYPH_DRAW = "\u270E"; // pencil: switch to drawing
	private static final String GLYPH_TEXT = "A"; // switch back to typing
	private static final String GLYPH_PEN = "\u270E"; // pencil: the pen tool
	private static final String GLYPH_ERASER = "\u274C"; // cross mark: eraser
	private static final String GLYPH_COLOR = "\u2588"; // full block, tinted with the ink color

	/** Shown instead of the size while Ctrl / Alt / Shift is held. */
	private static final String GLYPH_BIGGER = "\u207A";
	private static final String GLYPH_SMALLER = "\u207B";
	private static final String GLYPH_WHOLE_PAGE = "\u25A0";

	/** Superscript 0-9, so the brush size sits next to the glyph unobtrusively. */
	private static final String[] SUPERSCRIPTS = {
			"\u2070", "\u00B9", "\u00B2", "\u00B3", "\u2074",
			"\u2075", "\u2076", "\u2077", "\u2078", "\u2079"
	};

	/** Where the page is, so draw mode can swallow clicks meant for the text. */
	@FunctionalInterface
	public interface PageArea {
		boolean contains(double x, double y);
	}

	private final DrawingSession session;
	private final IntSupplier currentPage;

	private Button modeButton;
	private IconButton penButton;
	private IconButton eraserButton;
	private IconButton colorButton;

	public DrawToolbar(DrawingSession session, IntSupplier currentPage) {
		this.session = session;
		this.currentPage = currentPage;
	}

	/** Convenience for screens whose page area is a single rectangle. */
	public void addTo(Screen screen, List<AbstractWidget> widgets, int x, int y) {
		addTo(screen, widgets, x, y, (mouseX, mouseY) -> false);
	}

	public void addTo(Screen screen, List<AbstractWidget> widgets, int x, int y, PageArea pageArea) {
		this.modeButton = Button.builder(
				Component.literal(GLYPH_DRAW),
				button -> {
					this.session.toggleMode();
					update();
				}).bounds(x, y, TOGGLE, TOGGLE).build();

		int iconY = y + TOGGLE + 2;

		this.penButton = new IconButton(x, iconY, TOGGLE, ICON,
				Component.literal(GLYPH_PEN), () -> onTool(Tool.PEN));
		this.eraserButton = new IconButton(x, iconY + ICON, TOGGLE, ICON,
				Component.literal(GLYPH_ERASER), () -> onTool(Tool.ERASER));
		this.colorButton = new IconButton(x, iconY + 2 * ICON, TOGGLE, ICON,
				Component.literal(GLYPH_COLOR), () -> {
					this.session.cycleInkColor();
					update();
				});

		widgets.add(this.modeButton);
		widgets.add(this.penButton);
		widgets.add(this.eraserButton);
		widgets.add(this.colorButton);

		update();

		// Labels change with the held modifier, so they are refreshed every
		// frame rather than only on click.
		ScreenEvents.beforeExtract(screen).register(
				(s, graphics, mouseX, mouseY, tickProgress) -> update(mouseX, mouseY));

		blockTextInput(screen, pageArea);
	}

	/**
	 * While draw mode is on, the page behaves like a canvas and nothing else:
	 * clicks on it no longer move or extend the text selection, and typing no
	 * longer edits the text. Clicks anywhere else - this toolbar, the screen's
	 * own buttons, page arrows - are untouched, and Escape still closes.
	 *
	 * <p>Done with Fabric's per-screen input events rather than by injecting
	 * into a specific screen class, which is what lets the same code guard
	 * both the vanilla screen and Scribble's.
	 */
	private void blockTextInput(Screen screen, PageArea pageArea) {
		ScreenMouseEvents.allowMouseClick(screen).register(
				(s, event) -> !isCanvasInput(pageArea, event));
		ScreenMouseEvents.allowMouseDrag(screen).register(
				(s, event, dx, dy) -> !isCanvasInput(pageArea, event));
		ScreenMouseEvents.allowMouseRelease(screen).register(
				(s, event) -> !isCanvasInput(pageArea, event));

		ScreenKeyboardEvents.allowCharType(screen).register(
				(s, event) -> !this.session.isDrawMode());

		ScreenKeyboardEvents.allowKeyPress(screen).register((s, event) -> {
			if (!this.session.isDrawMode()) {
				return true;
			}

			// Ctrl-Z takes back the last stroke or whole-page action. The
			// screen never sees the key, so it cannot also undo a text edit.
			if (event.key() == GLFW.GLFW_KEY_Z && isKeyDown(GLFW.GLFW_KEY_LEFT_CONTROL, GLFW.GLFW_KEY_RIGHT_CONTROL)) {
				this.session.undo();
				return false;
			}

			return event.key() == GLFW.GLFW_KEY_ESCAPE;
		});
	}

	private boolean isCanvasInput(PageArea pageArea, MouseButtonEvent event) {
		return this.session.isDrawMode() && pageArea.contains(event.x(), event.y());
	}

	/**
	 * Clicking a tool always selects it. Held modifiers then act on it: Ctrl
	 * grows the brush, Alt shrinks it, and Shift applies the tool to the whole
	 * page (flood-fill for the pen, wipe for the eraser).
	 */
	private void onTool(Tool tool) {
		this.session.setTool(tool);

		if (isKeyDown(GLFW.GLFW_KEY_LEFT_CONTROL, GLFW.GLFW_KEY_RIGHT_CONTROL)) {
			this.session.growBrush();
		} else if (isKeyDown(GLFW.GLFW_KEY_LEFT_ALT, GLFW.GLFW_KEY_RIGHT_ALT)) {
			this.session.shrinkBrush();
		} else if (isKeyDown(GLFW.GLFW_KEY_LEFT_SHIFT, GLFW.GLFW_KEY_RIGHT_SHIFT)) {
			int page = this.currentPage.getAsInt();
			this.session.beginEdit(page);

			if (tool == Tool.PEN) {
				this.session.fillPage(page);
			} else {
				this.session.clearPage(page);
			}
		}

		update();
	}

	private void update() {
		update(Integer.MIN_VALUE, Integer.MIN_VALUE);
	}

	private void update(int mouseX, int mouseY) {
		boolean draw = this.session.isDrawMode();

		this.modeButton.setMessage(Component.literal(draw ? GLYPH_TEXT : GLYPH_DRAW));

		this.penButton.visible = draw;
		this.eraserButton.visible = draw;
		this.colorButton.visible = draw;

		this.penButton.setMessage(toolLabel(this.penButton, GLYPH_PEN, Tool.PEN, mouseX, mouseY));
		this.eraserButton.setMessage(toolLabel(this.eraserButton, GLYPH_ERASER, Tool.ERASER, mouseX, mouseY));

		int rgb = this.session.inkColor().rgb();
		this.colorButton.setMessage(Component.literal(GLYPH_COLOR).withStyle(style -> style.withColor(rgb)));
	}

	/**
	 * A tool's label is its glyph plus a superscript brush size. While the
	 * cursor is over the button and a modifier is held, the size is replaced
	 * by what that modifier would do, so the shortcuts are discoverable
	 * without a tooltip.
	 */
	private Component toolLabel(IconButton button, String glyph, Tool tool, int mouseX, int mouseY) {
		String suffix = superscript(this.session.brushSize(tool));

		if (isOver(button, mouseX, mouseY)) {
			if (isKeyDown(GLFW.GLFW_KEY_LEFT_CONTROL, GLFW.GLFW_KEY_RIGHT_CONTROL)) {
				suffix = GLYPH_BIGGER;
			} else if (isKeyDown(GLFW.GLFW_KEY_LEFT_ALT, GLFW.GLFW_KEY_RIGHT_ALT)) {
				suffix = GLYPH_SMALLER;
			} else if (isKeyDown(GLFW.GLFW_KEY_LEFT_SHIFT, GLFW.GLFW_KEY_RIGHT_SHIFT)) {
				suffix = GLYPH_WHOLE_PAGE;
			}
		}

		Component label = Component.literal(glyph + suffix);

		return this.session.tool() == tool
				? label.copy().withStyle(ChatFormatting.YELLOW)
				: label;
	}

	private static boolean isOver(IconButton button, int mouseX, int mouseY) {
		return button.visible
				&& mouseX >= button.getX() && mouseX < button.getX() + button.getWidth()
				&& mouseY >= button.getY() && mouseY < button.getY() + button.getHeight();
	}

	private static String superscript(int value) {
		return value >= 0 && value < SUPERSCRIPTS.length ? SUPERSCRIPTS[value] : String.valueOf(value);
	}

	/**
	 * Polls the key state directly, the same way the canvas polls the mouse -
	 * button callbacks carry no modifier information.
	 */
	private static boolean isKeyDown(int leftKey, int rightKey) {
		long window = Minecraft.getInstance().getWindow().handle();

		return GLFW.glfwGetKey(window, leftKey) == GLFW.GLFW_PRESS
				|| GLFW.glfwGetKey(window, rightKey) == GLFW.GLFW_PRESS;
	}
}
