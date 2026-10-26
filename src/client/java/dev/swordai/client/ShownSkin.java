package dev.swordai.client;

import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.ClientAsset;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Shows the player the opponent's own skin while the agent sees the colour key, at the cost of
 * drawing one player again instead of the whole world. The world is drawn once, with the keyed
 * figure in it; the agent's picture is taken from that; then the opponent is drawn over the figure
 * as it really looks, with its armour, held items and name tag.
 *
 * <p>Two things make it cover the figure exactly. It is drawn with the same wide model as the key
 * and a copy of its skin in which every outer layer is opaque (where the skin's own outer layer is
 * see-through, the layer underneath shows through on it), so that it has the keyed figure's outline
 * to the pixel. And it is drawn a little nearer the camera, scaled about the camera so that it looks
 * no different, so that it wins the depth test against the keyed figure; walls and blocks in front of
 * the opponent still hide it.
 */
public final class ShownSkin {
	/** How much nearer the camera the real figure is drawn than the keyed one, in blocks. */
	private static final double NEARER = 0.05;
	/** Skins prepared at once, at most; past that they are all let go and prepared again as needed. */
	private static final int MAX_SKINS = 32;

	/** What the level was drawn with this frame, taken as it starts. */
	private static final Matrix4f modelView = new Matrix4f();
	private static GpuBufferSlice fog;
	private static GpuBufferSlice projection;
	private static ProjectionType projectionType;
	private static DeltaTracker deltaTracker;

	/** The opponent's skins with opaque outer layers on the wide model, by skin and shown layers; null where it could not be made. */
	private static final Map<String, PlayerSkin> skins = new HashMap<>();
	private static final List<Identifier> registered = new ArrayList<>();

	private ShownSkin() {
	}

	/** The level is about to be drawn with these. */
	public static void levelBegins(DeltaTracker tracker, Matrix4f levelModelView, GpuBufferSlice levelFog) {
		KeyedSkin.frameBegins();
		deltaTracker = tracker;
		modelView.set(levelModelView);
		fog = levelFog;
		projection = RenderSystem.getProjectionMatrixBuffer();
		projectionType = RenderSystem.getProjectionType();
	}

	/**
	 * Draws the players keyed in this frame with their own skins over the keyed figures. Called right
	 * after the level is drawn, with its depth still in place, once the agent's picture is taken.
	 */
	static void drawOver(Minecraft mc) {
		List<Avatar> keyed = KeyedSkin.keyedThisFrame();
		if (keyed.isEmpty() || fog == null || projection == null || mc.level == null) {
			return;
		}
		GameRenderer gameRenderer = mc.gameRenderer;
		CameraRenderState camera = gameRenderer.getLevelRenderState().cameraRenderState;
		Vec3 eye = camera.pos;
		EntityRenderDispatcher dispatcher = mc.getEntityRenderDispatcher();
		PoseStack pose = new PoseStack();
		int submitted = 0;
		KeyedSkin.setShowing(true);
		try {
			for (Avatar avatar : keyed) {
				if (avatar.isRemoved()) {
					continue;
				}
				// The same moment between ticks as the level drew it with: the same pose, to the pixel.
				float partial = deltaTracker.getGameTimeDeltaPartialTick(!mc.level.tickRateManager().isEntityFrozen(avatar));
				EntityRenderState extracted = dispatcher.extractEntity(avatar, partial);
				if (!(extracted instanceof AvatarRenderState state)) {
					continue;
				}
				dressForTheScreen(mc, state);
				double x = state.x - eye.x;
				double y = state.y + state.boundingBoxHeight / 2 - eye.y;
				double z = state.z - eye.z;
				double distance = Math.sqrt(x * x + y * y + z * z);
				float nearer = (float) Math.max(0.5, 1.0 - NEARER / Math.max(distance, 0.1));
				pose.pushPose();
				pose.scale(nearer, nearer, nearer);
				dispatcher.submit(state, camera, state.x - eye.x, state.y - eye.y, state.z - eye.z, pose, gameRenderer.getSubmitNodeStorage());
				pose.popPose();
				submitted++;
			}
		} finally {
			KeyedSkin.setShowing(false);
		}
		if (submitted == 0) {
			return;
		}
		Matrix4fStack stack = RenderSystem.getModelViewStack();
		stack.pushMatrix();
		stack.mul(modelView);
		RenderSystem.backupProjectionMatrix();
		RenderSystem.setProjectionMatrix(projection, projectionType);
		GpuBufferSlice fogBefore = RenderSystem.getShaderFog();
		RenderSystem.setShaderFog(fog);
		// What the level left set; the hand is drawn with it next.
		gameRenderer.getLighting().setupFor(Lighting.Entry.LEVEL);
		try {
			gameRenderer.getFeatureRenderDispatcher().renderAllFeatures();
			mc.renderBuffers().bufferSource().endBatch();
		} finally {
			RenderSystem.setShaderFog(fogBefore);
			RenderSystem.restoreProjectionMatrix();
			stack.popMatrix();
		}
	}

