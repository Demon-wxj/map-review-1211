package local.mapreview.mixin;

import com.megawalls.player.MWPlayerData;
import local.mapreview.MapReviewClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.UUID;

@Mixin(value=MWPlayerData.class,remap=false)
public abstract class ReviewHudMixin {
    @Inject(method="setClient",at=@At("TAIL"),remap=false)
    private static void mapreview$controlledHud(UUID id,MWPlayerData data,CallbackInfo callback){
        if(MapReviewClient.controls(id))MWPlayerData.setClientLocal(data);
    }
    @Inject(method="setClientLocal",at=@At("HEAD"),cancellable=true,remap=false)
    private static void mapreview$keepActorHud(MWPlayerData data,CallbackInfo callback){
        if(!MapReviewClient.acceptsLocal(data))callback.cancel();
    }
}
