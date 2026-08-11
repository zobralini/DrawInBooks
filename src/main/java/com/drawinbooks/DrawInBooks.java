package com.drawinbooks;

import com.drawinbooks.net.DrawingSyncReceiver;

import net.fabricmc.api.ModInitializer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Common entrypoint, running on both sides.
 *
 * <p>No registry entries are added: drawings live in vanilla's
 * {@code minecraft:custom_data}, so nothing here changes what a client without
 * the mod sees or receives. The only thing registered is one serverbound
 * payload, which is what lets a survival player's drawing reach a server that
 * has the mod installed.
 */
public class DrawInBooks implements ModInitializer {
	public static final String MOD_ID = "drawinbooks";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		DrawingSyncReceiver.initialize();
	}
}
