package local.mapreview.mixin;

import local.mapreview.MapReviewMod;
import net.minecraft.network.DisconnectionInfo;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Vanilla invokes this on the server thread before cleanUp removes handler.player. */
@Mixin(ServerPlayNetworkHandler.class)
public abstract class ReviewDisconnectMixin {
    @Inject(method="onDisconnected",at=@At("HEAD"))
    private void mapreview$originalBeforeRemoval(DisconnectionInfo info,CallbackInfo callback){
        MapReviewMod.beforeNetworkDisconnect((ServerPlayNetworkHandler)(Object)this);
    }
}
