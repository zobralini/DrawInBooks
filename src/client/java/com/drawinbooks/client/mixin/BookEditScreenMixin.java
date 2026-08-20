package com.drawinbooks.client.mixin;

import java.util.List;

import com.drawinbooks.client.draw.BookLayout;
import com.drawinbooks.client.draw.BookScreenScale;
import com.drawinbooks.client.draw.BookSessions;
import com.drawinbooks.client.draw.DrawCanvasWidget;
import com.drawinbooks.client.draw.DrawToolbar;
import com.drawinbooks.client.draw.DrawingPersistence;
import com.drawinbooks.client.draw.DrawingSession;
import com.drawinbooks.client.draw.InkColor;
import com.drawinbooks.client.draw.ServerSupport;
import com.drawinbooks.component.BookDrawingStorage;
import com.drawinbooks.component.DrawingBlob;
import com.drawinbooks.component.PageBitmaps;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.BookEditScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds the drawing layer to the vanilla book edit screen.
 *
 * <p>Only the parts that need the screen's own internals live here - the page
 * list, the current page, and the moment vanilla saves. The toolbar and the
 * canvas are built by shared code, so that Scribble's replacement screen gets
 * exactly the same drawing layer through
 * {@link com.drawinbooks.client.compat.ScribbleCompat} without duplicating any
 * of it.
 */
@Mixin(BookEditScreen.class)
public abstract class BookEditScreenMixin extends Screen {
	@Shadow
	private int currentPage;

	@Shadow
	@Final
	private List<String> pages;

	/**
	 * Vanilla only ever calls this from the Done and Sign buttons - in 26.2
	 * {@code BookEditScreen} overrides neither {@code onClose} nor
	 * {@code removed}, so closing the book any other way discards the text
	 * entirely. That is fine for text and not fine for us: see
	 * {@link #drawinbooks$commitOnClose()}.
	 */
	@Shadow
	private void saveChanges() {
		throw new AssertionError("shadow");
	}

	@Unique
	private DrawingSession drawinbooks$session;

	@Unique
	private InteractionHand drawinbooks$hand = InteractionHand.MAIN_HAND;

	protected BookEditScreenMixin(Component title) {
		super(title);
	}

	@Inject(method = "init", at = @At("TAIL"))
	private void drawinbooks$addDrawingUi(CallbackInfo ci) {
		Screen self = (Screen) (Object) this;

		// Books get one extra GUI-scale step; put it back when the screen goes
		// away. Registered before enlarging, because enlarging re-runs init.
		//
		// Closing is also the last safe moment to commit the drawing. Vanilla
		// calls saveChanges when you press Sign - before you have typed the
		// title and before the book is actually converted - so a save that
		// only happened there would be long finished by the time the signed
		// book appears. Saving again here covers the whole signing flow.
		ScreenEvents.remove(self).register(screen -> {
			BookScreenScale.restore();
			drawinbooks$commitOnClose();
		});

		BookScreenScale.enlarge(this);

		// init() re-runs on window resize; keep the session (and any unsaved
		// strokes) alive across re-inits, only load from the item once.
		if (this.drawinbooks$session == null) {
			this.drawinbooks$hand = drawinbooks$findBookHand();

			ItemStack book = this.minecraft.player == null
					? ItemStack.EMPTY
					: this.minecraft.player.getItemInHand(this.drawinbooks$hand);

			// A rebuilt screen is a new object, so the field above is no help
			// when the game replaces the screen rather than re-running init -
			// which is what alt-tab and fullscreen do. Ask for the session back
			// before falling back to reading the book.
			this.drawinbooks$session = BookSessions.restore(
					this.drawinbooks$hand, drawinbooks$slot(), book);

			if (this.drawinbooks$session == null) {
				DrawingBlob.Decoded stored = BookDrawingStorage.read(book).orElse(null);

				this.drawinbooks$session = stored == null
						? DrawingSession.fromPages(null, null)
						: DrawingSession.fromPages(stored.pages(), InkColor.byIndex(stored.colorIndex()));

				BookSessions.remember(
						this.drawinbooks$session, this.drawinbooks$hand, drawinbooks$slot(), book);
			}
		}

		DrawingSession session = this.drawinbooks$session;
		int bookLeft = BookLayout.bookLeft(this.width);

		// Where a drawing could not be saved, the canvas is read-only: it shows
		// what is already on the book but takes no input, exactly like the
		// screen for a signed book.
		addRenderableWidget(new DrawCanvasWidget(
				bookLeft + BookLayout.CANVAS_X,
				BookLayout.CANVAS_Y,
				session,
				() -> this.currentPage,
				ServerSupport.editingAllowed()));

		int pageLeft = bookLeft + BookLayout.PAGE_TEXT_X;

		new DrawToolbar(session, () -> this.currentPage).addTo(
				self,
				Screens.getWidgets(self),
				DrawToolbar.toolbarX(bookLeft, BookLayout.BOOK_WIDTH),
				BookLayout.PAGE_TEXT_Y,
				(mouseX, mouseY) -> mouseX >= pageLeft && mouseX < pageLeft + BookLayout.PAGE_TEXT_WIDTH
						&& mouseY >= BookLayout.PAGE_TEXT_Y
						&& mouseY < BookLayout.PAGE_TEXT_Y + BookLayout.PAGE_TEXT_HEIGHT);
	}

	/**
	 * Vanilla routes both "Done" and "Sign" through saveChanges. Reserving the
	 * text pages has to happen here specifically: vanilla is about to read
	 * {@code pages} to build the packet, and empty pages would be dropped,
	 * taking their drawings with them.
	 */
	@Inject(method = "saveChanges", at = @At("HEAD"))
	private void drawinbooks$saveDrawings(CallbackInfo ci) {
		DrawingSession session = this.drawinbooks$session;

		if (session == null || !session.isDirty()) {
			return;
		}

		drawinbooks$reservePagesForDrawings(session);
		drawinbooks$commit();
	}

	/**
	 * Closing the book, by any route vanilla doesn't already handle.
	 *
	 * <p>Vanilla saves text only from Done and Sign. If the drawing reaches
	 * further than the text does - draw on page 13 of a book whose text ends at
	 * page 12 - and the player closes with Escape, the drawing is stored but
	 * those pages never come into existence, so the book reopens saying "1 of
	 * 12" with two invisible drawn pages past the end. Vanilla's own save is
	 * the only thing that can create them, so it is called: the injection at
	 * its head reserves the pages first.
	 *
	 * <p>Only when the pages are actually missing, so closing a book without
	 * saving still discards text everywhere else, exactly like vanilla.
	 */
	@Unique
	private void drawinbooks$commitOnClose() {
		DrawingSession session = this.drawinbooks$session;

		if (session != null && session.isDirty() && session.drawnPageCount() > this.pages.size()) {
			saveChanges();
			return;
		}

		drawinbooks$commit();
	}

	/** Writes the current drawing to the book, if there is anything to write. */
	@Unique
	private void drawinbooks$commit() {
		DrawingSession session = this.drawinbooks$session;

		if (session != null && session.isDirty()) {
			DrawingPersistence.persist(
					this.minecraft, this.drawinbooks$hand, session.toPages(), session.inkColor());
		}
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

	/** Hotbar slot of the book, or -1 for the offhand - just an identity key. */
	@Unique
	private int drawinbooks$slot() {
		if (this.drawinbooks$hand == InteractionHand.OFF_HAND || this.minecraft.player == null) {
			return -1;
		}

		return this.minecraft.player.getInventory().getSelectedSlot();
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
}
