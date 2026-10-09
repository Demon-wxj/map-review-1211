package local.mapreview;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.math.Vec3d;
import java.util.*;

/** One explicitly created solo review owns its actors and the human's physical connection. */
public final class ReviewSession {
    public final MinecraftServer server;
    public final UUID owner;
    public final UUID nonce = UUID.randomUUID();
    public final ServerPlayerEntity judge;
    public final ServerPlayNetworkHandler realHandler;
    public final Map<Integer,ServerPlayerEntity> actors = new LinkedHashMap<>();
    public final Map<UUID,ServerPlayNetworkHandler> dummyHandlers = new HashMap<>();
    public final Map<UUID,Map<String,Object>> views = new HashMap<>();
    public final NbtCompound original;
    public final ServerWorld originalWorld;
    public final Vec3d originalPosition;
    public final float originalYaw, originalPitch;
    public final List<String> events = new ArrayList<>();
    public final Map<String,Map<Integer,String>> checks = new LinkedHashMap<>();
    public String game = "hub";
    public int seat = 0;
    public boolean closing;
    public boolean restorePending;
    public boolean restored;
    public boolean reported;
    public boolean leavePending;
    public String nextGame;
    public int ticks;
    public String lastError = "";
    public int generation;

    public ReviewSession(ServerPlayerEntity owner, NbtCompound original) {
        this.server = owner.getServer(); this.owner = owner.getUuid(); this.judge = owner;
        this.realHandler = owner.networkHandler; this.original = original;
        this.originalWorld = owner.getServerWorld(); this.originalPosition = owner.getPos();
        this.originalYaw = owner.getYaw(); this.originalPitch = owner.getPitch();
    }
    public ServerPlayerEntity controller() { return realHandler.player; }
    public ServerPlayerEntity actor(int seat) { return seat == 0 ? judge : actors.get(seat); }
    public boolean owns(ServerPlayerEntity player) { return player == judge || actors.values().stream().anyMatch(p -> p == player); }
    public boolean ownsActor(ServerPlayerEntity player) { return actors.values().stream().anyMatch(p -> p == player); }
    public void log(String message) { events.add(java.time.Instant.now() + " " + message); }
}
