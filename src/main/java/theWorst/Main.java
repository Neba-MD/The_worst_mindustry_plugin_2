package theWorst;

import arc.Core;
import arc.Events;
import arc.math.Mathf;
import arc.struct.Array;
import arc.struct.ArrayMap;
import arc.util.*;
import mindustry.entities.type.BaseUnit;
import mindustry.entities.type.Player;
import mindustry.game.EventType.ServerLoadEvent;
import mindustry.game.EventType.WorldLoadEvent;
import mindustry.game.Team;
import mindustry.gen.Call;
import mindustry.net.Administration;
import mindustry.plugin.Plugin;
import mindustry.type.Item;
import mindustry.type.ItemType;
import mindustry.type.UnitType;
import org.json.simple.JSONObject;
import theWorst.dataBase.*;
import theWorst.helpers.CoreBuilder;
import theWorst.helpers.MapManager;
import theWorst.helpers.Tester;
import theWorst.helpers.WaveSkipper;
import theWorst.interfaces.Interruptible;
import theWorst.interfaces.LoadSave;
import theWorst.requests.Factory;
import theWorst.requests.Loadout;
import theWorst.requests.Requester;

import java.io.File;
import java.util.Arrays;
import java.util.HashMap;

import static arc.util.Log.info;
import static java.lang.Math.min;
import static mindustry.Vars.*;

public class Main extends Plugin {
    public static final String directory = "config/mods/The_Worst/";
    static final String saveFile =directory + "save.json";
    static final String configFile =directory + "settings.json";




    public static final String[] itemIcons = {"\uF838", "\uF837", "\uF836", "\uF835", "\uF832", "\uF831", "\uF82F", "\uF82E", "\uF82D", "\uF82C"};
    public static String welcomeMessage;
    ArrayMap<String, LoadSave> loadSave = new ArrayMap<>();
    public static ArrayMap<String, Requester> configured = new ArrayMap<>();

    public static int transportTime = 3 * 60;

    public static Array<Item> items = new Array<>();
    public final int pageSize=15;
    static Array<Interruptible> interruptibles = new Array<>();
    private HashMap<String,String> dmMap = new HashMap<>();



    Timer.Task autoSaveThread;

    int defaultAutoSaveFrequency=5;

    MapManager mapManager;
    Factory factory;
    ServerPlayer serverPlayer;
    DiscordBot bot;

    ActionManager actionManager =new ActionManager();
    Loadout loadout = new Loadout();
    CoreBuilder builder = new CoreBuilder();
    WaveSkipper skipper = new WaveSkipper();
    Hud hud = new Hud();
    Database dataBase=new Database();
    Tester tester =new Tester();
    Vote vote = new Vote();
    VoteKick voteKick=new VoteKick();

    public Main() {


        Events.on(WorldLoadEvent.class, e -> interruptibles.forEach(Interruptible::interrupt));

        Events.on(ServerLoadEvent.class, e -> {
            netServer.admins.addChatFilter((player,message)->{
                if(!Database.hasEnabled(player,Setting.chat)){
                    Tools.errMessage(player,"You have chat disabled, use \"/set chat on\" to enable it.");
                    return null;
                }
                PlayerData pd=Database.getData(player);
                String msgColor="["+pd.textColor+"]";
                pd.lastAction= Time.millis();
                if(pd.rank==Rank.AFK){
                    Database.afkThread.run();
                }
                if(pd.trueRank==Rank.griefer){
                    if(Time.timeSinceMillis(pd.lastMessage)<10000L){
                        Tools.noPerm(player);
                        return null;
                    }
                    msgColor="[pink]";
                }else if(Tools.isCommandRelated(message) && vote.voting){
                    vote.addVote(player,message);
                    return null;
                }
                pd.lastMessage=Time.millis();
                pd.messageCount++;
                Database.updateRank(player,Stat.messageCount);
                Tools.sendChatMessage(player,msgColor+message);
                return null;
            });
            //so we can replace it with our own messages
            Administration.Config.showConnectMessages.set(false);
            load_items();
            bot = new DiscordBot();
            serverPlayer = new ServerPlayer();
            mapManager = new MapManager();
            mapManager.cleanup();
            if (!makeDir()) {
                Log.info("Unable to create directory " + directory + ".");
                return;
            }
            factory = new Factory(loadout);
            addToGroups();
            load();
            config();
            tester.loadQuestions();
            dataBase.loadRanks();
            autoSave(defaultAutoSaveFrequency);
            hud.update();
            hud.startCycle(10);


        });
    }

