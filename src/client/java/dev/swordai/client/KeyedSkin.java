package dev.swordai.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.core.ClientAsset;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * While the agent is running, the opponent is drawn with a flat two-colour skin (blue head, green
 * body) and without anything that would cover or tint it. In the arena that is every other player;
 * in a fight on a server it is only the one being fought. This only changes what this client
 * renders; the other player's real skin, armour and health are untouched.
 *
 * <p>The player need not look at that figure: with {@link #setShowReal} the agent's picture is taken
 * with the key and then {@link ShownSkin} draws the opponent's own skin over the figure, for the
 * screen only.
 */
public final class KeyedSkin {
	private static final PlayerSkin SKIN = PlayerSkin.insecure(
			new ClientAsset.ResourceTexture(
					Identifier.fromNamespaceAndPath("swordai", "entity/keyed_skin"),
					Identifier.fromNamespaceAndPath("swordai", "textures/entity/keyed_skin.png")),
			null, null, PlayerModelType.WIDE);

	private static volatile boolean active;
	/** Null: every other player. */
	private static volatile UUID target;
	/**
	 * The player is shown the opponent as it really looks: once the agent's picture is taken, the
	 * opponent's own skin is drawn over the keyed figure (see {@link ShownSkin}).
	 */
	private static volatile boolean showReal;
	/** Set while the opponent is looked at again for its own skin. Render thread only. */
	private static boolean showing;
	/** While the real skin is shown: how often the opponent was drawn with the key, and how often with its own skin. */
	private static long keyedDraws;
	private static long plainDraws;
	/** The players drawn with the key in this frame, for {@link ShownSkin}. Render thread only. */
	private static final List<Avatar> keyedThisFrame = new ArrayList<>();

	private KeyedSkin() {
	}

	public static void setActive(boolean value) {
		active = value;
	}

	public static void setShowReal(boolean value) {
		if (value && !showReal) {
			keyedDraws = 0;
			plainDraws = 0;
		}
		showReal = value;
	}

	/** For the diagnosis when showing the skin goes wrong: draws of the opponent with the key and without, since it was switched on. */
	public static String drawCounts() {
		return keyedDraws + " keyed / " + plainDraws + " plain draws of the opponent";
	}

	/** Whether the opponent's own skin is to be drawn over the key right now. */
	public static boolean showReal() {
		return active && showReal;
	}

	/** Forgets the players keyed so far: a new frame's level is about to be drawn. */
	static void frameBegins() {
		keyedThisFrame.clear();
	}

	/** The players drawn with the key in this frame. */
	static List<Avatar> keyedThisFrame() {
		return keyedThisFrame;
	}

	public static void setShowing(boolean value) {
		showing = value;
	}

	/** True while the opponent is looked at for the player's eyes, not the agent's. */
	public static boolean showing() {
		return showing;
	}

	/** True while the picture being drawn is the agent's: rain and snow are left out of it, as they would streak across the colour key. */
	public static boolean hidesWeather() {
		return active && !showing;
	}

	/** Limits the recolouring to one player; null lifts the limit. */
	public static void setTarget(UUID player) {
		target = player;
	}

	/** Called for every player-shaped entity right after vanilla has filled in its render state. */
	public static void apply(Avatar avatar, AvatarRenderState state) {
		if (!active || avatar == Minecraft.getInstance().player) {
			return;
		}
		UUID only = target;
		if (only != null && !only.equals(avatar.getUUID())) {
			return;
		}
		if (showing) {
			plainDraws++;
			return;
		}
		if (showReal) {
			keyedDraws++;
			if (!keyedThisFrame.contains(avatar)) {
				keyedThisFrame.add(avatar);
			}
		}
		state.skin = SKIN;
		// Armour is still worn (it changes damage); it just must not hide the colour key.
		state.headEquipment = ItemStack.EMPTY;
		state.chestEquipment = ItemStack.EMPTY;
		state.legsEquipment = ItemStack.EMPTY;
		state.feetEquipment = ItemStack.EMPTY;
		state.headItem.clear();
		state.heldOnHead.clear();
		// Whatever is held would cover part of the figure: a sword a little, a shield a lot. The arms
		// keep their pose; only the things in them are not drawn.
		state.rightHandItemState.clear();
		state.leftHandItemState.clear();
		state.wornHeadType = null;
		state.wornHeadProfile = null;
		state.showCape = false;
		state.showHat = true;
		state.showJacket = true;
		state.showLeftSleeve = true;
		state.showRightSleeve = true;
		state.showLeftPants = true;
		state.showRightPants = true;
		state.showExtraEars = false;
		state.arrowCount = 0;
		state.stingerCount = 0;
		state.parrotOnLeftShoulder = null;
		state.parrotOnRightShoulder = null;
		// The red hurt flash would shift the key colours for half a second after every hit.
		state.hasRedOverlay = false;
		state.isInvisible = false;
		state.isInvisibleToPlayer = false;
		state.displayFireAnimation = false;
		state.nameTag = null;
		state.scoreText = null;
		state.lightCoords = LightTexture.FULL_BRIGHT;
	}
}
