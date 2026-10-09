package local.mapreview;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import java.util.*;

public final class ReviewSessions {
    private static final Map<MinecraftServer,ReviewSession> ACTIVE = new IdentityHashMap<>();
    public static ReviewSession get(MinecraftServer server) { return ACTIVE.get(server); }
    public static void put(ReviewSession session) { if(ACTIVE.putIfAbsent(session.server,session)!=null) throw new IllegalStateException("已有单人审查会话。"); }
    public static void remove(ReviewSession session) { ACTIVE.remove(session.server,session); }
    public static ReviewSession of(ServerPlayerEntity player) {
        if(player == null || player.getServer() == null) return null;
        var s=get(player.getServer());
        return s!=null && (s.owns(player) || player.networkHandler==s.realHandler) ? s : null;
    }
    public static boolean isActor(ServerPlayerEntity player) { var s=of(player); return s!=null && s.ownsActor(player); }
    public static boolean isController(ServerPlayerEntity player) { var s=of(player); return s!=null && s.controller()==player; }
    public static void rememberView(ServerPlayerEntity player, Map<String,Object> view) { var s=of(player); if(s!=null && s.ownsActor(player)) s.views.put(player.getUuid(),new LinkedHashMap<>(view)); }
}
