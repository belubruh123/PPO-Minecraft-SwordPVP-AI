package dev.swordai.server;

import java.util.UUID;

/**
 * Snapshot of the arena, published by the server thread once per tick and read by the client
 * thread. Immutable, so it can be handed across threads through a single volatile reference.
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
		boolean botShieldUp,
		float botShieldCd,
		boolean agentShieldUp,
		float agentShieldCd,
		boolean kitHasShield,
		boolean kitHasAxe,
		long tick,
		int botTechnique,
		int botKeys,
		String error) {

	public static final Truth INACTIVE = new Truth(false, false, null, 0, 0, 0, 0, 0, 0,
			0f, 0f, false, false, false, false, false, 0f, false, 0f, false, false, 0L, -1, 0, null);

	public static Truth failed(String error) {
		return new Truth(false, false, null, 0, 0, 0, 0, 0, 0,
				0f, 0f, false, false, false, false, false, 0f, false, 0f, false, false, 0L, -1, 0, error);
	}
}
