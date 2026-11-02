package com.wynnbridge.mixin;

/** Carries the player's account name on the render state for the label mixin. */
public interface PlayerEntityRenderStateAccess {
	void wynnbridge$setPlayerName(String name);

	String wynnbridge$getPlayerName();
}
