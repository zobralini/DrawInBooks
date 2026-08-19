package com.drawinbooks.client.draw;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

import com.drawinbooks.client.config.DrawConfig;
import com.drawinbooks.component.PageBitmaps;

/**
 * In-memory editing state for one open book edit screen: a mutable working
 * copy of every page bitmap, the active tool and the text/draw mode toggle.
 * Nothing here touches the ItemStack; committing back to the data component
 * happens explicitly via {@link #toPages()} when the vanilla screen saves.
 */
public final class DrawingSession {
	/** Lazily-created working bitmaps, indexed by page. */
	private final byte[][] workingPages = new byte[PageBitmaps.MAX_PAGES][];

	/** How many edits can be taken back with Ctrl-Z. */
	public static final int UNDO_DEPTH = 7;

	/**
	 * One copied page, shared by every book screen for as long as the game
	 * runs. Static on purpose: that is what makes it possible to copy a page
	 * out of one book and paste it into another. It never touches an item and
	 * is never written to disk - closing the game loses it.
	 */
	private static byte[] clipboard;

	/** Brush side length per tool, indexed by {@link Tool#ordinal()}. */
	private final int[] brushSizes = new int[Tool.values().length];

	/**
	 * Whole-page snapshots taken before each edit, newest last. One entry per
	 * stroke or whole-page action, capped at {@link #UNDO_DEPTH} (about 25 KB
	 * of working memory - never stored on the item).
	 */
	private final Deque<Snapshot> undoStack = new ArrayDeque<>();

	private record Snapshot(int pageIndex, byte[] before) {
	}

	private Tool tool = Tool.PEN;
	private InkColor inkColor;
	private boolean drawMode = false;
	private boolean dirty = false;

	/**
	 * Bumped on every change to any page. Renderers cache their geometry
	 * against this, so an untouched drawing costs one comparison per frame
	 * instead of a full rescan.
	 */
	private int revision;

	private DrawingSession() {
		DrawConfig config = DrawConfig.get();

		this.brushSizes[Tool.PEN.ordinal()] = Math.clamp(config.penSize, Tool.PEN.minSize(), Tool.PEN.maxSize());
		this.brushSizes[Tool.ERASER.ordinal()] =
				Math.clamp(config.eraserSize, Tool.ERASER.minSize(), Tool.ERASER.maxSize());
		this.inkColor = config.defaultColor();
	}

	public static DrawingSession fromPages(List<byte[]> pages, InkColor inkColor) {
		DrawingSession session = new DrawingSession();

		// A book that already carries a color resumes on it; otherwise the
		// configured default applies.
		if (inkColor != null) {
			session.inkColor = inkColor;
		}

		if (pages != null) {
			for (int i = 0; i < pages.size() && i < PageBitmaps.MAX_PAGES; i++) {
				session.workingPages[i] = pages.get(i).clone();
			}
		}

		return session;
	}

	public InkColor inkColor() {
		return this.inkColor;
	}

	/**
	 * Cycles the pen color red - black - blue - green - yellow. This only
	 * changes what future strokes are drawn in; pixels already on the page keep
	 * their own color.
	 */
	public void cycleInkColor() {
		this.inkColor = this.inkColor.next();

		if (drawnPageCount() > 0) {
			this.dirty = true; // the "last used color" hint is worth saving
		}
	}

	public Tool tool() {
		return this.tool;
	}

	public void setTool(Tool tool) {
		this.tool = tool;
	}

	/** Brush side length for the given tool, in bitmap pixels. */
	public int brushSize(Tool tool) {
		return this.brushSizes[tool.ordinal()];
	}

	/** Ctrl-click: one step bigger, up to the tool's maximum. */
	public void growBrush() {
		int index = this.tool.ordinal();
		this.brushSizes[index] = Math.min(this.brushSizes[index] + 1, this.tool.maxSize());
	}

	/** Alt-click: one step smaller, down to the tool's minimum. */
	public void shrinkBrush() {
		int index = this.tool.ordinal();
		this.brushSizes[index] = Math.max(this.brushSizes[index] - 1, this.tool.minSize());
	}

	public boolean isDrawMode() {
		return this.drawMode;
	}

	public void toggleMode() {
		this.drawMode = !this.drawMode;
	}

	public boolean isDirty() {
		return this.dirty;
	}

	public int revision() {
		return this.revision;
	}

	/** Marks the pages as changed, invalidating every cached rendering. */
	private void touch() {
		this.dirty = true;
		this.revision++;
	}

	/** Read-only peek for rendering; null means the page is blank. */
	public byte[] peekPage(int pageIndex) {
		if (pageIndex < 0 || pageIndex >= PageBitmaps.MAX_PAGES) {
			return null;
		}

		return this.workingPages[pageIndex];
	}

	public boolean hasDrawing(int pageIndex) {
		byte[] page = peekPage(pageIndex);
		return page != null && !PageBitmaps.isBlank(page);
	}

	/** Number of pages up to and including the last one that has ink. */
	public int drawnPageCount() {
		for (int i = PageBitmaps.MAX_PAGES - 1; i >= 0; i--) {
			if (hasDrawing(i)) {
				return i + 1;
			}
		}

		return 0;
	}

	/**
	 * Records the state of a page so the next edit can be undone. Call once
	 * per user action - at the start of a stroke, or before a whole-page
	 * change - not per pixel.
	 */
	public void beginEdit(int pageIndex) {
		if (pageIndex < 0 || pageIndex >= PageBitmaps.MAX_PAGES) {
			return;
		}

		byte[] current = this.workingPages[pageIndex];

		this.undoStack.addLast(new Snapshot(
				pageIndex, current == null ? PageBitmaps.blankPage() : current.clone()));

		while (this.undoStack.size() > UNDO_DEPTH) {
			this.undoStack.removeFirst();
		}
	}

