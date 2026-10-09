package local.mapreview;

import com.mojang.authlib.GameProfile;
import com.megawalls.minigame.PrivateSidebar;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.impl.networking.RegistrationPayload;
import local.mapreview.mixin.EntityTrackerInvoker;
import local.mapreview.mixin.PlayerManagerAccessor;
import local.mapreview.mixin.ReviewMWDataAccessor;
import local.mapreview.mixin.ReviewClassSkinsAccessor;
import local.mapreview.mixin.ServerChunkLoadingManagerAccessor;
import local.mapreview.mixin.ServerPlayNetworkHandlerAccessor;
import net.minecraft.entity.Entity;
import net.minecraft.network.packet.s2c.play.DifficultyS2CPacket;
import net.minecraft.network.packet.s2c.common.CustomPayloadS2CPacket;
import net.minecraft.network.packet.s2c.play.ExperienceBarUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityAttributesS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityTrackerUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.GameJoinS2CPacket;
import net.minecraft.network.packet.s2c.play.HealthUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerListS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerRespawnS2CPacket;
import net.minecraft.server.network.ChunkDataSender;
import net.minecraft.server.network.ChunkFilter;
import net.minecraft.server.network.ConnectedClientData;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.GameMode;
import net.minecraft.world.GameRules;
import net.minecraft.world.chunk.WorldChunk;

/** Eight transient server players; exactly one entity owns the human connection. */
public final class ReviewActors {
    private static final Map<ReviewSession, Integer> RESYNC = new IdentityHashMap<>();
    private ReviewActors() { }

    private static void mainThread(ReviewSession s) {
        if (!s.server.isOnThread()) throw new IllegalStateException("角色操作必须在服务端线程执行。");
    }

    /** Dummy clients consume no chunk batches; the controlled entity uses the real sender. */
    private static final class DummyChunks extends ChunkDataSender {
        DummyChunks() { super(false); }
        @Override public void add(WorldChunk chunk) { }
        @Override public void unload(ServerPlayerEntity player, ChunkPos pos) { }
        @Override public void sendChunkBatches(ServerPlayerEntity player) { }
        @Override public boolean isInNextBatch(long pos) { return false; }
    }

    private static ServerPlayNetworkHandler dummy(ReviewSession s, ServerPlayerEntity player) {
        var previous = player.networkHandler;
        var result = new ServerPlayNetworkHandler(s.server, new DummyReviewConnection(), player,
            new ConnectedClientData(player.getGameProfile(), 0, player.getClientOptions(), false));
        // The vanilla constructor binds itself to the player; preparation must not steal TCP ownership.
        player.networkHandler = previous;
        ((ServerPlayNetworkHandlerAccessor) result).mapreview$chunkDataSender(new DummyChunks());
        s.dummyHandlers.put(player.getUuid(), result);
        return result;
    }

    public static void create(ReviewSession s) {
        mainThread(s);
        if (!s.actors.isEmpty()) throw new IllegalStateException("测试角色已创建。");
        dummy(s, s.judge);
        var manager = (PlayerManagerAccessor) s.server.getPlayerManager();
        for (int seat = 1; seat <= 8; seat++) {
            UUID id = UUID.nameUUIDFromBytes(("mapreview:" + s.nonce + ":" + seat).getBytes(StandardCharsets.UTF_8));
            if (manager.mapreview$playerMap().containsKey(id)) throw new IllegalStateException("测试角色身份冲突。");
            String name = "Review_" + s.nonce.toString().substring(0, 6) + "_" + seat;
            if (s.server.getPlayerManager().getPlayer(name) != null) throw new IllegalStateException("测试角色名称冲突：" + name);
            var player = new ServerPlayerEntity(s.server, s.judge.getServerWorld(),
                new GameProfile(id, name), s.judge.getClientOptions());
            player.networkHandler = dummy(s, player);
            player.changeGameMode(GameMode.SURVIVAL);
            double angle = (seat - 1) * Math.PI / 4;
            player.refreshPositionAndAngles(s.judge.getX() + Math.cos(angle) * 3,
                s.judge.getY(), s.judge.getZ() + Math.sin(angle) * 3, s.judge.getYaw(), 0);
            player.setCustomName(Text.literal("测试角色 " + seat));
            player.addCommandTag("mapreview_actor");
            s.actors.put(seat, player);
            manager.mapreview$players().add(player);
            manager.mapreview$playerMap().put(id, player);
            // A player profile must precede its entity spawn packet on every real client.
            s.server.getPlayerManager().sendToAll(PlayerListS2CPacket.entryFromPlayer(List.of(player)));
            player.getServerWorld().onPlayerConnected(player);
            player.onSpawn();
        }
        s.log("创建八个独立测试角色；裁判保留原真人实体和连接。");
    }

