package com.drawinbooks.client;

import com.drawinbooks.client.compat.ScribbleCompat;
import com.drawinbooks.client.debug.ItemSizeOverlay;
import com.drawinbooks.client.draw.BookScreenScale;
import com.drawinbooks.client.draw.DrawingPersistence;

import net.fabricmc.api.ClientModInitializer;

public class DrawInBooksClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		DrawingPersistence.initialize();
		BookScreenScale.initialize();

		// Does nothing unless Scribble is installed, in which case it replaces
		// the book screen entirely and our mixins would never run.
		ScribbleCompat.initialize();

		// Does nothing unless the debug option is turned on.
		ItemSizeOverlay.initialize();
	}
}
