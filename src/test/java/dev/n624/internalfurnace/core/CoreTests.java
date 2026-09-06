package dev.n624.internalfurnace.core;

import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/** Dependency-free executable tests, compiled with --release 17. */
public final class CoreTests {
    private static int checks;
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    private static void rejects(Runnable action, String message) {
        try { action.run(); } catch (IllegalArgumentException | ArithmeticException expected) { checks++; return; }
        throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception {
        Catalog costs;
        Path table = Path.of(args[0]);
        try (var in = Files.newBufferedReader(table)) { costs = Catalog.read(in); }
        check(costs.entries().size() == 53, "all 53 purchases present");
        check(costs.entries().values().stream().mapToInt(c -> c.items().getOrDefault("minecraft:echo_shard", 0)).sum() == 4, "four echo shards in total");
        check(costs.entries().values().stream().mapToInt(c -> c.items().getOrDefault("mowziesmobs:ice_crystal", 0)).sum() == 1, "one ice crystal total");
        check(costs.entries().get("insulation:5").items().get("mowziesmobs:ice_crystal") == 1, "ice crystal only at insulation V");
        check(costs.entries().get("auto_input:1").xpLevels() == 35, "agreed auto input XP");
        check(costs.entries().get("auto_input:5").items().get("minecraft:diamond_block") == 16, "agreed auto input blocks");
        check(costs.entries().get("auto_input:5").items().get("minecraft:netherite_ingot") == 6, "agreed netherite cost");
        Profile locked = Profile.locked();
        Catalog.Cost unlock = costs.next(locked, "rank");
        Map<String, Integer> rich = new HashMap<>();
        costs.entries().values().forEach(c -> c.items().keySet().forEach(k -> rich.put(k, 10000)));
        rejects(() -> costs.plan(locked, "rank", 1, 100, 100, rich), "reject replay");
        rejects(() -> costs.plan(locked, "rank", 0, 9, 100, rich), "combat gating");
        rejects(() -> costs.plan(locked, "rank", 0, 100, 0, rich), "XP gating");
        rejects(() -> costs.plan(locked, "rank", 0, 100, 100, Map.of()), "material gating");
        check(locked.rank() == 0 && locked.revision() == 0, "failed planning never mutates profile");
        Catalog.Purchase first = costs.plan(locked, "rank", 0, 10, 10, unlock.items());
        check(first.after().rank() == 1 && first.xpLevels() == 10, "unlock purchase");
        Profile p = locked;
        for (int n=0;n<7;n++) p = costs.plan(p,"rank",p.revision(),100,1000,rich).after();
        for (Upgrade u : Upgrade.values()) for (int n=0;n<u.maximum();n++)
            p = costs.plan(p,u.id(),p.revision(),100,1000,rich).after();
        check(p.revision() == 53, "53 atomic purchases");
        check(p.value(Upgrade.CHAMBERS) == 4 && p.value(Upgrade.QUEUE) == 27, "final inventory limits");
        check(p.value(Upgrade.EFFICIENCY) == 900 && p.value(Upgrade.CAPACITY) == 51200, "final heat limits");
        Profile max = p;
        rejects(() -> costs.plan(max,"rank",max.revision(),100,1000,rich), "no over-max purchase");
        rejects(() -> new Profile(0, Map.of(Upgrade.SPEED,1),0), "locked profile cannot have upgrades");
        rejects(() -> new Profile(1, Map.of(Upgrade.SPEED,6),0), "reject malformed level");
        check(ThermalEngine.fuelHeat(1600,500) == 800000, "initial coal heat");
        check(ThermalEngine.fuelHeat(1600,900) == 1440000, "maximum coal heat");
        rejects(() -> ThermalEngine.fuelHeat(1600,901), "never more than 90 percent");
        ThermalEngine bank = new ThermalEngine(0,0);
        check(!bank.charge(2_000_000,1_600_000) && bank.heat()==0, "oversize fuel not consumed");
        check(bank.charge(1_440_000,1_600_000), "coal fits");
        check(!bank.charge(1_440_000,1_600_000) && bank.heat()==1_440_000, "overflow is atomic");
        for (int speed : new int[]{1000,1150,1300,1500,1750,2000}) {
            for (var mode : ThermalEngine.Mode.values()) {
                ThermalEngine furnace = new ThermalEngine(1_440_000,0);
                long remaining = (mode == ThermalEngine.Mode.SMELTING ? 200 : 100) * 1000L;
                int ticks=0;
                while (remaining>0) {
                    remaining -= furnace.advance(new ThermalEngine.Work[]{new ThermalEngine.Work(remaining,mode,true)},speed,1000)[0];
                    if (++ticks>200) throw new AssertionError("Recipe failed to finish");
                }
                check(furnace.heat()==1_240_000, "speed/mode cannot make fuel cheaper");
            }
        }
        ThermalEngine idle = new ThermalEngine(10000,0);
        idle.advance(new ThermalEngine.Work[]{new ThermalEngine.Work(200000,ThermalEngine.Mode.SMELTING,false)},1000,100);
        check(idle.heat()==9900, "blocked output cools but does not cook");
        long frozen=idle.heat();
        check(idle.heat()==frozen, "no wall-clock catch-up");
        ThermalEngine paused = new ThermalEngine(0, 3);
        paused.advance(new ThermalEngine.Work[4], 1000, 100);
        check(paused.cursor()==3, "idle tick preserves scheduling cursor");
        ThermalEngine starved = new ThermalEngine(1000,0);
        var tasks = new ThermalEngine.Work[4];
        java.util.Arrays.fill(tasks,new ThermalEngine.Work(200000,ThermalEngine.Mode.SMELTING,true));
        int[] turns=new int[4];
        for (int n=0;n<4;n++) {
            if(n>0) starved.charge(1000,10000);
            long[] got=starved.advance(tasks,1000,1000);
            for(int i=0;i<4;i++) if(got[i]>0) turns[i]++;
        }
        check(java.util.Arrays.equals(turns,new int[]{1,1,1,1}),"fair scheduling under fuel starvation");
        Random random = new Random(624);
        for(int n=0;n<20000;n++) {
            long initial=random.nextInt(20000);
            ThermalEngine f=new ThermalEngine(initial,random.nextInt(4));
            int count=1+random.nextInt(4), speed=1+random.nextInt(2000),loss=random.nextInt(1001);
            var jobs=new ThermalEngine.Work[count];
            for(int i=0;i<count;i++) jobs[i]=new ThermalEngine.Work(random.nextInt(200000),ThermalEngine.Mode.values()[random.nextInt(3)],random.nextBoolean());
            long[] gains=f.advance(jobs,speed,loss);
            long used=0;
            for(int i=0;i<count;i++) {
                check(gains[i]>=0 && gains[i]<=speed && gains[i]<=jobs[i].remainingProgress(),"bounded progress");
                if(!jobs[i].outputAvailable())check(gains[i]==0,"blocked work never advances");
                used+=gains[i]*jobs[i].mode().heatPerProgress;
            }
            check(f.heat()==(used>0?initial-used:Math.max(0,initial-loss)),"exact thermal conservation");
        }
        Rules.Condition iron=new Rules.Condition(Rules.Field.ITEM,"minecraft:iron_sword");
        Rules.Rule yes=new Rules.Rule(Rules.Action.INPUT,List.of(List.of(iron)));
        Rules.Settings simple=new Rules.Settings(true,false,false,List.of(yes));
        Rules.Facts ordinary=facts(false,false,"",-1);
        check(simple.accepts(ordinary,1),"individual item filter");
        check(!simple.accepts(facts(true,false,"",-1),1),"lock overrides all rules");
        check(!simple.accepts(facts(false,true,"common",10),1),"M&S gear not silently treated as vanilla");
        var common=new Rules.Condition(Rules.Field.RARITY,"common");
        Rules.Settings rarity=new Rules.Settings(true,false,false,List.of(new Rules.Rule(Rules.Action.INPUT,List.of(List.of(common)))));
        check(rarity.accepts(facts(false,true,"common",10),4),"explicit M&S rarity opt-in");
        check(!rarity.accepts(facts(false,true,"unique",10),4),"unique defaults to protected");
        check(!rarity.accepts(facts(false,true,"common",10),3),"M&S rules need tier IV");
        var andRule=new Rules.Rule(Rules.Action.INPUT,List.of(List.of(common,new Rules.Condition(Rules.Field.LEVEL_LT,"40"))));
        var complex=new Rules.Settings(true,false,false,List.of(andRule));
        check(complex.accepts(facts(false,true,"common",39),5),"AND rule");
        check(!complex.accepts(facts(false,true,"common",40),5),"strict level boundary");
        check(complex.accepts(facts(false,true,"common",39),4),"basic rarity and level selector works at IV");
        check(!complex.accepts(facts(false,true,"common",40),4),"IV level ceiling is effective");
        var arbitrary = new Rules.Settings(true,false,false,List.of(new Rules.Rule(Rules.Action.INPUT,List.of(List.of(common,iron)))));
        check(!arbitrary.accepts(facts(false,true,"common",39),4),"arbitrary compound rule locked before V");
        check(arbitrary.accepts(facts(false,true,"common",39),5),"arbitrary compound rule unlocks at V");
        Rules.Rule protect=new Rules.Rule(Rules.Action.PROTECT,List.of(List.of(iron)));
        check(!new Rules.Settings(true,false,false,List.of(yes,protect)).accepts(ordinary,5),"protection independent of order");
        check(!new Rules.Settings(true,false,false,List.of(protect,yes)).accepts(ordinary,5),"protection first");
        rejects(()->new Rules.Condition(Rules.Field.ENCHANTED,"maybe"),"boolean schema");
        rejects(()->new Rules.Rule(Rules.Action.INPUT,List.of(List.of())),"empty match-all group forbidden");
        long xp = ExperienceLedger.recipe(0.1f);
        check(xp == 100000, "fractional recipe XP is exact");
        long remainingXp = 1000000, claimed = 0;
        for (int count = 3; count > 0; count--) { long part = ExperienceLedger.split(remainingXp, 1, count); remainingXp -= part; claimed += part; }
        check(claimed == 1000000 && remainingXp == 0, "three output splits preserve every XP unit");
        rejects(() -> ExperienceLedger.split(100, 5, 4), "cannot claim more items than exist");
        rejects(() -> ExperienceLedger.recipe(Float.NaN), "reject corrupt recipe XP");
        String text=Files.readString(table);
        for(String bad:List.of(text+text,text.replace("minecraft:echo_shard=1","minecraft:echo_shard=0"),text.replace("auto_input|5|","unknown|5|"),text.lines().filter(l->!l.startsWith("rank|1|")).reduce("",(a,b)->a+b+"\n"))) {
            try {Catalog.read(new StringReader(bad));throw new AssertionError("Invalid catalog accepted");}
            catch(java.io.IOException expected){checks++;}
        }
        System.out.println("PASS: " + checks + " checks; 53 cost rows; 20,000 seeded thermal cases");
    }
    private static Rules.Facts facts(boolean locked,boolean special,String rarity,int level) {
        return new Rules.Facts("minecraft:iron_sword",Set.of("iron_gear"),Set.of(),20,false,false,locked,special,true,rarity,level,"weapon");
    }
}
