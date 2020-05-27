package theWorst;

import arc.struct.Array;
import arc.util.Strings;
import arc.util.Time;
import arc.util.Timer;
import mindustry.entities.type.Player;
import mindustry.gen.Call;
import mindustry.net.Packets;
import theWorst.dataBase.Database;
import theWorst.dataBase.Rank;

import static mindustry.Vars.player;
import static mindustry.Vars.playerGroup;

public class VoteKick{
    final int kickDuration=60*60;
    Player target;
    Array<String> voted = new Array<>();
    Timer.Task task;
    int votes;
    boolean voting=false;

    public void aVoteKick(String arg,Player player){
        Player found;
        if(arg.length() > 1 && arg.startsWith("#") && Strings.canParseInt(arg.substring(1))){
            int id = Strings.parseInt(arg.substring(1));
            found = playerGroup.find(p -> p.id == id);
        }else{
            found = playerGroup.find(p -> p.name.equalsIgnoreCase(arg));
        }
        if(found == null){
            found=Tools.findPlayer(arg);
            if(found == null) {
                player.sendMessage(Main.prefix+"No player[orange]'" + arg + "'[] found.");
                return;
            }
        }

        if(found.isAdmin){
            player.sendMessage(Main.prefix+"Did you really expect to be able to mark an admin?");
            return;
        }
        if(found==player){
            player.sendMessage(Main.prefix+"You cannot kick your self.");
            return;
        }
        if(Database.isGriefer(player)){
            player.sendMessage("You don t have a permission to do this.");
            return;
        }
        voting=true;
        votes=0;
        this.target = found;
        vote(player, 1);
        this.task = Timer.schedule(() -> {
            if(checkPass()){
                Call.sendMessage(Strings.format(Main.prefix+"Vote failed. Not enough votes to kick[green] {0}[].", target.name));
                restart();
            }
        }, 60);
    }

    void vote(Player player, int d){
        if (voted.contains(player.con.address)) {
            player.sendMessage(Main.prefix + "You already voted,sit down.");
            return;
        }
        if(player==target){
            player.sendMessage(Main.prefix+"You cannot vote on your own trial.");
            return;
        }
        if(!voting){
            player.sendMessage(Main.prefix+"No votekick to vote for, you may try to write just \"y\" or \"n\" to chat.");
            return;
        }
        votes += d;
        voted.add(player.con.address);
        if(checkPass()){
            Call.sendMessage(Strings.format(Main.prefix+"[orange]{0}[] has voted on kicking[scarlet] {1}[]. ({2}/{3})\nType [orange]/vote <y/n>[] to agree.",
                    player.name, target.name, votes, Vote.getRequired()));
        }

    }

    boolean checkPass(){
        if(votes >= Vote.getRequired()){
            Call.sendMessage(Strings.format(Main.prefix+"Vote passed.[scarlet] {0}[] will be banned from the server for {1} minutes.", target.name, (kickDuration/60)));
            target.getInfo().lastKicked = Time.millis() + kickDuration*1000;
            playerGroup.all().each(p -> p.uuid != null && p.uuid.equals(target.uuid), p -> p.con.kick(Packets.KickReason.vote));
            Database.setRank(target, Rank.griefer);
            restart();
            return false;
        }
        return true;
    }
    private void restart(){
        task.cancel();
        voting=false;
        voted.clear();
    }
}
