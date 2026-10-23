package dev.swordai;

import dev.swordai.server.ArenaServer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SwordAi implements ModInitializer {
	public static final String MOD_ID = "swordai";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		ServerTickEvents.END_SERVER_TICK.register(ArenaServer::onServerTick);
		ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, baseDamage, damageTaken, blocked) ->
				ArenaServer.onDamage(entity, source, damageTaken));
		ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, amount) -> ArenaServer.allowDeath(entity));
		// Both paths put the player's own inventory back before their data is written to disk.
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> ArenaServer.onPlayerLeft(handler.getPlayer(), server));
		ServerLifecycleEvents.SERVER_STOPPING.register(ArenaServer::onServerStopping);
	}
}