    private static void stopTracking(ReviewSession s, ServerPlayerEntity player) {
        for (ServerWorld world : s.server.getWorlds()) {
            var access = (ServerChunkLoadingManagerAccessor) world.getChunkManager().chunkLoadingManager;
            for (Object tracker : access.mapreview$entityTrackers().values()) {
                ((EntityTrackerInvoker) tracker).mapreview$stopTracking(player);
            }
        }
    }

    private static void track(ServerPlayerEntity player) {
        var access = (ServerChunkLoadingManagerAccessor) player.getServerWorld().getChunkManager().chunkLoadingManager;
        for (Object tracker : access.mapreview$entityTrackers().values()) {
            ((EntityTrackerInvoker) tracker).mapreview$updateTrackedStatus(player);
        }
    }

    public static void control(ReviewSession s, int seat) {
        mainThread(s);
        if (seat < 0 || seat > 8) throw new IllegalArgumentException("请选择裁判或 1–8 号角色。");
        var target = s.actor(seat);
        var old = s.controller();
        if (old == target) { s.seat = seat; ReviewRoleSync.send(s, true); return; }
        if (target == null || target.isRemoved()) throw new IllegalStateException("所选角色当前不可用。");
        old.closeHandledScreen();
        target.closeHandledScreen();
        // The old identity clears its real screen; the parked target invalidates its dummy-send cache.
        PrivateSidebar.clear(old);
        PrivateSidebar.clear(target);
        // Entity-tracker listeners retain the handler object, so detach before rebinding it.
        stopTracking(s, old);
        stopTracking(s, target);
        var parkedHandler = s.dummyHandlers.get(old.getUuid());
        if (parkedHandler == null) parkedHandler = dummy(s, old);
        old.networkHandler = parkedHandler;
        old.networkHandler.player = old;
        target.networkHandler = s.realHandler;
        s.realHandler.player = target;
        s.seat = seat;
        s.generation++;
        var chunkSender = new ChunkDataSender(false);
        ((ServerPlayNetworkHandlerAccessor) s.realHandler).mapreview$chunkDataSender(chunkSender);
        var world = target.getServerWorld();
        var manager = s.server.getPlayerManager();
        // This is a PLAY-phase world/player initialization; the physical login stays intact.
        s.realHandler.sendPacket(new GameJoinS2CPacket(target.getId(), s.server.getSaveProperties().isHardcore(),
            s.server.getWorldRegistryKeys(), manager.getMaxPlayerCount(), manager.getViewDistance(), manager.getSimulationDistance(),
            world.getGameRules().getBoolean(GameRules.REDUCED_DEBUG_INFO),
            !world.getGameRules().getBoolean(GameRules.DO_IMMEDIATE_RESPAWN),
            world.getGameRules().getBoolean(GameRules.DO_LIMITED_CRAFTING), target.createCommonPlayerSpawnInfo(world), false));
        // A repeated join reuses the client's old player object; respawn binds a fresh player to the new world.
        s.realHandler.sendPacket(new PlayerRespawnS2CPacket(target.createCommonPlayerSpawnInfo(world), (byte) 0));
        // A repeated PLAY join fires client JOIN listeners, including Axiom's connection reset.
        // Reannounce the real handler's existing receivers so mods can perform their normal handshake.
        // Permission and license checks remain with the respective mods and the current server player.
        var channels = List.copyOf(ServerPlayNetworking.getReceived(s.realHandler));
        if (!channels.isEmpty()) s.realHandler.sendPacket(new CustomPayloadS2CPacket(
            new RegistrationPayload(RegistrationPayload.REGISTER, channels)));
        ReviewAxiomConnection.refresh(target);
        s.realHandler.sendPacket(new DifficultyS2CPacket(world.getDifficulty(), world.getLevelProperties().isDifficultyLocked()));
        s.realHandler.sendPacket(PlayerListS2CPacket.entryFromPlayer(manager.getPlayerList()));
        // The PLAY handler keeps its global scoreboard across joins/respawns; duplicate ADD packets are invalid.
        manager.sendCommandTree(target);
        manager.sendWorldInfo(target, world);
        target.sendAbilitiesUpdate();
        s.realHandler.sendPacket(new EntityAttributesS2CPacket(target.getId(), target.getAttributes().getAttributesToSend()));
        var tracked = target.getDataTracker().getChangedEntries();
        if (tracked != null) s.realHandler.sendPacket(new EntityTrackerUpdateS2CPacket(target.getId(), tracked));
        manager.sendStatusEffects(target);
        manager.sendPlayerStatus(target);
        s.realHandler.sendPacket(new HealthUpdateS2CPacket(target.getHealth(), target.getHungerManager().getFoodLevel(),
            target.getHungerManager().getSaturationLevel()));
        s.realHandler.sendPacket(new ExperienceBarUpdateS2CPacket(target.experienceProgress, target.totalExperience, target.experienceLevel));
        // A fresh client world has no chunks even if the actor previously watched them.
        target.setChunkFilter(ChunkFilter.IGNORE_ALL);
        ((ServerChunkLoadingManagerAccessor) world.getChunkManager().chunkLoadingManager).mapreview$sendWatchPackets(target);
        // Existing actor watches may already be fully loaded; explicitly enqueue their actual chunks in the fresh sender.
        target.getChunkFilter().forEach(pos -> {
            var chunk = world.getChunkManager().getWorldChunk(pos.x, pos.z);
            if (chunk != null) chunkSender.add(chunk);
        });
        // The first normal, ACK-based batch precedes teleport so spawn physics has a solid world.
        chunkSender.sendChunkBatches(target);
        s.realHandler.syncWithPlayerPosition();
        s.realHandler.requestTeleport(target.getX(), target.getY(), target.getZ(), target.getYaw(), target.getPitch());
        world.getChunkManager().updatePosition(target);
        track(old);
        RESYNC.put(s, 80);
        ReviewRoleSync.send(s, true);
        s.log(seat == 0 ? "真实连接切回原裁判实体。" : "真实连接接管 " + seat + " 号测试角色。");
    }

