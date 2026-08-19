package com.drawinbooks.client.compat;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.util.List;

import com.drawinbooks.DrawInBooks;
import com.drawinbooks.client.draw.BookLayout;
import com.drawinbooks.client.draw.BookScreenScale;
import com.drawinbooks.client.draw.DrawCanvasWidget;
import com.drawinbooks.client.draw.DrawToolbar;
import com.drawinbooks.client.draw.DrawingPersistence;
import com.drawinbooks.client.draw.DrawingSession;
import com.drawinbooks.client.draw.InkColor;
import com.drawinbooks.client.draw.ServerSupport;
import com.drawinbooks.component.BookDrawingStorage;
import com.drawinbooks.component.DrawingBlob;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Support for the <a href="https://modrinth.com/mod/scribble">Scribble</a> mod.
 *
 * <p>Scribble doesn't extend the vanilla book screen, it <em>replaces</em> it
 * with its own class. So with Scribble installed, the mixins in this mod never
 * run and the drawing layer simply isn't there - no toolbar, no drawings, on
 * any book. This class puts it back.
 *
 * <p>It attaches through Fabric's generic screen events instead of a mixin,
 * and reads Scribble's layout through reflection, so this mod does not need to
 * compile or depend on Scribble at all. If Scribble is absent, nothing here
 * ever runs; if Scribble changes its internals, drawing quietly stops working
 * on its screens instead of crashing.
 *
 * <p>What is borrowed from Scribble is deliberately the smallest possible
 * surface - two public fields and two public methods - and its page area
 * happens to be exactly the vanilla 114x128, which is why the canvas needs no
 * separate geometry:
 *
 * <pre>
 *   ScribbleBookScreen.currentPage      public int
 *   ScribbleBookScreen.pagesToShow      public int   (it can show two pages)
 *   ScribbleBookScreen.getBackgroundX() public int
 *   ScribbleBookScreen.getBackgroundY() public int
 * </pre>
 */
public final class ScribbleCompat {
	private static final String BASE_CLASS = "me.chrr.scribble.screen.ScribbleBookScreen";
	private static final String EDIT_CLASS = "me.chrr.scribble.screen.ScribbleBookEditScreen";

	/** Scribble lays its page text out at background + (36 + 126 * page, 30). */
	private static final int PAGE_OFFSET_X = 36;
	private static final int PAGE_OFFSET_Y = 30;
	private static final int PAGE_STRIDE_X = 126;

	private static Class<?> baseClass;
	private static Class<?> editClass;
	private static Field currentPageField;
	private static Field pagesToShowField;
	private static MethodHandle backgroundX;
	private static MethodHandle backgroundY;

	private ScribbleCompat() {
	}

	/** True for Scribble's own book screens, so the GUI scale bump sticks. */
	public static boolean isBookScreen(Object screen) {
		return baseClass != null && baseClass.isInstance(screen);
	}

