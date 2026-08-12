package com.drawinbooks.client.mixin;

import java.util.List;

import com.drawinbooks.client.draw.BookLayout;
import com.drawinbooks.client.draw.BookScreenScale;
import com.drawinbooks.client.draw.CanvasRenderer;
import com.drawinbooks.component.BookDrawingStorage;
import com.drawinbooks.component.DrawingBlob;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.BookViewScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Renders drawings when a signed book is read. Without this, a written book
 * shows its text but no drawing - the reading screen is a different class
 * from the editing one.
 *
 * <p>Drawn from {@code afterExtract}, i.e. after everything else on the
 * screen, so the drawing sits on top of the page text exactly as it does
 * while editing. A widget would be drawn before the screen's own text.
 *
 * <p>The screen only carries the book's text ({@code BookAccess}), not the
 * ItemStack it came from, so the drawing is read off the book the player is
 * holding. That covers reading a signed book from the hand; a book placed in
 * a lectern (which uses a subclass of this screen) is not covered yet and
 * would need the stack passed down from the lectern block entity.
 */
@Mixin(BookViewScreen.class)
public abstract class BookViewScreenMixin extends Screen {
	@Shadow
	private int currentPage;

	protected BookViewScreenMixin(Component title) {
		super(title);
	}

	@Inject(method = "init", at = @At("TAIL"))
	private void drawinbooks$renderDrawings(CallbackInfo ci) {
		Screen self = (Screen) (Object) this;

		// Reading a book gets the same extra GUI-scale step as editing one.
		ScreenEvents.remove(self).register(screen -> BookScreenScale.restore());
		BookScreenScale.enlarge(this);

		ItemStack book = drawinbooks$heldBook();

		if (book == null) {
			return;
		}

		DrawingBlob.Decoded stored = BookDrawingStorage.read(book).orElse(null);

		if (stored == null) {
			return;
		}

		List<byte[]> pages = stored.pages();

		// A reader never edits, so the geometry is built once and replayed -
		// the revision passed in is constant.
		CanvasRenderer.RunCache cache = new CanvasRenderer.RunCache();

		ScreenEvents.afterExtract(self).register((screen, graphics, mouseX, mouseY, tickProgress) -> {
			int page = this.currentPage;

			if (page >= 0 && page < pages.size()) {
				cache.render(
						graphics,
						BookLayout.bookLeft(this.width) + BookLayout.CANVAS_X,
						BookLayout.CANVAS_Y,
						pages.get(page), page, 0);
			}
		});
	}

	private ItemStack drawinbooks$heldBook() {
		LocalPlayer player = this.minecraft == null ? null : this.minecraft.player;

		if (player == null) {
			return null;
		}

		ItemStack stack = player.getMainHandItem();

		if (!stack.is(Items.WRITTEN_BOOK)) {
			stack = player.getOffhandItem();
		}

		return stack.is(Items.WRITTEN_BOOK) ? stack : null;
	}
}
