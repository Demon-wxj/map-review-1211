package local.mapreview;

import com.megawalls.minigame.GameSessions;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.world.GameMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.*;
import static net.minecraft.server.command.CommandManager.*;

public final class MapReviewMod implements ModInitializer {
    public static final Logger LOGGER=LoggerFactory.getLogger("mapreview");
    @FunctionalInterface private interface Action {void run(ServerPlayerEntity p) throws Exception;}
    private static int execute(ServerCommandSource source,Action action){
        try{action.run(source.getPlayerOrThrow());return 1;}
        catch(Exception e){source.sendError(Text.literal("[地图审查] "+message(e)));LOGGER.debug("Review command",e);return 0;}
    }
    private static String message(Exception e){return e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();}
    @Override public void onInitialize(){
        ReviewRoleSync.register();
        CommandRegistrationCallback.EVENT.register((dispatcher,access,env)->{
            var root=dispatcher.register(literal("mapreview").executes(c->execute(c.getSource(),ReviewMenu::open))
                .then(literal("panel").executes(c->execute(c.getSource(),ReviewMenu::open)))
                .then(literal("on").executes(c->execute(c.getSource(),MapReviewMod::on)))
                .then(literal("solo_on").executes(c->execute(c.getSource(),MapReviewMod::on)))
                .then(literal("off").executes(c->execute(c.getSource(),MapReviewMod::off)))
                .then(literal("solo_off").executes(c->execute(c.getSource(),MapReviewMod::off)))
                .then(literal("control").then(argument("seat",IntegerArgumentType.integer(0,8)).executes(c->execute(c.getSource(),p->control(p,IntegerArgumentType.getInteger(c,"seat"))))))
                .then(literal("enter").then(argument("game",StringArgumentType.word()).executes(c->execute(c.getSource(),p->enter(p,StringArgumentType.getString(c,"game"))))))
                .then(literal("start").executes(c->execute(c.getSource(),MapReviewMod::start)))
                .then(literal("stop").executes(c->execute(c.getSource(),MapReviewMod::stop)))
                .then(literal("leave").executes(c->execute(c.getSource(),MapReviewMod::leave)))
                .then(literal("status").executes(c->execute(c.getSource(),MapReviewMod::status)))
                .then(literal("report").executes(c->execute(c.getSource(),MapReviewMod::report)))
                .then(literal("inspect").executes(c->execute(c.getSource(),MapReviewMod::report))
                    .then(argument("game",StringArgumentType.word()).executes(c->execute(c.getSource(),p->inspect(p,StringArgumentType.getString(c,"game"))))))
                .then(literal("checklist").executes(c->execute(c.getSource(),ReviewMenu::checklist)))
                .then(literal("mark").then(argument("step",IntegerArgumentType.integer(1,36)).then(argument("result",StringArgumentType.word())
                    .executes(c->execute(c.getSource(),p->mark(p,IntegerArgumentType.getInteger(c,"step"),StringArgumentType.getString(c,"result")))))))
                .then(literal("as").then(argument("seat",IntegerArgumentType.integer(0,8)).then(argument("operation",StringArgumentType.greedyString())
                    .executes(c->execute(c.getSource(),p->{var s=require(p);GamesAdapter.asCommand(s,IntegerArgumentType.getInteger(c,"seat"),StringArgumentType.getString(c,"operation"));}))))));
            dispatcher.register(literal("review").executes(c->execute(c.getSource(),ReviewMenu::open)).redirect(root));
        });
        ServerLifecycleEvents.SERVER_STARTED.register(server->{try{GamesAdapter.recoverRules(server);}catch(Exception e){LOGGER.warn("Review rules await normal arena recovery",e);}});
        ServerTickEvents.END_SERVER_TICK.register(server->{
            var s=ReviewSessions.get(server);if(s==null){if(server.getTicks()%100==0)try{GamesAdapter.recoverRules(server);}catch(Exception ignored){}return;}
            try{
                s.ticks++;ReviewActors.tick(s);
                if((s.closing||s.leavePending)&&s.ticks%20==0&&GamesAdapter.settled(s))finish(s);
                if(!s.closing&&!s.leavePending&&s.ticks%100==0)
                    s.controller().sendMessage(Text.literal("审查 · "+(s.seat==0?"裁判／说书人":"角色 "+s.seat)+" · "+s.game+" · /review"),true);
            }catch(Exception e){recordError(s,e);}
        });
        ServerPlayConnectionEvents.JOIN.register((handler,sender,server)->{
            var s=ReviewSessions.get(server);
            if(s!=null&&s.owner.equals(handler.player.getUuid())&&handler!=s.realHandler){
                handler.disconnect(Text.literal("地图审查正在恢复上一连接，请稍后重新连接。"));return;
            }
            if(s!=null&&handler!=s.realHandler){
                try{requestClose(s,"其他真人加入，正在结束单人审查并恢复原玩家。");}catch(Exception e){recordError(s,e);}
            }
            if(s==null||!s.owner.equals(handler.player.getUuid()))try{ReviewRecovery.recoverLogin(handler.player);}catch(Exception e){
                LOGGER.error("Cannot recover review player",e);handler.player.sendMessage(Text.literal("[地图审查] 自动恢复未完成，恢复记录已保留："+message(e)),false);
            }
        });
        // ReviewDisconnectMixin handles the canonical main-thread vanilla cleanup.
        // Fabric's Netty disconnect notification must never mutate player entities.
        ServerLifecycleEvents.SERVER_STOPPING.register(server->{
            var s=ReviewSessions.get(server);if(s==null)return;
            try{requestClose(s,"服务端停止，保留恢复记录。");ReviewActors.control(s,0);ReviewRecovery.restoreOriginal(s);}
            catch(Exception e){recordError(s,e);}
        });
    }
    public static void beforeNetworkDisconnect(net.minecraft.server.network.ServerPlayNetworkHandler handler){
        var s=ReviewSessions.of(handler.player);if(s==null||handler!=s.realHandler)return;
        try{requestClose(s,"真人连接断开，正在恢复原档案。");ReviewActors.control(s,0);ReviewRecovery.restoreOriginal(s);}
        catch(Exception e){recordError(s,e);}
    }
    public static ReviewSession require(ServerPlayerEntity p){
        var s=ReviewSessions.of(p);
        if(s==null||p.networkHandler!=s.realHandler||s.controller()!=p)throw new IllegalStateException("请由审查人输入 /review on。");
        return s;
    }
    public static void on(ServerPlayerEntity p) throws Exception {
        var prior=ReviewSessions.get(p.getServer());
        if(prior!=null){require(p);ReviewMenu.open(p);return;}
        if(!p.getServer().isSingleplayer()&&!p.hasPermissionLevel(2))throw new IllegalStateException("创建测试角色需要房主或管理员权限。");
        if(!net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.canSend(p,ReviewRoleSync.Role.ID))throw new IllegalStateException("操作客户端需要安装同版地图审查 Mod。");
        if(!p.isAlive())throw new IllegalStateException("请先重生再开始审查。");
        if(!p.currentScreenHandler.getCursorStack().isEmpty())throw new IllegalStateException("请先把鼠标上的物品放回背包。");
        for(int slot=1;slot<=4;slot++)if(!p.playerScreenHandler.getSlot(slot).getStack().isEmpty())throw new IllegalStateException("请先收回合成栏里的物品。");
        p.closeHandledScreen();
        if(ReviewRecovery.pending(p))ReviewRecovery.recoverLogin(p);
        if(!GamesAdapter.idle(p.getServer()))throw new IllegalStateException("请先结束现有比赛、排队或桌游，再开启审查。");
        var s=new ReviewSession(p,GameSessions.capture(p));ReviewRecovery.begin(s);ReviewSessions.put(s);
        try{
            if(!GameSessions.switchTo(p,GameSessions.HUB))throw new IllegalStateException("原角色不能正常离场，请先处理当前桌游。");
            p.setInvulnerable(true);p.changeGameMode(GameMode.SPECTATOR);ReviewActors.create(s);
            ReviewRoleSync.send(s,true);
            tell(p,"单人审查已开启：8 个可操作角色 + 独立裁判／说书人。/review control 0–8 切换。");ReviewMenu.open(p);
        }catch(Exception e){
            try{GamesAdapter.stop(s);if(GamesAdapter.settled(s)){GamesAdapter.leave(s);ReviewActors.remove(s);ReviewRecovery.restoreOriginal(s);ReviewRecovery.complete(s);ReviewSessions.remove(s);}else{s.closing=true;}}
            catch(Exception recovery){e.addSuppressed(recovery);s.closing=true;}throw e;
        }
    }
    public static void control(ServerPlayerEntity p,int seat){var s=require(p);available(s);ReviewActors.control(s,seat);tell(s.controller(),seat==0?"当前身份：裁判／说书人。":"当前身份：测试角色 "+seat+"。可正常移动、操作物品和游戏界面。");}
    public static void enter(ServerPlayerEntity p,String game){
        var s=require(p);available(s);var e=GameInspection.inspect(s.server,game);
        if(!e.configured())throw new IllegalStateException("入口未配置："+e.name());
        ReviewActors.control(s,0);s.nextGame=game;s.leavePending=true;GamesAdapter.stop(s);
        if(GamesAdapter.settled(s))finish(s);else tell(s.controller(),"正在恢复当前场地，完成后进入 "+e.name()+"。");
    }
    public static void start(ServerPlayerEntity p){var s=require(p);available(s);ReviewActors.control(s,0);GamesAdapter.start(s);tell(s.controller(),GamesAdapter.status(s));}
    public static void stop(ServerPlayerEntity p){var s=require(p);ReviewActors.control(s,0);GamesAdapter.stop(s);tell(s.controller(),"已请求结束比赛。恢复完成后可 /review leave 或选择其他游戏。");}
    public static void leave(ServerPlayerEntity p){var s=require(p);available(s);ReviewActors.control(s,0);s.nextGame=null;s.leavePending=true;GamesAdapter.stop(s);if(GamesAdapter.settled(s))finish(s);}
    public static void off(ServerPlayerEntity p){var s=require(p);requestClose(s,"退出单人审查。");if(GamesAdapter.settled(s))finish(s);}
    private static void available(ReviewSession s){if(s.closing||s.leavePending)throw new IllegalStateException("正在收尾和恢复场地，请等待完成。");}
    private static void requestClose(ReviewSession s,String reason){ReviewActors.control(s,0);s.nextGame=null;s.leavePending=false;s.closing=true;s.log(reason);GamesAdapter.stop(s);tell(s.controller(),reason);}
    private static void finish(ReviewSession s){
        try{
            ReviewActors.control(s,0);GamesAdapter.leave(s);
            if(s.closing){
                if(!s.reported){try{ReviewReport.write(s.server,s);s.reported=true;}catch(java.io.IOException e){LOGGER.warn("Review report could not be written; restoring player first",e);}}
                s.restored=false;ReviewRecovery.restoreOriginal(s);ReviewRoleSync.cleanup(s);ReviewActors.remove(s);ReviewRoleSync.send(s,false);ReviewRecovery.complete(s);ReviewSessions.remove(s);
                tell(s.judge,"审查已结束，原玩家状态已恢复；报告保存到存档 map-review/reports。");
            }else{
                String next=s.nextGame;s.nextGame=null;s.leavePending=false;
                if(next!=null){GamesAdapter.enter(s,next);s.judge.setInvulnerable(true);tell(s.controller(),"已进入 "+next+"。/review start 开局，/review control 1–8 操作。");}
                else tell(s.controller(),"已退出当前游戏，可选择下一个入口。");
            }
        }catch(Exception e){s.nextGame=null;s.leavePending=false;recordError(s,e);if(!s.closing)tell(s.controller(),"可 /review stop、/review leave 或 /review off 正常收尾。");}
    }
    public static void status(ServerPlayerEntity p){var s=require(p);tell(p,"当前 "+s.game+"，控制 "+s.seat+"；"+GamesAdapter.status(s));if(!s.lastError.isEmpty())tell(p,"最近问题："+s.lastError);}
    public static void inspect(ServerPlayerEntity p,String game){var e=GameInspection.inspect(p.getServer(),game);tell(p,e.name()+" · "+(e.configured()?"入口已配置":"入口未配置"));for(String detail:e.details())tell(p,detail);for(String issue:e.issues())tell(p,"待检查："+issue);}
    public static void report(ServerPlayerEntity p) throws java.io.IOException {var s=ReviewSessions.of(p);if(s!=null&&s.controller()!=p)s=null;var file=ReviewReport.write(p.getServer(),s);tell(p,"审查报告："+file.toAbsolutePath());}
    public static void mark(ServerPlayerEntity p,int step,String result){
        var s=require(p);available(s);var steps=GameInspection.steps(s.game);if(step<1||step>steps.size())throw new IllegalArgumentException("当前清单只有 "+steps.size()+" 项。");
        String value=switch(result){case "pass","通过"->"通过";case "fail","失败"->"失败";case "skip","跳过"->"跳过";case "clear","未测试"->"未测试";default->throw new IllegalArgumentException("结果用 pass / fail / skip / clear。");};
        s.checks.computeIfAbsent(s.game,k->new LinkedHashMap<>()).put(step,value);s.log(s.game+" 清单 "+step+" 用户记录："+value);tell(p,"第 "+step+" 项："+value);
    }
    public static void tell(ServerPlayerEntity p,String message){p.sendMessage(Text.literal("[地图审查] "+message),false);}
    public static void recordError(ReviewSession s,Exception e){String text=message(e);if(!text.equals(s.lastError)){s.lastError=text;s.log("问题："+text);LOGGER.error("Map review: {}",text,e);tell(s.controller(),text);}}
}
