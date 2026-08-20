package com.drawinbooks.client.draw;

import java.util.Arrays;

import com.drawinbooks.component.BookDrawingStorage;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

/**
 * Keeps the in-progress drawing alive across a screen being rebuilt.
 *
 * <p>The working session used to live in a field on the book screen, which is
 * fine right up until something replaces that screen object. Alt-tabbing and
 * toggling fullscreen do exactly that, and every unsaved stroke went with it -
 * the new screen read the book back from the item and saw the last saved state.
 *
 * <p>So the session is held here instead, outside any screen, and a freshly
 * built screen asks for it back. It is dropped a few seconds after the last
 * book screen closes, which is long enough to survive any rebuild and short
 * enough that it cannot follow the player around.
 *
 * <p>Handing it back to the <em>wrong</em> book would be far worse than losing
 * it, so the match is deliberately strict: same hand, same slot, and the book
 * must still carry exactly the drawing this session started from.
 */
public final class BookSessions {
	/** How long a session outlives its screen, in client ticks (~3s). */
	private static final int KEEP_TICKS = 60;

	private static DrawingSession session;
	private static InteractionHand hand;
	private static int slot;

	/** The drawing that was on the book when this session began. */
	private static byte[] originBlob;

	private static int idleTicks;

	private BookSessions() {
	}

	public static void initialize() {
		ClientTickEvents.END_CLIENT_TICK.register(BookSessions::tick);
	}

	private static void tick(Minecraft minecraft) {
		if (session == null) {
			return;
		}

		Screen screen = minecraft.gui == null ? null : minecraft.gui.screen();

		if (BookScreenScale.isBookScreen(screen)) {
			idleTicks = 0;
			return;
		}

		if (++idleTicks > KEEP_TICKS) {
			forget();
		}
	}

	/**
	 * The session for this book, if the one being held belongs to it.
	 *
	 * @return the live session, or null - in which case the caller should read
	 *         the drawing off the item as usual
	 */
	public static DrawingSession restore(InteractionHand forHand, int forSlot, ItemStack stack) {
		if (session == null || hand != forHand || slot != forSlot) {
			return null;
		}

		if (!Arrays.equals(originBlob, BookDrawingStorage.readBlob(stack).orElse(null))) {
			return null; // a different book, or this one changed under us
		}

		idleTicks = 0;
		return session;
	}

	/** Hands a session over to be kept for the next screen that asks. */
	public static void remember(DrawingSession newSession, InteractionHand newHand, int newSlot, ItemStack stack) {
		session = newSession;
		hand = newHand;
		slot = newSlot;
		originBlob = BookDrawingStorage.readBlob(stack).orElse(null);
		idleTicks = 0;
	}

	private static void forget() {
		session = null;
		hand = null;
		slot = 0;
		originBlob = null;
		idleTicks = 0;
	}
}
