package local.mapreview.mixin;

import com.megawalls.minigame.*;
import local.mapreview.*;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value={ClassicDuel.class,PotDuel.class,Spleef.class,TntRun.class,HotPotato.class},remap=false)
public abstract class ReviewEnrollmentMixin {
    @Inject(method="enroll",at=@At("HEAD"),cancellable=true,remap=false)
    private static void mapreview$roster(ServerPlayerEntity player,CallbackInfoReturnable<Boolean> callback){if(!ReviewAdmissions.allowed(player))callback.setReturnValue(false);}
}