	/** The opponent as the player is to see it, on the keyed figure's outline; no second shadow under it. */
	private static void dressForTheScreen(Minecraft mc, AvatarRenderState state) {
		int shown = (state.showHat ? 1 : 0) | (state.showJacket ? 2 : 0) | (state.showRightSleeve ? 4 : 0)
				| (state.showLeftSleeve ? 8 : 0) | (state.showRightPants ? 16 : 0) | (state.showLeftPants ? 32 : 0);
		PlayerSkin opaque = opaqueSkin(mc, state.skin, shown);
		if (opaque != null) {
			state.skin = opaque;
			state.showHat = true;
			state.showJacket = true;
			state.showRightSleeve = true;
			state.showLeftSleeve = true;
			state.showRightPants = true;
			state.showLeftPants = true;
		}
		state.shadowRadius = 0f;
		state.shadowPieces.clear();
		// The glow outline has a pass of its own in the level, which is over.
		state.outlineColor = EntityRenderState.NO_OUTLINE;
	}

	private static PlayerSkin opaqueSkin(Minecraft mc, PlayerSkin skin, int shown) {
		String key = skin.body().texturePath() + "|" + skin.model() + "|" + shown;
		if (skins.containsKey(key)) {
			return skins.get(key);
		}
		if (skins.size() >= MAX_SKINS) {
			for (Identifier id : registered) {
				mc.getTextureManager().release(id);
			}
			registered.clear();
			skins.clear();
		}
		PlayerSkin made = null;
		NativeImage pixels = pixels(mc, skin.body().texturePath());
		if (pixels != null) {
			try (pixels) {
				if (pixels.getWidth() == 64 && pixels.getHeight() == 64) {
					NativeImage opaque = opaqueLayers(pixels, skin.model() == PlayerModelType.SLIM, shown);
					Identifier id = Identifier.fromNamespaceAndPath("swordai", "shown_skin/" + registered.size());
					mc.getTextureManager().register(id, new DynamicTexture(id::toString, opaque));
					registered.add(id);
					made = new PlayerSkin(new ClientAsset.DownloadedTexture(id, ""), skin.cape(), skin.elytra(), PlayerModelType.WIDE, skin.secure());
				}
			} catch (RuntimeException e) {
				dev.swordai.SwordAi.LOGGER.warn("could not prepare the opponent's skin " + skin.body().texturePath() + " to be shown", e);
			}
		}
		skins.put(key, made);
		return made;
	}