    private boolean makeDir() {
        File dir = new File(directory);
        if (!dir.exists()) {
            return dir.mkdir();
        }
        return true;
    }

    private void addToGroups(){
        interruptibles.add(loadout);
        interruptibles.add(factory);
        interruptibles.add(vote);
        interruptibles.add(actionManager);
        loadSave.put("loadout", loadout);
        loadSave.put("factory", factory);
        loadSave.put("Database",dataBase);
        loadSave.put("hud",hud);
        loadSave.put("bot",bot);
        configured.put("loadout", loadout);
        configured.put("factory", factory);
    }

    public static String milsToTime(long mils){
        long sec=mils/1000;
        long min=sec/60;
        long hour=min/60;
        long days=hour/24;
        return String.format("%d:%02d:%02d:%02d",
                days%365,hour%24,min%60,sec%60);
    }

    public void load() {
        dataBase.loadData();
        mapManager.load();
        Tools.loadJson(saveFile,(data)->{
            for (String k : loadSave.keys()) {
                if (data.containsKey(k) && loadSave.containsKey(k)) {
                    loadSave.get(k).load((JSONObject) data.get(k));
                }
            }
        },this::save);
    }

    public void save() {
        Tools.saveJson(saveFile,"Save updated.",()->{
            JSONObject saveData = new JSONObject();
            loadSave.keys().forEach((k) -> saveData.put(k, loadSave.get(k).save()));
            return saveData;
        });
        dataBase.saveData();
        mapManager.save();
    }

    public void config(){
        Tools.loadJson(configFile,(data)->{
            JSONObject content=(JSONObject)data.get("loadout");
            for(Object o:content.keySet()){
                loadout.getConfig().put((String)o,Tools.getInt(content.get(o)));
            }
            content=(JSONObject)data.get("factory");
            for(Object o:content.keySet()){
                factory.getConfig().put((String)o,Tools.getInt(content.get(o)));
            }
            welcomeMessage=(String) data.get("welcomeMessage");
            transportTime=Tools.getInt(data.get("transTime"));
        },this::createDefaultConfig);
    }

    private void createDefaultConfig() {
        Tools.saveJson(configFile,null,()->{
            JSONObject data = new JSONObject();
            JSONObject load = new JSONObject();
            for(String o:loadout.getConfig().keys()){
                load.put(o,Tools.getInt(loadout.getConfig().get(o)));
            }
            data.put("loadout",load);
            load =new JSONObject();
            for(String o:factory.getConfig().keys()){
                load.put(o,Tools.getInt(factory.getConfig().get(o)));
            }
            data.put("factory",load);
            data.put("transTime",180);
            data.put("welcomeMessage",null);
            return data;
        });
    }

    private void autoSave(int interval){
        if (autoSaveThread != null){
            autoSaveThread.cancel();
        }
        autoSaveThread = Timer.schedule(this::save,0,interval*60);
        Log.info("Autosave started.It will save every "+ interval +"min.");
    }

    public String formPage(Array<String > data,int page,String title,int pageSize){
        StringBuilder b=new StringBuilder();
        int pageCount=(int)Math.ceil(data.size/(float)pageSize);
        page=Mathf.clamp(page,1,pageCount)-1;
        int start=page*pageSize;
        int end=min(data.size,(page+1)*pageSize);
        b.append("[orange]--").append(title.toUpperCase()).append("(").append(page + 1).append("/");
        b.append(pageCount).append(")--[]\n\n");
        for(int i=start;i<end;i++){
            b.append(data.get(i)).append("\n");
        }
        return b.toString();
    }



    public void invalidArg(Player player, String arg){
        Tools.errMessage(player, "Invalid argument [orange]"+arg+"[], make sure you pick one of the options.");
    }

