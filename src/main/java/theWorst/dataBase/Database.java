package theWorst.dataBase;

import arc.Events;
import arc.struct.Array;
import arc.util.Log;
import arc.util.Time;
import arc.util.Timer;
import mindustry.content.Items;
import mindustry.entities.traits.BuilderTrait;
import mindustry.entities.type.Player;
import mindustry.game.EventType;
import mindustry.game.Team;
import mindustry.gen.Call;
import mindustry.net.Administration;
import mindustry.type.ItemStack;
import mindustry.world.blocks.storage.CoreBlock;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import theWorst.*;
import theWorst.Package;
import theWorst.interfaces.LoadSave;
import theWorst.interfaces.Votable;
import theWorst.requests.Loadout;

import java.io.*;
import java.util.HashMap;
import java.util.HashSet;

import static mindustry.Vars.*;

public class Database implements Votable, LoadSave {
    public static HashMap<String,PlayerData> data=new HashMap<>();
    public static HashMap<String,SpecialRank> ranks=new HashMap<>();
    static HashSet<String> subNets=new HashSet<>();
    public final static String saveFile= Main.directory+"database.ser";
    public final static String rankFile= Main.directory+"rankConfig.json";
    public static Timer.Task afkThread;
    static SpecialRank error =new SpecialRank();



    public Database(){



        Events.on(EventType.GameOverEvent.class, e ->{
            for(Player p:playerGroup){
                PlayerData pd=getData(p);
                if(p.getTeam()==e.winner) {
                    pd.gamesWon++;
                    updateRank(p,Stat.gamesWon);
                }
                pd.gamesPlayed++;
                updateRank(p,Stat.gamesPlayed);
            }
        });

        Events.on(EventType.BuildSelectEvent.class, e->{
            if(e.builder instanceof Player){
                boolean happen =false;
                Player player=(Player)e.builder;
                CoreBlock.CoreEntity core= Loadout.getCore(player);
                if(core==null) return;
                BuilderTrait.BuildRequest request = player.buildRequest();
                if(request==null) return;
                if(hasSpecialPerm(player,Perm.destruct) && request.breaking){
                    happen=true;
                    for(ItemStack s:request.block.requirements){
                        core.items.add(s.item,s.amount/2);
                    }
                    Call.onDeconstructFinish(request.tile(),request.block,((Player) e.builder).id);

                }else if(hasSpecialPerm(player,Perm.build) && !request.breaking){
                    if(core.items.has(request.block.requirements)){
                        happen=true;
                        for(ItemStack s:request.block.requirements){
                            core.items.remove(s);
                        }
                        Call.onConstructFinish(e.tile,request.block,((Player) e.builder).id,
                                (byte) request.rotation,player.getTeam(),false);
                        e.tile.configure(request.config);
                    }
                }
                if(happen)
                    Events.fire(new EventType.BlockBuildEndEvent(e.tile,player,e.team,e.breaking));
            }
        });

        Events.on(EventType.UnitDestroyEvent.class, e->{
            if(e.unit instanceof Player){
                getData((Player) e.unit).deaths++;
                updateRank((Player) e.unit,Stat.deaths);
            }else if(e.unit.getTeam()== Team.crux){
                for(Player p:playerGroup){
                    getData(p).enemiesKilled++;
                    updateRank(p,Stat.enemiesKilled);
                }
            }
        });

        Events.on(EventType.PlayerIpBanEvent.class,e->{
            netServer.admins.unbanPlayerIP(e.ip);
            for(PlayerData pd:data.values()){
                if(pd.ip.equals(e.ip)){
                    pd.trueRank=Rank.griefer;
                    pd.getInfo().lastKicked=Time.millis();
                    bunUnBunSubNet(pd,true);
                    break;
                }
            }
        });

        Events.on(EventType.BlockBuildEndEvent.class, e->{
            if(e.player == null) return;
            if(!e.breaking && e.tile.block().buildCost/60<1) return;
            PlayerData pd=getData(e.player);
            if(e.breaking){
                pd.buildingsBroken++;
                updateRank(e.player,Stat.buildingsBroken);
            }else {
                pd.buildingsBuilt++;
                updateRank(e.player,Stat.buildingsBuilt);
            }
        });

        Events.on(EventType.PlayerConnect.class, e -> {
            PlayerData pd;
            Player player=e.player;
            String uuid=player.uuid;
            if(!data.containsKey(uuid)) {
                data.put(uuid,new PlayerData(player));
                Hud.addAd("We have a newcomer [orange]"+player.name+"[white].",30);
                pd=getData(uuid);
                for(Setting s:Setting.values()){
                    pd.settings.add(s.name());
                }
            }else {
                pd=getData(uuid);
            }
            if(pd==null) return;
            pd.connect(player);
            pd.lastAction= Time.millis();
            if(pd.rank==Rank.AFK){
                pd.rank=pd.trueRank;
            }
            if(isSubNetBanned(player)){
                setRank(player,Rank.griefer);
            } else {
                updateRank(player,null);
                updateName(player,pd);
            }
            player.isAdmin=pd.trueRank.isAdmin;
            Call.sendMessage("[accent]" + player.name + "[accent] (ID:" + pd.serverId +") has connected");
        });

        Events.on(EventType.PlayerLeave.class, e->{
            PlayerData pd=getData(e.player);
            pd.disconnect();
            Call.sendMessage("[accent]" + e.player.name + "[accent] (ID:" + pd.serverId +") has disconnected");
        });

        afkThread=Timer.schedule(()->{
            for(Player p:playerGroup){
                PlayerData pd=getData(p);
                if(pd==null) return;
                if(Rank.AFK.condition(pd)){
                    if(pd.rank==Rank.AFK) return;
                    Call.sendMessage(Main.prefix+"[orange]"+p.name+"[] became "+Rank.AFK.getName()+".");
                    setRank(p,Rank.AFK);
                }else if(pd.rank==Rank.AFK){
                    Call.sendMessage(Main.prefix+"[orange]"+p.name+"[] is not "+pd.rank.getName()+" anymore.");
                    pd.rank=pd.trueRank;
                    updateName(p,pd);
                }
            }
        },0,60);

        error.name="error";
        error.color="scarlet";
        error.description="If your rank got removed you ll obtain this for little time.";
    }

