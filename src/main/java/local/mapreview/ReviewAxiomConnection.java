package local.mapreview;

import java.util.Set;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.network.ServerPlayerEntity;
import org.slf4j.LoggerFactory;

/** Optional Axiom 6 connection refresh after a PLAY join reinitializes its client.
 * Invalidate only this connection's public handshake caches. Axiom's own tick
 * issues a fresh nonce and checks permissions; its client still performs licensing.
 * No client-only classes, permission changes, or Axiom configuration writes. */
final class ReviewAxiomConnection {
    private ReviewAxiomConnection() { }

    static void refresh(ServerPlayerEntity player) {
        if (!FabricLoader.getInstance().isModLoaded("axiom")) return;
        try {
            Class<?> hook = Class.forName("com.moulberry.axiom.hooks.MinecraftServerExt");
            if (!hook.isInstance(player.getServer())) return;
            Object server = hook.getMethod("axiom$getAxiomServer").invoke(player.getServer());
            if (server == null) return;
            for (String field : new String[] {"activeAxiomPlayers", "sentDisableReasonPlayers"}) {
                Object values = server.getClass().getField(field).get(server);
                if (!(values instanceof Set<?> cache)) throw new ReflectiveOperationException("Unexpected Axiom connection cache: " + field);
                cache.remove(player.getUuid());
            }
        } catch (ReflectiveOperationException | LinkageError error) {
            LoggerFactory.getLogger("mapreview").warn("Could not refresh Axiom's connection after a review role switch", error);
        }
    }
}
