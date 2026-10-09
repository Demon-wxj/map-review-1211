package local.mapreview;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.entity.player.*;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.*;
import net.minecraft.screen.*;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import java.util.*;

/** Vanilla container buttons. Nothing can be extracted or inserted. */
public final class ReviewMenu {
    @FunctionalInterface private interface Button {void click(ServerPlayerEntity p,int mouse) throws Exception;}
    public static void open(ServerPlayerEntity p){
        var active=ReviewSessions.get(p.getServer());if(active!=null)MapReviewMod.require(p);
        var entries=GameInspection.inspect(p.getServer());
        p.openHandledScreen(new SimpleNamedScreenHandlerFactory((id,inv,player)->new Panel(id,inv,entries,false),Text.literal("地图审查 · 8 角色 + 裁判")));
    }
    public static void checklist(ServerPlayerEntity p){var s=MapReviewMod.require(p);p.openHandledScreen(new SimpleNamedScreenHandlerFactory((id,inv,player)->new Panel(id,inv,List.of(),true),Text.literal("审查清单 · "+s.game)));}
    public static void handleClick(ServerPlayerEntity p,ReviewRoleSync.MenuClick click){
        if(click.slot()<0||click.slot()>=54||click.mouse()<0||click.mouse()>1)return;
        var active=ReviewSessions.get(p.getServer());
        if(active!=null&&(p.networkHandler!=active.realHandler||active.controller()!=p))return;
        if(p.currentScreenHandler instanceof Panel panel&&panel.syncId==click.syncId())
            panel.onSlotClick(click.slot(),click.mouse(),SlotActionType.PICKUP,p);
    }
    private static ItemStack icon(Item item,String name,List<String> lore){var stack=new ItemStack(item);stack.set(DataComponentTypes.CUSTOM_NAME,Text.literal(name));stack.set(DataComponentTypes.LORE,new LoreComponent(lore.stream().map(Text::literal).map(t->(Text)t).toList()));return stack;}
    private static final class Panel extends GenericContainerScreenHandler {
        private final UUID owner;private final Map<Integer,Button> buttons=new HashMap<>();private boolean used;
        private void button(int slot,Item item,String name,List<String> lore,Button action){getInventory().setStack(slot,icon(item,name,lore));buttons.put(slot,action);}
        Panel(int id,PlayerInventory inventory,List<GameInspection.Entry> entries,boolean checks){
            super(ScreenHandlerType.GENERIC_9X6,id,inventory,new SimpleInventory(54),6);owner=inventory.player.getUuid();
            for(int slot=0;slot<54;slot++)getInventory().setStack(slot,icon(Items.GRAY_STAINED_GLASS_PANE," ",List.of()));
            var p=(ServerPlayerEntity)inventory.player;var s=ReviewSessions.of(p);
            if(checks){
                var steps=GameInspection.steps(s.game);
                for(int i=0;i<Math.min(36,steps.size());i++){
                    final int step=i+1;String value=s.checks.getOrDefault(s.game,Map.of()).getOrDefault(step,"未测试");
                    Item item=switch(value){case "通过"->Items.LIME_DYE;case "失败"->Items.RED_DYE;case "跳过"->Items.YELLOW_DYE;default->Items.PAPER;};
                    button(i,item,step+". "+steps.get(i),List.of("结果："+value,"左键轮换 未测试→通过→失败→跳过","此记录是你的实际测试结果"),(who,mouse)->{
                        String next=mouse==1?"clear":switch(value){case "未测试"->"pass";case "通过"->"fail";case "失败"->"skip";default->"clear";};
                        MapReviewMod.mark(who,step,next);checklist(who);
                    });
                }
                button(49,Items.COMPASS,"返回总面板",List.of(),(who,mouse)->open(who));
                button(50,Items.WRITABLE_BOOK,"导出报告",List.of("配置检查 + 手动测试记录"),(who,mouse)->MapReviewMod.report(who));
            }else{
                for(int i=0;i<Math.min(18,entries.size());i++){
                    var e=entries.get(i);var lore=new ArrayList<String>();lore.add("左键进入 · 右键查看问题");lore.add("入口："+(e.configured()?"已配置":"未配置"));lore.addAll(e.issues().stream().limit(6).toList());
                    button(i,e.issues().isEmpty()?Items.LIME_CONCRETE:Items.ORANGE_CONCRETE,e.name(),lore,(who,mouse)->{
                        if(mouse==1){MapReviewMod.inspect(who,e.id());return;}if(ReviewSessions.of(who)==null)MapReviewMod.on(who);MapReviewMod.enter(who,e.id());
                    });
                }
                if(s!=null){
                    button(22,Items.PAPER,"当前 "+s.game+" · 控制 "+s.seat,List.of(GamesAdapter.status(s),"角色控制是实际玩家输入","其余角色不会自动决定私人行动"),(who,mouse)->MapReviewMod.status(who));
                    for(int seat=0;seat<=8;seat++){final int pick=seat;button(27+seat,seat==0?Items.ENDER_EYE:Items.PLAYER_HEAD,seat==0?"裁判／说书人":"测试角色 "+seat,List.of("左键切换控制","独立物品、身份与私人信息"),(who,mouse)->MapReviewMod.control(who,pick));}
                }
                button(45,Items.EMERALD,"开启单人审查",List.of("默认 8 个角色 + 独立裁判","原玩家档案先备份"),(who,mouse)->MapReviewMod.on(who));
                button(46,Items.DIAMOND_SWORD,"开始当前游戏",List.of("使用原游戏的真实开局检查"),(who,mouse)->MapReviewMod.start(who));
                button(47,Items.REDSTONE,"结束当前比赛",List.of("等待场地和玩家状态恢复"),(who,mouse)->MapReviewMod.stop(who));
                button(48,Items.BOOK,"逐项测试清单",List.of("通过／失败／跳过手动记录"),(who,mouse)->checklist(who));
                button(49,Items.WRITABLE_BOOK,"导出报告",List.of("保存到存档 map-review/reports"),(who,mouse)->MapReviewMod.report(who));
                button(50,Items.OAK_DOOR,"退出当前游戏",List.of("恢复后可以选择下一个"),(who,mouse)->MapReviewMod.leave(who));
                button(51,Items.BARRIER,"关闭单人审查",List.of("清理八角色并恢复原玩家"),(who,mouse)->MapReviewMod.off(who));
                button(52,Items.SPYGLASS,"当前身份桌游手机",List.of("仅钟楼／七连翻","请先切换要查看的角色"),(who,mouse)->{var review=MapReviewMod.require(who);GamesAdapter.asCommand(review,review.seat,"tt phone");});
            }
            button(53,Items.GLASS,"关闭面板",List.of("/review 随时再打开"),(who,mouse)->{});
        }
        @Override public boolean canUse(PlayerEntity p){return p.getUuid().equals(owner)&&p.isAlive();}
        @Override public ItemStack quickMove(PlayerEntity p,int slot){return ItemStack.EMPTY;}
        @Override public void onSlotClick(int slot,int mouse,SlotActionType action,PlayerEntity player){
            if(!(player instanceof ServerPlayerEntity p)||!canUse(p)||p.currentScreenHandler!=this||used)return;
            if(action!=SlotActionType.PICKUP||(mouse!=0&&mouse!=1))return;
            var button=buttons.get(slot);if(button==null)return;used=true;p.closeHandledScreen();
            try{button.click(p,mouse);}catch(Exception e){MapReviewMod.tell(p,e.getMessage()==null?e.getClass().getSimpleName():e.getMessage());}
        }
    }
}
