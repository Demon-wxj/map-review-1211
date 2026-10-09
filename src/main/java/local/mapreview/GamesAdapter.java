package local.mapreview;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.megawalls.game.MapConfig;
import com.megawalls.game.MatchFlow;
import com.megawalls.game.MatchTeams;
import com.megawalls.game.MatchTerrainJournal;
import com.megawalls.menu.HubMenu;
import com.megawalls.menu.MenuHandler;
import com.megawalls.menu.TabletopBridge;
import com.megawalls.minigame.*;
import com.megawalls.player.MWPlayerData;
import local.tabletop.ReviewTabletopBridge;
import local.tabletop.Sessions;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.WorldSavePath;
import net.minecraft.world.GameMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;

/** Eight actual participants use the installed games' admission, rules and cleanup. */
public final class GamesAdapter {
    private GamesAdapter() {}
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Set<String> GAMES = Set.of("megawalls", "classic_pvp", "pot_pvp", "bedwars",
        "final_duel", "tnt_run", "hot_potato", "flip7", "clocktower", "sao_story", "hub");
    private static final Map<ReviewSession,State> STATES = new IdentityHashMap<>();
    private static final class State {
        final String game;
        String table = "";
        boolean stopped;
        final Map<String,Boolean> relayBefore = new LinkedHashMap<>();
        State(String game) { this.game = game; }
    }
    private static State state(ReviewSession review) {
        var state = STATES.get(review);
        if (state == null || !state.game.equals(review.game)) throw new IllegalStateException("尚未进入审查游戏。");
        return state;
    }
    private static List<ServerPlayerEntity> players(ReviewSession review) {
        var result = new ArrayList<ServerPlayerEntity>();
        result.add(review.judge);
        for (int seat = 1; seat <= 8; seat++) {
            var actor = review.actor(seat);
            if (actor == null) throw new IllegalStateException("缺少测试角色 " + seat);
            var live = review.server.getPlayerManager().getPlayer(actor.getUuid());
            if (live == null) throw new IllegalStateException("测试角色 " + seat + " 不在线。");
            result.add(live);
        }
        return result;
    }
    private static void requireExclusive(ReviewSession review) {
        Set<UUID> owned = new HashSet<>();
        players(review).forEach(player -> owned.add(player.getUuid()));
        for (var player : review.server.getPlayerManager().getPlayerList())
            if (!owned.contains(player.getUuid()) && GameSessions.game(player).equals(review.game))
                throw new IllegalStateException("此游戏有其他玩家，审查工具不会加入或结束他们的对局。");
    }
    private static void requireOwnedParticipants(ReviewSession review) {
        Set<UUID> owned=new HashSet<>(); players(review).forEach(player -> owned.add(player.getUuid()));
        for (var player:review.server.getPlayerManager().getPlayerList()) {
            if (owned.contains(player.getUuid())) continue;
            boolean participant=switch(review.game) {
                case "megawalls" -> MatchFlow.roster(player)>0 || GameSessions.mw(player) && player.getCommandTags().contains("mw_ready");
                case "classic_pvp" -> ClassicDuel.queued(player) || ClassicDuel.inRound(player);
                case "pot_pvp" -> PotDuel.queued(player) || PotDuel.inRound(player);
                case "bedwars" -> BedWars.queued(player) || BedWars.inRound(player);
                case "final_duel" -> Spleef.queued(player) || Spleef.inRound(player);
                case "tnt_run" -> TntRun.queued(player) || TntRun.inRound(player);
                case "hot_potato" -> HotPotato.queued(player) || HotPotato.inRound(player);
                default -> false;
            };
            if (participant) throw new IllegalStateException("此游戏出现其他参赛或排队玩家；不能结束他们的对局，请先处理准入。");
        }
    }
    public static boolean idle(MinecraftServer server) {
        return MatchFlow.phase(server) == 0 && !MatchTerrainJournal.pending(server) && MapConfig.get(server,"wall_saved") == 0 &&
            ClassicDuel.stage() == ClassicDuel.Stage.WAITING && ClassicDuel.queueSize() == 0 && !DuelArena.exists(server) &&
            PotDuel.stage() == PotDuel.Stage.WAITING && PotDuel.queueSize() == 0 && !PotArena.exists(server) &&
            BedWars.stage() == BedWars.Stage.WAITING && !BedArena.exists(server) &&
            List.of("red","blue","green","yellow").stream().mapToLong(BedWars::chosenCount).sum() == 0 &&
            Spleef.stage() == Spleef.Stage.WAITING && Spleef.queueSize() == 0 &&
            TntRun.stage() == TntRun.Stage.WAITING && TntRun.queueSize() == 0 &&
            HotPotato.stage() == HotPotato.Stage.WAITING && HotPotato.queueSize() == 0 &&
            ReviewTabletopBridge.idle(server) && !hasRuleBackups(server) &&
            server.getPlayerManager().getPlayerList().stream().noneMatch(p -> p.getCommandTags().contains("mw_ready"));
    }
    public static void enter(ReviewSession review, String game) {
        if (!GAMES.contains(game)) throw new IllegalArgumentException("未知地图入口：" + game);
        if (STATES.containsKey(review)) throw new IllegalStateException("请先结束并退出当前审查游戏。");
        if (!idle(review.server)) throw new IllegalStateException("已有玩家排队、对局、桌游或待恢复记录；请先完成正常收尾。");
        players(review);
        review.game = game;
        var state = new State(game);
        STATES.put(review, state); // Retain partial admissions so the caller can perform normal cleanup.
        requireExclusive(review);
        for (var player : players(review)) {
            if (!HubMenu.travel(player, game)) throw new IllegalStateException("角色 " + player.getGameProfile().getName() + " 无法进入 " + game + "；请检查入口和场地。");
        }
        // The human remains an observer; no enrollment/ready call is made for the judge.
        review.judge.changeGameMode(GameMode.SPECTATOR);
        switch (game) {
            case "megawalls" -> {
                if (MatchTeams.count(review.server) != 4) throw new IllegalStateException("八角色四队审查需要现有四队配置；请检查战墙规则。");
                for (int seat = 1; seat <= 8; seat++) {
                    var actor = review.actor(seat);
                    actor.changeGameMode(GameMode.SURVIVAL);
                    MenuHandler.selectClass(actor, seat);
                    if (MWPlayerData.getServer(actor).getClassId() != seat ||
                        MatchFlow.chooseTeam(actor, MatchTeams.ALL.get((seat - 1) / 2)) != 1 || MatchFlow.ready(actor) != 1)
                        throw new IllegalStateException("战墙角色 " + seat + " 无法选择职业、队伍或准备。");
                }
            }
            case "classic_pvp", "pot_pvp" -> {
                boolean old = game.equals("classic_pvp") ? ClassicDuel.isRelay() : PotDuel.isRelay();
                state.relayBefore.put(game, old);
                if (!old) {
                    backupRelay(review, game, old);
                    if (!setRelay(review.actor(1), game, true)) throw new IllegalStateException("无法切换为八人车轮战；规则备份已保留。");
                }
                for (int seat = 1; seat <= 8; seat++) {
                    boolean ok = game.equals("classic_pvp") ? ClassicDuel.selectTeam(review.actor(seat),(seat - 1) / 4) :
                        PotDuel.selectTeam(review.actor(seat),(seat - 1) / 4);
                    if (!ok) throw new IllegalStateException("车轮战角色 " + seat + " 无法加入或选择队伍。");
                }
            }
            case "bedwars" -> {
                if (BedWars.teamCount() != 4) throw new IllegalStateException("八角色四队审查需要现有四队起床规则。");
                var teams = List.of("red", "blue", "green", "yellow");
                for (int seat = 1; seat <= 8; seat++) if (!BedWars.enroll(review.actor(seat),teams.get((seat - 1) / 2)))
                    throw new IllegalStateException("起床角色 " + seat + " 无法加入队伍。");
            }
            case "final_duel" -> { for (int seat=1;seat<=8;seat++) if (!Spleef.enroll(review.actor(seat))) throw new IllegalStateException("决一死战角色 "+seat+" 无法参加；请检查场地。"); }
            case "tnt_run" -> { for (int seat=1;seat<=8;seat++) if (!TntRun.enroll(review.actor(seat))) throw new IllegalStateException("TNT角色 "+seat+" 无法参加；请检查场地。"); }
            case "hot_potato" -> { for (int seat=1;seat<=8;seat++) if (!HotPotato.enroll(review.actor(seat))) throw new IllegalStateException("山芋角色 "+seat+" 无法参加；请检查场地。"); }
            case "clocktower", "flip7" -> {
                var host = game.equals("clocktower") ? review.judge : review.actor(1);
                if (game.equals("clocktower")) host.changeGameMode(GameMode.SURVIVAL);
                tabletop(host,"create " + (game.equals("clocktower") ? "clock" : "flip"));
                state.table = ReviewTabletopBridge.tableId(host);
                if (state.table.isEmpty()) throw new IllegalStateException("未成功建立桌游，请检查固定场地。");
                ReviewTabletopBridge.requireOwned(review,state.table);
                for (int seat = game.equals("clocktower") ? 1 : 2; seat <= 8; seat++) tabletop(review.actor(seat),"join " + state.table);
            }
            default -> { /* Hub/story are existing map entry functions, never invented match mechanics. */ }
        }
        review.log("已进入 " + game + "，八名实际测试角色，裁判不参赛。");
    }
    public static void start(ReviewSession review) {
        var state = state(review);
        if (state.stopped) throw new IllegalStateException("此局已关闭，请退出后重新进入。");
        requireExclusive(review);
        // A judge may have clicked a vanilla lobby control while inspecting it.
        // Remove only that identity through normal leave before constructing the match roster.
        switch (review.game) {
            case "megawalls" -> MatchFlow.depart(review.judge);
            case "classic_pvp" -> ClassicDuel.leave(review.judge);
            case "pot_pvp" -> PotDuel.leave(review.judge);
            case "bedwars" -> BedWars.leave(review.judge);
            case "final_duel" -> Spleef.leave(review.judge);
            case "tnt_run" -> TntRun.leave(review.judge);
            case "hot_potato" -> HotPotato.leave(review.judge);
            default -> { }
        }
        if (!review.game.equals("clocktower")) review.judge.changeGameMode(GameMode.SPECTATOR);
        boolean ok = switch (review.game) {
            case "megawalls" -> MatchFlow.requestStart(review.actor(1)) == 1;
            case "classic_pvp" -> ClassicDuel.requestStart(review.actor(1));
            case "pot_pvp" -> PotDuel.requestStart(review.actor(1));
            case "bedwars" -> BedWars.requestStart(review.actor(1));
            case "final_duel" -> Spleef.requestStart(review.actor(1));
            case "tnt_run" -> TntRun.requestStart(review.actor(1));
            case "hot_potato" -> HotPotato.requestStart(review.actor(1));
            case "clocktower", "flip7" -> {
                ReviewTabletopBridge.requireOwned(review,state.table);
                var host = review.game.equals("clocktower") ? review.judge : review.actor(1);
                if (review.game.equals("clocktower") && !ReviewTabletopBridge.hasAssignments(review,state.table)) tabletop(host,"autoassign");
                tabletop(host,"start");
                yield true;
            }
            default -> throw new IllegalStateException("此入口没有可调用的比赛开局；请审查地图现有道具/任务触发器。");
        };
        if (!ok) throw new IllegalStateException("开局未通过原游戏检查：" + status(review));
        if (!review.game.equals("clocktower")) review.judge.changeGameMode(GameMode.SPECTATOR);
        review.log("原游戏已接受开局：" + review.game);
    }
    public static void stop(ReviewSession review) {
        var state = STATES.get(review);
        if (state == null) return;
        requireOwnedParticipants(review);
        switch (state.game) {
            case "megawalls" -> {
                if (MatchFlow.phase(review.server) != 0 || MatchTerrainJournal.pending(review.server) || MapConfig.get(review.server,"wall_saved") == 1)
                    if (MatchFlow.reset(review.server) != 1) throw new IllegalStateException("战墙恢复未完成：" + MatchTerrainJournal.lastError());
            }
            case "classic_pvp" -> {
                if (ClassicDuel.stage()==ClassicDuel.Stage.RESETTING) { if (DuelArena.failed()) command(review.judge,"classic reset"); }
                else if (ClassicDuel.stage()!=ClassicDuel.Stage.WAITING) command(review.judge,"classic stop");
            }
            case "pot_pvp" -> {
                if (PotDuel.stage()==PotDuel.Stage.RESETTING) { if (PotArena.failed()) command(review.judge,"pot reset"); }
                else if (PotDuel.stage()!=PotDuel.Stage.WAITING) command(review.judge,"pot stop");
            }
            case "bedwars" -> {
                if (BedWars.stage()==BedWars.Stage.RESETTING) { if (BedArena.failed()) command(review.judge,"bw reset"); }
                else if (BedWars.stage()!=BedWars.Stage.WAITING) command(review.judge,"bw stop");
            }
            case "final_duel" -> {
                if (Spleef.stage()==Spleef.Stage.RESETTING) command(review.judge,"spleef reset");
                else if (Spleef.stage()!=Spleef.Stage.WAITING) command(review.judge,"spleef stop");
            }
            case "tnt_run" -> {
                if (TntRun.stage()==TntRun.Stage.RESETTING) command(review.judge,"tntrun reset");
                else if (TntRun.stage()!=TntRun.Stage.WAITING) command(review.judge,"tntrun stop");
            }
            case "hot_potato" -> { if (HotPotato.stage() != HotPotato.Stage.WAITING) command(review.judge,"hp stop"); }
            case "clocktower", "flip7" -> {
                if (ReviewTabletopBridge.exists(review,state.table)) {
                    ReviewTabletopBridge.requireOwned(review,state.table);
                    tabletop(review.game.equals("clocktower") ? review.judge : review.actor(1),"close");
                }
            }
            default -> { }
        }
        state.stopped = true;
        review.log("已请求原游戏正常收尾：" + state.game);
    }
    public static boolean settled(ReviewSession review) {
        var state = STATES.get(review);
        if (state == null) return true;
        boolean waiting = switch (state.game) {
            case "megawalls" -> MatchFlow.phase(review.server) == 0 && !MatchTerrainJournal.pending(review.server) && MapConfig.get(review.server,"wall_saved") == 0;
            case "classic_pvp" -> ClassicDuel.stage() == ClassicDuel.Stage.WAITING && !DuelArena.exists(review.server);
            case "pot_pvp" -> PotDuel.stage() == PotDuel.Stage.WAITING && !PotArena.exists(review.server);
            case "bedwars" -> BedWars.stage() == BedWars.Stage.WAITING && !BedArena.exists(review.server);
            case "final_duel" -> Spleef.stage() == Spleef.Stage.WAITING;
            case "tnt_run" -> TntRun.stage() == TntRun.Stage.WAITING;
            case "hot_potato" -> HotPotato.stage() == HotPotato.Stage.WAITING;
            case "clocktower", "flip7" -> !ReviewTabletopBridge.exists(review,state.table);
            default -> true;
        };
        if (!waiting) return false;
        for (var player : players(review)) {
            ClassicDuel.recover(player); PotDuel.recover(player); BedWars.recover(player);
            Spleef.recover(player); TntRun.recover(player); HotPotato.recover(player);
            if (ClassicDuel.hasRecovery(player) || PotDuel.hasRecovery(player) || BedWars.hasRecovery(player) ||
                Spleef.hasRecovery(player) || TntRun.hasRecovery(player) || HotPotato.hasRecovery(player)) return false;
            Path clockRecovery = review.server.getSavePath(WorldSavePath.ROOT).resolve("tabletop/clock-recovery").resolve(player.getUuidAsString()+".dat");
            if (Files.exists(clockRecovery)) Sessions.rejoin(player); // Production retry for a retained partial scene restore.
            if (Files.exists(clockRecovery)) return false;
        }
        return true;
    }
    public static void leave(ReviewSession review) {
        var state = STATES.get(review);
        if (state == null) return;
        if (!settled(review)) throw new IllegalStateException("原场地或玩家档案仍在恢复；请保留测试角色和日志。");
        requireOwnedParticipants(review);
        for (var player : players(review)) switch (state.game) {
            case "classic_pvp" -> ClassicDuel.leave(player);
            case "pot_pvp" -> PotDuel.leave(player);
            case "bedwars" -> BedWars.leave(player);
            case "final_duel" -> Spleef.leave(player);
            case "tnt_run" -> TntRun.leave(player);
            case "hot_potato" -> HotPotato.leave(player);
            default -> { }
        }
        for (var entry : state.relayBefore.entrySet()) {
            // Production finish sends participants to HUB. Acquire only an owned waiting identity
            // for the original setRelay permission check, then switch everyone out normally.
            var editor = review.judge;
            if (!GameSessions.game(editor).equals(entry.getKey())) {
                editor=review.actor(1);
                if (!GameSessions.switchTo(editor,entry.getKey())) throw new IllegalStateException("无法进入等待档案还原原规则；备份已保留。");
            }
            if (!setRelay(editor,entry.getKey(),entry.getValue())) throw new IllegalStateException("原车轮战规则还原失败；备份已保留。");
            deleteBackup(review.server,entry.getKey());
        }
        for (var player : players(review)) if (!GameSessions.switchTo(player,GameSessions.HUB))
            throw new IllegalStateException("角色 " + player.getGameProfile().getName() + " 正常离场失败，保留审查日志。");
        STATES.remove(review);
        review.game = "hub";
        review.log("已完成原游戏恢复、退场及临时规则还原。");
    }
    public static String status(ReviewSession review) {
        var state = STATES.get(review);
        if (state == null) return "尚未进入游戏";
        return switch (state.game) {
            case "megawalls" -> "战墙 " + MatchFlow.phaseName(review.server) + " · " + MatchFlow.startIssue(review.server);
            case "classic_pvp" -> "UHC " + ClassicDuel.stage() + " · 排队 " + ClassicDuel.queueSize() + " · " + engineIssue(ClassicDuel.class,review.server) + (DuelArena.failed()?" · 恢复失败":"");
            case "pot_pvp" -> "Pot " + PotDuel.stage() + " · 排队 " + PotDuel.queueSize() + " · " + engineIssue(PotDuel.class,review.server) + (PotArena.failed()?" · 恢复失败":"");
            case "bedwars" -> "起床 " + BedWars.stage() + " · " + BedWars.issue(review.server) + (BedArena.failed()?" · 恢复失败":"");
            case "final_duel" -> "决一死战 " + Spleef.stage() + " · 排队 " + Spleef.queueSize() + " · " + engineIssue(Spleef.class,review.server);
            case "tnt_run" -> "TNT " + TntRun.stage() + " · 排队 " + TntRun.queueSize() + " · " + engineIssue(TntRun.class,review.server);
            case "hot_potato" -> "山芋 " + HotPotato.stage() + " · 排队 " + HotPotato.queueSize() + " · " + engineIssue(HotPotato.class,review.server);
            case "clocktower", "flip7" -> ReviewTabletopBridge.status(review,state.table);
            default -> "地图入口 " + state.game + " · 没有本工具新增的对局规则";
        };
    }
    private static String engineIssue(Class<?> game,MinecraftServer server) {
        try {
            var method=game.getDeclaredMethod("issue",MinecraftServer.class); method.setAccessible(true);
            return String.valueOf(method.invoke(null,server));
        } catch (ReflectiveOperationException error) { return "无法读取原玩法场地检查，请查看检查清单/日志"; }
    }