    public static String getSubNet(PlayerData pd){
        String address=pd.ip;
        return address.substring(0,address.lastIndexOf("."));
    }

    public static void bunUnBunSubNet(PlayerData pd,boolean bun){
        String subNet=getSubNet(pd);
        boolean contains=subNets.contains(subNet);
        if(bun && !contains){
            subNets.add(subNet);
            return;
        }
        if(contains && !bun){
            subNets.remove(subNet);
        }
    }

    public static boolean isSubNetBanned(Player player){
        return subNets.contains(getSubNet(getData(player)));
    }

    public static boolean isGriefer(Player player){
        return Database.getData(player).trueRank==Rank.griefer;
    }

    public static Array<String> getSorted(String s) {
        Array<String> res=new Array<>();
        try {
            Stat stat=Stat.valueOf(s);
            Array<PlayerData> dt=Array.with(data.values());
            while(!dt.isEmpty()){
                PlayerData best=dt.first();
                for(int i=1;i<dt.size;i++){
                    if(best.getStat(stat)<dt.get(i).getStat(stat)){
                        best=dt.get(i);
                    }
                }
                dt.remove(best);
                res.add(formLine(best));
            }
            return res;
        } catch (IllegalArgumentException ex){
            return null;
        }
    }

    public void saveData() {
        try {
            FileOutputStream fileOut = new FileOutputStream(saveFile);
            ObjectOutputStream out = new ObjectOutputStream(fileOut);
            out.writeObject(data);
            out.close();
            fileOut.close();
        } catch (IOException i) {
            i.printStackTrace();
        }
    }

    public void loadData(){
        try {
            FileInputStream fileIn = new FileInputStream(saveFile);
            ObjectInputStream in = new ObjectInputStream(fileIn);
            Object obj=in.readObject();
            if(obj instanceof HashMap) data = (HashMap<String, PlayerData>) obj;
            in.close();
            fileIn.close();
            Log.info("Database loaded.");
        } catch (ClassNotFoundException c) {
            Log.info("class not found");
            c.printStackTrace();
        } catch (FileNotFoundException f){
            Log.info("database no found, creating new");
        }catch (IOException i){
            Log.info("Database is incompatible with current version.");
        }
    }

