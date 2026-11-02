package com.wynnbridge.mixin;

import com.wynnbridge.PlayerTitles;
import com.wynnbridge.WynnBridgeClient;
import net.minecraft.client.render.command.LabelCommandRenderer;
import net.minecraft.client.render.state.CameraRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Adds a player's custom title as an extra label directly above their nametag.
 *
 * <p>This hooks the last stop for every label, so the title is built from the
 * exact matrix, position, vertical offset, light and distance of the name label
 * itself. Whoever draws the nametag - vanilla or another mod that submits its
 * own lines - the title moves with it.
 */
@Mixin(LabelCommandRenderer.Commands.class)
public abstract class LabelCommandsMixin {
	/** One label line, matching the spacing vanilla uses between labels. */
	private static final float LABEL_LINE = 9.0f * 1.15f * 0.025f;

	/** Extra air between the title and the name, on top of one line. */
	private static final float TITLE_GAP = 3.0f * 0.025f;

	/** Guards the recursive call below: the added title is a label too. */
	private static final ThreadLocal<Boolean> ADDING = ThreadLocal.withInitial(() -> Boolean.FALSE);

	/** Labels already reported, so the log does not grow without bound. */
	private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();
	private static final int REPORT_LIMIT = 25;

	@Shadow
	public abstract void add(MatrixStack matrices, Vec3d labelPos, int offset, Text text,
			boolean seeThrough, int light, double squaredDistance, CameraRenderState cameraState);

	@Inject(method = "add", at = @At("HEAD"))
	private void wynnbridge$addTitle(MatrixStack matrices, Vec3d labelPos, int offset, Text text,
			boolean seeThrough, int light, double squaredDistance, CameraRenderState cameraState,
			CallbackInfo ci) {
		if (ADDING.get()) {
			return;
		}

		String title = titleFor(text);
		report(text, title);
		if (title == null) {
			return;
		}

		ADDING.set(Boolean.TRUE);
		try {
			matrices.push();
			matrices.translate(0.0f, LABEL_LINE + TITLE_GAP, 0.0f);
			add(matrices, labelPos, offset, PlayerTitles.toText(title),
					seeThrough, light, squaredDistance, cameraState);
			matrices.pop();
		} catch (Throwable throwable) {
			WynnBridgeClient.LOGGER.error("Could not draw a player title", throwable);
		} finally {
			ADDING.set(Boolean.FALSE);
		}
	}

	/** The title for a label, matched against the text the label carries. */
	private static String titleFor(Text text) {
		if (text == null) {
			return null;
		}
		String label = text.getString();
		if (label.isEmpty()) {
			return null;
		}
		String title = PlayerTitles.get(label);
		return title != null ? title : PlayerTitles.find(label);
	}

	private static void report(Text text, String title) {
		if (text == null || REPORTED.size() >= REPORT_LIMIT) {
			return;
		}
		String label = text.getString();
		if (label.isEmpty() || !REPORTED.add(label.toLowerCase(Locale.ROOT))) {
			return;
		}
		WynnBridgeClient.LOGGER.info("label '{}' -> title '{}'", label, title);
	}
}
