package com.drawinbooks.client.config;

import java.util.function.IntConsumer;

import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.network.chat.Component;

/**
 * A slider over a small integer range, used for the brush sizes - it takes the
 * same room as a button but shows the whole range at a glance.
 *
 * <p>Vanilla's slider works in 0..1, so the value is mapped both ways here and
 * the label always shows the real number rather than a percentage.
 */
public final class IntSlider extends AbstractSliderButton {
	private final String label;
	private final int min;
	private final int max;
	private final IntConsumer onChange;

	public IntSlider(int x, int y, int width, int height,
			String label, int min, int max, int initial, IntConsumer onChange) {
		super(x, y, width, height, Component.empty(), toFraction(initial, min, max));

		this.label = label;
		this.min = min;
		this.max = max;
		this.onChange = onChange;

		updateMessage();
	}

	private static double toFraction(int value, int min, int max) {
		return max == min ? 0 : (double) (Math.clamp(value, min, max) - min) / (max - min);
	}

	private int current() {
		return this.min + (int) Math.round(this.value * (this.max - this.min));
	}

	@Override
	protected void updateMessage() {
		int size = current();
		setMessage(Component.literal(this.label + ": " + size + " x " + size));
	}

	@Override
	protected void applyValue() {
		this.onChange.accept(current());
	}
}
