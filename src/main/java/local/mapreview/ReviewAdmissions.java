package local.mapreview;
import com.megawalls.minigame.GameSessions;
import net.minecraft.server.network.ServerPlayerEntity;
public final class ReviewAdmissions {
    public static boolean allowed(ServerPlayerEntity p){
        var s=ReviewSessions.get(p.getServer());
        if(s==null||!GameSessions.game(p).equals(s.game)||s.ownsActor(p))return true;
        MapReviewMod.tell(p,"当前游戏用于单人审查；裁判与其他真人保持旁观。结束审查后可正常参加。");return false;
    }
}
