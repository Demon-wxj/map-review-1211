package local.mapreview.mixin;
import com.megawalls.minigame.BedWars;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(value=BedWars.class,remap=false)
public abstract class ReviewBedEnrollmentMixin {
    @Inject(method="enroll",at=@At("HEAD"),cancellable=true,remap=false)
    private static void mapreview$roster(ServerPlayerEntity player,String team,CallbackInfoReturnable<Boolean> callback){if(!local.mapreview.ReviewAdmissions.allowed(player))callback.setReturnValue(false);}
}
