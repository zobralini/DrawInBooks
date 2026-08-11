package com.drawinbooks.client.draw;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * A frameless button: just its glyph, drawn with a text shadow, with a faint
 * highlight while hovered. Used for the drawing tools, so the toolbar reads as
 * a row of icons rather than a stack of vanilla button frames.
 *
 * <p>The label is whatever {@code setMessage} was last given, including its
 * style - that is how the color swatch renders in its own color.
 */
public final class IconButton extends AbstractWidget {
	private static final int HOVER_TINT = 0x33FFFFFF;
	private static final int LABEL_COLOR = 0xFFFFFFFF;

	@FunctionalInterface
	public interface OnPress {
		void onPress();
	}

	private final OnPress action;

	public IconButton(int x, int y, int width, int height, Component message, OnPress action) {
		super(x, y, width, height, message);
		this.action = action;
	}

	@Override
	protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		if (this.isHovered) {
			graphics.fill(getX(), getY(), getX() + this.width, getY() + this.height, HOVER_TINT);
		}

		var font = Minecraft.getInstance().font;
		Component label = getMessage();

		int textX = getX() + (this.width - font.width(label)) / 2;
		int textY = getY() + (this.height - font.lineHeight) / 2;

		// The label's own style color wins over this default, which is what
		// lets the color swatch draw itself red / black / blue.
		graphics.text(font, label, textX, textY, LABEL_COLOR, true);
	}

	@Override
	public void onClick(MouseButtonEvent event, boolean doubleClick) {
		this.action.onPress();
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput output) {
		defaultButtonNarrationText(output);
	}
}
