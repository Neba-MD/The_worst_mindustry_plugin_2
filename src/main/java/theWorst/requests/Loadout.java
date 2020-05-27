package theWorst.requests;

import arc.Events;
import arc.struct.Array;
import arc.util.Timer;
import mindustry.entities.type.Player;
import mindustry.game.EventType;
import mindustry.game.Team;
import mindustry.game.Teams;
import mindustry.gen.Call;
import mindustry.type.Item;
import mindustry.world.blocks.storage.CoreBlock;
import mindustry.world.modules.ItemModule;
import org.json.simple.JSONObject;
import theWorst.Hud;
import theWorst.Main;
import theWorst.Package;
import theWorst.Tools;
import theWorst.dataBase.Database;
import theWorst.interfaces.Interruptible;
import theWorst.interfaces.LoadSave;
import theWorst.interfaces.Votable;

import static mindustry.Vars.playerGroup;
import static mindustry.Vars.state;


public class Loadout extends Requester implements Interruptible, LoadSave, Votable {

    int[] storage = new int[10];

    final String STORAGE_SIZE = "storage_size";
    final String AUTO_LAUNCH_REQ ="autoLaunchReq";
    final String AUTO_LAUNCH_AMOUNT ="autoLaunchAmount";
    final String colon="[gray]<L>[]";

    public Timer.Task autoLaunch;
    int autoLatchFrequency =3*60;

    public Loadout() {
        super();
        config.put(STORAGE_SIZE, 10000000);
        config.put(AUTO_LAUNCH_REQ,75);
        config.put(AUTO_LAUNCH_AMOUNT,2);

        autoLaunch=Timer.schedule(()->{
            Array<CoreBlock.CoreEntity> cores=state.teams.cores(Team.sharded);
            if(cores.isEmpty()) return;
            int storageSize=0;
            for(CoreBlock.CoreEntity ce:cores){
                storageSize+=ce.block.itemCapacity;
            }
            int total=0;
            ItemModule content=cores.first().items;
            for(Item i:Main.items){
                int amount = content.get(i);
                if (amount/(float)storageSize>(config.get(AUTO_LAUNCH_REQ)/100F)){
                    int toLaunch =(int)(amount*(config.get(AUTO_LAUNCH_AMOUNT)/100F));
                    total+=toLaunch;
                    content.remove(i,toLaunch);
                    storage[getItemIdx(i)]+=toLaunch;
                }
            }
            if(total==0) return;
            Hud.addAd("Total of [green]"+total+"[] resources were aromatically launched to [orange]loadout[].",10);
        },0, autoLatchFrequency);
    }

    public Item getItemByName(String name) {
        for (Item i : Main.items) {
            if (i.name.equals(name)) {
                return i;
            }
        }
        return null;
    }

    public int getItemIdx(Item item) {
        if (item == null) {
            return -1;
        }
        return Main.items.indexOf(item);
    }

    private boolean canTransport() {
        return requests.size<config.get(THREAD_COUNT);
    }

    public String info() {
        StringBuilder message = new StringBuilder();
        message.append("[orange]--LOADOUT INFO--[]\n\n");
        int size = config.get(STORAGE_SIZE);
        for (int i = 0; i < Main.items.size; i++) {
            int amount = storage[i];
            message.append(amount != size ? "[white]" : "[green]");
            message.append(amount).append(Main.itemIcons[i]).append("\n");
        }
        return message.toString();
    }


    @Override
    public void fail(String object, int amount) {
        Call.sendMessage(Main.prefix+"Ship with " + Tools.report(object, amount)
                + " is going back to loadout");
        storage[getItemIdx(getItemByName(object))] += amount;

    }

