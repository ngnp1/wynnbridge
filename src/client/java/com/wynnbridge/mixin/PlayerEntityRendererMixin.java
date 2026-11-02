package com.wynnbridge.mixin;

import com.wynnbridge.PlayerTitles;
import com.wynnbridge.WynnBridgeClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.entity.PlayerEntityRenderer;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.client.render.state.CameraRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Draws a player's custom title one line above their nametag.
 *
 * <p>Labels are queued in label space: the matrix is scaled by 0.025 and a
 * positive Y offset moves a line upwards, which is how vanilla stacks the
 * player name and the display name. The title goes above both.
 *
 * <p>Everything is wrapped in a guard: a label is drawn once per player per
 * frame, so an exception here would take the whole client down.
 */
@Mixin(PlayerEntityRenderer.class)
public abstract class PlayerEntityRendererMixin {
	/** One label line, matching the translate vanilla uses between labels. */
	private static final float LABEL_LINE = 9.0f * 1.15f * 0.025f;

	/** Player names already reported, so the log does not grow every frame. */
	private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();
	private static final int REPORT_LIMIT = 25;

	/** Set once if rendering throws, so the failure is logged once and then skipped. */
	private static final AtomicBoolean BROKEN = new AtomicBoolean();

	@Inject(method = "renderLabelIfPresent", at = @At("HEAD"))
	private void wynnbridge$renderTitle(PlayerEntityRenderState state, MatrixStack matrices,
			OrderedRenderCommandQueue queue, CameraRenderState cameraState, CallbackInfo ci) {
		if (BROKEN.get()) {
			return;
		}
		try {
			render(state, matrices, queue, cameraState);
		} catch (Throwable throwable) {
			if (BROKEN.compareAndSet(false, true)) {
				WynnBridgeClient.LOGGER.error("Player titles disabled after a render failure", throwable);
			}
		}
	}

	private static void render(PlayerEntityRenderState state, MatrixStack matrices,
			OrderedRenderCommandQueue queue, CameraRenderState cameraState) {
		String name = accountName(state);
		if (name == null && state.displayName != null) {
			name = state.displayName.getString();
		}
		if (name == null && state.playerName != null) {
			name = state.playerName.getString();
		}
		if (name == null) {
			return;
		}

		String title = PlayerTitles.get(name);
		if (title == null && state.displayName != null) {
			title = PlayerTitles.find(state.displayName.getString());
		}
		report(name, state, title);
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

	/** The account name behind a render state, which the label text may decorate. */
	private static String accountName(PlayerEntityRenderState state) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.world == null) {
			return null;
		}
		Entity entity = client.world.getEntityById(state.id);
		return entity instanceof PlayerEntity player ? player.getStringifiedName() : null;
	}

	/** Logs the first few lookups so a missing title can be traced from the log. */
	private static void report(String name, PlayerEntityRenderState state, String title) {
		if (REPORTED.size() >= REPORT_LIMIT || !REPORTED.add(name.toLowerCase(Locale.ROOT))) {
			return;
		}
		WynnBridgeClient.LOGGER.info(
				"title lookup: name='{}' display='{}' playerName='{}' pos={} title='{}'",
				name,
				state.displayName == null ? "" : state.displayName.getString(),
				state.playerName == null ? "" : state.playerName.getString(),
				state.nameLabelPos,
				title);
	}
}