    public static void asCommand(ReviewSession review, int seat, String text) {
        state(review);
        var player = review.actor(seat);
        if (player == null || !review.owns(player)) throw new IllegalArgumentException("请选择0–8号审查身份。");
        String line = text == null ? "" : text.trim();
        if (line.startsWith("/")) line = line.substring(1);
        if (line.isBlank() || line.length() > 700 || line.indexOf('\n') >= 0 || line.indexOf('\r') >= 0 || line.indexOf('\0') >= 0)
            throw new IllegalArgumentException("请输入一条当前游戏的操作命令。");
        String[] words = line.split("\\s+",3);
        String root = root(review.game);
        if (words.length < 2 || !(words[0].equals(root) || root.equals("tt") && words[0].equals("tabletop")))
            throw new IllegalArgumentException("只允许当前游戏 /" + root + " 的操作。");
        String operation = words[1];
        if (operation.equals("start") || Set.of("stop","close","reset").contains(operation)) {
            if (words.length != 2) throw new IllegalArgumentException("此审查控制不接受附加参数。");
            ReviewActors.control(review,0);
            if (operation.equals("start")) start(review); else stop(review);
            return;
        }
        requireExclusive(review);
        Set<String> allowed = switch (review.game) {
            case "megawalls" -> Set.of("status","team","ready","class","skin","practice","recharge");
            case "classic_pvp", "pot_pvp", "bedwars" -> Set.of("status","team");
            case "final_duel", "tnt_run", "hot_potato" -> Set.of("status");
            case "flip7" -> Set.of("status","phone","rules","fliprules","draw","stay","target","next","autonext","msg","group");
            case "clocktower" -> Set.of("status","phone","script","rules","rulebook","faq","autoassign","role","votetime","review","logpage","exportlog","phase","nominate","vote","unvote","slay","closevote","execute","mark","finish","submit","resolve","phones","msg","group");
            default -> Set.of();
        };
        if (!allowed.contains(operation)) throw new IllegalArgumentException("审查代理不允许该操作；配置、任意OP命令和加入/离场由专用流程处理。");
        if (seat == 0 && !review.game.equals("clocktower") && !Set.of("status","phone","rules","fliprules").contains(operation))
            throw new IllegalArgumentException("裁判不参赛，请切换测试角色操作。");
        if (review.game.equals("megawalls") && operation.equals("class")) {
            if (words.length != 3 || !words[2].matches("[0-9]{1,2}")) throw new IllegalArgumentException("使用 /mw class 1–13。");
            int id = Integer.parseInt(words[2]); MenuHandler.selectClass(player,id);
            if (id < 1 || id > 13 || MWPlayerData.getServer(player).getClassId() != id) throw new IllegalStateException("原职业选择未通过。");
        } else if (root.equals("tt")) {
            var state = state(review); ReviewTabletopBridge.requireOwned(review,state.table);
            tabletop(player,line.substring(line.indexOf(' ')+1));
        } else if (operation.equals("status") && review.game.equals("megawalls")) {
            player.sendMessage(net.minecraft.text.Text.literal(status(review)),false);
        } else command(player,line);
        review.log("身份 " + seat + " 执行原游戏操作：" + operation);
    }
    private static String root(String game) {
        return switch(game) { case "megawalls" -> "mw"; case "classic_pvp" -> "classic"; case "pot_pvp" -> "pot";
            case "bedwars" -> "bw"; case "final_duel" -> "spleef"; case "tnt_run" -> "tntrun"; case "hot_potato" -> "hp";
            case "flip7","clocktower" -> "tt"; default -> ""; };
    }
    private static void command(ServerPlayerEntity player,String command) {
        try {
            int result = player.getServer().getCommandManager().getDispatcher().execute(command,player.getCommandSource().withLevel(2));
            if (result <= 0) throw new IllegalStateException("原游戏未接受操作：" + command);
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException error) {
            throw new IllegalArgumentException("原游戏命令无效：" + error.getMessage(),error);
        }
    }
    private static void tabletop(ServerPlayerEntity player,String command) {
        if (Sessions.command(player,command) != 1) throw new IllegalStateException("桌游未接受操作：" + command + "；请查看原游戏提示/场地检查。");
    }

