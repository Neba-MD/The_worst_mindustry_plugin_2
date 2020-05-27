package theWorst.requests;

import arc.Events;
import arc.struct.Array;
import arc.struct.ArrayMap;
import arc.util.Log;
import arc.util.Timer;
import mindustry.entities.type.BaseUnit;
import mindustry.entities.type.Player;
import mindustry.entities.type.base.BuilderDrone;
import mindustry.game.EventType;
import mindustry.gen.Call;
import mindustry.type.Item;
import mindustry.type.UnitType;
import org.json.simple.JSONObject;
import theWorst.Main;
import theWorst.Package;
import theWorst.Tools;
import theWorst.dataBase.Database;
import theWorst.interfaces.Interruptible;
import theWorst.interfaces.LoadSave;
import theWorst.interfaces.Votable;

import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;

import static java.lang.Math.pow;
import static java.lang.Math.sqrt;
import static mindustry.Vars.content;
import static mindustry.Vars.world;

public class Factory extends Requester implements Interruptible, LoadSave, Votable {
    final int UNIT_COUNT = 13;

    enum idx{
        buildLimit(10),
        buildTime(11),
        unitSize(12);

        public int i;

        idx(int i){
            this.i=i;
        }
    }

    final String colon="[gray]<F>[]";

    final String configFile = Main.directory+"factoryConfig.json";

    ArrayMap<String, int[]> stats = new ArrayMap<>();
    Array<String> statKeys = new Array<>();
    Loadout loadout;





    public Factory(Loadout loadout) {
        super();
        this.loadout = loadout;
        config.put(MAX_TRANSPORT,50);
        for (Item item : Main.items) {
            statKeys.add(item.name);
        }
        for (idx i:idx.values()){
            statKeys.add(i.name());
        }

        Events.on(EventType.BuildSelectEvent.class, e -> {
            Array<Request> requests = getRequests();
            boolean canPlace = true;
            for (Request r : requests) {
                double dist = sqrt((pow(e.tile.x - (float) (r.aPackage.x / 8), 2) +
                        pow(e.tile.y - (float) (r.aPackage.y / 8), 2)));
                if (dist < 5) {
                    canPlace = false;
                    break;
                }
            }
            if (!canPlace) {
                e.tile.removeNet();
                if (e.builder instanceof BuilderDrone) {
                    ((BuilderDrone) e.builder).kill();
                    Call.sendMessage(Main.prefix + "Builder Drone wos destroyed after it attempt to build on drop point");
                } else if (e.builder instanceof Player) {
                    ((Player) e.builder).sendMessage(Main.prefix + "You cannot build on unit drop point.");
                }
            }

        });

        config();
    }


