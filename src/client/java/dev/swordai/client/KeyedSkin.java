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

import java.util.UUID;

/**
 * While the agent is running, the opponent is drawn with a flat two-colour skin (blue head, green
 * body) and without anything that would cover or tint it. In the arena that is every other player;
 * in a fight on a server it is only the one being fought. This only changes what this client
 * renders; the other player's real skin, armour and health are untouched.
 *
 * <p>The player need not look at that figure: with {@link #setTwoPass} the keyed picture is drawn in
 * a pass of its own, for the agent only, and the screen gets the world as it really looks.
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
	 * The player is shown the opponent as it really looks: each frame the world is drawn twice, first
	 * with the colour key for the agent and then without it for the screen.
	 */
	private static volatile boolean twoPass;
	/** Set while the second of those two pictures is being drawn. Render thread only. */
	private static boolean showing;
	/** While two pictures are drawn per frame: how often the opponent was drawn with the key, and how often without. */
	private static long keyedDraws;
	private static long plainDraws;

	private KeyedSkin() {
	}

	public static void setActive(boolean value) {
		active = value;
	}

	public static void setTwoPass(boolean value) {
		if (value && !twoPass) {
			keyedDraws = 0;
			plainDraws = 0;
		}
		twoPass = value;
	}

	/** For the diagnosis when showing the skin goes wrong: draws of the opponent with the key and without, since it was switched on. */
	public static String drawCounts() {
		return keyedDraws + " keyed / " + plainDraws + " plain draws of the opponent";
	}

	/** Whether each frame is to be drawn twice right now. */
	public static boolean twoPass() {
		return active && twoPass;
	}

	public static void setShowing(boolean value) {
		showing = value;
	}

	/** True while the picture being drawn is the one for the player's eyes, not the agent's. */
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
		if (twoPass) {
			keyedDraws++;
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
