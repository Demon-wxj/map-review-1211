package local.mapreview.mixin;

import local.mapreview.ReviewRoleSync;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.screen.slot.SlotActionType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Review buttons remain usable by a spectator without changing their game mode. */
@Mixin(ClientPlayerInteractionManager.class)
public abstract class ReviewPanelClickMixin {
    @Inject(method="clickSlot",at=@At("HEAD"),cancellable=true)
    private void mapreview$panelButton(int syncId,int slot,int mouse,SlotActionType action,
                                      PlayerEntity player,CallbackInfo callback){
        var client=MinecraftClient.getInstance();
        if(player!=client.player||!(client.currentScreen instanceof GenericContainerScreen screen))return;
        String title=screen.getTitle().getString();
        if(!title.startsWith("地图审查 ·")&&!title.startsWith("审查清单 ·"))return;
        if(client.player.currentScreenHandler.syncId!=syncId||!ClientPlayNetworking.canSend(ReviewRoleSync.MenuClick.ID))return;
        // These are action buttons, never inventory transfers or client-side stack predictions.
        callback.cancel();
        if(action==SlotActionType.PICKUP&&slot>=0&&slot<54&&(mouse==0||mouse==1))
            ClientPlayNetworking.send(new ReviewRoleSync.MenuClick(syncId,slot,mouse));
    }
}