	public static void initialize() {
		if (!resolve()) {
			return;
		}

		DrawInBooks.LOGGER.info("Scribble detected - attaching the drawing layer to its book screens");

		ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
			if (baseClass.isInstance(screen)) {
				attach(screen, editClass.isInstance(screen));
			}
		});
	}

	/** @return true if every piece of Scribble we rely on was found */
	private static boolean resolve() {
		try {
			baseClass = Class.forName(BASE_CLASS);
			editClass = Class.forName(EDIT_CLASS);

			currentPageField = baseClass.getField("currentPage");
			pagesToShowField = baseClass.getField("pagesToShow");

			MethodHandles.Lookup lookup = MethodHandles.publicLookup();
			backgroundX = lookup.unreflect(baseClass.getMethod("getBackgroundX"));
			backgroundY = lookup.unreflect(baseClass.getMethod("getBackgroundY"));

			return true;
		} catch (ClassNotFoundException notInstalled) {
			return false;
		} catch (ReflectiveOperationException changed) {
			DrawInBooks.LOGGER.warn(
					"Scribble is installed but its book screen no longer looks the way this mod expects, "
							+ "so drawings will not show on it: {}", changed.toString());
			return false;
		}
	}

	private static void attach(Screen screen, boolean editable) {
		Minecraft minecraft = Minecraft.getInstance();

		// Scribble screens get the same scale bump as the vanilla ones. It has
		// to happen before anything is positioned: enlarging re-runs init, and
		// this method runs again with the new layout.
		ScreenEvents.remove(screen).register(closed -> BookScreenScale.restore());
		BookScreenScale.enlarge(screen);

		InteractionHand hand = findBookHand(minecraft, editable);
		ItemStack book = minecraft.player == null ? ItemStack.EMPTY : minecraft.player.getItemInHand(hand);

		if (!BookDrawingStorage.isBook(book)) {
			return;
		}

		DrawingBlob.Decoded stored = BookDrawingStorage.read(book).orElse(null);

		// Reading a book only needs a canvas, and only if there is one.
		if (!editable && stored == null) {
			return;
		}

		DrawingSession session = stored == null
				? DrawingSession.fromPages(null, null)
				: DrawingSession.fromPages(stored.pages(), InkColor.byIndex(stored.colorIndex()));

		int originX = invoke(backgroundX, screen);
		int originY = invoke(backgroundY, screen);
		int pagesShown = Math.max(1, readInt(pagesToShowField, screen, 1));

		List<AbstractWidget> widgets = Screens.getWidgets(screen);

		// Read-only where a drawing could not be saved anyway: the canvas still
		// shows what is on the book, it just takes no input.
		boolean drawable = editable && ServerSupport.editingAllowed();

		// One canvas per visible page - Scribble can show two at once.
		for (int i = 0; i < pagesShown; i++) {
			int pageOffset = i;

			widgets.add(new DrawCanvasWidget(
					originX + PAGE_OFFSET_X + i * PAGE_STRIDE_X + BookLayout.CANVAS_NUDGE_X,
					originY + PAGE_OFFSET_Y + BookLayout.CANVAS_NUDGE_Y,
					session,
					() -> readInt(currentPageField, screen, 0) + pageOffset,
					drawable));
		}

		if (!editable) {
			return;
		}

		int pagesGuarded = pagesShown;
		int pageTop = originY + PAGE_OFFSET_Y;

		int bookWidth = pagesShown * PAGE_STRIDE_X + 66;

		DrawToolbar toolbar = new DrawToolbar(session, () -> readInt(currentPageField, screen, 0));
		toolbar.addTo(
				screen,
				widgets,
				DrawToolbar.toolbarX(originX, bookWidth),
				pageTop,
				(mouseX, mouseY) -> {
					if (mouseY < pageTop || mouseY >= pageTop + BookLayout.PAGE_TEXT_HEIGHT) {
						return false;
					}

					// Any of the visible pages counts - Scribble can show two.
					for (int i = 0; i < pagesGuarded; i++) {
						int left = originX + PAGE_OFFSET_X + i * PAGE_STRIDE_X;

						if (mouseX >= left && mouseX < left + BookLayout.PAGE_TEXT_WIDTH) {
							return true;
						}
					}

					return false;
				});

		ScreenEvents.remove(screen).register(closed -> {
			if (session.isDirty()) {
				DrawingPersistence.persist(minecraft, hand, session.toPages(), session.inkColor());
			}
		});
	}

	private static InteractionHand findBookHand(Minecraft minecraft, boolean editable) {
		LocalPlayer player = minecraft.player;

		if (player == null) {
			return InteractionHand.MAIN_HAND;
		}

		var wanted = editable ? Items.WRITABLE_BOOK : Items.WRITTEN_BOOK;

		if (!player.getMainHandItem().is(wanted) && player.getOffhandItem().is(wanted)) {
			return InteractionHand.OFF_HAND;
		}

		return InteractionHand.MAIN_HAND;
	}

	private static int readInt(Field field, Object target, int fallback) {
		try {
			return field.getInt(target);
		} catch (IllegalAccessException | IllegalArgumentException e) {
			return fallback;
		}
	}

	private static int invoke(MethodHandle handle, Screen screen) {
		try {
			return (int) handle.invoke(screen);
		} catch (Throwable t) {
			return 0;
		}
	}
}
