package com.drawinbooks.client.mixin;

import java.util.List;

import com.drawinbooks.client.draw.BookLayout;
import com.drawinbooks.client.draw.BookScreenScale;
import com.drawinbooks.client.draw.DrawCanvasWidget;
import com.drawinbooks.client.draw.DrawingPersistence;
import com.drawinbooks.client.draw.DrawingSession;
import com.drawinbooks.client.draw.IconButton;
import com.drawinbooks.client.draw.InkColor;
import com.drawinbooks.client.draw.Tool;
import com.drawinbooks.component.BookDrawingStorage;
import com.drawinbooks.component.PageBitmaps;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.BookEditScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds the drawing layer to the vanilla book edit screen: a small toolbar on
 * the left (Draw/Text toggle + Pen/Erase) and an invisible canvas widget over
 * the page area. The vanilla page background is left untouched, and text and
 * drawings stay two fully independent layers.
 */
@Mixin(BookEditScreen.class)
public abstract class BookEditScreenMixin extends Screen {
	/** The framed mode toggle. */
	@Unique
	private static final int TOOL_BUTTON = 20;

	/** Frameless tool icons - shorter, since they carry no button frame. */
	@Unique
	private static final int ICON_BUTTON = 16;

	/**
	 * Toolbar labels are glyphs from Minecraft's own font (the unifont
	 * fallback covers these), so no texture asset is needed. If any of them
	 * ever renders as a missing-glyph box, swap in a plain ASCII letter -
	 * nothing else depends on these strings.
	 */
	@Unique
	private static final String GLYPH_DRAW = "\u270E"; // pencil: switch to drawing

	@Unique
	private static final String GLYPH_TEXT = "A"; // switch back to typing

	@Unique
	private static final String GLYPH_PEN = "\u270E"; // pencil: the pen tool

	@Unique
	private static final String GLYPH_ERASER = "\u274C"; // cross mark: eraser

	@Unique
	private static final String GLYPH_COLOR = "\u2588"; // full block, tinted with the ink color

	/** Shown instead of the size while Ctrl / Alt / Shift is held. */
	@Unique
	private static final String GLYPH_BIGGER = "\u207A"; // superscript plus

	@Unique
	private static final String GLYPH_SMALLER = "\u207B"; // superscript minus

	@Unique
	private static final String GLYPH_WHOLE_PAGE = "\u25A0"; // filled square

	/** Superscript 0-9, so the brush size sits next to the glyph unobtrusively. */
	@Unique
	private static final String[] SUPERSCRIPTS = {
			"\u2070", "\u00B9", "\u00B2", "\u00B3", "\u2074",
			"\u2075", "\u2076", "\u2077", "\u2078", "\u2079"
	};

	@Shadow
	private int currentPage;

	@Shadow
	@Final
	private List<String> pages;

	@Unique
	private DrawingSession drawinbooks$session;

	@Unique
	private InteractionHand drawinbooks$hand = InteractionHand.MAIN_HAND;

	@Unique
	private Button drawinbooks$modeButton;

	@Unique
	private IconButton drawinbooks$penButton;

	@Unique
	private IconButton drawinbooks$eraserButton;

	@Unique
	private IconButton drawinbooks$colorButton;

	protected BookEditScreenMixin(Component title) {
		super(title);
	}

