package com.drawinbooks.client.mixin;

import java.util.List;

import com.drawinbooks.client.draw.BookLayout;
import com.drawinbooks.client.draw.BookScreenScale;
import com.drawinbooks.client.draw.BookSource;
import com.drawinbooks.client.draw.CanvasRenderer;
import com.drawinbooks.component.BookDrawingStorage;
import com.drawinbooks.component.DrawingBlob;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.BookViewScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Renders drawings when a book is read - held in the hand, or standing in a
 * lectern. Without this a written book shows its text but no drawing, because
 * the reading screen is a different class from the editing one.
 *
 * <p>Drawn from {@code afterExtract}, i.e. after everything else on the
 * screen, so the drawing sits on top of the page text exactly as it does
 * while editing. A widget would be drawn before the screen's own text.
 *
 * <p>The screen carries only the book's text ({@code BookAccess}), never the
 * ItemStack it came from, so finding the stack is its own problem - see
 * {@link BookSource}.
 */
@Mixin(BookViewScreen.class)
public abstract class BookViewScreenMixin extends Screen {
	@Shadow
	private int currentPage;

	/** The stack the pages below were decoded from, for change detection. */
	@Unique
	private ItemStack drawinbooks$decodedFrom;

	@Unique
	private List<byte[]> drawinbooks$pages;

	/** Bumped on every re-decode, to invalidate the cached run geometry. */
	@Unique
	private int drawinbooks$revision;

	protected BookViewScreenMixin(Component title) {
		super(title);
	}

	@Inject(method = "init", at = @At("TAIL"))
	private void drawinbooks$renderDrawings(CallbackInfo ci) {
		Screen self = (Screen) (Object) this;

		// Reading a book gets the same extra GUI-scale step as editing one.
		ScreenEvents.remove(self).register(screen -> BookScreenScale.restore());
		BookScreenScale.enlarge(this);

		CanvasRenderer.RunCache cache = new CanvasRenderer.RunCache();

		ScreenEvents.afterExtract(self).register((screen, graphics, mouseX, mouseY, tickProgress) -> {
			drawinbooks$refresh();

			List<byte[]> pages = this.drawinbooks$pages;
			int page = this.currentPage;

			if (pages == null || page < 0 || page >= pages.size()) {
				return;
			}

			cache.render(
					graphics,
					BookLayout.bookLeft(this.width) + BookLayout.CANVAS_X,
					BookLayout.CANVAS_Y,
					pages.get(page), page, this.drawinbooks$revision);
		});
	}

	/**
	 * Decodes the drawing, but only when the book has actually changed.
	 *
	 * <p>Checked per frame rather than once at init because a lectern's book
	 * can be swapped out underneath an open screen - the screen handles that
	 * itself and stays open. The check is a reference comparison; decoding only
	 * happens when the stack is genuinely a different one.
	 */
	@Unique
	private void drawinbooks$refresh() {
		ItemStack book = BookSource.readingBook((Screen) (Object) this);

		if (book == this.drawinbooks$decodedFrom) {
			return;
		}

		this.drawinbooks$decodedFrom = book;
		this.drawinbooks$revision++;

		DrawingBlob.Decoded stored = BookDrawingStorage.read(book).orElse(null);

		this.drawinbooks$pages = stored == null ? null : stored.pages();
	}

}
