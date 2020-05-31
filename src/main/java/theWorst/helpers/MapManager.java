package theWorst.helpers;

import arc.Events;
import arc.struct.Array;
import arc.util.Log;
import arc.util.Strings;
import arc.util.Time;
import mindustry.Vars;
import mindustry.entities.type.Player;
import mindustry.game.*;
import mindustry.gen.Call;
import mindustry.maps.Map;
import theWorst.Hud;
import theWorst.Main;
import theWorst.Package;
import theWorst.Tools;
import theWorst.interfaces.Votable;

import java.io.*;
import java.util.HashMap;

import static mindustry.Vars.*;

public class MapManager implements Votable {
    final String saveFile=Main.directory+"mapData.ser";
    static final int defaultAirWave=1000000;
    public Map currentMap=maps.all().first();
    static HashMap<String,mapData> data=new HashMap<>();

    public MapManager(){

        Events.on(EventType.GameOverEvent.class, e -> endGame(e.winner==Team.sharded));

        Events.on(EventType.PlayEvent.class, e-> {
            currentMap=world.getMap();
            String name=currentMap.name();
            if(!data.containsKey(name)) data.put(name,new mapData(currentMap));
            else {
                data.get(name).started=Time.millis();
            }
            Hud.addAd(getWaveInfo(),30);
        });
    }

    public static double getMapRating(Map map){
        if(data.get(map.name())==null) return 5d;
        return data.get(map.name()).getRating();
    }

    @Override
    public void launch(Package p) {
        Map map=(Map)p.obj;
        if(map==null){
            Events.fire(new EventType.GameOverEvent(Team.crux));
            return;
        }

        Array<Player> players = new Array<>();
        for(Player player : playerGroup.all()) {
            players.add(player);
            player.setDead(true);
        }
        endGame(false);
        logic.reset();
        Call.onWorldDataBegin();
        world.loadMap(map, map.applyRules(Gamemode.survival));
        state.rules = world.getMap().applyRules(Gamemode.survival);
        logic.play();


        for(Player player : players){
            if(player.con == null) continue;

            player.reset();
            netServer.sendWorldData(player);
        }
    }

    @Override
    public Package verify(Player player, String object, int amount, boolean toBase) {

        Map map = findMap(object);

        if (map == null) {
            Tools.errMessage( player, "Map not found.");
            return null;
        }

        return new Package(object, map, player);
    }

    public static Map findMap(String object) {
        Array<Map> mapList = maps.all();
        if (!Strings.canParsePostiveInt(object)) {
            return maps.all().find(m -> m.name().equalsIgnoreCase(object.replace('_', ' '))
                    || m.name().equalsIgnoreCase(object));
        }
        int idx = Integer.parseInt(object);
        if (idx < mapList.size) {
            return maps.all().get(idx);
        }
        return null;
    }

    public Array<String> statistics(){
        Array<String> res=new Array<>();
        Array<mapData> maps=Array.with(data.values());
        double bestRatio=0;
        for (mapData m:maps){
            double r=m.getPlayRatio();
            if (r>bestRatio) bestRatio=r;
        }
        for (int i=0;i<maps.size;i++){
            mapData m=maps.get(i);
            int ra=(int)(m.getPlayRatio()/bestRatio*10);
            res.add(i+" | "+m.name+" | "+String.format("%.1f/10",m.getRating())+" | "+
                "<"+new String(new char[ra]).replace("\0", "=")+
                    new String(new char[10-ra]).replace("\0", "-")+">\n");
        }
        return res;
    }

    public Array<String> info() {
        Array<mindustry.maps.Map> maps=Vars.maps.customMaps();
        Array<String> res=new Array<>();
        for (int i=0;i<maps.size;i++){
            String m=maps.get(i).name();
            mapData md=data.get(m);
            int r= md==null ? 5:(int)md.getRating();
            res.add("[yellow]"+i+"[] | [gray]"+m+"[] | "+String.format("[%s]%d/10[]",r<6 ? r<3 ? "scarlet":"yellow":"green",r));
        }
        return res;
    }

    public String getMapStats(String identifier){
        Map map= identifier==null ? world.getMap():findMap(identifier);
        if(map==null){
            return null;
        }
        if(!data.containsKey(map.name())) {
            return "[gray]No info about this map yet.";
        }
        return "[orange]--MAP STATS--[]\n\n"+data.get(map.name()).toString();
    }

