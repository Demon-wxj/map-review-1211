package local.mapreview.mixin;

import local.mapreview.MapReviewClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Local exits clear only this tool before any third-party disconnect event can fail. */
@Mixin(MinecraftClient.class)
public abstract class ReviewClientDisconnectMixin {
    @Inject(method = "disconnect(Lnet/minecraft/client/gui/screen/Screen;Z)V", at = @At("HEAD"))
    private void mapreview$clearControlled(Screen nextScreen, boolean transferring, CallbackInfo callback) {
        MapReviewClient.clearControlled();
    }
}
