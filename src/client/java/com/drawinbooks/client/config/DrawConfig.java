package com.drawinbooks.client.config;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import com.drawinbooks.DrawInBooks;
import com.drawinbooks.client.draw.InkColor;
import com.drawinbooks.client.draw.Tool;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Client settings, in a plain properties file next to the game's other
 * configs. Deliberately not a config library: six values do not justify a
 * dependency, and a properties file is something a player can fix by hand if
 * they ever manage to lock themselves out of the in-game screen.
 *
 * <p>Everything is read through {@link #get()}, which returns the same
 * instance for the whole session - no file access happens while a book is
 * open.
 */
public final class DrawConfig {
	private static final String FILE_NAME = "drawinbooks.properties";

	private static DrawConfig instance;

	/** Open book screens one GUI-scale step larger than the rest of the game. */
	public boolean scaleUpBookGui = true;

	/** Show the drawing toolbar. With this off, drawings still render. */
	public boolean showEditingTools = true;

	public int penSize = Tool.PEN.defaultSize();
	public int eraserSize = Tool.ERASER.defaultSize();

	/** Which ink a freshly opened book starts on. */
	public int defaultColorIndex = InkColor.RED.ordinal();

	/**
	 * Which side of the book the toolbar sits on. Worth having because
	 * Scribble puts its own controls to the left of the book, where they would
	 * otherwise collide.
	 */
	public boolean toolbarOnRight = false;

	/**
	 * Show the serialized size of the held item in the action bar. A debug
	 * aid: every size claim this mod makes can be checked in game.
	 */
	public boolean debugItemSize = false;

	/**
	 * Keep drawings visible while writing text. Off means the page shows only
	 * what you are currently working on, which some people find less busy.
	 */
	public boolean showDrawingsInTextMode = true;

	private DrawConfig() {
	}

	public static DrawConfig get() {
		if (instance == null) {
			instance = load();
		}

		return instance;
	}

	public InkColor defaultColor() {
		return InkColor.byIndex(this.defaultColorIndex);
	}

	private static Path path() {
		return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
	}

	private static DrawConfig load() {
		DrawConfig config = new DrawConfig();
		Path file = path();

		if (!Files.isRegularFile(file)) {
			return config;
		}

		Properties properties = new Properties();

		try (Reader reader = Files.newBufferedReader(file)) {
			properties.load(reader);
		} catch (IOException e) {
			DrawInBooks.LOGGER.warn("Could not read {}, using defaults: {}", FILE_NAME, e.toString());
			return config;
		}

		config.scaleUpBookGui = bool(properties, "scaleUpBookGui", config.scaleUpBookGui);
		config.showEditingTools = bool(properties, "showEditingTools", config.showEditingTools);
		config.showDrawingsInTextMode = bool(properties, "showDrawingsInTextMode", config.showDrawingsInTextMode);
		config.toolbarOnRight = bool(properties, "toolbarOnRight", config.toolbarOnRight);
		config.debugItemSize = bool(properties, "debugItemSize", config.debugItemSize);

		// Clamped on read: a hand-edited file can only ever produce a usable
		// brush, never a broken one.
		config.penSize = clamp(properties, "penSize", config.penSize, Tool.PEN);
		config.eraserSize = clamp(properties, "eraserSize", config.eraserSize, Tool.ERASER);
		config.defaultColorIndex = InkColor.clampIndex(
				integer(properties, "defaultColorIndex", config.defaultColorIndex));

		return config;
	}

	public void save() {
		Properties properties = new Properties();
		properties.setProperty("scaleUpBookGui", Boolean.toString(this.scaleUpBookGui));
		properties.setProperty("showEditingTools", Boolean.toString(this.showEditingTools));
		properties.setProperty("showDrawingsInTextMode", Boolean.toString(this.showDrawingsInTextMode));
		properties.setProperty("toolbarOnRight", Boolean.toString(this.toolbarOnRight));
		properties.setProperty("debugItemSize", Boolean.toString(this.debugItemSize));
		properties.setProperty("penSize", Integer.toString(this.penSize));
		properties.setProperty("eraserSize", Integer.toString(this.eraserSize));
		properties.setProperty("defaultColorIndex", Integer.toString(this.defaultColorIndex));

		try (Writer writer = Files.newBufferedWriter(path())) {
			properties.store(writer, "Draw In Books - safe to edit by hand; bad values fall back to defaults");
		} catch (IOException e) {
			DrawInBooks.LOGGER.warn("Could not write {}: {}", FILE_NAME, e.toString());
		}
	}

	private static boolean bool(Properties properties, String key, boolean fallback) {
		String value = properties.getProperty(key);
		return value == null ? fallback : Boolean.parseBoolean(value.trim());
	}

	private static int integer(Properties properties, String key, int fallback) {
		String value = properties.getProperty(key);

		if (value == null) {
			return fallback;
		}

		try {
			return Integer.parseInt(value.trim());
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	private static int clamp(Properties properties, String key, int fallback, Tool tool) {
		return Math.clamp(integer(properties, key, fallback), tool.minSize(), tool.maxSize());
	}
}
