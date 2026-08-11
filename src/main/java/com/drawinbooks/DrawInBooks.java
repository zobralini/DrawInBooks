package com.drawinbooks;

import net.fabricmc.api.ModInitializer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Common entrypoint. There is nothing to register: drawings are stored in
 * vanilla's {@code minecraft:custom_data}, so the mod adds no registry
 * entries and no packets, and can be installed or removed on either side
 * without affecting anyone else.
 */
public class DrawInBooks implements ModInitializer {
	public static final String MOD_ID = "drawinbooks";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
	}
}
