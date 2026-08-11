package com.drawinbooks.client;

import com.drawinbooks.client.draw.BookScreenScale;
import com.drawinbooks.client.draw.DrawingPersistence;

import net.fabricmc.api.ClientModInitializer;

public class DrawInBooksClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		DrawingPersistence.initialize();
		BookScreenScale.initialize();
	}
}
