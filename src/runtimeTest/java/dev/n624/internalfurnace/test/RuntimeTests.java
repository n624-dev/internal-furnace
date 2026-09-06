package dev.n624.internalfurnace.test;

import com.robertx22.mine_and_slash.capability.entity.EntityData;
import dev.n624.internalfurnace.InternalFurnace;
import dev.n624.internalfurnace.core.*;
import dev.n624.internalfurnace.forge.*;
import net.minecraft.commands.Commands;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.*;
import net.minecraft.world.level.GameType;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraft.resources.ResourceLocation;
import java.nio.file.*;
import java.util.*;

@Mod("internal_furnace_test")
@Mod.EventBusSubscriber(modid="internal_furnace_test")
public final class RuntimeTests {
    private static int checks;
    public RuntimeTests() {
        if (!Boolean.getBoolean("internalfurnace.disposableTest")) throw new IllegalStateException("Test driver requires an explicitly disposable runtime");
    }
    private static void check(boolean test, String description) {
        checks++;
        if (!test) throw new IllegalStateException(description);
    }
    @SubscribeEvent public static void commands(RegisterCommandsEvent event) {
        var root = Commands.literal("furnacetest").requires(s -> s.hasPermission(2));
        root.then(Commands.literal("suite").executes(c -> {
            ServerPlayer p = c.getSource().getPlayerOrException();
            try { suite(p); InternalFurnace.LOGGER.info("FURNACE_RUNTIME PASS {} assertions", checks); }
            catch (Throwable error) { InternalFurnace.LOGGER.error("FURNACE_RUNTIME FAIL after " + checks, error); throw new IllegalStateException(error); }
            return 1;
        }));
        root.then(Commands.literal("readonly").executes(c -> {
            ServerPlayer owner=c.getSource().getPlayerOrException();
            ServerPlayer observer=owner.getServer().getPlayerList().getPlayerByName("FurnaceObserver");
            if(observer==null)throw new IllegalStateException("Connect a second real client named FurnaceObserver first");
            FurnaceMenu m=new FurnaceMenu(78,observer.getInventory(),owner);
            check(m.readOnly&&m.stillValid(observer),"real admin read-only menu is valid");
            CompoundTag before=m.data.save();
            for(int tab=0;tab<3;tab++) {
                m.tab=tab;
                for(int i=0;i<63;i++)for(ClickType type:List.of(ClickType.PICKUP,ClickType.SWAP,ClickType.QUICK_MOVE,ClickType.THROW))m.clicked(i,0,type,observer);
                m.setCarried(new ItemStack(Items.GLASS));m.clicked(63,0,ClickType.PICKUP_ALL,observer);m.setCarried(ItemStack.EMPTY);
            }
            check(before.equals(m.data.save()),"read-only clicks leave owner's data unchanged");
            InternalFurnace.LOGGER.info("FURNACE_TWO_CLIENT_READONLY PASS");return 1;
        }));
        root.then(Commands.literal("prepare").executes(c -> { prepare(c.getSource().getPlayerOrException()); return 1; }));
        root.then(Commands.literal("snapshot").executes(c -> {
            try { NbtIo.writeCompressed(InternalFurnace.data(c.getSource().getPlayerOrException()).save(), Path.of("furnace-snapshot.dat").toFile()); }
            catch (Exception e) { throw new IllegalStateException(e); } return 1;
        }));
        root.then(Commands.literal("verify").executes(c -> {
            try { check(InternalFurnace.data(c.getSource().getPlayerOrException()).save().equals(NbtIo.readCompressed(Path.of("furnace-snapshot.dat").toFile())), "persistent snapshot"); }
            catch (Exception e) { throw new IllegalStateException(e); }
            InternalFurnace.LOGGER.info("FURNACE_PERSISTENCE PASS"); return 1;
        }));
        root.then(Commands.literal("deathoff").executes(c -> {
            FurnaceData d = InternalFurnace.data(c.getSource().getPlayerOrException());
            check(d.profile.rank() == 7 && d.profile.level(Upgrade.QUEUE) == 5, "death retains upgrades");
            check(d.thermal.heat() == 0 && count(d) == 0, "death drains items and heat");
            long dropped = c.getSource().getLevel().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                    c.getSource().getPlayerOrException().getBoundingBox().inflate(100)).stream().mapToLong(e -> e.getItem().getCount()).sum();
            check(dropped == 39, "exactly 39 furnace items dropped: " + dropped);
            InternalFurnace.LOGGER.info("FURNACE_DEATH_OFF PASS"); return 1;
        }));
        event.getDispatcher().register(root);
    }
    private static void blank(ServerPlayer p) {
        p.closeContainer(); p.getInventory().clearContent(); p.setGameMode(GameType.SURVIVAL); p.clearFire(); p.setHealth(p.getMaxHealth());
        p.setExperienceLevels(0); p.setExperiencePoints(0); p.totalExperience=0;
        InternalFurnace.data(p).load(new FurnaceData().save());
    }
    private static void maximum(FurnaceData d) {
        EnumMap<Upgrade,Integer> levels = new EnumMap<>(Upgrade.class);
        for (Upgrade u : Upgrade.values()) levels.put(u, u.maximum());
        d.profile = new Profile(7, levels, 1);
    }
    private static int count(FurnaceData d) {
        int n=0;
        for (var storage : List.of(d.queue,d.fuel,d.output)) for(int i=0;i<storage.getContainerSize();i++) n+=storage.getItem(i).getCount();
        for (ItemStack s:d.active)n+=s.getCount();for(ItemStack s:d.finished)n+=s.getCount();return n;
    }
    private static int count(ServerPlayer p, FurnaceMenu menu) {
        int n=count(menu.data)+menu.getCarried().getCount();
        for(int i=0;i<p.getInventory().getContainerSize();i++)n+=p.getInventory().getItem(i).getCount();return n;
    }
    private static FurnaceMenu menu(ServerPlayer p,int tab) {
        FurnaceMenu m=new FurnaceMenu(77,p.getInventory(),p);m.tab=tab;p.containerMenu=m;return m;
    }
    private static void suite(ServerPlayer p) {
        checks=0; blank(p); FurnaceData d=InternalFurnace.data(p);
        EntityData.get(p).setLevel(100); check(MnsBridge.combatLevel(p)==100,"real M&S capability level");
        // Every purchase uses actual Minecraft inventory and M&S capability, plus replay rejection.
        for(String track:Catalog.tracks()) {
            Catalog.Cost cost;
            while((cost=InternalFurnace.catalog.next(d.profile,track))!=null) {
                p.getInventory().clearContent();p.setExperienceLevels(1000);
                for(var item:cost.items().entrySet()) {
                    ItemStack stack=new ItemStack(ForgeRegistries.ITEMS.getValue(new ResourceLocation(item.getKey())),item.getValue());
                    // Inventory.add splits block costs larger than one stack.
                    check(p.getInventory().add(stack),"cost fits inventory");
                }
                long revision=d.profile.revision();d.purchase(p,track,revision);
                check(d.profile.revision()==revision+1,"purchase revision");
                check(p.experienceLevel==1000-cost.xpLevels(),"purchase XP");
                var saved=d.save();
                try {d.purchase(p,track,revision);throw new AssertionError("replay accepted");}catch(IllegalArgumentException expected){}
                check(saved.equals(d.save()),"replay leaves state unchanged");
            }
        }
        p.getInventory().clearContent();d.paused=true;
        check(d.chambers()==4&&d.queueSlots()==27&&d.fuelSlots()==18&&d.outputSlots()==18,"maximum dimensions");
        check(d.resolve(p,new ItemStack(Items.SAND)).result().is(Items.GLASS),"real smelting");
        check(d.resolve(p,new ItemStack(Items.BEEF)).mode()==ThermalEngine.Mode.SMOKING,"real smoking");
        check(d.resolve(p,new ItemStack(Items.RAW_IRON)).mode()==ThermalEngine.Mode.BLASTING,"real blasting");
        FurnaceMenu fuelMenu=menu(p,1);
        for(int i=27;i<45;i++)check(fuelMenu.slots.get(i).isActive()&&fuelMenu.slots.get(i).mayPlace(new ItemStack(Items.COAL)),"empty fuel slot unlocked "+i);
        FurnaceMenu outputMenu=menu(p,2);
        for(int i=45;i<63;i++)check(outputMenu.slots.get(i).isActive()&&!outputMenu.slots.get(i).mayPlace(new ItemStack(Items.GLASS)),"empty output slot unlocked but cannot insert "+i);
        // All vanilla click paths must conserve inventory and account for output XP once.
        FurnaceMenu m=menu(p,0);m.setCarried(new ItemStack(Items.SAND,12));
        m.clicked(-999,0,ClickType.QUICK_CRAFT,p);for(int i=0;i<3;i++)m.clicked(i,1,ClickType.QUICK_CRAFT,p);m.clicked(-999,2,ClickType.QUICK_CRAFT,p);
        check(m.getCarried().isEmpty()&&d.queue.getItem(0).getCount()==4&&d.queue.getItem(2).getCount()==4,"left drag distribution");
        m.setCarried(new ItemStack(Items.SAND,6));m.clicked(-999,4,ClickType.QUICK_CRAFT,p);for(int i=0;i<3;i++)m.clicked(i,5,ClickType.QUICK_CRAFT,p);m.clicked(-999,6,ClickType.QUICK_CRAFT,p);
        check(m.getCarried().getCount()==3&&d.queue.getItem(2).getCount()==5,"right drag one each");
        m.setCarried(ItemStack.EMPTY);p.getInventory().setItem(0,new ItemStack(Items.RAW_IRON,7));m.clicked(0,0,ClickType.SWAP,p);
        check(d.queue.getItem(0).is(Items.RAW_IRON)&&p.getInventory().getItem(0).is(Items.SAND),"hotbar swap");
        p.getInventory().setItem(40,new ItemStack(Items.RAW_GOLD,2));m.clicked(1,40,ClickType.SWAP,p);
        check(d.queue.getItem(1).is(Items.RAW_GOLD)&&p.getInventory().getItem(40).is(Items.SAND),"offhand swap");
        ItemStack locked=new ItemStack(Items.SAND,9);locked.getOrCreateTag().putBoolean(MnsBridge.PROTECTED,true);p.getInventory().setItem(9,locked);
        int before=count(p,m);m.clicked(63,0,ClickType.QUICK_MOVE,p);check(p.getInventory().getItem(9).getCount()==9&&count(p,m)==before,"protected shift");
        d.drain();p.getInventory().clearContent();m=menu(p,2);d.output.setItem(0,new ItemStack(Items.GLASS,8));d.outputXp[0]=8*ExperienceLedger.UNIT;
        p.setExperienceLevels(0);p.setExperiencePoints(0);p.totalExperience=0;m.clicked(45,0,ClickType.SWAP,p);
        check(d.output.getItem(0).isEmpty()&&d.outputXp[0]==0&&p.totalExperience==8,"number key claims output XP");
        m.clicked(45,0,ClickType.SWAP,p);check(d.output.getItem(0).isEmpty()&&p.totalExperience==8,"output cannot be reinserted or reclaim XP");
        p.getInventory().clearContent();d.output.setItem(0,new ItemStack(Items.GLASS,8));d.outputXp[0]=8*ExperienceLedger.UNIT;
        m.clicked(45,1,ClickType.PICKUP,p);check(m.getCarried().getCount()==4&&d.outputXp[0]==4*ExperienceLedger.UNIT&&p.totalExperience==12,"partial output XP");
        m.clicked(46,0,ClickType.PICKUP_ALL,p);check(m.getCarried().getCount()==8&&d.outputXp[0]==0&&p.totalExperience==16,"collect all XP: cursor="+m.getCarried()+" output="+d.output.getItem(0)+" xp="+p.totalExperience);
        m.setCarried(ItemStack.EMPTY);d.output.setItem(0,new ItemStack(Items.GLASS,8));d.outputXp[0]=8*ExperienceLedger.UNIT;
        m.clicked(45,0,ClickType.QUICK_MOVE,p);check(d.outputXp[0]==0&&p.totalExperience==24,"shift XP once");
        // Seeded legal and hostile click sequences, preserving total items across every request.
        d.drain();p.getInventory().clearContent();m=menu(p,0);d.queue.setItem(0,new ItemStack(Items.SAND,64));p.getInventory().setItem(9,new ItemStack(Items.SAND,64));
        Random random=new Random(14);ClickType[] types={ClickType.PICKUP,ClickType.QUICK_MOVE,ClickType.SWAP,ClickType.PICKUP_ALL};
        for(int i=0;i<2000;i++) {
            int slot=random.nextBoolean()?random.nextInt(27):63+random.nextInt(36);ClickType type=types[random.nextInt(types.length)];
            int button=type==ClickType.SWAP?random.nextInt(9):random.nextInt(2);before=count(p,m);m.clicked(slot,button,type,p);check(count(p,m)==before,"click conservation "+i);
        }
        // Special-item confirmation, stale source and expiry.
        d.drain();p.getInventory().clearContent();m=menu(p,0);ItemStack named=new ItemStack(Items.IRON_SWORD);named.setHoverName(Component.literal("Rare test"));m.setCarried(named);
        m.clicked(0,0,ClickType.PICKUP,p);check(d.queue.getItem(0).isEmpty()&&m.status.contains("confirmation"),"special warning");
        long token=m.status.getLong("confirmation");m.setCarried(new ItemStack(Items.GOLDEN_SWORD));
        try{m.confirm(token);throw new AssertionError("stale confirmation accepted");}catch(IllegalArgumentException expected){}
        m.setCarried(named);m.clicked(0,0,ClickType.PICKUP,p);m.confirm(m.status.getLong("confirmation"));check(d.queue.getItem(0).hasCustomHoverName()&&m.getCarried().isEmpty(),"confirmed input");
        // Serialization uses actual item NBT and the production adapter.
        CompoundTag saved=d.save();FurnaceData restored=new FurnaceData();restored.load(saved);check(restored.healthy()&&saved.equals(restored.save()),"NBT round trip");
        int original=count(d);check(d.drain().stream().mapToInt(ItemStack::getCount).sum()==original&&d.drain().isEmpty(),"drain exactly once");
        blank(p); d=InternalFurnace.data(p); maximum(d);
        d.queue.setItem(0,new ItemStack(Items.SAND));d.queue.setItem(1,new ItemStack(Items.BEEF));
        d.queue.setItem(2,new ItemStack(Items.RAW_IRON));d.queue.setItem(3,new ItemStack(Items.SAND));
        d.thermal=new ThermalEngine(800000,0);
        for(int tick=0;tick<100;tick++)d.tick(p);
        check(d.completed==4 && d.thermal.heat()==0 && count(d)==4,"four real recipes conserve 800 heat at speed 2x");
        blank(p); d=InternalFurnace.data(p); maximum(d);d.queue.setItem(0,new ItemStack(Items.SAND));d.fuel.setItem(0,new ItemStack(Items.LAVA_BUCKET));d.tick(p);
        check(d.burned==1 && d.fuel.getItem(0).is(Items.BUCKET),"lava bucket remainder");
        blank(p);d=InternalFurnace.data(p);d.profile=new Profile(1,Map.of(),1);d.queue.setItem(0,new ItemStack(Items.SAND));d.fuel.setItem(0,new ItemStack(Items.LAVA_BUCKET));d.tick(p);
        check(d.burned==0&&d.fuel.getItem(0).is(Items.LAVA_BUCKET)&&d.thermal.heat()==0,"oversize fuel refused intact");
        for(int tier=1;tier<=5;tier++) {
            blank(p);d=InternalFurnace.data(p);maximum(d);
            EnumMap<Upgrade,Integer> levels=new EnumMap<>(d.profile.levels());levels.put(Upgrade.AUTO_INPUT,tier);d.profile=new Profile(7,levels,1);
            ItemStack input=new ItemStack(tier==2?Items.BEEF:tier>=3?Items.IRON_SWORD:Items.SAND);
            Rules.Condition condition=new Rules.Condition(tier==2?Rules.Field.CATEGORY:tier>=3?Rules.Field.DURABILITY_LT:Rules.Field.ITEM,tier==2?"food":tier>=3?"50":"minecraft:sand");
            if(tier>=3)input.setDamageValue(200);
            List<Rules.Condition> group=List.of(condition);
            if(tier>=4) {
                var gear=new com.robertx22.mine_and_slash.saveclasses.item_classes.GearItemData();gear.rar="common";gear.lvl=25;gear.gtype="sword";gear.saveToStack(input);
                var facts=MnsBridge.facts(input);check(facts.readable()&&facts.equipmentLevel()==25&&facts.rarity().equals("common"),"actual M&S gear decoding");
                group=List.of(new Rules.Condition(Rules.Field.RARITY,"common"),new Rules.Condition(Rules.Field.LEVEL_LT,"40"));
            }
            d.rules=new Rules.Settings(true,false,false,List.of(new Rules.Rule(Rules.Action.INPUT,List.of(group))));
            d.rules.validate(tier);p.getInventory().setItem(9,input.copy());p.tickCount=20;d.tick(p);
            check(p.getInventory().getItem(9).isEmpty()&&count(d)==1,"auto input tier "+tier);
            ItemStack protectedInput=input.copy();protectedInput.getOrCreateTag().putBoolean(MnsBridge.PROTECTED,true);p.getInventory().setItem(10,protectedInput);d.tick(p);
            check(!p.getInventory().getItem(10).isEmpty(),"protected auto input tier "+tier);
        }
        for(int tier=1;tier<=4;tier++) {
            blank(p);d=InternalFurnace.data(p);maximum(d);EnumMap<Upgrade,Integer> levels=new EnumMap<>(d.profile.levels());levels.put(Upgrade.AUTO_FUEL,tier);d.profile=new Profile(7,levels,1);
            d.autoFuel=true;d.queue.setItem(0,new ItemStack(Items.SAND));p.getInventory().setItem(9,new ItemStack(Items.COAL,4));p.tickCount=20;d.tick(p);
            check(p.getInventory().getItem(9).isEmpty()&&d.burned==1&&d.fuel.getItem(0).getCount()==3,"auto fuel tier "+tier);
        }
        prepare(p);
    }
    private static void prepare(ServerPlayer p) {
        blank(p);FurnaceData d=InternalFurnace.data(p);maximum(d);d.paused=true;EntityData.get(p).setLevel(100);
        d.queue.setItem(0,new ItemStack(Items.SAND,12));d.fuel.setItem(0,new ItemStack(Items.COAL,16));d.output.setItem(0,new ItemStack(Items.GLASS,8));d.outputXp[0]=8*ExperienceLedger.UNIT;
        d.active[0]=new ItemStack(Items.RAW_IRON);d.progress[0]=5000;d.fingerprint[0]="pending recipe verification";
        d.finished[1]=new ItemStack(Items.IRON_INGOT,2);d.finishedXp[1]=2*ExperienceLedger.UNIT;
        d.thermal=new ThermalEngine(0,0); // Paused furnaces still lose heat online; zero enables exact offline snapshot checks.
        InternalFurnace.open(p,p);
    }
}
