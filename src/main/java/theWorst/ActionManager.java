package theWorst;

import arc.Events;
import arc.util.Log;
import arc.util.Strings;
import arc.util.Time;
import arc.util.Timer;
import mindustry.entities.type.Player;
import mindustry.game.EventType;
import mindustry.gen.Call;
import mindustry.net.Administration;
import theWorst.dataBase.*;
import theWorst.interfaces.Interruptible;
import theWorst.interfaces.Votable;

import java.util.HashMap;
import java.util.TimerTask;

import static mindustry.Vars.*;

public class ActionManager implements Votable, Interruptible {
    TileInfo[][] data;
    static Emergency emergency= new Emergency();
    String reason;



    public ActionManager(){
        Events.on(EventType.PlayEvent.class, e->{
            data=new TileInfo[world.height()][world.width()];
            for(int y=0;y<world.height();y++){
                for(int x=0;x<world.width();x++){
                    data[y][x]=new TileInfo();
                }
            }
        });

        Events.on(EventType.TapEvent.class, e->{
            if(!Database.hasEnabled(e.player, Setting.inspect)) return;
            StringBuilder msg=new StringBuilder();
            TileInfo ti=data[e.tile.y][e.tile.x];
            if(ti.data.isEmpty()){
                msg.append("No one interacted with this tile.");
            }else {
                for(String s:ti.data.keySet()){
                    PlayerData pd=ti.data.get(s);
                    msg.append(String.format("[orange]%s : [gray]name[] - %s [gray]id[white] - %d[]\n",s,pd.originalName,pd.serverId));
                }
            }
            Call.onLabel(e.player.con,msg.toString(),5,e.tile.x*8,e.tile.y*8);
        });

        Events.on(EventType.BlockBuildEndEvent.class, e->{
            if(e.player==null ) return;
            TileInfo ti=data[e.tile.y][e.tile.x];
            if(e.breaking) {
                ti.lock=0;
            } else {
                if (Database.getData(e.player).trueRank.permission.getValue() >= Perm.high.getValue()) {
                    ti.lock=1;
                }
            }
        });

        Events.on(EventType.BlockDestroyEvent.class, e-> data[e.tile.y][e.tile.x].lock=0);

        Events.on(EventType.PlayerChatEvent.class,e->{
            if(!e.message.startsWith("/")) return;
            if(CommandUser.map.containsKey(e.player.uuid)){
                CommandUser.map.get(e.player.uuid).addOne(e.player);
                return;
            }
            CommandUser.map.put(e.player.uuid,new CommandUser(e.player));
        });

        Events.on(EventType.ServerLoadEvent.class,e-> netServer.admins.addActionFilter(action -> {
            Player player = action.player;
            if (player == null) return true;
            PlayerData pd=Database.getData(player);
            pd.lastAction= Time.millis();
            if(pd.rank==Rank.AFK){
                Database.afkThread.run();
            }
            if(action.type==Administration.ActionType.tapTile) return true;
            TileInfo ti=data[action.tile.y][action.tile.x];
            if(isEmergency() && pd.trueRank.permission.getValue()<Perm.high.getValue()) {
                Tools.errMessage(player,"You don t have permission to do anything during emergency.");
                return false;
            }
            if(!(pd.trueRank.permission.getValue()>=ti.lock)){
                if(pd.trueRank==Rank.griefer){
                    Tools.noPerm(player);
                }else {
                    Tools.errMessage(player,"You have to be at least "+Rank.verified.getName()+" to interact with this tile.");
                }
                return false;

            }
            ti.data.put(action.type.name(),pd);
            return true;
        }));
    }


    static class TileInfo{
        HashMap<String ,PlayerData> data = new HashMap<>();
        int lock= Perm.normal.getValue();
    }

    @Override
    public void launch(Package p) {
        PlayerData pd=((PlayerData)p.obj);
        Rank prevRank = pd.trueRank;
        if(p.object.equals("remove")){
            Tools.message("[orange]"+pd.originalName+"[] lost "+ Rank.griefer.getName()+" rank.");
            pd.trueRank=Rank.newcomer;
            Database.bunUnBunSubNet(pd,false);
            Log.info(pd.originalName+" is no longer griefer.");
        }else {
            Tools.message("[orange]"+pd.originalName+"[] obtained "+Rank.griefer.getName()+" rank.");
            pd.trueRank=Rank.griefer;
            Database.bunUnBunSubNet(pd,true);
            Log.info(pd.originalName+" wos marked as griefer.");
        }
        if(DiscordBot.activeLog()){
            DiscordBot.onRankChange(pd.originalName,pd.serverId,prevRank.name(),pd.trueRank.name(),
                    Database.getData(p.target).originalName,reason);
        }
        pd.rank=pd.trueRank;
        Player player=playerGroup.find(pl->pl.con.address.equals(pd.ip));
        if(player==null) return;
        Database.updateName(player,pd);
    }

