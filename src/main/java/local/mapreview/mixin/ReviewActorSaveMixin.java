package local.mapreview.mixin;

import local.mapreview.ReviewSessions;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerManager.class)
public abstract class ReviewActorSaveMixin {
    @Inject(method = "savePlayerData", at = @At("HEAD"), cancellable = true)
    private void mapreview$transientActor(ServerPlayerEntity player, CallbackInfo ci) {
        if (ReviewSessions.isActor(player)) ci.cancel();
    }
}