    private static void updateReplacement(ReviewSession s, int seat, ServerPlayerEntity player) {
        s.actors.put(seat, player);
        if (s.seat == seat) {
            player.networkHandler = s.realHandler;
            s.realHandler.player = player;
        } else {
            var handler = s.dummyHandlers.get(player.getUuid());
            if (handler == null) handler = dummy(s, player);
            handler.player = player;
            player.networkHandler = handler;
        }
        player.addCommandTag("mapreview_actor");
    }

    public static void tick(ReviewSession s) {
        mainThread(s);
        for (var entry : new ArrayList<>(s.actors.entrySet())) {
            int seat = entry.getKey();
            var player = entry.getValue();
            var registered = s.server.getPlayerManager().getPlayer(player.getUuid());
            if (registered != null && registered != player) {
                updateReplacement(s, seat, registered);
                player = registered;
            }
            if ((player.networkHandler == s.realHandler && (!s.closing || player.isAlive())) || player.isRemoved()) continue;
            if (!player.isAlive() && player.deathTime >= 3) {
                var replacement = s.server.getPlayerManager().respawnPlayer(player, false, Entity.RemovalReason.KILLED);
                updateReplacement(s, seat, replacement);
                replacement.networkHandler.syncWithPlayerPosition();
                s.log(seat + " 号角色完成原版重生。");
            } else {
                // World ticks call ServerPlayerEntity.tick; playerTick normally comes from TCP.
                player.playerTick();
            }
        }
        if (s.judge.networkHandler != s.realHandler && !s.judge.isRemoved()) s.judge.playerTick();
        if (s.closing) { RESYNC.remove(s); return; }
        Integer left = RESYNC.get(s);
        if (left != null) {
            track(s.controller());
            if (left <= 1) RESYNC.remove(s); else RESYNC.put(s, left - 1);
        }
    }

    public static void remove(ReviewSession s) {
        mainThread(s);
        if (s.controller() != s.judge) control(s, 0);
        for (var player : new ArrayList<>(s.actors.values())) {
            player.closeHandledScreen();
            s.server.getPlayerManager().remove(player);
            PrivateSidebar.forget(player.getUuid());
            ReviewMWDataAccessor.mapreview$serverData().remove(player.getUuid());
            ReviewClassSkinsAccessor.mapreview$last().remove(player.getUuid());
        }
        s.actors.clear();
        s.dummyHandlers.clear();
        s.views.clear();
        RESYNC.remove(s);
        s.realHandler.player = s.judge;
        s.judge.networkHandler = s.realHandler;
        s.seat = 0;
        s.log("测试角色实体与玩家列表已清理，真实连接回到原裁判。");
    }

    public static void saveOriginal(ReviewSession s) {
        mainThread(s);
        if (s.controller() != s.judge || s.judge.networkHandler != s.realHandler)
            throw new IllegalStateException("保存原档案前必须切回真人裁判。");
        ((PlayerManagerAccessor) s.server.getPlayerManager()).mapreview$savePlayerData(s.judge);
    }
}
