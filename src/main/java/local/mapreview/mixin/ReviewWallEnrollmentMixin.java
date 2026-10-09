package local.mapreview.mixin;
import com.megawalls.game.MatchFlow;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(value=MatchFlow.class,remap=false)
public abstract class ReviewWallEnrollmentMixin {
    @Inject(method="ready",at=@At("HEAD"),cancellable=true,remap=false)
    private static void mapreview$roster(ServerPlayerEntity player,CallbackInfoReturnable<Integer> callback){if(!local.mapreview.ReviewAdmissions.allowed(player))callback.setReturnValue(0);}
}
