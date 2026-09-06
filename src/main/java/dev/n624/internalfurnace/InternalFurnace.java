package dev.n624.internalfurnace;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.logging.LogUtils;
import dev.n624.internalfurnace.core.*;
import dev.n624.internalfurnace.forge.*;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.level.GameRules;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.common.capabilities.RegisterCapabilitiesEvent;
import net.minecraftforge.common.extensions.IForgeMenuType;
import net.minecraftforge.event.*;
import net.minecraftforge.event.entity.living.LivingDropsEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.network.NetworkHooks;
import net.minecraftforge.registries.*;
import org.slf4j.Logger;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.EnumMap;

@Mod(InternalFurnace.ID)
public final class InternalFurnace {
    public static final String ID = "internal_furnace";
    public static final Logger LOGGER = LogUtils.getLogger();
    private static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(ForgeRegistries.MENU_TYPES, ID);
    public static final RegistryObject<MenuType<FurnaceMenu>> MENU = MENUS.register("furnace", () -> IForgeMenuType.create(FurnaceMenu::new));
    private static final ForgeConfigSpec CONFIG;
    public static final ForgeConfigSpec.IntValue SCAN_TICKS, IDLE_LOSS;
    public static Catalog catalog;
    public static int recipeGeneration;
    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        SCAN_TICKS = builder.comment("Inventory scan interval; never every tick by default.").defineInRange("inventoryScanTicks", 10, 5, 200);
        IDLE_LOSS = builder.comment("Milliheat lost per idle online tick before insulation; 1000 = one heat unit.").defineInRange("idleLossMilliheat", 1000, 100, 1000);
        CONFIG = builder.build();
    }
    public InternalFurnace() {
        var bus = FMLJavaModLoadingContext.get().getModEventBus();
        MENUS.register(bus);
        bus.addListener((RegisterCapabilitiesEvent event) -> event.register(FurnaceData.class));
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, CONFIG);
        catalog = bundledCatalog(); FurnaceNetwork.register();
    }
    private static Catalog bundledCatalog() {
        try (InputStream stream = InternalFurnace.class.getResourceAsStream("/data/internal_furnace/upgrades.psv")) {
            if (stream == null) throw new IOException("Missing upgrade catalog");
            return Catalog.read(new InputStreamReader(stream, StandardCharsets.UTF_8));
        } catch (IOException error) { throw new IllegalStateException("Invalid bundled upgrade catalog", error); }
    }
    public static FurnaceData data(Player player) {
        return player.getCapability(FurnaceCapability.TYPE).orElseThrow(() -> new IllegalStateException("Missing furnace capability"));
    }
    public static void open(ServerPlayer viewer, ServerPlayer owner) {
        if (!viewer.getUUID().equals(owner.getUUID()) && !viewer.hasPermissions(2)) return;
        NetworkHooks.openScreen(viewer, new SimpleMenuProvider((id, inventory, player) -> new FurnaceMenu(id, inventory, owner),
                Component.translatable("menu.internal_furnace.title")), buffer -> buffer.writeUUID(owner.getUUID()).writeBoolean(viewer != owner));
    }
    @Mod.EventBusSubscriber(modid = ID)
    public static final class Events {
        @SubscribeEvent public static void attach(AttachCapabilitiesEvent<Entity> event) {
            if (event.getObject() instanceof Player) {
                FurnaceCapability provider = new FurnaceCapability();
                event.addCapability(new ResourceLocation(ID, "furnace"), provider); event.addListener(provider::invalidate);
            }
        }
        @SubscribeEvent public static void tick(TickEvent.PlayerTickEvent event) {
            if (event.phase == TickEvent.Phase.END && event.player instanceof ServerPlayer player && player.isAlive() && !player.isRemoved())
                player.getCapability(FurnaceCapability.TYPE).ifPresent(furnace -> furnace.tick(player));
        }
        @SubscribeEvent public static void reload(OnDatapackSyncEvent event) { recipeGeneration++; }
        @SubscribeEvent public static void logout(PlayerEvent.PlayerLoggedOutEvent event) { FurnaceNetwork.forget(event.getEntity().getUUID()); }
        @SubscribeEvent public static void clonePlayer(PlayerEvent.Clone event) {
            event.getOriginal().reviveCaps();
            try { data(event.getEntity()).load(data(event.getOriginal()).save()); }
            finally { event.getOriginal().invalidateCaps(); }
        }
        @SubscribeEvent(priority = EventPriority.HIGHEST) public static void drops(LivingDropsEvent event) {
            if (!(event.getEntity() instanceof ServerPlayer player) || player.level().getGameRules().getBoolean(GameRules.RULE_KEEPINVENTORY)) return;
            FurnaceData furnace = data(player);
            if (!furnace.healthy()) return; // Preserve quarantined bytes for explicit recovery.
            for (var item : furnace.drain()) event.getDrops().add(new ItemEntity(player.level(), player.getX(), player.getY(), player.getZ(), item));
        }
        @SubscribeEvent public static void starting(ServerStartingEvent event) {
            Path path = FMLPaths.CONFIGDIR.get().resolve("internal-furnace-upgrades.psv");
            Catalog loaded = bundledCatalog();
            if (Files.exists(path)) {
                try {
                    if (!Files.isRegularFile(path) || Files.size(path) > 200_000) throw new IOException("Invalid catalog file");
                    try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) { loaded = Catalog.read(reader); }
                } catch (IOException error) { throw new IllegalStateException("Refusing invalid Internal Furnace cost override", error); }
            }
            for (Catalog.Cost cost : loaded.entries().values()) for (String id : cost.items().keySet())
                if (!ForgeRegistries.ITEMS.containsKey(new ResourceLocation(id))) throw new IllegalStateException("Missing upgrade ingredient: " + id);
            catalog = loaded;
        }
        @SubscribeEvent public static void commands(RegisterCommandsEvent event) {
            event.getDispatcher().register(Commands.literal("internalfurnace")
                .executes(c -> { ServerPlayer p = c.getSource().getPlayerOrException(); open(p, p); return 1; })
                .then(Commands.literal("info").executes(c -> {
                    ServerPlayer p = c.getSource().getPlayerOrException(); FurnaceData d = data(p);
                    c.getSource().sendSuccess(() -> Component.literal("Rank " + d.profile.rank() + " | heat " + d.thermal.heat() / 1000.0
                            + "/" + d.capacity() / 1000 + " | M&S " + MnsBridge.combatLevel(p) + " | healthy=" + d.healthy()), false); return 1;
                }))
                .then(Commands.literal("admin").requires(s -> s.hasPermission(2))
                    .then(Commands.literal("open").then(Commands.argument("player", EntityArgument.player()).executes(c -> {
                        open(c.getSource().getPlayerOrException(), EntityArgument.getPlayer(c, "player")); return 1;
                    })))
                    .then(Commands.literal("setrank").then(Commands.argument("player", EntityArgument.player())
                        .then(Commands.argument("rank", IntegerArgumentType.integer(0, 7)).executes(c -> {
                            ServerPlayer p = EntityArgument.getPlayer(c, "player"); FurnaceData d = data(p); int rank = IntegerArgumentType.getInteger(c, "rank");
                            if (!d.healthy() || rank < d.profile.rank()) { c.getSource().sendFailure(Component.literal("Use reset CONFIRM before lowering progression; quarantined data needs manual recovery.")); return 0; }
                            d.profile = new Profile(rank, d.profile.levels(), Math.addExact(d.profile.revision(), 1)); return 1;
                        }))))
                    .then(Commands.literal("setupgrade").then(Commands.argument("player", EntityArgument.player())
                        .then(Commands.argument("track", StringArgumentType.word()).then(Commands.argument("level", IntegerArgumentType.integer(0, 6)).executes(c -> {
                            FurnaceData d = data(EntityArgument.getPlayer(c, "player"));
                            try {
                                Upgrade upgrade = Upgrade.byId(StringArgumentType.getString(c, "track")); int n = IntegerArgumentType.getInteger(c, "level");
                                if (!d.unlocked() || n < d.profile.level(upgrade)) throw new IllegalArgumentException("Unlock first; reset before downgrade");
                                upgrade.value(n); EnumMap<Upgrade, Integer> levels = new EnumMap<>(d.profile.levels()); levels.put(upgrade, n);
                                d.profile = new Profile(d.profile.rank(), levels, Math.addExact(d.profile.revision(), 1)); return 1;
                            } catch (IllegalArgumentException error) { c.getSource().sendFailure(Component.literal(error.getMessage())); return 0; }
                        })))))
                    .then(Commands.literal("reset").then(Commands.argument("player", EntityArgument.player()).then(Commands.literal("CONFIRM").executes(c -> {
                        ServerPlayer p = EntityArgument.getPlayer(c, "player"); FurnaceData d = data(p);
                        if (!d.healthy()) { c.getSource().sendFailure(Component.literal("Quarantined NBT cannot be discarded by reset")); return 0; }
                        p.closeContainer();
                        for (var stack : d.drain()) if (!p.getInventory().add(stack)) p.drop(stack, false);
                        d.load(new FurnaceData().save()); return 1;
                    }))))));
        }
    }
}
