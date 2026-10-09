package local.mapreview;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.BedBlock;
import net.minecraft.block.Blocks;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.enums.BedPart;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.function.MacroException;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Explicit, read-only inspection. No tick callbacks, commands, block writes or config saves. */
public final class GameInspection {
    public record Entry(String id, String name, boolean configured, List<String> issues, List<String> details) {
        public Entry { issues = List.copyOf(issues); details = List.copyOf(details); }
    }

    private static final Map<String, String> NAMES = names();
    private static final Map<String, String> FILES = Map.of(
        "classic_pvp", "classic-pvp.json", "pot_pvp", "pot-pvp.json",
        "final_duel", "spleef.json", "tnt_run", "tnt-run.json",
        "hot_potato", "hot-potato.json", "bedwars", "bedwars.json");
    private static final List<String> MW_TEAMS = List.of("red", "blue", "purple", "yellow");
    private static final List<String> BED_TEAMS = List.of("red", "blue", "green", "yellow");
    private GameInspection() { }

    /** Reads current world files once per invocation. Necessary point chunks may be loaded. */
    public static List<Entry> inspect(MinecraftServer server) {
        Context context = new Context(server);
        Map<String, Draft> entries = destinations(context);
        for (Draft entry : entries.values()) inspectEntry(context, entry);
        context.collectAreas();
        for (Draft entry : entries.values()) overlaps(context, entry);
        return entries.values().stream().map(Draft::entry).toList();
    }

    /** Checks physical points only for this entry; other areas are read solely for overlap geometry. */
    public static Entry inspect(MinecraftServer server, String id) {
        Context context = new Context(server);
        Draft entry = destinations(context).get(id);
        if (entry == null) return new Entry(id == null ? "" : id, id == null ? "未知入口" : id,
            false, List.of("当前菜单和已安装玩法中没有这个入口。"), List.of());
        inspectEntry(context, entry);
        context.collectAreas();
        overlaps(context, entry);
        return entry.entry();
    }

    public static List<String> steps(String id) {
        return switch (id == null ? "" : id) {
            case "megawalls" -> List.of("8 个角色选择职业并分到不同队伍，裁判不加入参赛名单。",
                "确认装备与准备限制，开局后检查墙倒、凋零、复活和最终死亡。",
                "完成胜负结算，再开始第二局，检查地图与玩家状态恢复。");
            case "classic_pvp", "pot_pvp" -> List.of("先核对当前 1v1 / 车轮战；车轮战由红、紫各 4 个角色参加。",
                "轮换角色检查真实攻击、物品、合法放置与破坏、死亡和下一位上场。",
                "结算并重开第二局，检查装备、队伍、原方块与背包恢复。");
            case "final_duel" -> List.of("8 个角色排队，裁判留在队列外，检查雪地与出生点。",
                "分别检查铲雪、雪球、击退、落出场地与淘汰。",
                "保留最后存活者完成结算，再检查雪地恢复和第二局。");
            case "tnt_run" -> List.of("8 个角色参加，检查出生地板、层数与头部净空。",
                "轮换角色走动，检查踩踏延迟、下层落点和最终淘汰。",
                "结束后检查每层地板恢复，再开第二局。");
            case "hot_potato" -> List.of("8 个角色排队，检查复用出生点是否挤在一起。",
                "检查持有者、传递距离与冷却、淘汰和最后两人阶段。",
                "完成结算，确认物品与效果清理后重开。");
            case "bedwars" -> List.of("8 个角色分队，4 队时可每队 2 人，裁判不入队。",
                "检查资源、商店、友伤、床两半、死亡复活与失床后淘汰。",
                "完成胜负并重开，检查床、方块、商店和玩家状态恢复。");
            case "flip7" -> List.of("8 个角色入桌；原主持也是玩家，额外裁判保持独立。",
                "按角色轮换摸牌、收手、行动牌目标、爆牌和轮末计分。",
                "完成一局，再检查下一局与原背包恢复。");
            case "clocktower" -> List.of("8 个角色入座，独立裁判作为说书人，先检查 8 个房间与门。",
                "合法配牌、逐个查看角色私密信息，再由说书人确认首夜任务。",
                "按角色提交夜间选择、提名与投票，说书人确认裁定、处决和昼夜。",
                "结算后检查复盘授权、关桌、门与背包恢复，再准备第二局。");
            case "sao_story" -> List.of("先补充并加载真实剧情函数，再绑定当前入口的 onEnter。",
                "检查进入触发、角色交互、剧情结束和重复进入；入口坐标本身不是剧情。");
            case "hub" -> List.of("从裁判位置逐项打开检查清单，再进入目标游戏。", "完成审查后停止角色工具，核对原玩家档案恢复。");
            default -> List.of("确认该自定义入口的已加载函数或 Mod 规则。", "按实际内容检查进入、交互、结束与重复使用。");
        };
    }

    private static Map<String, String> names() {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("megawalls", "超级战墙"); result.put("classic_pvp", "UHC PvP");
        result.put("pot_pvp", "Pot 药水对战"); result.put("final_duel", "决一死战");
        result.put("tnt_run", "TNT 跑酷"); result.put("hot_potato", "烫手山芋");
        result.put("bedwars", "起床战争"); result.put("flip7", "七连翻");
        result.put("clocktower", "染钟楼谜团"); result.put("sao_story", "SAO 小剧情");
        result.put("hub", "总大厅");
        return result;
    }

    private static final class Draft {
        final String id, name;
        final JsonObject destination;
        final Set<String> issues = new LinkedHashSet<>(), details = new LinkedHashSet<>();
        boolean configured;
        Draft(String id, String name, JsonObject destination) { this.id = id; this.name = name; this.destination = destination; }
        void issue(String text) { issues.add(text); }
        void detail(String text) { details.add(text); }
        Entry entry() { return new Entry(id, name, configured, new ArrayList<>(issues), new ArrayList<>(details)); }
    }

