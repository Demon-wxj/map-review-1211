package local.mapreview.mixin;
import com.megawalls.player.MWPlayerData;
import java.util.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(value=MWPlayerData.class,remap=false)
public interface ReviewClientCacheAccessor {
    @Accessor(value="clientCache",remap=false)
    static Map<UUID,MWPlayerData> mapreview$clientCache(){throw new AssertionError("Mixin not applied");}
}