    public static void switchSetting(Player player,Setting setting,boolean off){
        HashSet<String > settings=getData(player).settings;
        boolean contain=settings.contains(setting.name());
        if(off && contain){
            settings.remove(setting.name());
        } else if(!contain){
            settings.add(setting.name());
        }
    }

    public static boolean hasEnabled(Player player, Setting setting){
        return getData(player).settings.contains(setting.name());
    }

    public static PlayerData getData(String uuid){
        return data.get(uuid);
    }

    public static PlayerData getData(Player player){
        return getData(player.uuid);
    }

    public static PlayerData getData(int idx){
        if(idx>data.keySet().size()){
            return null;
        }
        for(PlayerData pd:data.values()){
            if(pd.serverId==idx){
                return pd;
            }
        }
        return null;
    }

    public static PlayerData findData(String arg){
        if(!Tools.isNotInteger(arg)){
            return getData(Integer.parseInt(arg));
        }
        Player p= Tools.findPlayer(arg);
        if(p!=null) return getData(p);
        return getData(arg);
    }

    public static String formLine(PlayerData pd){
        return "[yellow]"+pd.serverId+"[] | [gray]"+pd.originalName+"[] | "+pd.trueRank.getName();
    }

    public static Array<String> getOnlinePlayersIndexes(){
        Array<String> ids=new Array<>();
        ids.addAll(data.keySet());
        Array<String> res=new Array<>();
        for(Player p:playerGroup){
            res.add(formLine(getData(p)));
        }
        return res;
    }

    public static Array<String> getAllPlayersIndexes(String search){
        Array<String> res=new Array<>();
        for(String uuid:data.keySet()){
            PlayerData pd=getData(uuid);
            if(search!=null && !pd.originalName.toLowerCase().startsWith(search.toLowerCase())) continue;
            res.add(formLine(pd));
        }
        return res;
    }

    public static Array<String> getAllChinesePlayersIndexes(){
        Array<String> res=new Array<>();
        for(String uuid:data.keySet()){
            PlayerData pd=getData(uuid);
            for (int i = 0; i < pd.originalName.length(); i++){
                if(isCharCJK(pd.originalName.charAt(i))){
                    res.add(formLine(pd));
                    break;
                }
            }
        }
        return res;
    }

    public static Array<String> getAllRussianPlayersIndexes(){
        Array<String> res=new Array<>();
        for(String uuid:data.keySet()){
            PlayerData pd=getData(uuid);
            for (int i = 0; i < pd.originalName.length(); i++){
                if(isCharCyrillic(pd.originalName.charAt(i))){
                    res.add(formLine(pd));
                    break;
                }
            }
        }
        return res;
    }

    public static Array<String> getAllPlayersIndexesByRank(String search){
        Array<String> res=new Array<>();
        for(String uuid:data.keySet()){
            PlayerData pd=getData(uuid);
            if(!(pd.trueRank.name().equals(search) || (pd.specialRank!=null && pd.specialRank.equals(search)))) continue;
            res.add(formLine(pd));
        }
        return res;
    }

    public static Array<String> getRankInfo(){
        Array<String> res=new Array<>();
        for(SpecialRank sr:ranks.values()){
            res.add(sr.getSuffix()+"-[gray]"+sr.description+"[]\n");
        }
        return res;
    }

    public static void setRank(PlayerData pd,Rank rank,Player player){
        boolean wosGrifer=pd.rank==Rank.griefer;
        boolean wosAdmin=pd.rank.isAdmin;
        pd.rank=rank;
        Administration.PlayerInfo inf=pd.getInfo();
        if(rank.permanent){
            pd.trueRank=rank;
        }
        if(rank.isAdmin){
            if(!wosAdmin) netServer.admins.adminPlayer(inf.id,inf.adminUsid);
        }else if(wosAdmin && rank.permanent) {
            netServer.admins.unAdminPlayer(inf.id);
        }
        if (rank == Rank.griefer) {
            bunUnBunSubNet(pd, true);
        } else if (wosGrifer) {
            bunUnBunSubNet(pd, false);
        }
        if(player==null){
            player=playerGroup.find(p->p.con.address.equals(pd.ip));
            if(player==null) return;
        }
        updateName(player,pd);
        player.isAdmin=rank.isAdmin;
    }

    public enum Res{
        notFound,
        notPermitted,
        invalidRank,
        success
    }

