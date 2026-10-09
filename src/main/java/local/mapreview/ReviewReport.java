package local.mapreview;

import com.google.gson.GsonBuilder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.WorldSavePath;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

public final class ReviewReport {
    public static Path write(MinecraftServer server,ReviewSession s) throws java.io.IOException {
        var entries=GameInspection.inspect(server);
        Path dir=server.getSavePath(WorldSavePath.ROOT).resolve("map-review/reports");Files.createDirectories(dir);
        String name=DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").format(LocalDateTime.now());
        Path file=dir.resolve(name+".md");var text=new StringBuilder("# 当前地图审查\n\n");
        text.append("生成时间：").append(Instant.now()).append("\n\n默认：8 个测试角色 + 1 个裁判／说书人。\n\n配置检查与手动实测分别记录；配置完整不代表流程已经测试通过。\n\n");
        if(s!=null)text.append("当前入口：").append(s.game).append("；当前控制：").append(s.seat).append("。\n\n");
        for(var e:entries){
            text.append("## ").append(e.name()).append(" (`").append(e.id()).append("`)\n\n");
            text.append("入口：").append(e.configured()?"已配置":"未配置").append("\n\n");
            for(String detail:e.details())text.append("- ").append(detail.replace("\n"," ")).append("\n");
            if(!e.details().isEmpty())text.append('\n');
            for(String issue:e.issues())text.append("- 待检查：").append(issue.replace("\n"," ")).append("\n");
            if(e.issues().isEmpty())text.append("- 本次静态／点位检查未发现问题。\n");text.append('\n');
            var steps=GameInspection.steps(e.id());
            for(int i=0;i<steps.size();i++){
                String result=s==null?"未测试":s.checks.getOrDefault(e.id(),Map.of()).getOrDefault(i+1,"未测试");
                text.append("- ").append(i+1).append(". [").append(result).append("] ").append(steps.get(i)).append('\n');
            }text.append('\n');
        }
        if(s!=null){text.append("## 本次操作记录\n\n");for(String line:s.events)text.append("- ").append(line).append('\n');}
        Files.writeString(file,text,StandardCharsets.UTF_8);
        var record=new LinkedHashMap<String,Object>();record.put("created",Instant.now().toString());record.put("entries",entries);
        record.put("checks",s==null?Map.of():s.checks);record.put("events",s==null?List.of():s.events);
        Files.writeString(dir.resolve(name+".json"),new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(record),StandardCharsets.UTF_8);
        return file;
    }
}
