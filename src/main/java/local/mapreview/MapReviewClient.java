package local.mapreview;

import com.megawalls.player.MWPlayerData;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.*;
import java.util.UUID;

public final class MapReviewClient implements ClientModInitializer {
    private static volatile UUID controlled;
    public static void clearControlled(){controlled=null;}
    public static UUID controlledActor(){return controlled;}
    public static boolean controls(UUID id){return id!=null&&id.equals(controlled);}
    public static boolean acceptsLocal(MWPlayerData data){return controlled==null||MWPlayerData.getClient(controlled)==null||MWPlayerData.getClient(controlled)==data;}
    @Override public void onInitializeClient(){
        ClientPlayNetworking.registerGlobalReceiver(ReviewRoleSync.Role.ID,(payload,context)->{
            controlled=payload.active()?payload.actor():null;
            var data=MWPlayerData.getClient(payload.actor());MWPlayerData.setClientLocal(data==null?new MWPlayerData():data);
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler,client)->clearControlled());
        ClientPlayNetworking.registerGlobalReceiver(ReviewRoleSync.Cleanup.ID,(payload,context)->{
            var cache=local.mapreview.mixin.ReviewClientCacheAccessor.mapreview$clientCache();payload.actors().forEach(cache::remove);
        });
    }
}
