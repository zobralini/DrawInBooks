package com.drawinbooks.client.draw;

import java.util.List;
import java.util.function.IntSupplier;

import com.drawinbooks.DrawInBooks;
import com.drawinbooks.client.config.DrawConfig;
import com.drawinbooks.client.config.DrawConfigScreen;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

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

	/**
	 * Background for the mode toggle: a 20x40 sheet holding the normal frame
	 * and, under it, the hovered one. Only this button is textured - the tools
	 * below it stay frameless, so the strip reads as one button with a row of
	 * icons hanging off it rather than as seven buttons.
	 */
	private static final Identifier MODE_TEXTURE =
			Identifier.fromNamespaceAndPath(DrawInBooks.MOD_ID, "textures/gui/draw_button.png");

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
	private static final String GLYPH_COPY = "\u24D2"; // circled c: copy this page
	private static final String GLYPH_PASTE = "\u24DF"; // circled p: paste onto this page
	private static final String GLYPH_SETTINGS = "\u25CE"; // bullseye: settings

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

	private IconButton modeButton;
	private IconButton penButton;
	private IconButton eraserButton;
	private IconButton colorButton;
	private IconButton copyButton;
	private IconButton pasteButton;
	private IconButton settingsButton;

	/**
	 * Everything the labels are derived from, packed into one int. The labels
	 * are only rebuilt when this changes, instead of allocating a handful of
	 * Components every frame for text that almost never differs.
	 */
	private int labelState = -1;

	public DrawToolbar(DrawingSession session, IntSupplier currentPage) {
		this.session = session;
		this.currentPage = currentPage;
	}

	/**
	 * The toolbar's left edge, given where the book is. Which side it sits on
	 * is configurable, because Scribble puts its own controls to the left of
	 * the book and the two would otherwise overlap.
	 *
	 * @param bookLeft  screen x of the book's left edge
	 * @param bookWidth width of the book graphic
	 */
	public static int toolbarX(int bookLeft, int bookWidth) {
		return DrawConfig.get().toolbarOnRight
				? bookLeft + bookWidth + 2
				: bookLeft - WIDTH - 2;
	}

	public void addTo(Screen screen, List<AbstractWidget> widgets, int x, int y, PageArea pageArea) {
		// Settings stay reachable even with the toolbar hidden - otherwise
		// turning the tools off would be a one-way door.
		registerSettingsHotkey(screen);

		if (!DrawConfig.get().showEditingTools) {
			return;
		}

		this.modeButton = new IconButton(x, y, TOGGLE, TOGGLE,
				Component.literal(GLYPH_DRAW),
				() -> {
					this.session.toggleMode();
					update();
				},
				MODE_TEXTURE);

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
		this.copyButton = new IconButton(x, iconY + 3 * ICON, TOGGLE, ICON,
				Component.literal(GLYPH_COPY), this::onCopy);
		this.pasteButton = new IconButton(x, iconY + 4 * ICON, TOGGLE, ICON,
				Component.literal(GLYPH_PASTE), this::onPaste);
		this.settingsButton = new IconButton(x, iconY + 5 * ICON, TOGGLE, ICON,
				Component.literal(GLYPH_SETTINGS), () -> openSettings(screen));

		widgets.add(this.modeButton);
		widgets.add(this.penButton);
		widgets.add(this.eraserButton);
		widgets.add(this.colorButton);
		widgets.add(this.copyButton);
		widgets.add(this.pasteButton);
		widgets.add(this.settingsButton);

		update();

		// Labels change with the held modifier, so they are checked every
		// frame - but only rebuilt when something actually differs.
		ScreenEvents.beforeExtract(screen).register(
				(s, graphics, mouseX, mouseY, tickProgress) -> update(mouseX, mouseY));

		blockTextInput(screen, pageArea);
	}

	private static void openSettings(Screen parent) {
		Minecraft.getInstance().gui.setScreen(new DrawConfigScreen(parent));
	}

	/** Ctrl-G opens settings from any book screen, toolbar or not. */
	private static void registerSettingsHotkey(Screen screen) {
		ScreenKeyboardEvents.allowKeyPress(screen).register((s, event) -> {
			if (event.key() == GLFW.GLFW_KEY_G
					&& isKeyDown(GLFW.GLFW_KEY_LEFT_CONTROL, GLFW.GLFW_KEY_RIGHT_CONTROL)) {
				openSettings(screen);
				return false;
			}

			return true;
		});
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

			boolean control = isKeyDown(GLFW.GLFW_KEY_LEFT_CONTROL, GLFW.GLFW_KEY_RIGHT_CONTROL);

			// Ctrl-Z takes back the last stroke or whole-page action, Ctrl-C
			// and Ctrl-V move a whole page around. The screen never sees these
			// keys, so they cannot also act on the text - and in text mode this
			// method has already returned, so vanilla's own Ctrl-C and Ctrl-V
			// keep working there.
			if (control) {
				switch (event.key()) {
					case GLFW.GLFW_KEY_Z -> {
						this.session.undo();
						update();
						return false;
					}
					case GLFW.GLFW_KEY_C -> {
						onCopy();
						return false;
					}
					case GLFW.GLFW_KEY_V -> {
						onPaste();
						return false;
					}
					default -> {
						// falls through to the normal handling below
					}
				}
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
	/** Copies the page currently being looked at, drawing and all. */
	private void onCopy() {
		this.session.copyPage(this.currentPage.getAsInt());
		update();
	}

	/**
	 * Stamps the copied page onto the one being looked at. Shift replaces the
	 * page outright instead, matching what Shift does on the tools.
	 */
	private void onPaste() {
		this.session.pastePage(
				this.currentPage.getAsInt(),
				isKeyDown(GLFW.GLFW_KEY_LEFT_SHIFT, GLFW.GLFW_KEY_RIGHT_SHIFT));
		update();
	}

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
		this.labelState = -1;
		update(Integer.MIN_VALUE, Integer.MIN_VALUE);
	}

	private void update(int mouseX, int mouseY) {
		if (this.modeButton == null) {
			return;
		}

		int state = labelState(mouseX, mouseY);

		if (state == this.labelState) {
			return;
		}

		this.labelState = state;

		boolean draw = this.session.isDrawMode();

		this.modeButton.setMessage(Component.literal(draw ? GLYPH_TEXT : GLYPH_DRAW));

		this.penButton.visible = draw;
		this.eraserButton.visible = draw;
		this.colorButton.visible = draw;
		this.copyButton.visible = draw;
		this.pasteButton.visible = draw;
		this.settingsButton.visible = draw;

		this.penButton.setMessage(toolLabel(this.penButton, GLYPH_PEN, Tool.PEN, mouseX, mouseY));
		this.eraserButton.setMessage(toolLabel(this.eraserButton, GLYPH_ERASER, Tool.ERASER, mouseX, mouseY));

		int rgb = this.session.inkColor().rgb();
		this.colorButton.setMessage(Component.literal(GLYPH_COLOR).withStyle(style -> style.withColor(rgb)));

		// Paste dims itself when there is nothing to paste, which is the only
		// hint needed that the clipboard is empty.
		this.pasteButton.setMessage(DrawingSession.hasClipboard()
				? Component.literal(GLYPH_PASTE)
				: Component.literal(GLYPH_PASTE).withStyle(ChatFormatting.DARK_GRAY));
	}

	/** Everything the labels depend on, in one comparable value. */
	private int labelState(int mouseX, int mouseY) {
		int hovered = 0;

		if (isOver(this.penButton, mouseX, mouseY)) {
			hovered = 1;
		} else if (isOver(this.eraserButton, mouseX, mouseY)) {
			hovered = 2;
		}

		int modifier = 0;

		if (hovered != 0) {
			if (isKeyDown(GLFW.GLFW_KEY_LEFT_CONTROL, GLFW.GLFW_KEY_RIGHT_CONTROL)) {
				modifier = 1;
			} else if (isKeyDown(GLFW.GLFW_KEY_LEFT_ALT, GLFW.GLFW_KEY_RIGHT_ALT)) {
				modifier = 2;
			} else if (isKeyDown(GLFW.GLFW_KEY_LEFT_SHIFT, GLFW.GLFW_KEY_RIGHT_SHIFT)) {
				modifier = 3;
			}
		}

		// Field widths are sized for the largest value each can hold; the ink
		// field in particular needs three bits now that there are five colors.
		return (this.session.isDrawMode() ? 1 : 0)
				| (this.session.tool().ordinal() << 1)
				| (this.session.inkColor().ordinal() << 2)
				| (this.session.brushSize(Tool.PEN) << 5)
				| (this.session.brushSize(Tool.ERASER) << 8)
				| (hovered << 11)
				| (modifier << 13)
				| ((DrawingSession.hasClipboard() ? 1 : 0) << 15);
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
		return button != null && button.visible
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