	/** A copy of the skin's pixels, or null: a downloaded skin keeps them, one from the game's resources is read again. */
	private static NativeImage pixels(Minecraft mc, Identifier path) {
		try {
			AbstractTexture texture = mc.getTextureManager().getTexture(path);
			if (texture instanceof DynamicTexture dynamic && dynamic.getPixels() != null) {
				NativeImage source = dynamic.getPixels();
				NativeImage copy = new NativeImage(source.getWidth(), source.getHeight(), false);
				copy.copyFrom(source);
				return copy;
			}
			Optional<Resource> resource = mc.getResourceManager().getResource(path);
			if (resource.isPresent()) {
				try (InputStream in = resource.get().open()) {
					return NativeImage.read(in);
				}
			}
		} catch (IOException | RuntimeException e) {
			dev.swordai.SwordAi.LOGGER.warn("could not read the opponent's skin " + path, e);
		}
		return null;
	}

	/** Outer layer, its corner on the 64 by 64 skin, its size, where its inner layer is from it, and its bit in shown. */
	private static final int[][] LAYERS = {
			{32, 0, 32, 16, -32, 0, 1}, // hat over head
			{16, 32, 24, 16, 0, -16, 2}, // jacket over body
			{40, 32, 16, 16, 0, -16, 4}, // right sleeve over right arm
			{48, 48, 16, 16, -16, 0, 8}, // left sleeve over left arm
			{0, 32, 16, 16, 0, -16, 16}, // right trouser leg over right leg
			{0, 48, 16, 16, 16, 0, 32}, // left trouser leg over left leg
	};
	/** The arms' corners on the skin: inner and outer layer of the right arm, then of the left. */
	private static final int[][] ARMS = {{40, 16}, {40, 32}, {32, 48}, {48, 48}};

	/**
	 * The skin for the wide model with every outer layer opaque: the outer layer laid over the inner
	 * one, or the inner one alone where the outer layer is not shown. A slim skin's arms are widened
	 * from three pixels to four.
	 */
	static NativeImage opaqueLayers(NativeImage skin, boolean slim, int shown) {
		NativeImage out = new NativeImage(64, 64, true);
		out.copyFrom(skin);
		if (slim) {
			for (int[] arm : ARMS) {
				for (int v = 0; v < 16; v++) {
					for (int u = 0; u < 16; u++) {
						int from = slimColumn(u, v < 4);
						out.setPixel(arm[0] + u, arm[1] + v, from < 0 ? 0 : skin.getPixel(arm[0] + from, arm[1] + v));
					}
				}
			}
		}
		for (int[] layer : LAYERS) {
			boolean show = (shown & layer[6]) != 0;
			for (int y = layer[1]; y < layer[1] + layer[3]; y++) {
				for (int x = layer[0]; x < layer[0] + layer[2]; x++) {
					int inner = out.getPixel(x + layer[4], y + layer[5]) | 0xFF000000;
					out.setPixel(x, y, show ? over(out.getPixel(x, y), inner) : inner);
				}
			}
		}
		return out;
	}

	/**
	 * Where a column of a wide arm's texture comes from on a slim one (three wide, four deep), or -1
	 * for none. The top row holds the top and bottom faces; the row under it the four sides.
	 */
	static int slimColumn(int u, boolean topRow) {
		if (topRow) {
			if (u >= 4 && u < 8) {
				return 4 + (u - 4) * 3 / 4;
			}
			if (u >= 8 && u < 12) {
				return 7 + (u - 8) * 3 / 4;
			}
			return -1;
		}
		if (u < 4) {
			return u;
		}
		if (u < 8) {
			return 4 + (u - 4) * 3 / 4;
		}
		if (u < 12) {
			return 7 + (u - 8);
		}
		return 11 + (u - 12) * 3 / 4;
	}

	/** An opaque pixel: top laid over below by top's alpha. Alpha is the top byte, the colours in the rest. */
	static int over(int top, int below) {
		int a = top >>> 24;
		if (a == 255) {
			return top;
		}
		int out = 0xFF000000;
		for (int shift = 0; shift < 24; shift += 8) {
			int t = (top >>> shift) & 255;
			int b = (below >>> shift) & 255;
			out |= ((t * a + b * (255 - a)) / 255) << shift;
		}
		return out;
	}
}
