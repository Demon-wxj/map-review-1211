package local.mapreview.mixin;

import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(targets = "net.minecraft.server.world.ServerChunkLoadingManager$EntityTracker")
public interface EntityTrackerInvoker {
    @Invoker("stopTracking") void mapreview$stopTracking(ServerPlayerEntity player);
    @Invoker("updateTrackedStatus") void mapreview$updateTrackedStatus(ServerPlayerEntity player);
}
