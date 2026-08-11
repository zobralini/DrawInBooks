package com.drawinbooks.client.mixin;

import java.util.List;

import com.drawinbooks.client.draw.BookLayout;
import com.drawinbooks.client.draw.BookScreenScale;
import com.drawinbooks.client.draw.DrawCanvasWidget;
import com.drawinbooks.client.draw.DrawToolbar;
import com.drawinbooks.client.draw.DrawingPersistence;
import com.drawinbooks.client.draw.DrawingSession;
import com.drawinbooks.client.draw.InkColor;
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
		// book appears. Saving again here covers the whole signing flow, and
		// matches vanilla, which also saves book text on close.
		ScreenEvents.remove(self).register(screen -> {
			BookScreenScale.restore();
			drawinbooks$commit();
		});

		BookScreenScale.enlarge(this);

		// init() re-runs on window resize; keep the session (and any unsaved
		// strokes) alive across re-inits, only load from the item once.
		if (this.drawinbooks$session == null) {
			this.drawinbooks$hand = drawinbooks$findBookHand();

			ItemStack book = this.minecraft.player == null
					? ItemStack.EMPTY
					: this.minecraft.player.getItemInHand(this.drawinbooks$hand);

			DrawingBlob.Decoded stored = BookDrawingStorage.read(book).orElse(null);

			this.drawinbooks$session = stored == null
					? DrawingSession.fromPages(null, InkColor.RED)
					: DrawingSession.fromPages(stored.pages(), InkColor.byIndex(stored.colorIndex()));
		}

		DrawingSession session = this.drawinbooks$session;
		int bookLeft = BookLayout.bookLeft(this.width);

		addRenderableWidget(new DrawCanvasWidget(
				bookLeft + BookLayout.CANVAS_X,
				BookLayout.CANVAS_Y,
				session,
				() -> this.currentPage));

		int pageLeft = bookLeft + BookLayout.PAGE_TEXT_X;

		new DrawToolbar(session, () -> this.currentPage).addTo(
				self,
				Screens.getWidgets(self),
				bookLeft - DrawToolbar.WIDTH - 2,
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
