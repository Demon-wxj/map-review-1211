package local.mapreview;

import com.megawalls.network.S2C_SkillSync;
import com.megawalls.player.MWPlayerData;
import net.fabricmc.fabric.api.networking.v1.*;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import java.util.UUID;
import java.util.List;

/** Separate login identity from the currently controlled game identity for client HUD routing. */
public final class ReviewRoleSync {
    public record Role(UUID actor,int seat,boolean active) implements CustomPayload {
        public static final Id<Role> ID=new Id<>(Identifier.of("mapreview","controlled_role"));
        public static final PacketCodec<RegistryByteBuf,Role> CODEC=PacketCodec.of(
            (p,b)->{b.writeUuid(p.actor);b.writeVarInt(p.seat);b.writeBoolean(p.active);},b->new Role(b.readUuid(),b.readVarInt(),b.readBoolean()));
        @Override public Id<Role> getId(){return ID;}
    }
    public record Cleanup(List<UUID> actors) implements CustomPayload {
        public Cleanup{actors=List.copyOf(actors);if(actors.size()>8)throw new IllegalArgumentException("Too many review actors");}
        public static final Id<Cleanup> ID=new Id<>(Identifier.of("mapreview","clear_roles"));
        public static final PacketCodec<RegistryByteBuf,Cleanup> CODEC=PacketCodec.of(
            (p,b)->{b.writeVarInt(p.actors.size());p.actors.forEach(b::writeUuid);},b->{
                int count=b.readVarInt();if(count<0||count>8)throw new IllegalArgumentException("Invalid review actor count");
                var list=new java.util.ArrayList<UUID>();for(int i=0;i<count;i++)list.add(b.readUuid());return new Cleanup(list);
            });
        @Override public Id<Cleanup> getId(){return ID;}
    }
    public record MenuClick(int syncId,int slot,int mouse) implements CustomPayload {
        public static final Id<MenuClick> ID=new Id<>(Identifier.of("mapreview","menu_click"));
        public static final PacketCodec<RegistryByteBuf,MenuClick> CODEC=PacketCodec.of(
            (p,b)->{b.writeVarInt(p.syncId);b.writeVarInt(p.slot);b.writeVarInt(p.mouse);},
            b->new MenuClick(b.readVarInt(),b.readVarInt(),b.readVarInt()));
        @Override public Id<MenuClick> getId(){return ID;}
    }
    public static void register(){
        PayloadTypeRegistry.playS2C().register(Role.ID,Role.CODEC);
        PayloadTypeRegistry.playS2C().register(Cleanup.ID,Cleanup.CODEC);
        PayloadTypeRegistry.playC2S().register(MenuClick.ID,MenuClick.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(MenuClick.ID,(payload,context)->{
            var player=context.player();
            context.server().execute(()->ReviewMenu.handleClick(player,payload));
        });
    }
    public static void cleanup(ReviewSession s){
        var packet=new Cleanup(s.actors.values().stream().map(p->p.getUuid()).toList());
        for(var p:s.server.getPlayerManager().getPlayerList())if(!s.ownsActor(p)&&ServerPlayNetworking.canSend(p,Cleanup.ID))ServerPlayNetworking.send(p,packet);
    }
    public static void send(ReviewSession s,boolean active){
        var p=s.controller();if(ServerPlayNetworking.canSend(p,Role.ID))ServerPlayNetworking.send(p,new Role(p.getUuid(),s.seat,active));
        if(ServerPlayNetworking.canSend(p,com.megawalls.network.NetworkHandler.SKILL_SYNC_ID))
            ServerPlayNetworking.send(p,new S2C_SkillSync(p.getUuid(),MWPlayerData.getServer(p).toNbt()));
    }
}