	@Inject(method = "init", at = @At("TAIL"))
	private void drawinbooks$addDrawingUi(CallbackInfo ci) {
		// Books get one extra GUI-scale step; put it back when the screen goes
		// away. Registered before enlarging, because enlarging re-runs init.
		ScreenEvents.remove((Screen) (Object) this).register(screen -> BookScreenScale.restore());
		BookScreenScale.enlarge(this);

		// init() re-runs on window resize; keep the session (and any unsaved
		// strokes) alive across re-inits, only load from the item once.
		if (this.drawinbooks$session == null) {
			this.drawinbooks$hand = drawinbooks$findBookHand();
			ItemStack book = this.minecraft.player == null
					? ItemStack.EMPTY
					: this.minecraft.player.getItemInHand(this.drawinbooks$hand);

			BookDrawingStorage.Stored stored = BookDrawingStorage.read(book).orElse(null);

			this.drawinbooks$session = stored == null
					? DrawingSession.fromComponent(null, InkColor.RED)
					: DrawingSession.fromComponent(
							stored.drawings(), InkColor.byIndex(stored.colorIndex()));
		}

		DrawingSession session = this.drawinbooks$session;
		int bookLeft = BookLayout.bookLeft(this.width);

		// Canvas over the vanilla page area.
		addRenderableWidget(new DrawCanvasWidget(
				bookLeft + BookLayout.CANVAS_X,
				BookLayout.CANVAS_Y,
				session,
				() -> this.currentPage));

		// Side toolbar, hugging the left edge of the book. Square buttons
		// stacked with no gap so they read as one strip, labelled with font
		// glyphs instead of words. The only new visible UI element.
		int toolbarX = bookLeft - TOOL_BUTTON - 2;
		int y = BookLayout.PAGE_TEXT_Y;

		this.drawinbooks$modeButton = addRenderableWidget(Button.builder(
				Component.literal(GLYPH_DRAW),
				button -> {
					session.toggleMode();
					drawinbooks$updateToolbar();
				}).bounds(toolbarX, y, TOOL_BUTTON, TOOL_BUTTON).build());

		// The tools themselves are frameless icons hanging under the toggle,
		// so the toolbar reads as one strip of symbols instead of a stack of
		// vanilla button frames.
		int iconY = y + TOOL_BUTTON + 2;

		this.drawinbooks$penButton = addRenderableWidget(new IconButton(
				toolbarX, iconY, TOOL_BUTTON, ICON_BUTTON,
				Component.literal(GLYPH_PEN),
				() -> drawinbooks$onToolButton(session, Tool.PEN)));

		this.drawinbooks$eraserButton = addRenderableWidget(new IconButton(
				toolbarX, iconY + ICON_BUTTON, TOOL_BUTTON, ICON_BUTTON,
				Component.literal(GLYPH_ERASER),
				() -> drawinbooks$onToolButton(session, Tool.ERASER)));

		this.drawinbooks$colorButton = addRenderableWidget(new IconButton(
				toolbarX, iconY + 2 * ICON_BUTTON, TOOL_BUTTON, ICON_BUTTON,
				Component.literal(GLYPH_COLOR),
				() -> {
					session.cycleInkColor();
					drawinbooks$updateToolbar();
				}));

		drawinbooks$updateToolbar();
		drawinbooks$blockVanillaTextInput(session);

		// Tool labels change with the held modifier, so they are refreshed
		// every frame rather than only on click.
		ScreenEvents.beforeExtract((Screen) (Object) this).register(
				(screen, graphics, mouseX, mouseY, tickProgress) ->
						drawinbooks$updateToolbar(mouseX, mouseY));
	}

	/**
	 * While draw mode is on, the page behaves like a canvas and nothing else:
	 * clicks inside the page area no longer move or extend the vanilla text
	 * selection, and typing no longer edits the text. Clicks anywhere else
	 * (this toolbar, Done/Sign, page arrows) are untouched, and Escape still
	 * closes the screen.
	 *
	 * <p>Uses Fabric's per-screen input events rather than injecting into
	 * BookEditScreen's own mouse/key methods - the screen is free to change
	 * its internals between versions, these events are not.
	 */
	@Unique
	private void drawinbooks$blockVanillaTextInput(DrawingSession session) {
		Screen self = (Screen) (Object) this;

		ScreenMouseEvents.allowMouseClick(self).register(
				(screen, event) -> !drawinbooks$isCanvasInput(session, event));
		ScreenMouseEvents.allowMouseDrag(self).register(
				(screen, event, dx, dy) -> !drawinbooks$isCanvasInput(session, event));
		ScreenMouseEvents.allowMouseRelease(self).register(
				(screen, event) -> !drawinbooks$isCanvasInput(session, event));

		ScreenKeyboardEvents.allowCharType(self).register(
				(screen, event) -> !session.isDrawMode());
		ScreenKeyboardEvents.allowKeyPress(self).register((screen, event) -> {
			if (!session.isDrawMode()) {
				return true;
			}

			// Ctrl-Z takes back the last stroke or whole-page action. Vanilla
			// never sees the key, so it cannot also edit the text.
			if (event.key() == GLFW.GLFW_KEY_Z
					&& drawinbooks$isKeyDown(GLFW.GLFW_KEY_LEFT_CONTROL, GLFW.GLFW_KEY_RIGHT_CONTROL)) {
				drawinbooks$undo(session);
				return false;
			}

			return event.key() == GLFW.GLFW_KEY_ESCAPE;
		});
	}

