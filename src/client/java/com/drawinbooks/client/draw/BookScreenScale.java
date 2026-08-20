package com.drawinbooks.client.draw;

import com.mojang.blaze3d.platform.Window;

import com.drawinbooks.client.compat.ScribbleCompat;
import com.drawinbooks.client.config.DrawConfig;
import com.drawinbooks.client.config.DrawConfigScreen;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.BookEditScreen;
import net.minecraft.client.gui.screens.inventory.BookViewScreen;

/**
 * Renders book screens one GUI-scale step larger than the rest of the game.
 *
 * <p>A book at GUI scale 3 is cramped for drawing, but raising the game's own
 * setting to 4 makes every other menu and the hotbar oversized. Since the HUD
 * is not drawn while a screen is open, the scale can simply be bumped for the
 * duration of the book screen and put back afterwards.
 *
 * <p>This changes only the window's live scale, never the player's saved
 * option, so nothing can leak into the settings file if the game exits while a
 * book is open - the option is the source of truth both when computing the
 * bumped scale and when restoring it.
 */
public final class BookScreenScale {
	/** How many scale steps to add. */
	private static final int STEP = 1;

	private BookScreenScale() {
	}

	/**
	 * Safety net: whatever happens to the screen - closed, replaced, swapped
	 * by another mod, or removed without the usual event firing - the scale
	 * goes back as soon as no book screen is open. Without this a missed
	 * restore would leave the whole game at the bumped scale.
	 */
	public static void initialize() {
		ClientTickEvents.END_CLIENT_TICK.register(minecraft -> {
			Screen screen = minecraft.gui == null ? null : minecraft.gui.screen();

			if (!isBookScreen(screen)) {
				restore();
			}
		});
	}

	/**
	 * Bumps the scale and re-lays out the screen. Safe to call from
	 * {@code init()}: the resize re-runs init, but by then the window already
	 * reports the target scale, so the second call does nothing.
	 */
	public static void enlarge(Screen screen) {
		if (!DrawConfig.get().scaleUpBookGui) {
			return;
		}

		Minecraft minecraft = Minecraft.getInstance();
		Window window = minecraft.getWindow();

		int target = targetScale(minecraft, window);

		if (target <= 0 || (int) window.getGuiScale() == target) {
			return;
		}

		window.setGuiScale(target);
		screen.resize(window.getGuiScaledWidth(), window.getGuiScaledHeight());
	}

	/** Puts the player's own scale back. */
	public static void restore() {
		Minecraft minecraft = Minecraft.getInstance();
		Window window = minecraft.getWindow();
		int original = optionScale(minecraft, window);

		if ((int) window.getGuiScale() == original) {
			return;
		}

		window.setGuiScale(original);

		// Whatever screen replaces the book has to be laid out again. If the
		// book closed straight to the world there is nothing to re-lay out -
		// the HUD reads the window's scaled size fresh every frame.
		Screen next = minecraft.gui.screen();

		if (next != null) {
			next.resize(window.getGuiScaledWidth(), window.getGuiScaledHeight());
		}
	}

	/**
	 * Book screens keep the bump; anything else gives it back. The settings
	 * screen counts as one, so opening it from a book doesn't resize the world
	 * behind it and then resize it again on the way back.
	 */
	public static boolean isBookScreen(Screen screen) {
		return screen instanceof BookEditScreen
				|| screen instanceof BookViewScreen
				|| screen instanceof DrawConfigScreen
				|| ScribbleCompat.isBookScreen(screen);
	}

	/** @return the enlarged scale, or 0 when the window is already at its max */
	private static int targetScale(Minecraft minecraft, Window window) {
		boolean unicode = minecraft.options.forceUnicodeFont().get();
		int max = window.calculateScale(0, unicode);
		int current = optionScale(minecraft, window);
		int target = Math.min(current + STEP, max);

		return target > current ? target : 0;
	}

	/** The scale the game would use for any other screen. */
	private static int optionScale(Minecraft minecraft, Window window) {
		boolean unicode = minecraft.options.forceUnicodeFont().get();
		return window.calculateScale(minecraft.options.guiScale().get(), unicode);
	}
}
