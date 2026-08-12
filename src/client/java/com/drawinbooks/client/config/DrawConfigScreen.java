package com.drawinbooks.client.config;

import com.drawinbooks.client.draw.InkColor;
import com.drawinbooks.client.draw.Tool;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * The settings screen, reachable from the settings icon on the book toolbar
 * or with Ctrl-G while a book is open.
 *
 * <p>Toggles are plain buttons that cycle their own value rather than vanilla's
 * option widgets - fewer moving parts, and identical on every 26.x build - while
 * the brush sizes use sliders, since showing a whole small range at a glance is
 * exactly what a slider is for. Changes are written to disk as they are made,
 * so closing the screen any way at all keeps them.
 */
public final class DrawConfigScreen extends Screen {
	private static final int ROW_HEIGHT = 24;
	private static final int WIDTH = 220;

	private final Screen parent;
	private final DrawConfig config = DrawConfig.get();

	private int nextRow;

	public DrawConfigScreen(Screen parent) {
		super(Component.literal("Draw In Books"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		this.nextRow = 0;

		int x = this.width / 2 - WIDTH / 2;
		int top = this.height / 2 - 4 * ROW_HEIGHT;

		addToggle(x, top, "Scale up book GUI",
				() -> this.config.scaleUpBookGui,
				value -> this.config.scaleUpBookGui = value);

		addToggle(x, top, "Show editing tools",
				() -> this.config.showEditingTools,
				value -> this.config.showEditingTools = value);

		addToggle(x, top, "Show drawings while writing",
				() -> this.config.showDrawingsInTextMode,
				value -> this.config.showDrawingsInTextMode = value);

		addCycle(x, top, "Toolbar side",
				() -> this.config.toolbarOnRight ? "Right" : "Left",
				() -> this.config.toolbarOnRight = !this.config.toolbarOnRight);

		addCycle(x, top, "Default ink",
				() -> name(this.config.defaultColor()),
				() -> this.config.defaultColorIndex = this.config.defaultColor().next().ordinal());

		addSlider(x, top, "Pen size", Tool.PEN, this.config.penSize,
				value -> this.config.penSize = value);

		addSlider(x, top, "Eraser size", Tool.ERASER, this.config.eraserSize,
				value -> this.config.eraserSize = value);

		addToggle(x, top, "Debug: show item size",
				() -> this.config.debugItemSize,
				value -> this.config.debugItemSize = value);

		addRenderableWidget(Button.builder(
				Component.literal("Done"),
				button -> onClose())
				.bounds(this.width / 2 - 50, top + (this.nextRow + 1) * ROW_HEIGHT, 100, 20).build());
	}

	private int takeRow(int top) {
		return top + this.nextRow++ * ROW_HEIGHT;
	}

	private void addToggle(int x, int top, String label, BooleanGetter getter, BooleanSetter setter) {
		addCycle(x, top, label,
				() -> getter.get() ? "Yes" : "No",
				() -> setter.set(!getter.get()));
	}

	private void addCycle(int x, int top, String label, ValueText value, Runnable onClick) {
		addRenderableWidget(Button.builder(
				Component.literal(label + ": " + value.get()),
				button -> {
					onClick.run();
					button.setMessage(Component.literal(label + ": " + value.get()));
					this.config.save();
				}).bounds(x, takeRow(top), WIDTH, 20).build());
	}

	private void addSlider(int x, int top, String label, Tool tool, int initial, IntSetter setter) {
		addRenderableWidget(new IntSlider(
				x, takeRow(top), WIDTH, 20,
				label, tool.minSize(), tool.maxSize(), initial,
				value -> {
					setter.set(value);
					this.config.save();
				}));
	}

	private static String name(InkColor color) {
		String lower = color.name().toLowerCase(java.util.Locale.ROOT);
		return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		super.extractRenderState(graphics, mouseX, mouseY, delta);

		int top = this.height / 2 - 4 * ROW_HEIGHT;

		graphics.text(this.font, this.title,
				this.width / 2 - this.font.width(this.title) / 2, top - 16,
				0xFFFFFFFF, true);

		Component hint = Component.literal("Brush sizes are also adjustable in the book with Ctrl and Alt")
				.withStyle(ChatFormatting.DARK_GRAY);

		graphics.text(this.font, hint,
				this.width / 2 - this.font.width(hint) / 2,
				top + (this.nextRow + 3) * ROW_HEIGHT,
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

	@FunctionalInterface
	private interface IntSetter {
		void set(int value);
	}
}