    public static Integer processArg(Player player, String what, String arg) {
        if(arg.length()>6){
            if(player==null){
                Log.info("Too long number! Max 6 digits.");
            }else {
                Tools.errMessage(player,"Too long number! Max 6 digits.");
            }
            return null;
        }
        if (!Strings.canParsePostiveInt(arg)) {
            if(player==null){
                Log.info(what + " has to be integer.[scarlet]" + arg + "[] is not.");
            }else {
                Tools.errMessage(player, what + " has to be integer.[orange]" + arg + "[] is not.");
            }
            return null;
        }
        return Integer.parseInt(arg);
    }

    public boolean notEnoughArgs(Player player,int amount,String[] args ){
        if(args.length<amount){
            String m="Not enough arguments, expected at least "+amount+".";
            if(player!=null){
                Tools.errMessage(player,m);
            }else{
                Log.info(m);
            }
            return true;
        }
        return false;
    }

    private void load_items() {
        for (Item i : content.items()) {
            if (i.type == ItemType.material) {
                items.add(i);
            }
        }
    }

    @Override
    public void registerServerCommands(CommandHandler handler) {
        handler.removeCommand("admin");

        handler.register("w-help","<ranks/factory/hud>","Shows better explanation and more information" +
                "about entered topic.",args->{
            switch (args[0]){
                case "ranks":
                    SpecialRank.help();
                    break;
                case "factory":
                    Log.info("Missing.");
                    break;
                case "hud":
                    Log.info("Missing.");
            }
        });

        handler.register("w-hud","[speed/remove/add] [timeInMin/idx/all/message...]","Set speed of " +
                "message cycle, remove messages by index and add messages to cycle. Use /hud to see messages you added."
                ,args->{
            if(args.length==0){

                StringBuilder b=new StringBuilder();
                for(int i=0;i<hud.messages.size;i++){
                    b.append(i).append("-").append(hud.messages.get(i)).append("\n");
                }
                Log.info("Message will change every "+hud.speed+"min, messages are:\n"+b.toString()+"");
                return;
            }
            if(notEnoughArgs(null,2,args)) return;
            switch (args[0]){
                case "speed":
                    if(!Strings.canParseInt(args[1])){
                        Log.info("Speed has to be integer.");
                        return;
                    }
                    hud.startCycle(Integer.parseInt(args[1]));
                    Log.info("Speed changed.");
                    return;
                case "remove":
                    if(args[1].equals("all")){
                        hud.messages.clear();
                        Log.info("All erased.");
                    }
                    if(!Strings.canParseInt(args[1])){
                        Log.info("Index has to be integer.");
                        return;
                    }
                    int val=Integer.parseInt(args[1]);

                    if(hud.messages.isEmpty()){
                        Log.info("No messages to erase.");
                        return;
                    }

                    if(val>=hud.messages.size){
                        Log.info("Too height number, max is "+hud.messages.size+".");
                        return;
                    }

                    Log.info("Message \""+hud.messages.remove(val)+"\" wos erased.");
                    hud.messageCycle.run();
                    return;
                case "add":
                    StringBuilder b=new StringBuilder();
                    for(int i=1;i<args.length;i++){
                        b.append(args[i]);
                    }
                    hud.messages.add(b.toString());
                    Log.info("Message \""+ b.toString()+"\" wos added.");
            }
        });

        handler.register("w-load", "Reloads theWorst saved data.", args -> load());

        handler.register("w-save", "Saves theWorst data.", args -> save());

        handler.register("w-unkick", "<ID/uuid>", "Erases kick status of player player.", args -> {
            PlayerData pd = Database.findData(args[0]);
            if (pd == null) {
                Log.info("Player not found.");
                return;
            }
            pd.getInfo().lastKicked = Time.millis();
            Log.info(pd.originalName + " is not kicked anymore.");
        });

        handler.register("w-database", "<search/online>", "Shows database,list of all players that " +
                "ewer been on server.Use search as in browser.", args ->{
            for(String s:args[0].equals("online") ? Database.getOnlinePlayersIndexes() :Database.getAllPlayersIndexes(args[0])){
                Log.info(Tools.cleanColors(s));
            }
                });

        handler.register("w-set-rank", "<uuid/name/id> <rank/restart> [reason...]", "", args -> {
            switch (Database.setRankViaCommand(null,args[0],args[1],args.length==3 ? args[2] : null)){
                case notFound:
                    Log.info("Player not found");
                    break;
                case invalidRank:
                    Log.info("Rank not found.\nRanks:" + Arrays.toString(Rank.values())+"\n" +
                            "Custom ranks:"+Database.ranks.keySet());
            }
        });

        handler.register("w-info", "<uuid/name/index>", "Displays info about player.", args -> {
            PlayerData pd = Database.findData(args[0]);
            if (pd == null) {
                Log.info("Player not found. Search by name applies only on online players.");
                return;
            }
            Log.info(pd.toString());
        });

        handler.register("w-spawn", "<mob_name> <count> <playerName> [team] ", "Spawn mob in player position.", args -> {
            if (playerGroup.size() == 0) {
                Log.info("There is no one logged, why bother spawning units?");
            }
            UnitType unitType = Factory.getUnitByName(args[0]);
            if (unitType == null) {
                Log.info(args[0] + " is not valid unit.");
                return;
            }
            if (!Strings.canParsePostiveInt(args[1])) {
                Log.info("count has to be integer.");
                return;
            }
            int count = Integer.parseInt(args[1]);
            Player player = Tools.findPlayer(args[2]);
            if (player == null) {
                Log.info("Player not found.");
                return;
            }
            Team team = args.length > 3 ? Tools.getTeamByName(args[3]) : Team.crux;
            for (int i = 0; i < count; i++) {
                BaseUnit unit = unitType.create(team);
                unit.set(player.x, player.y);
                unit.add();
            }
        });

        handler.register("w-map-stats","Shows all maps with statistics.",args-> Log.info(mapManager.statistics()));

        handler.register("w-map-cleanup","Removes data about already removed maps.",args->{
            mapManager.cleanup();
            Log.info("Data removed");
        });

        handler.register("w-config","<factory/test/general/ranks/discord>", "Applies the factory configuration,settings and " +
                "loads test questions.", args -> {
            switch (args[0]){
                case "factory":
                    factory.config();
                    break;
                case "test":
                    tester.loadQuestions();
                    break;
                case "general":
                    config();
                    break;
                case "ranks":
                    dataBase.loadRanks();
                    break;
                case "discord":
                    bot.connect();
                    break;
                default:
                    Log.info("Invalid argument.");
            }
        });

        handler.register("w-trans-time", "[value]", "Sets transport time.", args -> {
            if (args.length == 0) {
                Log.info("trans-time is " + transportTime + ".");
                return;
            }
            if (!Strings.canParsePostiveInt(args[0])) {
                Log.info(args[0] + " is not an integer.");
                return;
            }
            transportTime = Integer.parseInt(args[0]);
            Log.info("trans-time set to " + transportTime + ".");
        });

        handler.register("w-autoSave", "[frequency]", "Initializes autosave or stops it.", args -> {
            Integer frequency = processArg(null, "Frequency", args[0]);
            if (frequency == null) return;
            if (frequency == 0) {
                info("If you want kill-server command so badly, you can open an issue on github.");
                return;
            }
            autoSave(frequency);
        });

        handler.register("exit", "Exit the server application.", arg -> {
            info("Shutting down server.");
            save();
            net.dispose();
            DiscordBot.disconnect();
            Core.app.exit();
        });
    }