    public static Res setRankViaCommand(Player player, String target, String rank, String reason){
        PlayerData pd = Database.findData(target);
        if (pd == null) {
            return Res.notFound;
        }
        String by = "terminal";
        if (player!=null ){
            by=player.name;
            if (pd.trueRank.isAdmin) {
                return Res.notPermitted;
            }
        }
        try {
            Rank r=Rank.valueOf(rank);
            if(player!=null && r.isAdmin){
                return Res.notPermitted;
            }
            if(reason==null){
                reason="Reason not provided.";
            }
            Rank prevRank = pd.trueRank;
            Database.setRank(pd,r );
            if(DiscordBot.activeLog()){
                DiscordBot.onRankChange(pd.originalName,pd.serverId,prevRank.name(),r.name(),by,reason);
            }
            Log.info("Rank of player " + pd.originalName + " is now " + pd.rank.name() + ".");
            Call.sendMessage("Rank of player [orange]"+ pd.originalName+"[] is now " +pd.rank.getName() +".");
        } catch (IllegalArgumentException e) {
            if(rank.equals("restart")) {
                pd.specialRank = null;
                Call.sendMessage(Main.prefix+"Rank of player [orange]" + pd.originalName + "[] wos restarted.");
                Log.info("Rank of player " + pd.originalName + " wos restarted.");
            }else if(!Database.ranks.containsKey(rank)){

                return Res.invalidRank;
            }else {
                pd.specialRank=rank;
                SpecialRank sr=Database.getSpecialRank(pd);
                if(sr!=null){
                    Log.info("Rank of player " + pd.originalName + " is now " + pd.specialRank + ".");
                    Call.sendMessage(Main.prefix+"Rank of player [orange]" + pd.originalName + "[] is now " + sr.getSuffix() + ".");
                }
            }
        }

        Player found = playerGroup.find(p->p.con.address.equals(pd.ip));
        if (found == null) {
            found = playerGroup.find(p-> p.uuid.equalsIgnoreCase(target));
            if (found == null) {
                return Res.success;
            }
        }
        Database.updateName(found,pd);
        return Res.success;
    }

    public static void setRank(PlayerData pd,Rank rank){
        setRank(pd,rank,null);
    }

    public static void setRank(Player player,Rank rank){
        setRank(getData(player),rank,player);
    }

    public static void updateRank(Player player,Stat stat){
        PlayerData pd=getData(player);
        SpecialRank specialRank=getSpecialRank(pd);
        if(pd.trueRank.permission==Perm.none) return;
        boolean set=false;
        for(SpecialRank sr:ranks.values()){
            if((sr.stat==stat || stat==null) && sr.condition(pd)){
                if(specialRank==null || specialRank.value<sr.value){
                    Call.sendMessage(Main.prefix+"[orange]"+player.name+"[white] obtained "+sr.getSuffix()+" rank.");
                    pd.specialRank=sr.name;
                    specialRank=sr;
                    player.name=pd.originalName+sr.getSuffix();
                }
                set=true;
            }
        }
        if(set || specialRank==null || specialRank.stat!=stat)return;
        Call.sendMessage(Main.prefix+"[orange]"+player.name+"[white] lost his "+specialRank.getSuffix()+" rank.");
        pd.specialRank=null;
        player.name=pd.originalName+pd.rank.getSuffix();
    }

    public static SpecialRank getSpecialRank(PlayerData pd){
        if(pd.specialRank==null) return null;
        SpecialRank sr=ranks.get(pd.specialRank);
        if(sr==null){
            pd.specialRank=null;
            return error;
        }
        return sr;
    }

    public static boolean hasPerm(Player player,Perm perm){
        return getData(player).trueRank.permission.getValue()>=perm.getValue();
    }

    public static boolean hasThisPerm(Player player,Perm perm){
        PlayerData pd=getData(player);
        return pd.rank.permission==perm || pd.trueRank.permission==perm;
    }

    public static boolean hasSpecialPerm(Player player,Perm perm){
        SpecialRank sr=getSpecialRank(getData(player));
        if(sr==null) return false;
        return sr.permissions.contains(perm);
    }

    public static void updateName(Player player,PlayerData pd){
        player.name=pd.originalName+(pd.specialRank==null ? pd.rank.getSuffix(): getSpecialRank(pd).getSuffix());
    }

