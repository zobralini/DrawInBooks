package com.drawinbooks.client.draw;

import com.drawinbooks.component.BookDrawingStorage;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.MenuAccess;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.LecternMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Finds the book a reading screen is actually showing.
 *
 * <p>A reading screen is handed a book's <em>text</em> and nothing else, so the
 * ItemStack - and with it the drawing - has to be found another way. There are
 * two cases and they have nothing in common:
 * <ul>
 *   <li><b>Held book</b> - it is in one of the player's hands.</li>
 *   <li><b>Lectern</b> - the player may be holding nothing at all. The stack
 *       lives on the screen's {@link LecternMenu}.</li>
 * </ul>
 *
 * <p>The lectern case is matched through the {@link MenuAccess} interface
 * rather than the vanilla screen class, so it keeps working when another mod
 * replaces the lectern screen with its own - which is exactly what Scribble
 * does, and why lecterns worked in a development client and not in a real one.
 */
public final class BookSource {
	private BookSource() {
	}

	/**
	 * @return the book being read, or {@link ItemStack#EMPTY} if there is none
	 *         to be found
	 */
	public static ItemStack readingBook(Screen screen) {
		if (screen instanceof MenuAccess<?> access && access.getMenu() instanceof LecternMenu lectern) {
			ItemStack book = lectern.getBook();
			return BookDrawingStorage.isBook(book) ? book : ItemStack.EMPTY;
		}

		LocalPlayer player = Minecraft.getInstance().player;

		if (player == null) {
			return ItemStack.EMPTY;
		}

		ItemStack stack = player.getMainHandItem();

		if (!stack.is(Items.WRITTEN_BOOK)) {
			stack = player.getOffhandItem();
		}

		return stack.is(Items.WRITTEN_BOOK) ? stack : ItemStack.EMPTY;
	}
}
