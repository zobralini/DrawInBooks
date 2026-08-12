package com.drawinbooks.client.config;

import com.drawinbooks.client.draw.InkColor;
import com.drawinbooks.client.draw.Tool;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * The settings screen, reachable from the gear on the book toolbar or with
 * Ctrl-G while a book is open.
 *
 * <p>Built out of plain buttons that cycle their own value rather than
 * vanilla's option widgets: fewer moving parts, and it renders identically on
 * every 26.x build. Changes are written to disk as they are made, so closing
 * the screen any way at all keeps them.
 */
public final class DrawConfigScreen extends Screen {
	private static final int ROW_HEIGHT = 24;
	private static final int WIDTH = 220;

	private final Screen parent;
	private final DrawConfig config = DrawConfig.get();

	public DrawConfigScreen(Screen parent) {
		super(Component.literal("Draw In Books"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		int x = this.width / 2 - WIDTH / 2;
		int y = this.height / 2 - 3 * ROW_HEIGHT;

		addToggle(x, y, "Scale up book GUI",
				() -> this.config.scaleUpBookGui,
				value -> this.config.scaleUpBookGui = value);

		addToggle(x, y + ROW_HEIGHT, "Show editing tools",
				() -> this.config.showEditingTools,
				value -> this.config.showEditingTools = value);

		addToggle(x, y + 2 * ROW_HEIGHT, "Show drawings while writing",
				() -> this.config.showDrawingsInTextMode,
				value -> this.config.showDrawingsInTextMode = value);

		addCycle(x, y + 3 * ROW_HEIGHT, "Toolbar side",
				() -> this.config.toolbarOnRight ? "Right" : "Left",
				() -> this.config.toolbarOnRight = !this.config.toolbarOnRight);

		addCycle(x, y + 4 * ROW_HEIGHT, "Default pen size",
				() -> this.config.penSize + " x " + this.config.penSize,
				() -> this.config.penSize = cycleSize(this.config.penSize, Tool.PEN));

		addCycle(x, y + 5 * ROW_HEIGHT, "Default eraser size",
				() -> this.config.eraserSize + " x " + this.config.eraserSize,
				() -> this.config.eraserSize = cycleSize(this.config.eraserSize, Tool.ERASER));

		addCycle(x, y + 6 * ROW_HEIGHT, "Default ink",
				() -> name(this.config.defaultColor()),
				() -> this.config.defaultColorIndex = this.config.defaultColor().next().ordinal());

		addRenderableWidget(Button.builder(
				Component.literal("Done"),
				button -> onClose()).bounds(this.width / 2 - 50, y + 8 * ROW_HEIGHT, 100, 20).build());
	}

	private void addToggle(int x, int y, String label, BooleanGetter getter, BooleanSetter setter) {
		addCycle(x, y, label,
				() -> getter.get() ? "Yes" : "No",
				() -> setter.set(!getter.get()));
	}

	private void addCycle(int x, int y, String label, ValueText value, Runnable onClick) {
		Button button = Button.builder(
				Component.literal(label + ": " + value.get()),
				b -> {
					onClick.run();
					b.setMessage(Component.literal(label + ": " + value.get()));
					this.config.save();
				}).bounds(x, y, WIDTH, 20).build();

		addRenderableWidget(button);
	}

	private static int cycleSize(int current, Tool tool) {
		return current >= tool.maxSize() ? tool.minSize() : current + 1;
	}

	private static String name(InkColor color) {
		String lower = color.name().toLowerCase(java.util.Locale.ROOT);
		return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		super.extractRenderState(graphics, mouseX, mouseY, delta);

		graphics.text(this.font, this.title,
				this.width / 2 - this.font.width(this.title) / 2,
				this.height / 2 - 4 * ROW_HEIGHT - 12,
				0xFFFFFFFF, true);

		Component hint = Component.literal("Sizes are also adjustable in the book with Ctrl and Alt")
				.withStyle(ChatFormatting.DARK_GRAY);

		graphics.text(this.font, hint,
				this.width / 2 - this.font.width(hint) / 2,
				this.height / 2 + 4 * ROW_HEIGHT + 4,
				0xFFFFFFFF, false);
	}

	@Override
	public void onClose() {
		this.config.save();

		// Back to the book, not to the world - this screen is opened from it.
		this.minecraft.gui.setScreen(this.parent);
	}

	@FunctionalInterface
	private interface ValueText {
		String get();
	}

	@FunctionalInterface
	private interface BooleanGetter {
		boolean get();
	}

	@FunctionalInterface
	private interface BooleanSetter {
		void set(boolean value);
	}
}
