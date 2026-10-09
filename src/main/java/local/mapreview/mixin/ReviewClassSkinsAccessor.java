package local.mapreview.mixin;

import com.megawalls.player.ClassSkins;
import java.util.Map;
import java.util.UUID;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = ClassSkins.class, remap = false)
public interface ReviewClassSkinsAccessor {
    @Accessor(value = "last", remap = false)
    static Map<UUID, Integer> mapreview$last() { throw new AssertionError("Mixin not applied"); }
}
