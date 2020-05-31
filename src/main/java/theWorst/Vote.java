package theWorst;

import arc.math.Mathf;
import arc.util.Timer;
import mindustry.entities.type.Player;
import mindustry.gen.Call;
import theWorst.dataBase.Database;
import theWorst.dataBase.Perm;
import theWorst.dataBase.PlayerData;
import theWorst.dataBase.Rank;
import theWorst.helpers.MapManager;
import theWorst.interfaces.Interruptible;
import theWorst.interfaces.Votable;
import theWorst.requests.Factory;
import theWorst.requests.Loadout;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

import static mindustry.Vars.player;
import static mindustry.Vars.playerGroup;

public class Vote implements Interruptible {
    static Votable votable;
    Package aPackage;

    String message;

    Set<String> voted = new HashSet<>();
    HashMap<String,Integer> recent = new HashMap<>();

    Timer.Task alert;
    Timer.Task task;

    boolean voting = false;

    int voteCooldown =60;
    int no, yes;
    int time;


    public void aVote(Votable votable, Package aPackage, String message) {
        Player requester=aPackage.target;
        if(Database.hasPerm(requester, Perm.highest) ||
                (votable instanceof Factory && Database.hasSpecialPerm(requester,Perm.factory))
                || (votable instanceof Loadout && Database.hasSpecialPerm(requester,Perm.loadout))){

            votable.launch(aPackage);
            Hud.addAd(requester.name+" just did "+message,10,new String[]{"gray","green"});
            return;
        }
        if (voting) {
            Tools.errMessage(requester, "Vote in process.");
            return;
        }
        if (Database.isGriefer(requester)){
            Tools.noPerm(player);
            return;
        }
        if(isRecent(requester)){
            int time=recent.get(requester.con.address);
            Tools.errMessage(requester,"Your last vote failed,to prevent spam you have to wait "
                    +time / 60 + "min" + time % 60 + "sec.");
            return;
        }
        Vote.votable = votable;
        this.aPackage = aPackage;
        this.message = message;
        restart();
        addVote(requester,"y");
        Tools.message(requester,"[orange]"+  requester.name +
                "[white] started vote for " + message + ". Send a message with \"y\" to agree or \"n\" to disagree.");
    }

    public void restart(){
        voted.clear();
        yes=0;
        no=0;
        time=voteCooldown;
        voting = true;
        alert = Timer.schedule(() -> {
            time--;
            if (time == 0) {
                alert.cancel();
            }
        }, 0, 1);
        task = Timer.schedule(() -> close(false), voteCooldown);
    }

    private boolean isRecent(Player player) {
        return recent.containsKey(player.con.address);
    }

    public void addToRecent(Player player){
        if(Database.hasPerm(player, Perm.high))return;
        recent.put(player.con.address, voteCooldown);
        Timer.schedule(new Timer.Task(){
            @Override
            public void run() {
                int time=recent.get(player.con.address);
                if(time==0){
                    recent.remove(player.con.address);
                    this.cancel();
                    return;
                }
                recent.put(player.con.address,time-1);
            }
        },0,1);

    }

    public static int getRequired() {
        int count = 0;
        for(Player p:playerGroup){
            if(Database.hasThisPerm(p,Perm.none)) continue;
            count+=1;
        }
        if (count == 2) {
            return 2;
        }
        int res = (int) Math.ceil(count / 2.0);
        if(!(votable instanceof MapManager)) res =Mathf.clamp(res,1,5);
        return  res;
    }

    public void addVote(Player player, String vote) {
        PlayerData pd=Database.getData(player);
        if (voted.contains(player.con.address)) {
            Tools.errMessage( player, "You already voted,sit down!");
            return;
        }

        if (pd.trueRank == Rank.griefer){
            Tools.noPerm(player);
            return;
        }

        if(pd.rank == Rank.AFK){
            Tools.errMessage( player, "You are "+Rank.AFK.getName()+", you cannot vote.");
            return;
        }

        voted.add(player.con.address);

        int req=getRequired();

        if(votable instanceof ActionManager && player.isAdmin){
            close( vote.equals("y"));
            return;
        }

        if (vote.equals("y")) {
            yes += 1;
            if (yes >= req) {
                close(true);
            }
        } else {
            no += 1;
            if (no >= req) {
                close(false);
            }
        }
    }

    public void close(boolean success) {
        if (!voting) {
            return;
        }
        voting = false;
        task.cancel();
        alert.cancel();
        String result ="vote-" + message;
        if (success) {
            votable.launch(aPackage);
            Hud.addAd(result+"-done",10,new String[]{"green","gray"});
        } else {
            addToRecent(aPackage.target);
            Hud.addAd(result+"-failed",10,new String[]{"scarlet","gray"});
        }
    }

    @Override
    public void interrupt() {
        close(false);
    }

    @Override
    public String getHudInfo() {
        if(!voting) return null;
        String color=time<10 && time%2==0 ? "gray":"white";
        return String.format("[%s]vote for %s %02ds [green] %d [][scarlet] %d [][gray]req %d[][]",
                color,message,time,yes,no,getRequired());
    }
}
