package dev.swordai.server;

import java.util.UUID;

/**
 * Snapshot of the arena, published by the server thread once per tick and read by the client
 * thread. Immutable, so it can be handed across threads through a single volatile reference.
 *
 * @param episodeId counts up every time an episode reset has been applied
 * @param hopId     counts up every time the bot was repositioned (including resets)
 * @param botFalling whether the server has the bot in the air and lower than a tick ago (the golden rule's K2, without lag)
 * @param agentSprinting whether the server has the agent sprinting: what its next hit is judged with
 * @param botTechnique the technique the technique-school bot was in on its last tick ({@code Technique} ordinal), or -1 when no
 *                     such bot plays
 * @param botKeys      that bot's keys on its last tick, as {@code TechniqueBot} packs them
 * @param error     non-null when the arena could not be set up; the session shows it and stops
 */
public record Truth(
		boolean active,
		boolean botReady,
		UUID botUuid,
		int episodeId,
		int hopId,
		double centerX,
		double floorY,
		double centerZ,
		int radius,
		float botHealth,
		float botCharge,
		boolean botSprinting,
		boolean botOnGround,
		boolean botFalling,
		boolean agentSprinting,
		long tick,
		int botTechnique,
		int botKeys,
		String error) {

	public static final Truth INACTIVE = new Truth(false, false, null, 0, 0, 0, 0, 0, 0, 0f, 0f, false, false, false, false, 0L, -1, 0, null);

	public static Truth failed(String error) {
		return new Truth(false, false, null, 0, 0, 0, 0, 0, 0, 0f, 0f, false, false, false, false, 0L, -1, 0, error);
	}
}