    private static boolean isCharCyrillic(final char c){
        return Character.UnicodeBlock.of(c).equals(Character.UnicodeBlock.CYRILLIC);
    }

    private static boolean isCharCJK(final char c) {
        return (Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS)
                || (Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A)
                || (Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B)
                || (Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CJK_COMPATIBILITY_FORMS)
                || (Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS)
                || (Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CJK_RADICALS_SUPPLEMENT)
                || (Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION)
                || (Character.UnicodeBlock.of(c) == Character.UnicodeBlock.ENCLOSED_CJK_LETTERS_AND_MONTHS);
    }

    public void loadRanks(){
        Tools.loadJson(rankFile,(data)->{
            ranks.clear();
            for(Object o:data.keySet()){
                String key=(String)o;
                ranks.put(key,new SpecialRank(key,(JSONObject) data.get(key),data));
            }

        },this::createDefaultRankConfig);
    }

    private void createDefaultRankConfig() {
        Tools.saveJson(rankFile,
                "Default "+rankFile+" created.Edit it adn the use apply config Command",
                ()->{
                    JSONObject data=new JSONObject();
                    JSONObject rank=new JSONObject();
                    rank.put("comment","This rank can be only obtained by having total count of deaths higher then " +
                            "other players,permission determinate special ability.");
                    rank.put("permission",Perm.suicide.name());
                    rank.put("tracked",Stat.deaths.name());
                    rank.put("mode",SpecialRank.Mode.best.name());
                    rank.put("value",1);
                    rank.put("color","#"+ Items.blastCompound.color);
                    rank.put("description","If you die the most of all players you ll obtain this rank. It allows you" +
                            "to use suicide command.");
                    data.put("kamikaze",rank);
                    rank=new JSONObject();
                    rank.put("comment","This rank can be only obtained by having total count of buildingsBuilt 5 and" +
                            "average build rate per hour 3.Build rate calculation: buildingsBuilt/playTime_in_hours");
                    rank.put("permission",Perm.build.name());
                    rank.put("tracked",Stat.buildingsBuilt.name());
                    rank.put("mode",SpecialRank.Mode.reqFreq.name());
                    rank.put("value",2);
                    rank.put("color","#"+ Items.plastanium.color);
                    rank.put("frequency",3);
                    rank.put("requirement",5);
                    rank.put("description","If you build a lot you will obtain this rank.When i mean a lot i mean a [red]LOT[].");
                    data.put("builder",rank);
                    rank=new JSONObject();
                    rank.put("comment","This rank can e only given by command and will stay until the condition of" +
                            "rank with higher value is met or rank is set to none.");
                    rank.put("permanent",true);
                    rank.put("color","yellow");
                    rank.put("value",3);
                    rank.put("description","It depends on how annoying you are to server owner.");
                    data.put("KID",rank);
                    rank=new JSONObject();
                    rank.put("comment","This rank can e only given by command and will stay until the condition of" +
                            "rank with higher value is met or rank is set to none.");
                    rank.put("permanent",false);
                    JSONArray perms =new JSONArray();
                    perms.add(Perm.build.name());
                    perms.add(Perm.destruct.name());
                    rank.put("permission",perms);
                    JSONArray linked =new JSONArray();
                    perms.add("builder");
                    perms.add("kamikaze");
                    rank.put("linked",linked);
                    rank.put("mode",SpecialRank.Mode.merge.name());
                    rank.put("color","#"+Items.pyratite.color);
                    rank.put("value",6);
                    rank.put("description","If you protect everything you built with your own body you ll get this rank");
                    data.put("suicidalBuilder",rank);
                    return data;
                });
    }

    @Override
    public void launch(Package p) {
        for(Player player:playerGroup){
            if(getData(player).rank==Rank.AFK && !player.isAdmin){
                player.con.kick("You have been AFK when kickAllAfk vote passed.",0);
            }
        }
    }

    @Override
    public Package verify(Player player, String object, int amount, boolean toBase) {
        return null;
    }

    @Override
    public void load(JSONObject data) {
        for(Object o:(JSONArray) data.get("subNets")){
            subNets.add((String)o);
        }
    }

    @Override
    public JSONObject save() {
        JSONArray subs =new JSONArray();
        subs.addAll(subNets);
        JSONObject data =new JSONObject();
        data.put("subNets",subs);
        return data;
    }
}
