package local.mapreview.mixin;

import net.minecraft.server.network.ChunkDataSender;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ServerPlayNetworkHandler.class)
public interface ServerPlayNetworkHandlerAccessor {
    @Mutable @Accessor("chunkDataSender") void mapreview$chunkDataSender(ChunkDataSender sender);
}