    /** Same production admission conditions, except the actor has no independent client channel. */
    public static boolean tabletopActorAdmission(ServerPlayerEntity player,String id) {
        var review = ReviewSessions.of(player);
        if (review == null || !review.ownsActor(player) || !TabletopBridge.destination(id) || !TabletopBridge.installed() || previousBusy(player)) return false;
        Item phone = Registries.ITEM.get(Identifier.of("tabletop","phone"));
        return GameSessions.canReceiveItem(player,id,phone);
    }
    private static boolean previousBusy(ServerPlayerEntity player) {
        return switch(GameSessions.game(player)) {
            case "megawalls" -> MatchFlow.phase(player.getServer()) >= 1 && MatchFlow.phase(player.getServer()) <= 4;
            case "classic_pvp" -> ClassicDuel.running() || ClassicDuel.stage() == ClassicDuel.Stage.RESETTING;
            case "pot_pvp" -> PotDuel.running() || PotDuel.stage() == PotDuel.Stage.RESETTING;
            case "bedwars" -> BedWars.stage() == BedWars.Stage.RUNNING || BedWars.stage() == BedWars.Stage.RESETTING;
            case "hot_potato" -> HotPotato.stage() == HotPotato.Stage.RUNNING || HotPotato.stage() == HotPotato.Stage.RESULT;
            case "final_duel" -> Spleef.stage() == Spleef.Stage.RUNNING || Spleef.stage() == Spleef.Stage.RESULT || Spleef.stage() == Spleef.Stage.RESETTING;
            case "tnt_run" -> TntRun.stage() == TntRun.Stage.RUNNING || TntRun.stage() == TntRun.Stage.RESETTING;
            default -> false;
        };
    }
    private static Path ruleDirectory(MinecraftServer server) { return server.getSavePath(WorldSavePath.ROOT).resolve("map-review/rule-backups"); }
    private static Path backupPath(MinecraftServer server,String game) { return ruleDirectory(server).resolve(game+".json"); }
    private static boolean hasRuleBackups(MinecraftServer server) { return Files.exists(backupPath(server,"classic_pvp")) || Files.exists(backupPath(server,"pot_pvp")); }
    public static boolean rulesPending(MinecraftServer server) { return hasRuleBackups(server); }
    private static void backupRelay(ReviewSession review,String game,boolean original) {
        Path path = backupPath(review.server,game);
        if (Files.exists(path)) throw new IllegalStateException("存在未还原的车轮战规则备份，请先恢复。");
        var document = new JsonObject(); document.addProperty("game",game); document.addProperty("originalRelay",original); document.addProperty("session",review.nonce.toString());
        writeJson(path,document);
    }
    private static boolean setRelay(ServerPlayerEntity player,String game,boolean value) {
        return game.equals("classic_pvp") ? ClassicDuel.setRelay(player,value) : PotDuel.setRelay(player,value);
    }
    private static void writeJson(Path path,JsonObject document) {
        try {
            Files.createDirectories(path.getParent()); Path temp=path.resolveSibling(path.getFileName()+".tmp");
            Files.writeString(temp,JSON.toJson(document),StandardCharsets.UTF_8); Files.move(temp,path,StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception error) { throw new IllegalStateException("无法持久保存审查规则恢复记录。",error); }
    }
    private static void deleteBackup(MinecraftServer server,String game) {
        try { Files.deleteIfExists(backupPath(server,game)); }
        catch (Exception error) { throw new IllegalStateException("规则已恢复，但备份删除失败；保留恢复记录。",error); }
    }
    /** Called after the games' SERVER_STARTED listeners; preserve all unrelated config fields. */
    public static void recoverRules(MinecraftServer server) {
        for (String game : List.of("classic_pvp","pot_pvp")) {
            Path backup=backupPath(server,game); if (!Files.exists(backup)) continue;
            boolean waiting=game.equals("classic_pvp") ? ClassicDuel.stage()==ClassicDuel.Stage.WAITING && ClassicDuel.queueSize()==0 && !DuelArena.exists(server) :
                PotDuel.stage()==PotDuel.Stage.WAITING && PotDuel.queueSize()==0 && !PotArena.exists(server);
            if (!waiting) continue; // The core restores arenas over subsequent ticks after SERVER_STARTED.
            try {
                var saved=JSON.fromJson(Files.readString(backup,StandardCharsets.UTF_8),JsonObject.class);
                if (!game.equals(saved.get("game").getAsString())) throw new IllegalStateException("规则备份模式不匹配。");
                boolean original=saved.get("originalRelay").getAsBoolean();
                Path config=server.getSavePath(WorldSavePath.ROOT).resolve(game.equals("classic_pvp")?"classic-pvp.json":"pot-pvp.json");
                if (!Files.exists(config) && (game.equals("classic_pvp")?ClassicDuel.isRelay():PotDuel.isRelay())==original) {
                    // A crash before the first normal config save changed nothing.
                    deleteBackup(server,game); continue;
                }
                var current=JSON.fromJson(Files.readString(config,StandardCharsets.UTF_8),JsonObject.class);
                current.addProperty("relay",original); writeJson(config,current);
                Class<?> type=game.equals("classic_pvp")?ClassicDuel.class:PotDuel.class;
                // These version-pinned production loaders perform their normal config validation.
                var load=type.getDeclaredMethod("load",MinecraftServer.class); load.setAccessible(true); load.invoke(null,server);
                if ((game.equals("classic_pvp")?ClassicDuel.isRelay():PotDuel.isRelay())!=original) throw new IllegalStateException("原规则重载失败。");
                deleteBackup(server,game);
            } catch (Exception error) { throw new IllegalStateException("车轮战原规则恢复失败，备份已保留：" + backup,error); }
        }
    }
}
