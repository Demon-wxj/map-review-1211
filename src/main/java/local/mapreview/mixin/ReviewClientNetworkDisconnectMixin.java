package local.mapreview.mixin;

import local.mapreview.MapReviewClient;
import net.minecraft.client.network.ClientCommonNetworkHandler;
import net.minecraft.network.ClientConnection;
import io.netty.channel.ChannelHandlerContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Clear this tool at the client connection before third-party disconnect callbacks. */
@Mixin(value = ClientConnection.class, priority = 2000)
public abstract class ReviewClientNetworkDisconnectMixin {
    private void mapreview$clearClientControl() {
        var connection = (ClientConnection)(Object)this;
        if (!connection.isOpen() && connection.getPacketListener() instanceof ClientCommonNetworkHandler)
            MapReviewClient.clearControlled();
    }
    @Inject(method = "channelInactive", at = @At("HEAD"))
    private void mapreview$clearControlled(ChannelHandlerContext context, CallbackInfo callback) {
        mapreview$clearClientControl();
    }
    @Inject(method = "handleDisconnection", at = @At("HEAD"))
    private void mapreview$clearControlled(CallbackInfo callback) {
        mapreview$clearClientControl();
    }
}
