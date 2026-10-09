package local.mapreview;

import com.megawalls.minigame.GameSessions;
import net.minecraft.nbt.*;
import net.minecraft.registry.*;
import net.minecraft.network.packet.s2c.play.GameStateChangeS2CPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.WorldSavePath;
import java.nio.file.*;
import java.io.IOException;
import java.util.*;

/** Durable before-touch snapshot. The journal survives until the original player has been saved. */
public final class ReviewRecovery {
    private static Path root(MinecraftServer server) { return server.getSavePath(WorldSavePath.ROOT).resolve("map-review/recovery"); }
    private static Path folder(MinecraftServer server, UUID owner) { return root(server).resolve(owner.toString()); }
    private static Path profiles(MinecraftServer server, UUID owner) { return server.getSavePath(WorldSavePath.ROOT).resolve("minigame-profiles").resolve(owner.toString()); }
    public static boolean pending(ServerPlayerEntity p) { return Files.exists(folder(p.getServer(),p.getUuid()).resolve("original.dat")); }
    public static void begin(ReviewSession s) throws IOException {
        Path dir=folder(s.server,s.owner), file=dir.resolve("original.dat");
        if(Files.exists(file)) throw new IOException("有未完成的审查恢复记录，请先重新登录完成恢复。");
        Files.createDirectories(dir);
        Path source=profiles(s.server,s.owner), backup=dir.resolve("profiles");
        deleteTree(backup); copyTree(source,backup);
        var n=new NbtCompound();n.put("snapshot",s.original.copy());
        n.put("vanilla",s.judge.writeNbt(new NbtCompound()));
        n.putString("game",GameSessions.game(s.judge)); n.putString("dimension",s.originalWorld.getRegistryKey().getValue().toString());
        n.putDouble("x",s.originalPosition.x);n.putDouble("y",s.originalPosition.y);n.putDouble("z",s.originalPosition.z);
        n.putFloat("yaw",s.originalYaw);n.putFloat("pitch",s.originalPitch);
        n.putBoolean("profilesExisted",Files.isDirectory(source));n.putUuid("nonce",s.nonce);
        var tags=new NbtList();s.judge.getCommandTags().forEach(t->tags.add(NbtString.of(t)));n.put("allTags",tags);
        write(file,n);
    }
    private static void write(Path path,NbtCompound n) throws IOException {
        Path temp=path.resolveSibling(path.getFileName()+".tmp");NbtIo.writeCompressed(n,temp);
        Files.move(temp,path,StandardCopyOption.REPLACE_EXISTING);
    }
    private static void restore(ServerPlayerEntity p,NbtCompound n) throws IOException {
        var key=RegistryKey.of(RegistryKeys.WORLD,Identifier.of(n.getString("dimension")));
        var world=p.getServer().getWorld(key);if(world==null) throw new IOException("原维度未加载："+key.getValue());
        Path target=profiles(p.getServer(),p.getUuid()), dir=folder(p.getServer(),p.getUuid()), backup=dir.resolve("profiles");
        if(n.getBoolean("profilesExisted")&&!Files.isDirectory(backup))throw new IOException("原小游戏档案备份缺失，保留当前档案和恢复日志。");
        Path staged=dir.resolve("profiles-staged-"+UUID.randomUUID());
        if(n.getBoolean("profilesExisted")){Files.createDirectories(staged);copyTree(backup,staged);}
        // Read vanilla data as well: attributes, command tags, spawn, abilities and custom NBT belong to the original human.
        p.readNbt(n.getCompound("vanilla"));
        GameSessions.mark(p,n.getString("game"));GameSessions.restore(p,n.getCompound("snapshot"));
        for(String tag:new HashSet<>(p.getCommandTags()))p.removeCommandTag(tag);
        for(var tag:n.getList("allTags",NbtElement.STRING_TYPE))p.addCommandTag(tag.asString());
        p.setInvulnerable(n.getCompound("vanilla").getBoolean("Invulnerable"));
        p.teleport(world,n.getDouble("x"),n.getDouble("y"),n.getDouble("z"),Set.of(),n.getFloat("yaw"),n.getFloat("pitch"));
        // readNbt already restores the server's mode, so changeGameMode can be a no-op.
        // Explicitly restore the controlled client's mode after its spectator/actor rebind.
        p.setCameraEntity(p);
        p.networkHandler.sendPacket(new GameStateChangeS2CPacket(GameStateChangeS2CPacket.GAME_MODE_CHANGED,p.interactionManager.getGameMode().getId()));
        p.sendAbilitiesUpdate();
        p.getServer().getPlayerManager().sendCommandTree(p);
        p.getServer().getPlayerManager().sendPlayerStatus(p);p.currentScreenHandler.syncState();
        Path displaced=dir.resolve("profiles-displaced-"+UUID.randomUUID());
        if(Files.exists(target))Files.move(target,displaced);
        try{if(n.getBoolean("profilesExisted"))Files.move(staged,target);}
        catch(IOException error){if(!Files.exists(target)&&Files.exists(displaced))Files.move(displaced,target);throw error;}
    }
    public static void restoreOriginal(ReviewSession s) throws IOException {
        if(s.restored)return;
        var n=NbtIo.readCompressed(folder(s.server,s.owner).resolve("original.dat"),NbtSizeTracker.ofUnlimitedBytes());
        restore(s.judge,n);ReviewActors.saveOriginal(s);verifySaved(s.judge);s.restored=true;s.log("原玩家物品、位置、状态及小游戏档案已恢复并保存。");
    }
    public static void complete(ReviewSession s) throws IOException {
        if(!s.restored)throw new IOException("原玩家尚未恢复，保留恢复日志。");
        Path dir=folder(s.server,s.owner);
        // Generated actor paths are derived only from the persisted nonce and fixed seat numbers.
        for(int seat=1;seat<=8;seat++){
            UUID id=UUID.nameUUIDFromBytes(("mapreview:"+s.nonce+":"+seat).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            deleteTree(profiles(s.server,id));
        }
        Files.move(dir.resolve("original.dat"),dir.resolve("restored.dat"),StandardCopyOption.REPLACE_EXISTING);
    }
    public static void recoverLogin(ServerPlayerEntity p) throws IOException {
        if(!pending(p))return;
        Path dir=folder(p.getServer(),p.getUuid()),file=dir.resolve("original.dat");
        var n=NbtIo.readCompressed(file,NbtSizeTracker.ofUnlimitedBytes());restore(p,n);
        ((local.mapreview.mixin.PlayerManagerAccessor)p.getServer().getPlayerManager()).mapreview$savePlayerData(p);
        verifySaved(p);
        archiveAbandonedActors(p.getServer(),p.getUuid(),n.getUuid("nonce"));
        Files.move(file,dir.resolve("restored.dat"),StandardCopyOption.REPLACE_EXISTING);
        p.sendMessage(net.minecraft.text.Text.literal("[地图审查] 上次中断的原玩家状态已恢复。"),false);
    }
    private static void archiveAbandonedActors(MinecraftServer server,UUID owner,UUID nonce) throws IOException {
        Path world=server.getSavePath(WorldSavePath.ROOT),dir=folder(server,owner).resolve("abandoned-actors");
        for(int seat=1;seat<=8;seat++){
            UUID id=UUID.nameUUIDFromBytes(("mapreview:"+nonce+":"+seat).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            Path saved=dir.resolve(id.toString());
            Path profile=profiles(server,id);if(Files.exists(profile)){Files.createDirectories(saved);Files.move(profile,saved.resolve("profiles-"+UUID.randomUUID()));}
            for(String kind:List.of("classic-pvp-recovery","pot-pvp-recovery","bedwars-recovery","spleef-recovery","tnt-run-recovery","hot-potato-recovery","tabletop/clock-recovery")){
                Path record=world.resolve(kind).resolve(id+".dat");if(Files.exists(record)){
                    Files.createDirectories(saved);Files.move(record,saved.resolve(kind.replace('/','_')+"-"+UUID.randomUUID()+".dat"));
                }
            }
            String name="Review_"+nonce.toString().substring(0,6)+"_"+seat;
            server.getScoreboard().clearTeam(name);
        }
    }
    private static void verifySaved(ServerPlayerEntity p) throws IOException {
        Path file=p.getServer().getSavePath(WorldSavePath.ROOT).resolve("playerdata").resolve(p.getUuidAsString()+".dat");
        if(!Files.isRegularFile(file))throw new IOException("原玩家存档尚未落盘，保留恢复日志。");
        var expected=p.writeNbt(new NbtCompound());var saved=NbtIo.readCompressed(file,NbtSizeTracker.ofUnlimitedBytes());
        for(String key:expected.getKeys())if(!Objects.equals(expected.get(key),saved.get(key)))throw new IOException("原玩家存档校验未通过（"+key+"），保留恢复日志。");
    }
    private static void copyTree(Path source,Path target) throws IOException {
        if(!Files.isDirectory(source))return;
        try(var stream=Files.walk(source)){for(Path path:stream.toList()){
            Path out=target.resolve(source.relativize(path));
            if(Files.isDirectory(path))Files.createDirectories(out);else Files.copy(path,out,StandardCopyOption.REPLACE_EXISTING);
        }}
    }
    private static void deleteTree(Path path) throws IOException {
        if(!Files.exists(path))return;
        try(var stream=Files.walk(path)){for(Path item:stream.sorted(Comparator.reverseOrder()).toList())Files.delete(item);}
    }
}
