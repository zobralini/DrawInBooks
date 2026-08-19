package com.drawinbooks.client.draw;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * A button that is just its glyph, drawn with a text shadow.
 *
 * <p>Two looks, from the same class:
 * <ul>
 *   <li><b>Frameless</b> (no texture) - only the glyph, with a faint highlight
 *       while hovered. Used for the tools, so the strip under the book reads as
 *       a row of icons rather than a stack of vanilla button frames.</li>
 *   <li><b>Textured</b> - a background blitted from a two-frame sheet, normal
 *       on top and hovered underneath. Used for the mode toggle, which is the
 *       one control that is always on screen and so wants to look like a
 *       button.</li>
 * </ul>
 *
 * <p>The glyph is drawn on top either way, and is whatever {@code setMessage}
 * was last given, including its style - that is how the color swatch renders in
 * its own color, and why nothing is baked into the texture: the toggle alone
 * alternates between two glyphs, and the tools carry a brush size that changes
 * under the cursor.
 */
public final class IconButton extends AbstractWidget {
	private static final int HOVER_TINT = 0x33FFFFFF;

	/** White with a shadow, which is how a glyph reads over the dark GUI. */
	private static final int LABEL_COLOR = 0xFFFFFFFF;

	/**
	 * Over the parchment background the same white glyph disappears, so a
	 * textured button draws it in a dark brown instead - and without a shadow,
	 * which on dark-on-light text only reads as a smudge.
	 */
	private static final int TEXTURED_LABEL_COLOR = 0xFF2B2118;

	@FunctionalInterface
	public interface OnPress {
		void onPress();
	}

	private final OnPress action;

	/** Background sheet, or null for a frameless button. */
	private final Identifier texture;

	/** Height of one frame in that sheet; the sheet holds two, stacked. */
	private final int frameHeight;

	public IconButton(int x, int y, int width, int height, Component message, OnPress action) {
		this(x, y, width, height, message, action, null);
	}

	/**
	 * @param texture a sheet exactly {@code width} wide and {@code 2 * height}
	 *                tall: the normal frame on top, the hovered one below
	 */
	public IconButton(int x, int y, int width, int height, Component message, OnPress action, Identifier texture) {
		super(x, y, width, height, message);
		this.action = action;
		this.texture = texture;
		this.frameHeight = height;
	}

	@Override
	protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		boolean textured = this.texture != null;

		if (textured) {
			graphics.blit(RenderPipelines.GUI_TEXTURED, this.texture,
					getX(), getY(),
					0.0F, this.isHovered ? this.frameHeight : 0.0F,
					this.width, this.height,
					this.width, this.frameHeight * 2);
		} else if (this.isHovered) {
			graphics.fill(getX(), getY(), getX() + this.width, getY() + this.height, HOVER_TINT);
		}

		var font = Minecraft.getInstance().font;
		Component label = getMessage();

		int textX = getX() + (this.width - font.width(label)) / 2;

		// The button is 20 tall and a line is 9, so 11 pixels of slack cannot
		// be split evenly and one has to go above or below. Inside a frame the
		// glyph looks high when it goes below, so textured buttons round the
		// other way. The frameless icons have no frame to sit inside and are
		// left where they are.
		int textY = getY() + (this.height - font.lineHeight + (textured ? 1 : 0)) / 2;

		// The label's own style color wins over this default, which is what
		// lets the color swatch draw itself red / black / blue / green / yellow.
		graphics.text(font, label, textX, textY,
				textured ? TEXTURED_LABEL_COLOR : LABEL_COLOR, !textured);
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