    @Override
    public void launch(Package p) {
        Timer.Task task;
        Database.getData(p.target).loadoutVotes++;
        Item targetItem = getItemByName(p.object);
        CoreBlock.CoreEntity core = getCore(p.target);
        if(core==null)return;
        int amount = getTransportAmount(targetItem, p.amount, core, p.toBase);
        int idx = getItemIdx(targetItem);
        if (p.toBase) {

            storage[idx] -= amount;
            int finalAmount = amount;
            task = new Timer.Task() {
                @Override
                public void run() {
                    core.items.add(targetItem, finalAmount);
                    Call.sendMessage(Main.prefix + "[green]" + Tools.report(p.object, p.amount) + " arrived to core.");
                }
            };
            requests.add(new Request(Main.transportTime, task, this, p, true));
            if(p.amount!=amount && canTransport()){
                launch(new Package(p.object,p.amount-amount,p.toBase,p.target));
            }
                p.amount=amount;
        } else {
            if (amount == -1) {
                idx = 0;
                for (Item i : Main.items) {
                    amount = getTransportAmount(i, p.amount, core, p.toBase);
                    core.items.remove(i, amount);
                    storage[idx] += amount;
                    idx++;
                }
            } else {
                core.items.remove(targetItem, amount);
                storage[idx] += amount;

            }
            Call.sendMessage(Main.prefix + "[green][orange]" + (p.object.equals("all") ? p.amount + " of all" : amount + " " + p.object) + "[] arrived to loadout.");
        }
    }

    @Override
    public Package verify(Player player, String object, int amount, boolean toBase) {
        if (requests.size == config.get(THREAD_COUNT) && toBase) {
            player.sendMessage(Main.prefix + " All the ships are occupied at the moment.");
            return null;
        }
        Item targetItem = getItemByName(object);
        if (targetItem == null && !(object.equals("all") && !toBase)) {
            player.sendMessage(Main.prefix + "The [scarlet] " + object + "[] doesn't exist.");
            return null;
        }
        CoreBlock.CoreEntity core = getCore(player);
        if(core==null){
            player.sendMessage(Main.prefix + "Loadout cannot find any core.");
            return null;
        }
        if (getTransportAmount(targetItem, amount, core, toBase) == 0) {
            player.sendMessage(Main.prefix + "Nothing to transport.");
            return null;
        }
        return new Package(object, amount, toBase, player);
    }

    public static CoreBlock.CoreEntity getCore(Player p) {
        Teams.TeamData teamData = state.teams.get(p.getTeam());
        if(teamData.cores.isEmpty()) return null;
        return teamData.cores.first();
    }


    public int getTransportAmount(Item item, int amount, CoreBlock.CoreEntity core, boolean toBase) {
        if (item == null) {
            return -1;
        }

        int maxTransport = config.get(MAX_TRANSPORT);
        int capacity = config.get(STORAGE_SIZE);
        int loadout_amount = storage[getItemIdx(item)];
        int core_amount = core.items.get(item);

        if (toBase) {
            if (loadout_amount < amount) {
                amount = loadout_amount;
            }
            if (amount > maxTransport) {
                amount = maxTransport;
            }
        } else {
            if (amount > core_amount) {
                amount = core_amount;
            }
            if (loadout_amount + amount > capacity) {
                amount = capacity - loadout_amount;
            }
        }
        return amount;
    }

    @Override
    public void interrupt() {
        requests.forEach(Request::interrupt);
        requests.clear();
    }

    @Override
    public String getHudInfo() {
        StringBuilder b=new StringBuilder();
        int free=config.get(THREAD_COUNT);
        b.append(colon);
        for (Request r:requests){
            free-=1;
            b.append(String.format("%d:%02d", r.time / 60, r.time % 60))
                    .append(" trans ").append(Tools.report(r.aPackage.object,r.aPackage.amount));
            b.append(colon);
        }
        while (free>0){
            free-=1;
            b.append("[green]free[]");
            b.append(colon);
        }
        return b.toString();
    }

    @Override
    public JSONObject save() {
        JSONObject data = new JSONObject();
        int idx = 0;
        for (Item item : Main.items) {
            data.put(item.name, storage[idx]);
            idx++;
        }
        return data;
    }

    @Override
    public void load(JSONObject data) {
        int idx = 0;
        for (Item item : Main.items) {

            Integer val = Tools.getInt(data.get(item.name));
            storage[idx] = val;
            idx++;
        }
    }

}
