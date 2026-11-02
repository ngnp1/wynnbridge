package com.wynnbridge.mixin;

import com.wynnbridge.PlayerTitles;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.entity.PlayerEntityRenderer;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.client.render.state.CameraRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws a player's custom title one line above their nametag.
 *
 * <p>Labels are queued in label space: the matrix is scaled by 0.025 and a
 * positive Y offset moves a line upwards, which is how vanilla stacks the
 * player name and the display name. The title goes above both.
 */
@Mixin(PlayerEntityRenderer.class)
public abstract class PlayerEntityRendererMixin {
	/** One label line, matching the translate vanilla uses between labels. */
	private static final float LABEL_LINE = 9.0f * 1.15f * 0.025f;

	@Inject(method = "renderLabelIfPresent", at = @At("HEAD"))
	private void wynnbridge$renderTitle(PlayerEntityRenderState state, MatrixStack matrices,
			OrderedRenderCommandQueue queue, CameraRenderState cameraState, CallbackInfo ci) {
		Text name = state.displayName != null ? state.displayName : state.playerName;
		if (name == null) {
			return;
		}

		String title = PlayerTitles.get(name.getString());
		if (title == null || title.isEmpty()) {
			return;
		}

		int lines = (state.playerName != null ? 1 : 0) + (state.displayName != null ? 1 : 0);
		if (lines == 0) {
			return;
		}

		matrices.push();
		matrices.translate(0.0f, LABEL_LINE * lines, 0.0f);
		queue.submitLabel(matrices, state.nameLabelPos, 0, PlayerTitles.toText(title),
				!state.sneaking, state.light, state.squaredDistanceToCamera, cameraState);
		matrices.pop();
	}
}