    private record Point(double x, double y, double z) {
        BlockPos block() { return BlockPos.ofFloored(x, y, z); }
        boolean finite() { return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z)
            && Math.abs(x) < 29_999_984 && Math.abs(z) < 29_999_984 && Math.abs(y) < 2048; }
        String display() { return String.format(java.util.Locale.ROOT, "%.2f, %.2f, %.2f", x, y, z); }
    }

    private record Bounds(String dimension, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        long dx() { return (long) maxX - minX + 1; }
        long dy() { return (long) maxY - minY + 1; }
        long dz() { return (long) maxZ - minZ + 1; }
        long volume() { return dx() * dy() * dz(); }
        boolean ordered() { return minX <= maxX && minY <= maxY && minZ <= maxZ; }
        boolean contains(Point point) { return point.x >= minX && point.x < maxX + 1d && point.y >= minY
            && point.y < maxY + 1d && point.z >= minZ && point.z < maxZ + 1d; }
        boolean contains(BlockPos point) { return point.getX() >= minX && point.getX() <= maxX
            && point.getY() >= minY && point.getY() <= maxY && point.getZ() >= minZ && point.getZ() <= maxZ; }
        boolean intersects(Bounds other) { return dimension.equals(other.dimension) && minX <= other.maxX
            && maxX >= other.minX && minY <= other.maxY && maxY >= other.minY && minZ <= other.maxZ && maxZ >= other.minZ; }
        String display() { return "(" + minX + "," + minY + "," + minZ + ") → (" + maxX + "," + maxY + "," + maxZ
            + ") · " + dx() + "×" + dy() + "×" + dz() + " 格"; }
    }

    private static final class Context {
        final MinecraftServer server;
        final Path root;
        final Map<String, JsonElement> files = new HashMap<>();
        final Map<String, String> fileIssues = new HashMap<>();
        final Map<String, Bounds> areas = new LinkedHashMap<>();
        final Set<String> pointChunks = new HashSet<>();
        Context(MinecraftServer server) { this.server = server; root = server.getSavePath(WorldSavePath.ROOT); }
        JsonElement read(String name) {
            if (files.containsKey(name) || fileIssues.containsKey(name)) return files.get(name);
            Path file = root.resolve(name);
            if (!Files.isRegularFile(file)) { fileIssues.put(name, "缺少 " + name + "，请先配置该内容。"); return null; }
            try { JsonElement element = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)); files.put(name, element); return element; }
            catch (IOException | RuntimeException error) { fileIssues.put(name, name + " 无法读取或 JSON 格式错误，原文件未修改。"); return null; }
        }
        JsonObject object(String name, Draft draft) {
            JsonElement element = read(name);
            if (element == null || !element.isJsonObject()) {
                if (draft != null) draft.issue(fileIssues.getOrDefault(name, name + " 顶层必须是对象。"));
                return null;
            }
            return element.getAsJsonObject();
        }
        ServerWorld world(String dimension, Draft draft) {
            Identifier identifier = Identifier.tryParse(dimension);
            ServerWorld world = identifier == null ? null : server.getWorld(RegistryKey.of(RegistryKeys.WORLD, identifier));
            if (world == null) draft.issue("维度不存在或名称无效：" + dimension);
            return world;
        }
        void loadPoint(ServerWorld world, BlockPos point) {
            String key = world.getRegistryKey().getValue() + ":" + (point.getX() >> 4) + "," + (point.getZ() >> 4);
            if (pointChunks.add(key)) world.getChunk(point);
        }
        boolean function(String value) {
            Identifier id = Identifier.tryParse(value);
            return id != null && server.getCommandFunctionManager().getFunction(id).isPresent();
        }
        void collectAreas() {
            for (Map.Entry<String, String> entry : FILES.entrySet()) {
                JsonObject config = object(entry.getValue(), null);
                if (config != null) try { Bounds bounds = cornerBounds(config); if (bounds != null) areas.put(entry.getKey(), bounds); }
                catch (RuntimeException ignored) { /* The relevant entry reports invalid fields. */ }
            }
            JsonObject config = object("map_config.json", null);
            if (config != null) try {
                Point first = flatPoint(config, "arena1"), second = flatPoint(config, "arena2");
                if (first != null && second != null && integer(config, "arena1_set", 0) == 1 && integer(config, "arena2_set", 0) == 1)
                    areas.put("megawalls", bounds("minecraft:overworld", first, second));
            } catch (RuntimeException ignored) { }
        }
    }

    private static Map<String, Draft> destinations(Context context) {
        Map<String, Draft> result = new LinkedHashMap<>();
        JsonElement menu = context.read("minigame-menu.json");
        if (menu != null && menu.isJsonArray()) {
            int index = 0;
            for (JsonElement element : menu.getAsJsonArray()) {
                index++;
                if (!element.isJsonObject()) {
                    Draft invalid = new Draft("invalid_entry_" + index, "无效菜单项 " + index, null);
                    invalid.issue("该菜单项必须是对象，原小游戏菜单可能无法加载。" );
                    result.put(invalid.id, invalid);
                    continue;
                }
                JsonObject destination = element.getAsJsonObject();
                try {
                    String id = text(destination, "id", "");
                    if (id.isBlank()) id = "invalid_entry_" + index;
                    Draft draft = new Draft(id, text(destination, "name", NAMES.getOrDefault(id, id)), destination);
                    if (result.containsKey(id)) result.get(id).issue("菜单中这个 ID 重复出现，请先消除重复入口。");
                    else result.put(id, draft);
                } catch (RuntimeException error) {
                    Draft invalid = new Draft("invalid_entry_" + index, "无效菜单项 " + index, null);
                    invalid.issue("该菜单项的 ID 或名称格式错误，原小游戏菜单可能无法加载。" );
                    result.put(invalid.id, invalid);
                }
            }
        }
        for (Map.Entry<String, String> entry : NAMES.entrySet()) {
            boolean known = entry.getKey().equals("hub") || entry.getKey().equals("sao_story") || installed(owner(entry.getKey()));
            if (known) result.computeIfAbsent(entry.getKey(), id -> new Draft(id, entry.getValue(), null));
        }
        if (menu == null || !menu.isJsonArray()) for (Draft draft : result.values())
            draft.issue(context.fileIssues.getOrDefault("minigame-menu.json", "minigame-menu.json 顶层必须是入口数组。"));
        return result;
    }

    private static void inspectEntry(Context context, Draft draft) {
        try {
            inspectDestination(context, draft);
            String owner = owner(draft.id);
            if (!owner.isEmpty()) {
                if (!installed(owner)) draft.issue("缺少 " + owner + " Mod；入口文字不能代替真实玩法。");
                else draft.detail("玩法执行：" + (owner.equals("tabletop") ? "桌间 Mod" : "小游戏 Mod") + "；裁判不占参赛角色。" );
            }
            switch (draft.id) {
                case "megawalls" -> megaWalls(context, draft);
                case "classic_pvp", "pot_pvp" -> duel(context, draft);
                case "final_duel", "tnt_run", "hot_potato" -> elimination(context, draft);
                case "bedwars" -> bedWars(context, draft);
                case "clocktower" -> clock(context, draft);
                case "flip7" -> flip(context, draft);
                case "sao_story" -> story(context, draft);
                case "hub" -> {
                    draft.detail("审查工具：8 名角色 + 1 位独立裁判；钟楼时裁判可做说书人。" );
                    draft.detail("当前启用数据包：" + String.join("、", context.server.getDataPackManager().getEnabledIds()));
                    draft.detail("检查按需读取配置与点位，完整玩法需按各项步骤实际操作。" );
                }
                default -> {
                    draft.detail("自定义入口；只检查已保存地点和绑定函数，玩法由原内容负责。" );
                    String function = enterFunction(draft.destination);
                    if (function.isBlank()) draft.issue("没有已绑定的进入函数；自定义玩法及 8 人规则待确认。" );
                    draft.detail("8 人 + 裁判的支持情况需按该内容实际规则核对。" );
                }
            }
        } catch (RuntimeException error) {
            draft.issue("配置字段类型或数值不合法，无法完成本项检查；原配置未修改。" );
        }
    }

    private static void inspectDestination(Context context, Draft draft) {
        if (draft.destination == null) { draft.issue("当前菜单缺少此入口地点，请先设置进入位置。" ); return; }
        JsonObject destination = draft.destination;
        Point point = point(destination);
        draft.configured = bool(destination, "configured", false) && point != null && point.finite();
        double yaw = number(destination, "yaw", 0), pitch = number(destination, "pitch", 0);
        if (!Double.isFinite(yaw) || !Double.isFinite(pitch) || Math.abs(pitch) > 90) {
            draft.issue("入口朝向无效，原小游戏菜单可能无法加载。" ); draft.configured = false;
        }
        if (!draft.id.matches("[a-z0-9_-]{1,32}") || draft.id.equals("_current")) draft.issue("入口 ID 不符合原菜单规则。" );
        if (draft.name.isBlank() || draft.name.length() > 48) draft.issue("入口名称为空或超过 48 个字符。" );
        String icon = text(destination, "icon", "");
        Identifier iconId = Identifier.tryParse(icon);
        if (iconId == null || !Registries.ITEM.containsId(iconId) || iconId.equals(Identifier.ofVanilla("air")))
            draft.issue("入口图标不是已注册物品，原小游戏菜单可能无法加载。" );
        if (!draft.configured) draft.issue("入口地点尚未设置或坐标无效。" );
        String dimension = text(destination, "dimension", "minecraft:overworld");
        ServerWorld world = context.world(dimension, draft);
        if (point != null && point.finite()) {
            draft.detail("进入地点：" + dimension + " · " + point.display());
            if (world != null) safePoint(context, draft, world, point, "入口", true);
        }
        String function = enterFunction(destination);
        if (!function.isBlank()) {
            draft.detail("进入函数：" + function);
            if (!context.function(function)) draft.issue("进入函数尚未加载或名称无效：" + function);
            else {
                try {
                    context.server.getCommandFunctionManager().getFunction(Identifier.of(function)).orElseThrow()
                        .withMacroReplaced(null, context.server.getCommandManager().getDispatcher());
                } catch (MacroException error) { draft.issue("进入函数需要额外参数，请绑定可直接执行的无参数函数。" ); }
            }
            if (!Set.of("sao_story", "flip7", "clocktower").contains(draft.id))
                draft.issue("原小游戏菜单仅允许剧情、七连翻、钟楼绑定进入函数；此 ID 的绑定不能直接生效。" );
        }
    }

    private static String owner(String id) {
        return switch (id) {
            case "megawalls", "classic_pvp", "pot_pvp", "final_duel", "tnt_run", "hot_potato", "bedwars" -> "megawalls";
            case "clocktower", "flip7" -> "tabletop";
            default -> "";
        };
    }
    private static boolean installed(String id) { return !id.isEmpty() && FabricLoader.getInstance().isModLoaded(id); }

    private static void story(Context context, Draft draft) {
        draft.detail("剧情入口本身只负责传送，不会生成剧情或自动创建 8 个角色。" );
        if (enterFunction(draft.destination).isBlank()) draft.issue("待补：SAO 剧情没有绑定 onEnter 启动函数。" );
        if (!context.function("absurd_story:solo_on")) draft.detail("示例 absurd_story:solo_on 当前未加载。" );
    }

    private static void duel(Context context, Draft draft) {
        JsonObject config = context.object(FILES.get(draft.id), draft);
        if (config == null) return;
        boolean relay = bool(config, "relay", false);
        draft.detail(relay ? "当前 4v4 车轮战：8 名角色，红、紫各 4 人。" : "当前 1v1：2 人；8 名角色可轮流检查，8 人参赛需要选择车轮战。" );
        if (!relay) draft.issue("当前人数规则为 1v1，8 个角色不能同时排队开局。" );
        if (!installed("goldenagecombat")) draft.issue("缺少 Golden Age Combat，原游戏的旧版战斗开局检查会拒绝。" );
        else legacyCombat(draft);
        Bounds area = arena(context, draft, config);
        if (area == null) return;
        if (area.dx() > 1024 || area.dz() > 1024 || area.dy() > 384) draft.issue("选区超出原游戏上限：水平单边 1024，高度 384 格。" );
        ServerWorld world = context.world(area.dimension, draft);
        Point first = point(object(config, "spawn1")), second = point(object(config, "spawn2"));
        for (int index = 1; index <= 2; index++) {
            Point spawn = index == 1 ? first : second;
            inspectSpawn(context, draft, world, area, spawn, "出生 " + index, true);
            if (spawn != null && (!area.contains(spawn.block().down()) || !area.contains(spawn.block().up())))
                draft.issue("出生 " + index + " 的脚下或头部方块超出选区。" );
        }
        if (first != null && second != null && first.block().getSquaredDistance(second.block()) < 16)
            draft.issue("两个出生点至少相距 4 格。" );
        draft.detail("原规则会恢复对局触碰的方块；检查结束后仍需实际重开第二局。" );
    }

    private static void elimination(Context context, Draft draft) {
        JsonObject config = context.object(FILES.get(draft.id), draft);
        if (config == null) return;
        int minimum = integer(config, "minPlayers", 2), maximum = integer(config, "maxPlayers", 10);
        JsonArray spawns = array(config, "spawns");
        int effective = draft.id.equals("final_duel") ? Math.min(10, Math.min(maximum, spawns.size())) : maximum;
        draft.detail("人数：" + minimum + "–" + effective + "；已保存 " + spawns.size() + " 个出生点。" );
        if (minimum > 8 || effective < 8) draft.issue("当前人数/出生容量不支持 8 名角色同时参加。" );
        if (minimum < 2 || maximum < minimum || maximum > 32 || spawns.size() > 32)
            draft.issue("人数或出生点数量超出原游戏配置范围。" );
        if (spawns.size() < minimum) draft.issue("出生点少于最少参赛人数。" );
        if (!draft.id.equals("final_duel") && spawns.size() < 8)
            draft.issue("8 名角色将循环复用出生点，可能挤在一起；当前点位只有 " + spawns.size() + " 个。" );
        Bounds area = arena(context, draft, config);
        if (area == null) return;
        ServerWorld world = context.world(area.dimension, draft);
        Set<Integer> layers = new LinkedHashSet<>();
        if (draft.id.equals("final_duel")) {
            if (area.dx() > 256 || area.dy() > 64 || area.dz() > 256 || area.volume() > 1_048_576)
                draft.issue("雪地选区超过原规则上限：256×64×256 内且总体积不超过 1048576 格。" );
            double lose = number(config, "loseY", area.minY - .5);
            if (!Double.isFinite(lose) || lose > area.minY) draft.issue("淘汰高度应不高于选区最低层。" );
            draft.detail("只核对出生点雪地；完整雪块备份数量由原游戏开局处理，本检查不扫描整块场地。" );
        } else if (draft.id.equals("tnt_run")) {
            for (JsonElement value : array(config, "layers")) {
                int y = value.getAsInt();
                if (!layers.add(y)) draft.issue("地板层重复：Y " + y);
                if (y < area.minY || y >= area.maxY) draft.issue("地板层 Y " + y + " 越界或没有头部高度。" );
            }
            draft.detail("地板层：" + layers);
            if (layers.isEmpty()) draft.issue("尚未设置地板层。" );
            if (area.dx() > 256 || area.dz() > 256 || area.dx() * area.dz() * layers.size() > 65536)
                draft.issue("地板层总面积超过 65536 格或水平单边超过 256 格。" );
        }
        Set<BlockPos> used = new HashSet<>();
        for (int index = 0; index < Math.min(32, spawns.size()); index++) {
            Point spawn = point(spawns.get(index).isJsonObject() ? spawns.get(index).getAsJsonObject() : null);
            String label = "出生 " + (index + 1);
            inspectSpawn(context, draft, world, area, spawn, label, true);
            if (spawn == null || !spawn.finite() || world == null) continue;
            if (!used.add(spawn.block())) draft.issue(label + " 与另一个出生点位于同一格。" );
            if (world.isOutOfHeightLimit(spawn.block())) continue;
            context.loadPoint(world, spawn.block());
            if (draft.id.equals("final_duel")) {
                boolean support = snow(world, spawn.block().down()) && area.contains(spawn.block().down())
                    || snow(world, spawn.block()) && area.contains(spawn.block());
                if (!support) draft.issue(label + " 不在选中的雪块/雪层上方。" );
                if (spawn.y <= number(config, "loseY", area.minY - .5)) draft.issue(label + " 已低于淘汰高度。" );
            } else if (draft.id.equals("tnt_run")) {
                BlockPos floor = spawn.block().down();
                if (!layers.contains(floor.getY()) || !world.getBlockState(floor).isFullCube(world, floor))
                    draft.issue(label + " 脚下不是已设置地板层的完整方块。" );
            }
        }
    }

    private static void megaWalls(Context context, Draft draft) {
        JsonObject config = context.object("map_config.json", draft);
        if (config == null) return;
        int teams = integer(config, "teams", 2) == 4 ? 4 : 2;
        int walls = integer(config, "wall_count", 1) == 4 ? teams : 1;
        draft.detail("当前 " + teams + " 队；8 名角色可每队 " + (8 / teams) + " 人，至少两队有人并准备。" );
        if (context.server.getDataPackManager().getEnabledIds().contains("file/megawalls"))
            draft.detail("当前已启用世界配套 file/megawalls 数据包。" );
        else draft.detail("file/megawalls 未启用；实际配套内容以已加载函数与原游戏开局检查为准。" );
        if (!context.function("megawalls:init")) draft.issue("配套 megawalls:init 函数未加载。" );
        List<String> required = new ArrayList<>(List.of("center", "lobby"));
        for (String team : MW_TEAMS.subList(0, teams)) { required.add(team + "spawn"); required.add(team + "wither"); }
        for (int index = 1; index <= walls * 2; index++) required.add("wall" + index);
        boolean restore = integer(config, "restore_bounds_required", 0) == 1;
        if (restore) { required.add("arena1"); required.add("arena2"); }
        ServerWorld world = context.server.getOverworld();
        for (String key : required) {
            Point point = flatPoint(config, key);
            if (integer(config, key + "_set", 0) != 1 || point == null) draft.issue("缺少地图点位：" + key);
            else safePoint(context, draft, world, point, key, key.endsWith("spawn") || key.equals("lobby"));
        }
        for (String team : MW_TEAMS.subList(0, teams)) {
            Point spawn = flatPoint(config, team + "spawn"), wither = flatPoint(config, team + "wither");
            if (spawn != null && wither != null && spawn.block().getSquaredDistance(wither.block()) < 9)
                draft.issue(team + " 出生点与凋零不足 3 格。" );
        }
        for (int index = 0; index < walls; index++) {
            Point first = flatPoint(config, "wall" + (index * 2 + 1)), second = flatPoint(config, "wall" + (index * 2 + 2));
            if (first != null && second != null && bounds("minecraft:overworld", first, second).volume() > 262144)
                draft.issue("第 " + (index + 1) + " 面战墙体积超过 262144 格。" );
        }
        Point first = flatPoint(config, "arena1"), second = flatPoint(config, "arena2");
        if (restore && first != null && second != null) {
            Bounds area = bounds("minecraft:overworld", first, second);
            context.areas.put(draft.id, area); draft.detail("战场边界：" + area.display());
            int margin = integer(config, "journal_margin", 16); if (margin == 0) margin = 16;
            if (margin < 0 || margin > 64 || area.dx() - 1 + 2L * margin > 1024 || area.dz() - 1 + 2L * margin > 1024)
                draft.issue("战场加恢复余量后的水平单边超过 1024 格，或余量参数无效。" );
            for (String setting : config.keySet()) {
                if (!setting.endsWith("_set") || integer(config, setting, 0) != 1) continue;
                String key = setting.substring(0, setting.length() - 4);
                if (key.equals("lobby") || key.equals("training") || key.startsWith("arena")) continue;
                Point point = flatPoint(config, key);
                if (point != null && (point.x < area.minX || point.x >= area.maxX + 1d || point.z < area.minZ || point.z >= area.maxZ + 1d))
                    draft.issue("战场水平边界不包含 " + key);
            }
        }
        draft.detail("狼人是现有职业；未据此认定地图另有独立狼人杀。" );
    }

    private static void bedWars(Context context, Draft draft) {
        JsonObject config = context.object("bedwars.json", draft);
        if (config == null) return;
        int teams = integer(config, "teams", 4), perTeam = integer(config, "maxPerTeam", 4);
        int minimum = integer(config, "minPlayers", 2);
        draft.detail("当前 " + teams + " 队，每队最多 " + perTeam + " 人；8 名角色须分到至少两队。" );
        if ((teams != 2 && teams != 4) || perTeam < 1 || perTeam > 8 || minimum < 2 || minimum > teams * perTeam)
            draft.issue("队伍或人数参数超出原规则。" );
        if (minimum > 8 || teams * perTeam < 8) draft.issue("当前人数配置不支持 8 名角色。" );
        Bounds area = arena(context, draft, config);
        if (area == null) return;
        ServerWorld world = context.world(area.dimension, draft);
        JsonObject bases = object(config, "bases");
        Set<BlockPos> beds = new HashSet<>();
        for (String team : BED_TEAMS.subList(0, Math.min(4, Math.max(0, teams)))) {
            JsonObject base = object(bases, team);
            if (base == null) { draft.issue(team + " 队没有基地配置。" ); continue; }
            inspectSpawn(context, draft, world, area, point(object(base, "spawn")), team + " 出生", true);
            Point shop = point(object(base, "shop")); inspectSpawn(context, draft, world, area, shop, team + " 商店", false);
            Point bed = point(object(base, "bed")); inspectSpawn(context, draft, world, area, bed, team + " 床", false);
            if (bed != null && bed.finite() && world != null && !world.isOutOfHeightLimit(bed.block())) {
                context.loadPoint(world, bed.block());
                var state = world.getBlockState(bed.block());
                if (!(state.getBlock() instanceof BedBlock)) draft.issue(team + " 床点位没有床方块。" );
                else {
                    BlockPos foot = state.get(BedBlock.PART) == BedPart.FOOT ? bed.block() : bed.block().offset(state.get(BedBlock.FACING).getOpposite());
                    BlockPos head = foot.offset(state.get(BedBlock.FACING));
                    context.loadPoint(world, foot); context.loadPoint(world, head);
                    var f = world.getBlockState(foot); var h = world.getBlockState(head);
                    if (!f.isOf(state.getBlock()) || !h.isOf(state.getBlock()) || !f.contains(BedBlock.PART) || !h.contains(BedBlock.PART)
                        || f.get(BedBlock.PART) != BedPart.FOOT || h.get(BedBlock.PART) != BedPart.HEAD || f.get(BedBlock.FACING) != h.get(BedBlock.FACING))
                        draft.issue(team + " 床的两半不完整或朝向不一致。" );
                    if (!beds.add(foot)) draft.issue(team + " 与其它队共用同一张床。" );
                    if (!area.contains(head) || !area.contains(foot)) draft.issue(team + " 床的另一半超出选区。" );
                }
            }
        }
        Set<String> resources = new HashSet<>();
        JsonArray generators = array(config, "generators");
        if (generators.size() > 64) draft.issue("资源点超过原游戏的 64 个上限。" );
        for (int index = 0; index < Math.min(64, generators.size()); index++) {
            JsonObject generator = generators.get(index).isJsonObject() ? generators.get(index).getAsJsonObject() : null;
            String type = text(generator, "type", ""); resources.add(type);
            Point point = point(object(generator, "point"));
            if (point == null || !point.finite() || !area.contains(point.block())) draft.issue("资源点 " + (index + 1) + " 缺失或越界。" );
        }
        if (!resources.containsAll(Set.of("bronze", "iron", "gold"))) draft.issue("需各有至少一个铜、铁、金资源点。" );
        JsonObject shop = context.object("bedwars-shop.json", draft);
        if (shop != null && array(shop, "offers").isEmpty()) draft.issue("商店没有配置商品。" );
    }

    private static void flip(Context context, Draft draft) {
        draft.detail("七连翻支持 3–18 人；8 个角色可以入桌，原主持也参与牌局。" );
        draft.detail("独立裁判不占玩家牌局，检查时切换到原主持角色操作开局。" );
        JsonObject config = context.object("tabletop/flip-tables.json", draft);
        if (config == null) return;
        JsonObject stations = object(config, "stations"), seats = object(config, "seats");
        int count = stations == null ? 0 : stations.size();
        int seatCount = 0;
        if (seats != null) for (JsonElement value : seats.asMap().values()) if (value.isJsonObject()) seatCount += value.getAsJsonObject().size();
        draft.detail("保存的桌边操作点 " + count + " 个，铺牌席位 " + seatCount + " 个。" );
        if (count == 0) draft.issue("没有保存的摸牌/收手操作点；需实际准备牌桌并放置道具。" );
        if (seatCount < 8) draft.detail("未固定 8 个铺牌席位；原游戏可以在首次行动时记住位置。" );
        Map<String, Integer> tables = new LinkedHashMap<>();
        Map<String, Set<String>> controls = new HashMap<>();
        if (stations != null) for (Map.Entry<String, JsonElement> item : stations.entrySet()) {
            if (!item.getValue().isJsonObject()) { draft.issue("有无效的桌边操作点记录。" ); continue; }
            JsonObject station = item.getValue().getAsJsonObject();
            String table = text(station, "table", "");
            if (table.isBlank()) draft.issue("桌边操作点缺少对应桌号：" + item.getKey());
            else tables.merge(table, 1, Integer::sum);
            Point position = point(station);
            ServerWorld world = context.world(text(station, "dimension", "minecraft:overworld"), draft);
            safePoint(context, draft, world, position, "桌边操作点 " + item.getKey(), false);
            if (position != null && position.finite() && world != null && !world.isOutOfHeightLimit(position.block())
                && world.getWorldBorder().contains(position.block())) {
                context.loadPoint(world, position.block());
                String control = Registries.BLOCK.getId(world.getBlockState(position.block()).getBlock()).toString();
                if (!Set.of("tabletop:flip_draw", "tabletop:flip_stay").contains(control))
                    draft.issue("桌边操作点 " + item.getKey() + " 的摸牌/收手道具已不存在。" );
                else controls.computeIfAbsent(table, ignored -> new HashSet<>()).add(control);
            }
        }
        for (Map.Entry<String, Integer> table : tables.entrySet()) {
            JsonObject tableSeats = object(seats, table.getKey());
            draft.detail("牌桌 " + table.getKey() + " · " + table.getValue() + " 个操作点 / " + (tableSeats == null ? 0 : tableSeats.size()) + " 个固定铺牌点。" );
            if (!controls.getOrDefault(table.getKey(), Set.of()).containsAll(Set.of("tabletop:flip_draw", "tabletop:flip_stay")))
                draft.issue("牌桌 " + table.getKey() + " 缺少实际摸牌或收手道具。" );
        }
    }

    private static void clock(Context context, Draft draft) {
        draft.detail("默认 8 名角色 + 1 位说书人；原游戏支持 5–12 名角色，说书人不占角色席位。" );
        draft.detail("8 人基础配比：5 镇民、1 外来者、1 爪牙、1 恶魔，特殊角色沿原规则调整。" );
        JsonObject fixed = context.object("tabletop/fixed-clock.json", draft);
        JsonObject arenas = context.object("tabletop/clock-arenas.json", draft);
        if (fixed == null || arenas == null) return;
        String dimension = text(fixed, "dimension", "minecraft:overworld");
        Point anchor = point(object(fixed, "arenaAnchor"));
        if (anchor == null || !anchor.finite()) { draft.issue("固定钟楼缺少有效场地锚点。" ); return; }
        String key = dimension + ":" + anchor.block().getX() + "," + anchor.block().getY() + "," + anchor.block().getZ();
        JsonObject layout = object(arenas, key);
        if (layout == null) { draft.issue("固定钟楼锚点没有对应场地配置：" + key); return; }
        if (!dimension.equals(text(layout, "dimension", ""))) draft.issue("固定钟楼与场地配置的维度不一致。" );
        if (!bool(layout, "enabled", false)) draft.issue("钟楼场地尚未启用。" );
        draft.detail("固定场地：" + key);
        if (text(fixed, "hostUUID", "").isBlank()) draft.issue("固定场地没有保存说书人身份。" );
        Point grimoire = point(object(fixed, "grimoirePos"));
        if (grimoire == null) draft.issue("尚未绑定固定魔典讲台。" );
        else draft.detail("固定魔典：" + grimoire.display());
        ServerWorld world = context.world(dimension, draft);
        if (grimoire != null && grimoire.finite() && world != null && !world.isOutOfHeightLimit(grimoire.block())
            && world.getWorldBorder().contains(grimoire.block())) {
            context.loadPoint(world, grimoire.block());
            if (!Registries.BLOCK.getId(world.getBlockState(grimoire.block()).getBlock()).equals(Identifier.of("tabletop", "grimoire_lectern")))
                draft.issue("固定魔典坐标没有实际的桌间魔典讲台。" );
        }
        Point center = point(object(layout, "center")), jail = point(object(layout, "jail"));
        safePoint(context, draft, world, center, "说书人位置", true);
        safePoint(context, draft, world, jail, "处决牢笼", true);
        JsonObject seats = object(layout, "seats"), rooms = object(layout, "rooms");
        draft.detail("发言席 " + (seats == null ? 0 : seats.size()) + " 个；房间 " + (rooms == null ? 0 : rooms.size()) + " 个。" );
        Map<Integer, Bounds> ranges = new LinkedHashMap<>();
        Map<BlockPos, Integer> doors = new HashMap<>(), seatBlocks = new HashMap<>();
        for (int number = 1; number <= 8; number++) {
            Point seat = point(object(seats, Integer.toString(number)));
            safePoint(context, draft, world, seat, number + " 号发言席", true);
            if (seat != null && seat.finite()) {
                Integer prior = seatBlocks.putIfAbsent(seat.block(), number);
                if (prior != null) draft.issue(number + " 号与 " + prior + " 号发言席重叠。" );
            }
            JsonObject room = object(rooms, Integer.toString(number));
            if (room == null) { draft.issue("缺少 " + number + " 号房间。" ); continue; }
            Point spawn = point(object(room, "spawn"));
            safePoint(context, draft, world, spawn, number + " 号房间出生点", true);
            Bounds range = roomBounds(object(room, "bounds"), dimension);
            Point door = point(object(room, "door"));
            if (range == null) draft.issue(number + " 号房间缺少范围。" );
            else {
                ranges.put(number, range);
                if (!range.ordered() || range.volume() < 4 || range.volume() > 1_000_000) draft.issue(number + " 号房间范围不合法。" );
                if (spawn != null && (!range.contains(spawn) || spawn.y + 1.8 >= range.maxY + 1d))
                    draft.issue(number + " 号房间出生点或头部不在保存范围内。" );
                if (center != null && range.contains(center) || jail != null && range.contains(jail))
                    draft.issue("说书人位置或处决牢笼落在 " + number + " 号房间内。" );
                if (world != null) worldBounds(draft, world, range, number + " 号房间范围");
            }
            if (door == null || !door.finite()) draft.issue(number + " 号房间缺少有效房门。" );
            else {
                BlockPos position = door.block();
                Integer previous = doors.putIfAbsent(position, number);
                if (previous != null) draft.issue(number + " 号与 " + previous + " 号房间共用同一房门。" );
                if (range != null && !nearDoor(range, position)) draft.issue(number + " 号房门不在房间边界旁。" );
                if (world != null && !world.isOutOfHeightLimit(position) && world.getWorldBorder().contains(position)) {
                    context.loadPoint(world, position);
                    var lower = world.getBlockState(position); var upper = world.getBlockState(position.up());
                    if (!(lower.getBlock() instanceof DoorBlock)) draft.issue(number + " 号房门坐标没有实际门方块。" );
                    else if (!upper.isOf(lower.getBlock()) || !upper.contains(DoorBlock.HALF)
                        || lower.get(DoorBlock.HALF) != DoubleBlockHalf.LOWER || upper.get(DoorBlock.HALF) != DoubleBlockHalf.UPPER)
                        draft.issue(number + " 号房门必须保存完整的上下两半，坐标指向下半格。" );
                } else if (world != null) draft.issue(number + " 号房门超出世界范围。" );
            }
        }
        for (Map.Entry<Integer, Bounds> first : ranges.entrySet()) for (Map.Entry<Integer, Bounds> second : ranges.entrySet())
            if (first.getKey() < second.getKey() && first.getValue().intersects(second.getValue()))
                draft.issue(first.getKey() + " 号与 " + second.getKey() + " 号房间范围重叠。" );
        draft.detail("enabled 保存值不代表开局检查已通过；以上按当前 8 席重新核对。" );
    }

    private static boolean nearDoor(Bounds bounds, BlockPos position) {
        return bounds.contains(position) || bounds.contains(position.north()) || bounds.contains(position.south())
            || bounds.contains(position.east()) || bounds.contains(position.west());
    }

    private static void legacyCombat(Draft draft) {
        try {
            Object holder = Class.forName("fuzs.goldenagecombat.GoldenAgeCombat").getField("CONFIG").get(null);
            var get = Class.forName("fuzs.puzzleslib.api.config.v3.ConfigHolder").getMethod("get", Class.class);
            Class<?> type = Class.forName("fuzs.goldenagecombat.config.ServerConfig");
            Object config = get.invoke(holder, type);
            for (String field : List.of("removeAttackCooldown", "weakAttacksKnockBackPlayers", "fishingRodKnockback"))
                if (!type.getField(field).getBoolean(config)) draft.issue("旧版战斗开局要求未满足：" + field + " 需开启。" );
            type = Class.forName("fuzs.goldenagecombat.config.CommonConfig"); config = get.invoke(holder, type);
            if (!type.getField("oldAttackDamage").getBoolean(config)) draft.issue("旧版武器伤害未开启，原游戏会拒绝开局。" );
        } catch (ReflectiveOperationException | LinkageError error) {
            draft.issue("无法读取配套旧版战斗设置，原游戏的兼容检查可能拒绝开局。" );
        }
    }

    private static Bounds arena(Context context, Draft draft, JsonObject config) {
        Bounds area = cornerBounds(config);
        if (area == null) { draft.issue("尚未设置有效的 arena1 / arena2 选区。" ); return null; }
        context.areas.put(draft.id, area); draft.detail("选区：" + area.display());
        ServerWorld world = context.world(area.dimension, draft);
        if (world != null) worldBounds(draft, world, area, "选区");
        return area;
    }

    private static void worldBounds(Draft draft, ServerWorld world, Bounds bounds, String label) {
        BlockPos first = new BlockPos(bounds.minX, bounds.minY, bounds.minZ), second = new BlockPos(bounds.maxX, bounds.maxY, bounds.maxZ);
        if (!bounds.ordered() || world.isOutOfHeightLimit(first) || world.isOutOfHeightLimit(second)
            || !world.getWorldBorder().contains(first) || !world.getWorldBorder().contains(second))
            draft.issue(label + " 超出世界高度或边界。" );
    }

    private static void inspectSpawn(Context context, Draft draft, ServerWorld world, Bounds area, Point point, String label, boolean physical) {
        if (point != null && point.finite() && !area.contains(point.block())) draft.issue(label + " 不在选区内。" );
        safePoint(context, draft, world, point, label, physical);
    }

    private static void safePoint(Context context, Draft draft, ServerWorld world, Point point, String label, boolean physical) {
        if (point == null || !point.finite()) { draft.issue(label + " 未设置或坐标无效。" ); return; }
        if (world == null) return;
        if (world.isOutOfHeightLimit(point.block()) || point.y + 1.8 >= world.getTopY() || !world.getWorldBorder().contains(point.block())) {
            draft.issue(label + " 超出世界高度或边界。" ); return;
        }
        if (!physical) return;
        BlockPos low = BlockPos.ofFloored(point.x - .3, point.y - .06, point.z - .3);
        BlockPos high = BlockPos.ofFloored(point.x + .3, point.y + 1.8, point.z + .3);
        for (int cx = low.getX() >> 4; cx <= high.getX() >> 4; cx++) for (int cz = low.getZ() >> 4; cz <= high.getZ() >> 4; cz++)
            context.loadPoint(world, new BlockPos(cx << 4, low.getY(), cz << 4));
        Box body = new Box(point.x - .29, point.y + .01, point.z - .29, point.x + .29, point.y + 1.8, point.z + .29);
        Box support = new Box(point.x - .29, point.y - .06, point.z - .29, point.x + .29, point.y + .001, point.z + .29);
        if (world.getBlockCollisions(null, body).iterator().hasNext()) draft.issue(label + " 脚部/头部空间有方块阻挡。" );
        if (!world.getBlockCollisions(null, support).iterator().hasNext()) draft.issue(label + " 脚下没有支撑地面。" );
    }

    private static void overlaps(Context context, Draft draft) {
        Bounds current = context.areas.get(draft.id);
        if (current == null) return;
        for (Map.Entry<String, Bounds> other : context.areas.entrySet()) if (!other.getKey().equals(draft.id) && current.intersects(other.getValue()))
            draft.issue("选区与「" + NAMES.getOrDefault(other.getKey(), other.getKey()) + "」重叠；同时开局可能互相阻止，需确认是否有意共用。" );
    }

    private static Bounds cornerBounds(JsonObject config) {
        Point first = point(object(config, "corner1")), second = point(object(config, "corner2"));
        return first == null || second == null || !first.finite() || !second.finite() ? null
            : bounds(text(config, "dimension", "minecraft:overworld"), first, second);
    }
    private static Bounds bounds(String dimension, Point first, Point second) {
        return new Bounds(dimension, MathHelper.floor(Math.min(first.x, second.x)), MathHelper.floor(Math.min(first.y, second.y)),
            MathHelper.floor(Math.min(first.z, second.z)), MathHelper.floor(Math.max(first.x, second.x)),
            MathHelper.floor(Math.max(first.y, second.y)), MathHelper.floor(Math.max(first.z, second.z)));
    }
    private static Bounds roomBounds(JsonObject object, String dimension) {
        if (object == null) return null;
        for (String key : List.of("minX", "minY", "minZ", "maxX", "maxY", "maxZ")) if (!has(object, key)) return null;
        return new Bounds(dimension, object.get("minX").getAsInt(), object.get("minY").getAsInt(), object.get("minZ").getAsInt(),
            object.get("maxX").getAsInt(), object.get("maxY").getAsInt(), object.get("maxZ").getAsInt());
    }
    private static Point flatPoint(JsonObject object, String name) {
        if (!has(object, name + "_x") || !has(object, name + "_y") || !has(object, name + "_z")) return null;
        return new Point(object.get(name + "_x").getAsDouble(), object.get(name + "_y").getAsDouble(), object.get(name + "_z").getAsDouble());
    }
    private static Point point(JsonObject object) {
        if (object == null || !has(object, "x") || !has(object, "y") || !has(object, "z")) return null;
        return new Point(object.get("x").getAsDouble(), object.get("y").getAsDouble(), object.get("z").getAsDouble());
    }
    private static boolean snow(ServerWorld world, BlockPos point) { var state = world.getBlockState(point); return state.isOf(Blocks.SNOW_BLOCK) || state.isOf(Blocks.SNOW); }
    private static boolean has(JsonObject object, String key) { return object != null && object.has(key) && !object.get(key).isJsonNull(); }
    private static JsonObject object(JsonObject object, String key) { return has(object, key) && object.get(key).isJsonObject() ? object.getAsJsonObject(key) : null; }
    private static JsonArray array(JsonObject object, String key) { return has(object, key) && object.get(key).isJsonArray() ? object.getAsJsonArray(key) : new JsonArray(); }
    private static String text(JsonObject object, String key, String fallback) { return has(object, key) ? object.get(key).getAsString() : fallback; }
    private static int integer(JsonObject object, String key, int fallback) { return has(object, key) ? object.get(key).getAsInt() : fallback; }
    private static double number(JsonObject object, String key, double fallback) { return has(object, key) ? object.get(key).getAsDouble() : fallback; }
    private static boolean bool(JsonObject object, String key, boolean fallback) { return has(object, key) ? object.get(key).getAsBoolean() : fallback; }
    private static String enterFunction(JsonObject object) { return text(object, "onEnter", "").trim(); }
}
