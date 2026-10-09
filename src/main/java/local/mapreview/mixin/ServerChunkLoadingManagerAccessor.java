package local.mapreview.mixin;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerChunkLoadingManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ServerChunkLoadingManager.class)
public interface ServerChunkLoadingManagerAccessor {
    @Accessor("entityTrackers") Int2ObjectMap<?> mapreview$entityTrackers();
    @Invoker("sendWatchPackets") void mapreview$sendWatchPackets(ServerPlayerEntity player);
}