	/** True when a mouse event belongs to the drawing canvas, not to vanilla. */
	@Unique
	private boolean drawinbooks$isCanvasInput(DrawingSession session, MouseButtonEvent event) {
		if (!session.isDrawMode()) {
			return false;
		}

		// The text layer only reacts to clicks on the page itself, so only
		// that rectangle needs to be swallowed.
		int left = BookLayout.bookLeft(this.width) + BookLayout.PAGE_TEXT_X;
		int top = BookLayout.PAGE_TEXT_Y;

		return event.x() >= left && event.x() < left + BookLayout.PAGE_TEXT_WIDTH
				&& event.y() >= top && event.y() < top + BookLayout.PAGE_TEXT_HEIGHT;
	}

	/**
	 * Vanilla routes both "Done" and "Sign" through saveChanges. Commit the
	 * drawing layer at the same moment, and make sure pages that only have a
	 * drawing still exist as text pages - signing drops empty trailing pages,
	 * which would take the drawing with them.
	 */
	@Inject(method = "saveChanges", at = @At("HEAD"))
	private void drawinbooks$saveDrawings(CallbackInfo ci) {
		DrawingSession session = this.drawinbooks$session;

		if (session == null || !session.isDirty()) {
			return;
		}

		drawinbooks$reservePagesForDrawings(session);

		DrawingPersistence.persist(
				this.minecraft, this.drawinbooks$hand, session.toComponent(), session.inkColor());
	}

	@Unique
	private void drawinbooks$reservePagesForDrawings(DrawingSession session) {
		int drawn = Math.min(session.drawnPageCount(), PageBitmaps.MAX_PAGES);

		while (this.pages.size() < drawn) {
			this.pages.add(" ");
		}

		for (int i = 0; i < drawn; i++) {
			if (session.hasDrawing(i) && this.pages.get(i).isEmpty()) {
				this.pages.set(i, " ");
			}
		}
	}

	/**
	 * Clicking a tool always selects it. Held modifiers then act on it:
	 * Ctrl grows the brush, Alt shrinks it, and Shift applies the tool to the
	 * entire page (flood-fill for the pen, wipe for the eraser).
	 */
	@Unique
	private void drawinbooks$onToolButton(DrawingSession session, Tool tool) {
		session.setTool(tool);

		if (drawinbooks$isKeyDown(GLFW.GLFW_KEY_LEFT_CONTROL, GLFW.GLFW_KEY_RIGHT_CONTROL)) {
			session.growBrush();
		} else if (drawinbooks$isKeyDown(GLFW.GLFW_KEY_LEFT_ALT, GLFW.GLFW_KEY_RIGHT_ALT)) {
			session.shrinkBrush();
		} else if (drawinbooks$isKeyDown(GLFW.GLFW_KEY_LEFT_SHIFT, GLFW.GLFW_KEY_RIGHT_SHIFT)) {
			session.beginEdit(this.currentPage);

			if (tool == Tool.PEN) {
				session.fillPage(this.currentPage);
			} else {
				session.clearPage(this.currentPage);
			}
		}

		drawinbooks$updateToolbar();
	}

	/**
	 * Undoes the last edit and, if it happened on another page, flips there so
	 * the player can see what changed.
	 */
	@Unique
	private void drawinbooks$undo(DrawingSession session) {
		int page = session.undo();

		if (page >= 0 && page != this.currentPage && page < this.pages.size()) {
			this.currentPage = page;
		}
	}

	/**
	 * Polls the key state directly, the same way the canvas polls the mouse -
	 * button callbacks carry no modifier information.
	 */
	@Unique
	private static boolean drawinbooks$isKeyDown(int leftKey, int rightKey) {
		long window = Minecraft.getInstance().getWindow().handle();

		return GLFW.glfwGetKey(window, leftKey) == GLFW.GLFW_PRESS
				|| GLFW.glfwGetKey(window, rightKey) == GLFW.GLFW_PRESS;
	}