	public boolean canUndo() {
		return !this.undoStack.isEmpty();
	}

	/**
	 * Takes back the most recent edit.
	 *
	 * @return the page it applied to, so the screen can flip there, or -1
	 */
	public int undo() {
		Snapshot snapshot = this.undoStack.pollLast();

		if (snapshot == null) {
			return -1;
		}

		this.workingPages[snapshot.pageIndex()] = snapshot.before();
		touch();

		return snapshot.pageIndex();
	}

	/** Whether there is a copied page waiting to be pasted. */
	public static boolean hasClipboard() {
		return clipboard != null;
	}

	/**
	 * Copies the whole drawing on one page. Copying a page with nothing on it
	 * does nothing rather than emptying the clipboard, so a stray Ctrl-C on a
	 * fresh page can't lose what you were about to paste.
	 *
	 * @return true if something was copied
	 */
	public boolean copyPage(int pageIndex) {
		byte[] page = peekPage(pageIndex);

		if (page == null || PageBitmaps.isBlank(page)) {
			return false;
		}

		clipboard = page.clone();
		return true;
	}

	/**
	 * Replaces a page with the copied one - in this book or any other. The
	 * page is replaced outright rather than merged, and the replacement is a
	 * normal undoable edit.
	 *
	 * @return true if the page changed
	 */
	public boolean pastePage(int pageIndex) {
		if (clipboard == null || pageIndex < 0 || pageIndex >= PageBitmaps.MAX_PAGES) {
			return false;
		}

		if (Arrays.equals(this.workingPages[pageIndex], clipboard)) {
			return false;
		}

		beginEdit(pageIndex);
		this.workingPages[pageIndex] = clipboard.clone();
		touch();

		return true;
	}

	/** Paints a single brush dab centered on the given bitmap pixel. */
	public void paint(int pageIndex, int px, int py, int value, int size) {
		byte[] page = pageForWriting(pageIndex, value);

		if (page != null) {
			dab(page, px, py, value, size);
		}
	}

	/**
	 * Paints a straight line of brush dabs between two points (Bresenham), so
	 * a fast drag doesn't leave gaps between frames.
	 */
	public void strokeLine(int pageIndex, int x0, int y0, int x1, int y1, int value, int size) {
		byte[] page = pageForWriting(pageIndex, value);

		if (page == null) {
			return;
		}

		int dx = Math.abs(x1 - x0);
		int dy = -Math.abs(y1 - y0);
		int sx = x0 < x1 ? 1 : -1;
		int sy = y0 < y1 ? 1 : -1;
		int error = dx + dy;
		int x = x0;
		int y = y0;

		while (true) {
			dab(page, x, y, value, size);

			if (x == x1 && y == y1) {
				return;
			}

			int doubled = 2 * error;

			if (doubled >= dy) {
				error += dy;
				x += sx;
			}

			if (doubled <= dx) {
				error += dx;
				y += sy;
			}
		}
	}

	/** Wipes the whole page (Shift-click on the eraser button). */
	public void clearPage(int pageIndex) {
		if (pageIndex < 0 || pageIndex >= PageBitmaps.MAX_PAGES) {
			return;
		}

		if (this.workingPages[pageIndex] != null && !PageBitmaps.isBlank(this.workingPages[pageIndex])) {
			this.workingPages[pageIndex] = PageBitmaps.blankPage();
			touch();
		}
	}

	/** Floods the whole page with the current ink (Shift-click on the pen). */
	public void fillPage(int pageIndex) {
		byte[] page = pageForWriting(pageIndex, this.inkColor.pixelValue());

		if (page == null) {
			return;
		}

		PageBitmaps.fill(page, this.inkColor.pixelValue());
		touch();
	}

	/**
	 * Stamps a square of the given side length centered on the pixel. Even
	 * sizes cannot be perfectly centered, so they extend one pixel further
	 * right and down.
	 */
	private void dab(byte[] page, int px, int py, int value, int size) {
		int before = (size - 1) / 2;
		int after = size / 2;

		for (int dy = -before; dy <= after; dy++) {
			for (int dx = -before; dx <= after; dx++) {
				if (PageBitmaps.setColor(page, px + dx, py + dy, value)) {
					touch();
				}
			}
		}
	}

	/**
	 * Returns the writable bitmap for a page, allocating it on first ink.
	 * Returns null when there is nothing to do (bad index, or erasing a page
	 * that is still blank).
	 */
	private byte[] pageForWriting(int pageIndex, int value) {
		if (pageIndex < 0 || pageIndex >= PageBitmaps.MAX_PAGES) {
			return null;
		}

		byte[] page = this.workingPages[pageIndex];

		if (page == null) {
			if (value == PageBitmaps.BLANK) {
				return null; // erasing a blank page is a no-op
			}

			page = PageBitmaps.blankPage();
			this.workingPages[pageIndex] = page;
		}

		return page;
	}

	/**
	 * Snapshot of the working state, or {@code null} when every page is blank
	 * - meaning the drawing should be removed from the book rather than stored
	 * as an empty one.
	 */
	public List<byte[]> toPages() {
		int drawn = drawnPageCount();

		if (drawn == 0) {
			return null;
		}

		List<byte[]> pages = new ArrayList<>(drawn);

		for (int i = 0; i < drawn; i++) {
			byte[] page = this.workingPages[i];
			pages.add(page != null ? page.clone() : PageBitmaps.blankPage());
		}

		return pages;
	}
}
