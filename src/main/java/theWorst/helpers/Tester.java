package theWorst.helpers;

import arc.struct.Array;
import arc.struct.ArrayMap;
import arc.util.Log;
import arc.util.Timer;
import mindustry.entities.type.Player;
import org.json.simple.JSONArray;
import theWorst.Main;
import theWorst.Tools;
import theWorst.dataBase.Database;
import theWorst.dataBase.Perm;
import theWorst.dataBase.PlayerData;
import theWorst.dataBase.Rank;

import java.io.FileWriter;
import java.io.IOException;

public class Tester {
    final String testFile="test.json";
    ArrayMap<String, Array<String>> questions=new ArrayMap<>();
    ArrayMap<String,int[]> tested=new ArrayMap<>();

    public void loadQuestions(){
        Tools.loadJson(Main.directory+testFile,(test)->{
            questions.clear();
            for(Object o:test.keySet()){
                JSONArray options=(JSONArray) test.get(o);
                Array<String> opt=new Array<>();
                for(Object op:options){
                    opt.add((String)op);
                }
                questions.put((String)o,opt);
            }
        },this::createExample);
    }

    private void createExample() {
            try (FileWriter file = new FileWriter(Main.directory + testFile)) {
                file.write("{" +
                        "\"some question?\":" +
                        "[" +
                            "\"1)option\"," +
                            "\"2)other option\"," +
                            "\"#3)right option starts with hashtag\"" +
                        "]," +
                        "\"some other question?\":" +
                        "[" +
                        "\"1)option\"," +
                        "\"2)other option\"," +
                        "\"#3)right option\"" +
                        "]" +
                        "}");
                Log.info("Example "+Main.directory + testFile+" created.");
            } catch (IOException ex) {
                Log.info("Error when creating example test.");
            }
    }

    public void ask(Player player,int idx){
        if(idx>=questions.size){
            if(tested.get(player.uuid)[1]==(float)questions.size){
                player.sendMessage(Main.prefix+"[green]Congratulation you passed the test! You will obtain " +
                        "rank VERIFIED which means that emergency no longer affects you.");
                Database.setRank(player, Rank.verified);
                tested.removeKey(player.uuid);
                return;
            }
            player.sendMessage(Main.prefix+"[scarlet]You failed the test.You can try it later.");
            Timer.schedule(()->tested.removeKey(player.uuid),15*60);
            return;
        }
        String question=questions.keys().toArray().get(idx);
        StringBuilder b=new StringBuilder();
        for(String option:questions.get(question)){
            b.append(option.replace("#","")).append("\n");
        }
        player.sendMessage(Main.prefix+question+"\n"+b.toString());
    }

    public void processAnswer(Player player,String answer){
        String uuid=player.uuid;
        PlayerData pd=Database.getData(player);
        if(pd.trueRank.permission.getValue()>=Perm.high.getValue()){
            player.sendMessage(Main.prefix+"You don t need test, you are already verified.");
            return;
        }
        if (pd.trueRank==Rank.griefer){
            player.sendMessage(Main.noPerm);
            return;
        }
        if(tested.containsKey(uuid) && tested.get(uuid)[0]>=questions.size){
            if(questions.size==0){
                player.sendMessage(Main.prefix+"No test available. If you want to become verified ask admin.");
            }
            player.sendMessage(Main.prefix+"[scarlet]You were tested recently.There is one hour cooldown.");
            return;
        }
        if(answer.equals("start")){
            if(!tested.containsKey(uuid)){
                player.sendMessage(Main.prefix+"Test started. Type /test (#) to answer.");
                tested.put(uuid,new int[2]);
                ask(player,0);
                return;
            }

            return;
        }
        if(!tested.containsKey(uuid)){
            player.sendMessage(Main.prefix+"You are not being tested.");
            return;
        }
        if(answer.equals("egan")){
            ask(player,tested.get(uuid)[0]);
            return;
        }
        if(answer.equals("quit")){
            tested.removeKey(uuid);
            player.sendMessage(Main.prefix+"Test exited.");
            return;
        }
        Integer idx=Main.processArg(player,"Answer",answer);
        if(idx==null){
            return;
        }
        Array<String> ques=questions.get(questions.keys().toArray().get(tested.get(uuid)[0]));
        if(idx>ques.size || idx==0){
            player.sendMessage(Main.prefix+"There are only "+ques.size+" options.");
            return;
        }
        if(ques.get(idx-1).startsWith("#")){
            tested.get(uuid)[1]+=1;
        }
        player.sendMessage(Main.prefix+"Answer received.");
        tested.get(player.uuid)[0]+=1;
        ask(player,tested.get(uuid)[0]);
    }

}
