package local.tabletop;

import local.mapreview.ReviewSession;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Read-only access to the owned table; all mutations remain in the production commands. */
public final class ReviewTabletopBridge {
    private ReviewTabletopBridge() {}
    public static boolean idle(MinecraftServer server) {
        return Sessions.GAMES.values().stream().noneMatch(table -> table.server == server);
    }
    public static String tableId(ServerPlayerEntity host) {
        return Sessions.MEMBERS.getOrDefault(host.getUuid(), "");
    }
    public static boolean exists(ReviewSession review, String id) {
        var table = Sessions.GAMES.get(id);
        return table != null && table.server == review.server;
    }
    private static Set<UUID> ownedIds(ReviewSession review) {
        Set<UUID> ids = new HashSet<>();
        ids.add(review.owner);
        review.actors.values().forEach(player -> ids.add(player.getUuid()));
        return ids;
    }
    public static boolean isReviewTable(ReviewSession review, String id) {
        var table = Sessions.GAMES.get(id);
        return table != null && table.server == review.server &&
            (table.type.equals("clock") ? table.host.equals(review.owner) :
                review.actor(1) != null && table.host.equals(review.actor(1).getUuid()));
    }
    public static void requireOwned(ReviewSession review, String id) {
        var table = Sessions.GAMES.get(id);
        if (!isReviewTable(review, id) || !ownedIds(review).containsAll(table.audience()))
            throw new IllegalStateException("此桌不是当前审查独占的桌；不允许影响其他玩家。");
    }
    public static boolean hasAssignments(ReviewSession review, String id) {
        requireOwned(review, id);
        return !Sessions.GAMES.get(id).assignments.isEmpty();
    }
    public static int rosterCount(ReviewSession review,String id) {
        requireOwned(review,id);
        return Sessions.GAMES.get(id).roster.size();
    }
    public static boolean running(ReviewSession review,String id) {
        requireOwned(review,id);
        return Sessions.GAMES.get(id).running;
    }
    public static String status(ReviewSession review, String id) {
        if (!exists(review, id)) return "审查桌已关闭";
        requireOwned(review, id);
        var table = Sessions.GAMES.get(id);
        String result = table.type + " · " + table.id + " · 玩家 " + table.roster.size() +
            " · " + (table.running ? table.ended() ? "已结算" : "对局中" : "准备中");
        if (table.type.equals("clock")) {
            String issue = ClockArena.issue(table, review.server);
            if (!ClockArena.enabled(table, review.server)) result += " · 钟楼场地未启用";
            else if (!issue.isEmpty()) result += " · " + issue;
        }
        return result;
    }
}
