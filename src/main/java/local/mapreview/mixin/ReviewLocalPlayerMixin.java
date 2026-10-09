package local.mapreview.mixin;

import local.mapreview.MapReviewClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.network.PlayerListEntry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Keep the authenticated connection while reading the controlled role's mode and skin. */
@Mixin(AbstractClientPlayerEntity.class)
public abstract class ReviewLocalPlayerMixin {
    @Inject(method="getPlayerListEntry",at=@At("HEAD"),cancellable=true)
    private void mapreview$localRoleEntry(CallbackInfoReturnable<PlayerListEntry> callback){
        var client=MinecraftClient.getInstance();
        var controlled=MapReviewClient.controlledActor();
        if((Object)this!=client.player||controlled==null||client.getNetworkHandler()==null)return;
        var entry=client.getNetworkHandler().getPlayerListEntry(controlled);
        if(entry!=null)callback.setReturnValue(entry);
    }
}
