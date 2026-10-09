package local.mapreview.mixin;

import com.megawalls.player.MWPlayerData;
import java.util.Map;
import java.util.UUID;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Remove only this session's transient UUIDs; never reset real-player state. */
@Mixin(value = MWPlayerData.class, remap = false)
public interface ReviewMWDataAccessor {
    @Accessor(value = "serverData", remap = false)
    static Map<UUID, MWPlayerData> mapreview$serverData() { throw new AssertionError("Mixin not applied"); }
}