    @Override
    public void registerClientCommands(CommandHandler handler) {
        handler.removeCommand("vote");
        handler.removeCommand("votekick");
        handler.removeCommand("t");

        handler.<Player>register("link","<pin/refuse>","Links your account with discord if you provide " +
                "a link, or refuses link attempt",(args,player)->{
            PlayerData pd = Database.getData(player);
            if(!DiscordBot.pendingLinks.containsKey(pd.serverId)){
                Tools.errMessage(player,"You don't have any link request, make sure you entered right id on your discord.");
                return;
            }
            if(args[0].equals("refuse")){
                DiscordBot.pendingLinks.remove(pd.serverId);
                Tools.message(player,"Link request refused.");
                return;
            }
            if(args[0].equals(DiscordBot.pendingLinks.get(pd.serverId).pin)){
                pd.discordLink = DiscordBot.pendingLinks.remove(pd.serverId).id;
                Tools.message(player,"your account wos successfully linked, re-log to update your rank.");
                return;
            }
            Tools.errMessage(player,"Incorrect pin.");
        });

        handler.<Player>register("mute","<name/id> [unmute]","Mutes player for you.",(args,player)->{
            Player target = Tools.findPlayer(args[0]);
            if(target==null){
                Tools.errMessage(player,"Player does not exist or is not connected.");
                return;
            }
            if(target==player){
                Tools.errMessage(player,"You cannot mute your self.");
                return;
            }
            Database.switchSetting(player,target.uuid,args.length==2);
            Tools.message(player,target.name+" is "+(args.length==2 ? "unmuted":"muted")+".");
        });

        handler.<Player>register("mkgf","[playerName] [reason...]","Adds, or removes if payer is marked, griefer mark of given " +
                        "player name.",(args, player) ->{
            if(playerGroup.size() < 3 && !player.isAdmin) {
                Tools.errMessage(player,"At least 3 players are needed to mark a griefer.");
                return;
            }
            if(args.length == 0) {
                Tools.message(player,Tools.getPlayerList());
                return;
            }
            actionManager.reason=args.length==2 ? args[1] : "Reason not provided";
            Package p=actionManager.verify(player,args[0],0,false);
            if(p==null) return;
            vote.aVote(actionManager,p,"[pink]"+p.object+"[] griefer mark on/of [pink]"+((PlayerData)p.obj).originalName+"[]");
        });

        handler.<Player>register("emergency","[off]","Starts emergency.For admins only.",(args, player) ->{
            if(!player.isAdmin){
                Tools.errMessage(player,"Only admin can start or disable emergency.");
                return;
            }
            ActionManager.switchEmergency(args.length==1);
        });

        handler.<Player>register("maps","[page/rate/info/rules] [mapName/mapIdx/1-10]","Displays list maps,rates current map or " +
                        "display information about current or chosen map.",
                (args, player) -> {
            Integer page;
            if(args.length>0){
                if(args[0].equals("info") || args[0].equals("rules")){
                    String stats=args[0].equals("rules") ? mapManager.getMapRules(args.length==2 ? args[1]:null):
                            mapManager.getMapStats(args.length==2 ? args[1]:null);
                    if(stats==null){
                        Tools.errMessage(player,"Map not found.");
                        return;
                    }
                    Call.onInfoMessage(player.con,stats);
                    return;
                }
                if(args[0].equals("rate")){
                    if(notEnoughArgs(player,2,args)) return;
                    Integer rating=processArg(player,"rating",args[1]);
                    if(rating==null) return;
                    rating= Mathf.clamp(rating,1,10);
                    mapManager.rate(player,rating);
                    return;
                }
                page=processArg(player,"Page",args[0]);
                if(page==null)return;
            }else {
                page=1;
            }
            Call.onInfoMessage(player.con,formPage(mapManager.info(),page,"mpa list",pageSize));
        });

        handler.<Player>register("l", "<fill/use/info> [itemName/all] [itemAmount]",
                "Fill loadout with resources from core/send resources from loadout to core.", (args, player) -> {
            boolean use;
            switch (args[0]){
                case "info":
                    Call.onInfoMessage(player.con, loadout.info());
                    return;
                case "use":
                    use=true;
                    break;
                case "fill":
                    use=false;
                    break;
                default:
                    invalidArg(player, args[0]);
                    return;
            }
            if(notEnoughArgs(player,3,args)) return;

            Integer amount=processArg(player,"Item amount",args[2]);
            if(amount==null)return;

            Package p = loadout.verify(player, args[1], amount, use);
            if (p == null) return;

            vote.aVote(loadout, p, "launch [orange]" +
                    (p.object.equals("all") ? p.amount + " of all" : p.amount + " " + p.object) + "[] to "
                    + (use ? "core" : "loadout"));
        });

        handler.<Player>register("f", "<build/send/info/price> [unitName/all] [unitAmount]",
                "Build amount of unit or Send amount of units from hangar.",
                (args, player) -> {
            boolean send;
            switch (args[0]){
                case "info":
                    Call.onInfoMessage(player.con, factory.info());
                    return;
                case "price":
                    if(notEnoughArgs(player,2,args)) return;
                    int amount = args.length == 2 || !Strings.canParsePostiveInt(args[2]) ? 1 : Integer.parseInt(args[2]);
                    Call.onInfoMessage(player.con, factory.price(player, args[1], amount));
                    return;
                case "send":
                    send=true;
                    break;
                case "build":
                    send=false;
                    break;
                default:
                    invalidArg(player,args[0]);
                    return;
            }
            if( notEnoughArgs(player,3,args)) return;

            Integer amount=processArg(player,"Unit amount",args[2]);
            if(amount==null)return;

            Package p = factory.verify(player, args[1],amount, send);
            if (p == null) return;

            vote.aVote(factory, p, args[0] + " " + Tools.report(p.object, p.amount) + " units");
        });

        handler.<Player>register("build-core", "<small/normal/big>", "Makes new core.",
                (arg, player) -> {
            Package p = builder.verify(player, arg[0], 0, true);
            if (p == null) return;

            vote.aVote(builder, p, "building " + arg[0] + " core at "+p.x+" "+p.y);
        });

        handler.<Player>register("vote", "<map/skipwave/restart/gameover/kickallafk/y/n> [indexOrName/waveAmount]", "Opens vote session or votes in case of votekick.",
                (arg, player) -> {
            if(Database.getData(player).rank==Rank.AFK){
                Tools.errMessage(player,"You are "+Rank.AFK.getName()+", vote isn't enabled for you.");
                return;
            }
            Package p;
            String secArg = arg.length == 2 ? arg[1] : "0";
            switch (arg[0]) {
                case "map":
                    p = mapManager.verify(player, secArg, 0, false);
                    if (p == null) return;

                    vote.aVote(mapManager, p, "changing map to " + ((mindustry.maps.Map)p.obj).name());
                    return;
                case "skipwave":
                    Integer amount=processArg(player,"Wave amount",secArg);
                    if(amount==null)return;

                    p = skipper.verify(player, null, amount, false);
                    vote.aVote(skipper, p, "skipping " + p.amount + " waves");
                    return;
                case "restart":
                    vote.aVote(mapManager, new Package(null ,world.getMap(), player),
                            "restart the game");
                    return;
                case "gameover":
                    vote.aVote(mapManager, new Package(null ,null, player),
                            "gameover.");
                    return;
                case "kickallafk":
                    vote.aVote(dataBase,new Package(null,null ,player),"kick all afk players");
                case "y":
                    voteKick.vote(player,1);
                    return;
                case "n":
                    voteKick.vote(player,-1);
                    return;
                default:
                    invalidArg(player, arg[0]);
            }
        });

        handler.<Player>register("suicide","Kill your self.",(arg, player) -> {
            if(!Database.hasSpecialPerm(player,Perm.suicide)){
                Tools.noPerm(player);
                return;
            }
            player.onDeath();
            player.kill();
            Tools.message(player.name+"[white] committed suicide.");
            Timer.schedule(()->Tools.message("F..."),5);
        });

        handler.<Player>register("set","[settingName/textColor] [on/off/color]","Set your message color or " +
                        "enable/disable any setting.Write just /set for setting list.",(arg, player) ->{
            if(arg.length==0) {
                Tools.message(player,"Options:"+ Arrays.toString(Setting.values()));
                return;
            }
            if(notEnoughArgs(player,2,arg)) return;

            if(arg[0].equals("textColor")){
                Database.getData(player).textColor=arg[1];
                Tools.message(player,"["+arg[1]+"]if you see your color in brackets it probably isn t " +
                        "valid or recognized.");
                return;
            }
            try {
                Setting s=Setting.valueOf(arg[0]);
                Database.switchSetting(player,s,arg[1].equals("off"));
                Tools.message(player,"You toggled the [orange]"+s.name()+"[] "+arg[1]+".");
            } catch (IllegalArgumentException ex){
                Tools.errMessage(player,"Non existent setting. Use /set to see options.");
            }
                });

        handler.<Player>register("search","<searchKey/chinese/russian/sort/online/rank> [sortType/rankName] [reverse]",
                "Search for player by name, display all chinese or russian players, all online players," +
                        "all players with specified rank or sorted list of players.You can optionally invert the " +
                        "list when using sort option.",(arg,player)->{
            Array<String> res = Tools.getSearchResult(arg,player,null);
            if(res==null) return;
            int begin = Math.max(0,res.size-200);
            for(int i=begin;i<res.size;i++){
                player.sendMessage(res.get(i));
            }
            if(res.isEmpty()){
                Tools.errMessage(player,"No results found.");
            }
        });

        handler.<Player>register("info","[name/ID|list/ranks] [page] ","Displays info about you or another player.",
                (arg,player)-> {
            if(arg.length>=1){
                Integer page=1;
                if(arg.length==2){
                    page=processArg(player,"Page",arg[1]);
                    if(page==null)return;
                }
                if(arg[0].equals("list")){
                    Call.onInfoMessage(player.con, formPage(Database.getAllPlayersIndexes(null),
                            page, "player list",pageSize));
                    return;
                }
                if(arg[0].equals("ranks")){
                    Call.onInfoMessage(player.con, formPage(Database.getRankInfo(), page, "rank info",6));
                    return;
                }
                PlayerData pd=Database.findData(arg[0]);
                if(pd==null){
                    Tools.errMessage(player,"Player not found.");
                    return;
                }
                Call.onInfoMessage(player.con,pd.toString());
                return;
            }
            Call.onInfoMessage(player.con,Database.getData(player).toString());
                });

        handler.<Player>register("votekick", "[player]", "Vote to kick a player.", (args, player) -> {

            if(!Administration.Config.enableVotekick.bool()) {
                Tools.errMessage(player,"Vote-kick is disabled on this server.");
                return;
            }
            if(voteKick.voting){
                Tools.errMessage(player,"Votekick in process.");
                return;
            }
            if(playerGroup.size() < 3) {
                Tools.errMessage(player,"At least 3 players are needed to start a votekick.");
                return;
            }
            if(player.isLocal){
                Tools.errMessage(player,"Just kick them yourself if you're the host.");
                return;

            }
            if(args.length == 0) {
                player.sendMessage(Tools.getPlayerList());
                return;
            }

            voteKick.aVoteKick(args[0],player);

        });

        handler.<Player>register("test","<start/egan/quit/numberOfOption>","Complete the test to " +
                        "become verified player.",(args, player) -> tester.processAnswer(player,args[0]));

        handler.<Player>register("set-rank","<playerName/uuid/ID> <rank/restart> [reason...]","Command for admins.",(args,player)->{
            if(!player.isAdmin){
                Tools.errMessage(player,"You are not an admin.");
                return;
            }
            switch (Database.setRankViaCommand(player,args[0],args[1],args.length==3 ? args[2] : null)){
                case notFound:
                    Tools.errMessage(player,"Player not found");
                    break;
                case invalidRank:
                    Tools.errMessage(player,"Rank not found.\nRanks:" + Arrays.toString(Rank.values())+"\n" +
                            "Custom ranks:"+Database.ranks.keySet());
                    break;
                case notPermitted:
                    Tools.errMessage(player,"Changing or assigning admin rank can be done only thorough terminal.");
            }
        });
        handler.<Player>register("dm","[id/name/text...]", "Send direct message to player.", (args,player) -> {
            Player other=Tools.findPlayer(args[0]);
            if(other!=null) {
                dmMap.put(player.uuid,other.uuid);
                Tools.message(player,"Your dm target is now "+other.name+ ". You can just /dm text to send a message.");
                return;
            }
            if(!dmMap.containsKey(player.uuid)){
                Tools.errMessage(player,"No player found. Use /search online to see who you can connect with.");
                return;
            }
            other=playerGroup.find(p->p.uuid.equals(dmMap.get(player.uuid)));
            player.sendMessage("[#ffdfba][DM to "+other.name+"][white]:"+args[0]);
            other.sendMessage("[#ffdfba][DM from "+player.name+"][white]:"+args[0]);
        });
    }
}
