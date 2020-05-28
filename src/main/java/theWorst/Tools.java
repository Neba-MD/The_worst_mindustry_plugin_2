package theWorst;

import arc.struct.Array;
import arc.util.Log;
import arc.util.Tmp;
import mindustry.entities.type.Player;
import mindustry.game.Team;
import mindustry.io.MapIO;
import mindustry.world.Tile;
import org.javacord.api.entity.channel.TextChannel;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import theWorst.dataBase.Database;
import theWorst.dataBase.Perm;
import theWorst.dataBase.Rank;
import theWorst.dataBase.Stat;
import theWorst.interfaces.runLoad;
import theWorst.interfaces.runSave;

import java.awt.image.BufferedImage;
import java.io.*;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Set;

import static mindustry.Vars.*;

public class Tools {
    public static String toString(Array<String> struct){
        StringBuilder b=new StringBuilder();
        for(String s :struct){
            b.append(s).append(" ");
        }
        return b.toString();
    }

    public static Team getTeamByName(String name) {
        for (Team t : Team.all()) {
            if (t.name.equals(name)) {
                return t;
            }
        }
        return null;
    }

    public static String cleanName(String name){
        name=cleanColors(name);
        while (name.contains("<")){
            int first=name.indexOf("<"),last=name.indexOf(">");
            name=name.substring(0,first)+name.substring(last+1);
        }
        name=name.replace(" ","_");
        return name;
    }

    public static String cleanColors(String string){
        while (string.contains("[")){
            int first=string.indexOf("["),last=string.indexOf("]");
            if(first==-1 || last==-1 || last<first) break;
            else string=string.substring(0,first)+string.substring(last+1);
        }
        return string;
    }

    public static String getPlayerList(){
        StringBuilder builder = new StringBuilder();
        builder.append("[orange]Players: \n");
        for(Player p : playerGroup.all()){
            if(p.isAdmin || p.con == null || p == player || Database.hasPerm(p, Perm.higher)) continue;

            builder.append("[lightgray] ").append(p.name).append("[accent] (#").append(p.id).append(")\n");
        }
        return builder.toString();
    }

    public static Player findPlayer(String name) {
        for(Player p:playerGroup){
            String pName=Tools.cleanName(p.name);
            if(pName.equalsIgnoreCase(name)){
                return p;
            }
        }
        return null;
    }



    public static boolean isNotInteger(String str) {
        if (str == null || str.trim().isEmpty()) {
            return true;
        }
        for (int i = 0; i < str.length(); i++) {
            if (!Character.isDigit(str.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    public static String report(String object, int amount) {
        return "[orange]" + (object.equals("all") ? "all" : amount + " " + object) + "[]";
    }

    public static Integer getInt(Object integer) {
        if (integer instanceof Integer) {
            return (int) integer;
        }
        if (integer instanceof Long) {
            return ((Long) integer).intValue();
        }
        return null;
    }

    public static void loadJson(String filename, runLoad load, Runnable save){
        try (FileReader fileReader = new FileReader(filename)) {
            JSONParser jsonParser = new JSONParser();
            Object obj = jsonParser.parse(fileReader);
            JSONObject saveData = (JSONObject) obj;
            load.run(saveData);
            fileReader.close();
            Log.info("Data from "+filename+" loaded.");
        } catch (FileNotFoundException ex) {
            Log.info("No "+filename+" found.Default one wos created.");
            save.run();
        } catch (ParseException ex) {
            Log.info("Json file "+filename+" is invalid.");
        } catch (IOException ex) {
            Log.info("Error when loading data from " + filename + ".");
        }
    }

    public static void saveJson(String filename,String success, runSave save){

        try (FileWriter file = new FileWriter(filename)) {
            file.write(save.run().toJSONString());
            file.close();
            Log.info(success==null ? "Default "+ filename+ " created" : success);
        } catch (IOException ex) {
            Log.info("Error when creating/updating "+filename+".");
        }
    }

    public static void downloadFile(InputStream in, String dest) throws IOException {
        Files.copy(in, Paths.get(dest), StandardCopyOption.REPLACE_EXISTING);
    }

    public static BufferedImage getMiniMapImg() {
        BufferedImage img = new BufferedImage(world.width(), world.height(),BufferedImage.TYPE_INT_ARGB);
        for(int x = 0; x < img.getWidth(); x++){
            for(int y = 0; y < img.getHeight(); y++){
                Tile tile = world.tile(x,y);
                img.setRGB(x, img.getHeight() - 1 - y, Tmp.c1.set(MapIO.colorFor(tile.floor(), tile.block(), tile.overlay(), tile.getTeam())).argb8888());
            }
        }
        return img;
    }

    public static class JsonMap {
        private final JSONObject data;
        public String[] keys;

        public JsonMap(JSONObject data) {
            this.data = data;
            keys = new String[data.size()];
            int i = 0;
            for (Object o : data.keySet()) {
                keys[i] = (String) o;
                i++;
            }
        }

        public String getString(String key) {
            return (String) data.get(key);
        }

        public boolean containsKey(String key) {
            return data.containsKey(key);
        }

        public Integer getInt(String key) {
            if (!data.containsKey(key)) return null;
            return ((Long) data.get(key)).intValue();
        }

        public JSONObject getJsonObj(String key){
            return (JSONObject) data.get(key);
        }
    }

    public static Array<String> getSearchResult(String[] arg, Player player, TextChannel channel){
        Array<String> res;
        if(arg.length==1) {
            switch (arg[0]) {
                case "rank":
                    String msg="Available ranks: " + Arrays.toString(Rank.values()) +
                            "\nAvailable special ranks:" + Database.ranks.toString();
                    if (player!=null){
                        player.sendMessage(Main.prefix + msg);
                    }else {
                        channel.sendMessage(msg);
                    }
                    return null;
                case "sort":
                    msg = "Available sort types: " + Arrays.toString(Stat.values());
                    if (player!=null){
                        player.sendMessage(Main.prefix + msg);
                    }else {
                        channel.sendMessage(msg);
                    }
                    return null;
                case "chinese":
                    res = Database.getAllChinesePlayersIndexes();
                    break;
                case "russian":
                    res = Database.getAllRussianPlayersIndexes();
                    break;
                case "online":
                    res = Database.getOnlinePlayersIndexes();
                    break;
                default:
                    res = Database.getAllPlayersIndexes(arg[0]);
                    break;
            }
        }else {
            res =arg[0].equals("sort") ? Database.getSorted(arg[1]):Database.getAllPlayersIndexesByRank(arg[1]);
            if (res == null) {
                String msg ="Invalid sort type, for list of available see /search sort";
                if (player!=null){
                    player.sendMessage(Main.prefix + msg);
                }else {
                    channel.sendMessage(msg);
                }
                return null;
            }
            if(arg.length==3){
                res.reverse();
            }
        }
        return res;
    }

    public static String findBestMatch(String word, Set<String> pool){
        int bestPoints = 0;
        String bestMatch = null;
        for(String s:pool){
            String shorter = word.length()>s.length() ? s:word;
            int points=0;
            for(int i=0;i<shorter.length();i++){
                if(word.charAt(i)==s.charAt(i)) points++;
            }
            if(points>bestPoints) {
                bestPoints=points;
                bestMatch=s;
            }
        }
        return bestMatch;
    }

    public static boolean isCommandRelated(String message){
        return message.startsWith("/") || message.equalsIgnoreCase("y") || message.equalsIgnoreCase("n");
    }
}