    @Override
    public Package verify(Player player, String object, int amount, boolean toBase) {
        Player target;
        PlayerData pd=null;
        if(object.length() > 1 && object.startsWith("#") && Strings.canParseInt(object.substring(1))){
            int id = Strings.parseInt(object.substring(1));
            target = playerGroup.find(p -> p.id == id);
        }else{
            target = playerGroup.find(p -> p.name.equalsIgnoreCase(object));
        }
        if(target==null){
            if(!Strings.canParseInt(object)){
                target=Tools.findPlayer(object);
            }

            if(target==null){
                pd=Database.findData(object);
                if(pd!=null){
                    if(pd.trueRank.isAdmin){
                        Tools.errMessage(player," You cannot mark " + pd.trueRank.getName() + ".");
                        return null;
                    }
                }
            }
        }
        if(target!=null){
            if(target.isAdmin){
                Tools.errMessage(player,"Did you really expect to be able to mark an admin?");
                return null;
            }
            if(target==player){
                Tools.errMessage(player,"You cannot mark your self.");
                return null;
            }
            pd=Database.getData(target);
        }
        if(pd==null){
            Tools.errMessage(player,"Player not found.");
            return null;
        }
        Package p=new Package(pd.trueRank==Rank.griefer ? "remove":"add",pd,player);
        if(player.isAdmin){
            launch(p);
            return null;
        }
        return p;
    }



    static class Emergency{
        Timer.Task thread;
        int time;
        boolean red;

        void start(){

            if(active()){
                restart();
            }else {
                Tools.message("[scarlet]Emergency started you cannot build or break nor configure anything." +
                        "Be patient admins are currently dealing with griefer attack.[]");
            }
            time= 180;
            thread=Timer.schedule(new TimerTask() {
                @Override
                public void run() {
                    if(time<=0){
                        restart();
                    }
                    time--;
                    red=!red;
                }
            }, 0,1);
        }

        boolean active(){
            return thread!=null;
        }

        String getColor(){
            return red ? "[scarlet]":"[gray]";
        }

        void restart(){
            if(thread==null){
                return;
            }
            Tools.message("[green]Emergency ended.");
            thread.cancel();
            thread=null;
        }
    }

    public static boolean isEmergency(){
        return emergency.active();
    }

    public static void switchEmergency(boolean off) {
        if(off){
            emergency.restart();
            return;
        }
        emergency.start();
    }

    @Override
    public String getHudInfo() {
        String time=String.format("%d:%02d",emergency.time/60,emergency.time%60);
        return emergency.active() ? emergency.getColor() + time +
                "Emergency mode. Be patient [blue]admins[] have to eliminate all [pink]griefers[]" + time + "[]":null;
    }

    @Override
    public void interrupt() {
        emergency.restart();
    }

    static class CommandUser{
        static HashMap<String,CommandUser> map = new HashMap<>();
        int commandUseLimit=5;
        String uuid;
        int used=1;
        Timer.Task thread;


        private void addOne(Player player){
            if(Database.hasPerm(player,Perm.high)) return;
            used+=1;
            if(used>=commandUseLimit){
                netServer.admins.addSubnetBan(player.con.address.substring(0,player.con.address.lastIndexOf(".")));
                player.con.kick("You wer banned for command spamming. You can appeal on our discord.");
                terminate();
                return;
            }
            if (used>=2){
                Tools.errMessage(player,commandUseLimit-used+" more quick uses of command and you will be banned for spam.");
            }
        }

        private void terminate(){
            map.remove(uuid);
            thread.cancel();
        }

        private CommandUser(Player player) {
            uuid = player.uuid;
            map.put(uuid, this);
            thread=Timer.schedule(()->{
                used--;
                if (used == 0) {
                    terminate();
                }
            }, 2, 2);
        }
    }
}