    public String getMapRules(String identifier){
        Map map= identifier==null ? world.getMap():findMap(identifier);
        if(map==null){
            return null;
        }
        Rules rules=map.rules();
        int firstAirWave=data.get(map.name()).firstAirWave;
        return "[orange]--MAP RULES--[]\n\n"+
                String.format("[gray]name:[] %s\n" +
                        "[gray]mode:[] %s\n" +
                        "[gray]first air-wave:[] "+(firstAirWave==defaultAirWave ? "none":firstAirWave)+"\n " +
                        "[orange]Multipliers[]\n" +
                        "[gray]build cost:[] %.2f\n"+
                        "[gray]build speed:[] %.2f\n"+
                        "[gray]block health:[] %.2f\n"+
                        "[gray]unit build speed:[] %.2f\n"+
                        "[gray]unit damage:[] %.2f\n"+
                        "[gray]unit health:[] %.2f\n"+
                        "[gray]player damage:[] %.2f\n"+
                        "[gray]player health:[] %.2f\n"+
                "",map.name(),rules.mode().name(),
                rules.buildCostMultiplier,
                rules.buildSpeedMultiplier,
                rules.blockHealthMultiplier,
                rules.unitBuildSpeedMultiplier,
                rules.unitDamageMultiplier,
                rules.unitHealthMultiplier,
                rules.playerDamageMultiplier,
                rules.playerHealthMultiplier);
    }



    public void endGame(boolean won) {
        if(currentMap==null){
            return;
        }
        mapData md=data.get(currentMap.name());
        if(md==null) return;
        md.playtime+=Time.timeSinceMillis(md.started);
        md.timesPlayed++;
        if(won) md.timesWon++;
        if(state.wave>md.waveRecord) md.waveRecord=state.wave;
        currentMap=null;
    }

    public void rate(Player player,int rating){
        mapData md=data.get(world.getMap().name());
        md.ratings.put(player.uuid,(byte)rating);
        Tools.message(player,String.format("You gave [orange]%d/10[] to map [orange]%s[].",rating,world.getMap().name()));
    }

    public void cleanup() {
        Array<String> toRemove=new Array<>();
        for(String md:data.keySet()){
            if(maps.byName(md)==null){
                toRemove.add(md);
            }
        }
        toRemove.forEach(m->data.remove(m));
    }

    static class mapData implements Serializable {
        int timesPlayed=0;
        int timesWon=0;
        int waveRecord=0;
        int firstAirWave=defaultAirWave;
        long started=Time.millis();
        long playtime=0;
        long bornDate=Time.millis();

        String name;
        HashMap<String,Byte> ratings=new HashMap<>();

        mapData(Map map){
            name=map.name();
            for(SpawnGroup sg:map.rules().spawns){
                if(sg.type.flying && sg.begin<firstAirWave) firstAirWave=sg.begin;
            }
        }

        double getPlayRatio(){
            return (playtime-timesPlayed*1000*60*5)/(double)Time.timeSinceMillis(bornDate);
        }

        public String toString(){
            return "[gray]name:[] " + name + "\n" +
                    "[gray]author:[] " + maps.byName(name).author() + "\n" +
                    "[gray]first air-wave:[] " + (firstAirWave==defaultAirWave ? "none":firstAirWave) + "\n" +
                    "[gray]times played:[] " + timesPlayed + "\n" +
                    "[gray]times won:[] " + timesWon + "\n" +
                    "[gray]wave record:[] " + waveRecord + "\n" +
                    "[gray]server age:[] " + Main.milsToTime(Time.timeSinceMillis(bornDate)) + "\n" +
                    "[gray]total play time:[] " + Main.milsToTime(playtime) + "\n" +
                    String.format("[gray]rating:[] %.1f/10",getRating());
        }

        public float getRating(){
            if (ratings.size()==0) return 0;
            float total=0;
            for(byte b:ratings.values()){
                total+=b;
            }
            return total/ratings.size();
        }


    }

    public String getWaveInfo(){
        if(data.get(currentMap.name()).firstAirWave==defaultAirWave) return "No air waves on this map.";
        return "Air enemy starts at wave [orange]"+data.get(currentMap.name()).firstAirWave+"[] !";
    }

    public void save() {
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

    public void load(){
        try {
            FileInputStream fileIn = new FileInputStream(saveFile);
            ObjectInputStream in = new ObjectInputStream(fileIn);
            Object obj=in.readObject();
            if(obj instanceof HashMap) data = (HashMap<String, mapData>) obj;
            in.close();
            fileIn.close();
            Log.info("Map data loaded");
        } catch (ClassNotFoundException c) {
            Log.info("class not found");
            c.printStackTrace();
        } catch (FileNotFoundException f){
            Log.info("database no found, creating new");
        }catch (IOException i){
            Log.info("Map database is incompatible with current version.");
        }
    }
}
