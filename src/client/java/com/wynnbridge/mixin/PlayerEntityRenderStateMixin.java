package com.wynnbridge.mixin;

import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Remembers the account name of the player a render state belongs to. */
@Mixin(PlayerEntityRenderState.class)
public abstract class PlayerEntityRenderStateMixin implements PlayerEntityRenderStateAccess {
	@Unique
	private String wynnbridge$playerName;

	@Override
	public void wynnbridge$setPlayerName(String name) {
		this.wynnbridge$playerName = name;
	}

	@Override
	public String wynnbridge$getPlayerName() {
		return this.wynnbridge$playerName;
	}
}