	@Unique
	private static Component drawinbooks$glyph(String glyph, boolean selected) {
		Component label = Component.literal(glyph);
		return selected ? label.copy().withStyle(ChatFormatting.YELLOW) : label;
	}

	@Unique
	private InteractionHand drawinbooks$findBookHand() {
		if (this.minecraft != null && this.minecraft.player != null
				&& !this.minecraft.player.getMainHandItem().is(Items.WRITABLE_BOOK)
				&& this.minecraft.player.getOffhandItem().is(Items.WRITABLE_BOOK)) {
			return InteractionHand.OFF_HAND;
		}

		return InteractionHand.MAIN_HAND;
	}

	/** Refresh after a click, when the cursor position doesn't matter. */
	@Unique
	private void drawinbooks$updateToolbar() {
		drawinbooks$updateToolbar(Integer.MIN_VALUE, Integer.MIN_VALUE);
	}

	@Unique
	private void drawinbooks$updateToolbar(int mouseX, int mouseY) {
		DrawingSession session = this.drawinbooks$session;
		boolean draw = session.isDrawMode();

		this.drawinbooks$modeButton.setMessage(Component.literal(draw ? GLYPH_TEXT : GLYPH_DRAW));

		// Tool buttons only exist while drawing. They stay clickable even when
		// already selected - Shift-clicking a tool has to keep working - so the
		// selected tool is marked by coloring its glyph rather than by greying
		// the button out.
		this.drawinbooks$penButton.visible = draw;
		this.drawinbooks$eraserButton.visible = draw;
		this.drawinbooks$colorButton.visible = draw;

		this.drawinbooks$penButton.setMessage(drawinbooks$toolLabel(
				session, this.drawinbooks$penButton, GLYPH_PEN, Tool.PEN, mouseX, mouseY));
		this.drawinbooks$eraserButton.setMessage(drawinbooks$toolLabel(
				session, this.drawinbooks$eraserButton, GLYPH_ERASER, Tool.ERASER, mouseX, mouseY));

		int rgb = session.inkColor().rgb();
		this.drawinbooks$colorButton.setMessage(
				Component.literal(GLYPH_COLOR).withStyle(style -> style.withColor(rgb)));
	}

	/**
	 * A tool's label is its glyph plus a superscript brush size. While the
	 * cursor is over the button and a modifier is held, the size is replaced
	 * by what that modifier would do, so the shortcuts are discoverable
	 * without a tooltip: {@code +} bigger, {@code -} smaller, a filled square
	 * for "apply to the whole page".
	 */
	@Unique
	private static Component drawinbooks$toolLabel(
			DrawingSession session, IconButton button, String glyph, Tool tool, int mouseX, int mouseY) {
		String suffix = superscript(session.brushSize(tool));

		if (drawinbooks$isOver(button, mouseX, mouseY)) {
			if (drawinbooks$isKeyDown(GLFW.GLFW_KEY_LEFT_CONTROL, GLFW.GLFW_KEY_RIGHT_CONTROL)) {
				suffix = GLYPH_BIGGER;
			} else if (drawinbooks$isKeyDown(GLFW.GLFW_KEY_LEFT_ALT, GLFW.GLFW_KEY_RIGHT_ALT)) {
				suffix = GLYPH_SMALLER;
			} else if (drawinbooks$isKeyDown(GLFW.GLFW_KEY_LEFT_SHIFT, GLFW.GLFW_KEY_RIGHT_SHIFT)) {
				suffix = GLYPH_WHOLE_PAGE;
			}
		}

		return drawinbooks$glyph(glyph + suffix, session.tool() == tool);
	}

	/**
	 * Hover test done against the button's own bounds rather than its internal
	 * hover flag, which isn't reachable from here.
	 */
	@Unique
	private static boolean drawinbooks$isOver(IconButton button, int mouseX, int mouseY) {
		return button.visible
				&& mouseX >= button.getX() && mouseX < button.getX() + button.getWidth()
				&& mouseY >= button.getY() && mouseY < button.getY() + button.getHeight();
	}

	@Unique
	private static String superscript(int value) {
		return value >= 0 && value < SUPERSCRIPTS.length
				? SUPERSCRIPTS[value]
				: String.valueOf(value);
	}
}
