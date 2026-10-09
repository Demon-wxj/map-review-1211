package local.mapreview.mixin;

import local.mapreview.ReviewSessions;
import local.tabletop.ReviewTabletopBridge;
import local.tabletop.Sessions;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.LinkedHashMap;
import java.util.Map;

/** Keep each inactive owned actor's real private view; never send it to another player. */
@Mixin(value = Sessions.class, remap = false)
public abstract class TabletopViewMixin {
    @Inject(method = "command", at = @At("HEAD"), cancellable = true, remap = false)
    private static void mapreview$fixedJudge(ServerPlayerEntity player,String command,CallbackInfoReturnable<Integer> callback) {
        var review=ReviewSessions.of(player);
        if(review==null||!review.owns(player)||!review.game.equals("clocktower")||command==null)return;
        String operation=command.trim().split("\\s+",2)[0];
        if(operation.equals("storyteller")&&ReviewTabletopBridge.isReviewTable(review,ReviewTabletopBridge.tableId(player))){
            local.mapreview.MapReviewMod.tell(player,"单人审查的裁判固定为说书人；请切换控制身份查看其他角色。");
            callback.setReturnValue(0);
        }
    }

    @Inject(method = "send", at = @At("HEAD"), cancellable = true, remap = false)
    private static void mapreview$ownedView(ServerPlayerEntity player, Map<String,Object> view,
                                           boolean opening, CallbackInfo callback) {
        var review = ReviewSessions.of(player);
        if (review == null || !review.owns(player)) return;
        review.views.put(player.getUuid(), new LinkedHashMap<>(view));
        if (review.controller() != player) callback.cancel();
    }

    @Inject(method = "join", at = @At("HEAD"), remap = false)
    private static void mapreview$exclusiveTable(ServerPlayerEntity player, String id, CallbackInfo callback) {
        var review = ReviewSessions.get(player.getServer());
        if (review != null && ReviewTabletopBridge.isReviewTable(review, id) &&
            (!review.owns(player) || review.game.equals("flip7") && player == review.judge))
            throw new IllegalStateException("此桌用于当前单人地图审查，请使用其他牌桌。");
    }
}