    public String getHudInfo(){
        StringBuilder b=new StringBuilder();
        int free=config.get(THREAD_COUNT);
        b.append(colon);
        for (Request r:requests){
            free-=1;
            b.append(String.format("%d:%02d", r.time / 60, r.time % 60))
                .append(r.stoppable ? " trans ":" build ").append(Tools.report(r.aPackage.object,r.aPackage.amount));
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
    public void fail(String object, int amount) {
        if (object.equals("all")) {
            Call.sendMessage(Main.prefix + "Ship with all units don t have enough fuel to go back.All units are lost.");
            return;
        }
        stats.get(object)[UNIT_COUNT] += amount;
        Call.sendMessage(Main.prefix + Tools.report(object, amount) + " are going back to base.");
    }

    @Override
    public void launch(Package p) {
        Request req;
        Database.getData(p.target).factoryVotes++;
        if (p.toBase) {
            int used=0,all;
            ArrayList<BaseUnit> units = new ArrayList<>();
            if (p.object.equals("all")) {
                for (String name : stats.keys()) {
                    used+=addUnits(getUnitByName(name), units, p.target, -1, p.x, p.y);
                }
                all=getUnitCount("all");
            } else {
                used=addUnits(getUnitByName(p.object), units, p.target, p.amount, p.x, p.y);
                all=p.amount;
            }

            req = new Request(Main.transportTime, new Timer.Task() {
                @Override
                public void run() {
                    for (BaseUnit u : units) {
                        u.add();
                    }
                    Call.sendMessage(Main.prefix + "[green]" + Tools.report(p.object, p.amount) + " units arrived.");
                }
            }, this, p, true);
            requests.add(req);
            if(all!=used && canTransport()){
                launch(new Package(p.object,p.amount-used,p.toBase,p.target,p.x,p.y));
            }
            p.amount=used;
            world.tile(p.x / 8, p.y / 8).removeNet();
        } else {
            int[] thisUnitStats = stats.get(p.object);
            for (int i = 0; i < Main.items.size; i++) {
                int requires = thisUnitStats[i];
                loadout.storage[i] -= requires * p.amount;
            }
            int buildTime = (int) (p.amount / (float) thisUnitStats[idx.buildLimit.i] * thisUnitStats[idx.buildTime.i] * 60);
            req = new Request(buildTime, new Timer.Task() {
                @Override
                public void run() {
                    stats.get(p.object)[UNIT_COUNT] += p.amount;
                    Call.sendMessage(Main.prefix + "[green]" + Tools.report(p.object, p.amount) + " wos finished and are waiting in a hangar.");
                }
            }, this, p, false);
            requests.add(req);
        }

    }

    private boolean canTransport() {
        return requests.size<config.get(THREAD_COUNT);
    }

    @Override
    public theWorst.Package verify(Player player, String object, int amount, boolean toBase) {
        if (!canTransport()) {
            player.sendMessage(Main.prefix + "Factory is doing maximum amount of tasks actually.");
            return null;
        }
        if (!stats.containsKey(object) && !(object.equals("all") && toBase)) {
            player.sendMessage(Main.prefix + "Factory cannot build nor send [scarlet] " + object + "[].");
            return null;
        }
        boolean hasEnough = true;
        Package p;
        if (!toBase) {
            int[] cost = stats.get(object);
            for (int i = 0; i < Main.items.size; i++) {
                int missing = cost[i] * amount - loadout.storage[i];
                if (missing > 0) {
                    hasEnough = false;
                    player.sendMessage(Main.prefix + "You are missing [scarlet]" + missing + "[]"
                            + Main.itemIcons[i] + ".");
                }
            }
            if (!hasEnough) {
                return null;
            }
            p= new Package(object, amount, false, player);
        } else {
            int uCount = getUnitCount(object);
            if (object.equals("all")) {
                amount = uCount;
            }
            if (uCount == 0) {
                player.sendMessage(Main.prefix + "Nothing to launch.");
                return null;
            }
            if (uCount < amount) {
                player.sendMessage(Main.prefix + "There are only" + Tools.report(object, uCount) + ".");
                return null;
            }
            int x = (int) player.x;
            int y = (int) player.y;
            if (world.tile(x / 8, y / 8).solid()) {
                player.sendMessage(Main.prefix + "Land unit cant be dropped on a solid block.");
                return null;
            }
            p= new theWorst.Package(object, amount, true, player, x, y);
        }
        return p;
    }

    public static UnitType getUnitByName(String name) {
        return content.units().find(unitType -> unitType.name.equals(name));
    }

    public int getUnitCount(String key) {
        if (key.equals("all")) {
            int res = 0;
            for (String k : stats.keys()) {
                res += getUnitCount(k);
            }
            return res;
        }
        return stats.get(key)[UNIT_COUNT];
    }

    public int addUnits(UnitType unitType, ArrayList<BaseUnit> units, Player player, int amount, int x, int y) {
        amount = amount == -1 ? stats.get(unitType.name)[UNIT_COUNT] : amount;
        int size=stats.get(unitType.name)[idx.unitSize.i];
        int used=0;
        int totalSize=0;
        for (int i = 0; i < amount && totalSize<config.get(MAX_TRANSPORT); i++) {
            BaseUnit unit = unitType.create(player.getTeam());
            unit.set(x, y);
            units.add(unit);
            totalSize+=size;
            used+=1;
        }

        stats.get(unitType.name)[UNIT_COUNT] -= used;
        return used;
    }

    @Override
    public void interrupt() {
        requests.forEach(Request::interrupt);
        requests.clear();
    }

    public String info() {
        StringBuilder message = new StringBuilder();
        message.append("[orange]--FACTORY INFO--[]\n\nunit/in hangar\n");
        for (String name : stats.keys()) {
            message.append(name).append("/").append(getUnitCount(name)).append("\n");
        }
        return message.toString();
    }

    public String price(Player player, String unitName, int amount) {
        if (!stats.containsKey(unitName)) {
            player.sendMessage(Main.prefix + "There is no [scarlet]" + unitName + "[] only " +
                    Tools.toString(stats.keys().toArray()) + ".");
            return null;
        }
        StringBuilder message = new StringBuilder();
        message.append("[orange]--").append(amount).append(" ").append(unitName.toUpperCase()).append("--[]").append("\n\n");
        message.append("in loadout / price\n");
        for (int i = 0; i < 10; i++) {
            int inLoadout = loadout.storage[i];
            int price = stats.get(unitName)[i];
            if (price == 0) {
                continue;
            }
            message.append(price > inLoadout ? "[red]" : "[white]");
            message.append(inLoadout).append(" [white]/ ").append((price * amount)).append(Main.itemIcons[i]).append("\n");
        }
        message.append("\n[scarlet]!!![]Factory will take resources from loadout not from the core[scarlet]!!![]\n");
        message.append("Build time: [orange]").append(stats.get(unitName)[idx.buildTime.i]).append("[].\n");
        message.append("Factory can build [orange]").append(stats.get(unitName)[idx.buildLimit.i]).append("units at the same time.");
        return message.toString();
    }

    @Override
    public JSONObject save() {
        JSONObject data = new JSONObject();
        for (String name : stats.keys()) {
            data.put(name, getUnitCount(name));
        }
        return data;
    }

    @Override
    public void load(JSONObject data) {
        for (String name : stats.keys()) {
            if (!stats.containsKey(name)) {
                continue;
            }
            Integer val = Tools.getInt(data.get(name));
            stats.get(name)[UNIT_COUNT] = val;
        }
    }

    public void config() {
        Tools.loadJson(configFile,
                (settings)->{
                    for (Object setting : settings.keySet()) {
                        if (getUnitByName((String) setting) == null) {
                            Log.info("Unit name " + setting + " is invalid.It will be ignored.");
                            StringBuilder list = new StringBuilder();
                            for (UnitType unit : content.units()) {
                                list.append(unit.name).append(" ");
                            }
                            Log.info("Valid units: " + list.toString());
                            continue;
                        }
                        int[] data = new int[14];
                        int idx = 0;
                        boolean fail = false;
                        JSONObject jsInfo = (JSONObject) settings.get(setting);
                        for (String key : statKeys) {

                            if (!jsInfo.containsKey(key)) {
                                Log.info("Config loading:missing property " + key + " in " + setting + ". " + setting + " will be ignored.");
                                fail = true;
                                break;
                            }
                            Integer val = Tools.getInt(jsInfo.get(key));
                            data[idx] = val;
                            idx++;
                        }
                        if (fail) {
                            continue;
                        }
                        stats.put((String) setting, data);
                    }

                },this::createDefaultConfig);
    }

    public void createDefaultConfig() {
        try (FileWriter file = new FileWriter(configFile)) {

            JSONObject unit = new JSONObject();
            for (String key : statKeys) {
                unit.put(key, 5);
            }
            JSONObject config = new JSONObject();
            config.put("eruptor", unit);
            file.write(config.toJSONString());
            Log.info("Default " + configFile + " successfully created.Edit it and use apply-config command.");
        } catch (IOException ex) {
            Log.info("Error when creating default config.");
        }
    }
}
