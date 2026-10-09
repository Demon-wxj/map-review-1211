package local.mapreview.mixin;

import com.megawalls.menu.TabletopBridge;
import local.mapreview.GamesAdapter;
import local.mapreview.ReviewSessions;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Dummy actors lack a negotiated client; every other admission condition remains enforced. */
@Mixin(value = TabletopBridge.class, remap = false)
public abstract class TabletopAdmissionMixin {
    @Inject(method = "canEnter", at = @At("HEAD"), cancellable = true, remap = false)
    private static void mapreview$ownedActor(ServerPlayerEntity player, String id,
                                             CallbackInfoReturnable<Boolean> callback) {
        if (ReviewSessions.isActor(player)) callback.setReturnValue(GamesAdapter.tabletopActorAdmission(player, id));
    }
}
