package local.mapreview.mixin;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.scoreboard.ServerScoreboard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(PlayerManager.class)
public interface PlayerManagerAccessor {
    @Accessor("players") List<ServerPlayerEntity> mapreview$players();
    @Accessor("playerMap") Map<UUID, ServerPlayerEntity> mapreview$playerMap();
    @Invoker("savePlayerData") void mapreview$savePlayerData(ServerPlayerEntity player);
    @Invoker("sendScoreboard") void mapreview$sendScoreboard(ServerScoreboard scoreboard, ServerPlayerEntity player);
}
