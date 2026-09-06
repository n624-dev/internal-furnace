package dev.n624.internalfurnace.forge;

import dev.n624.internalfurnace.InternalFurnace;
import dev.n624.internalfurnace.client.FurnaceClient;
import dev.n624.internalfurnace.core.Upgrade;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.*;
import net.minecraftforge.network.simple.SimpleChannel;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.*;
import java.util.function.Supplier;

public final class FurnaceNetwork {
    private FurnaceNetwork() {}
    private static final String PROTOCOL = protocol();
    private static String protocol() {
        var path = net.minecraftforge.fml.ModList.get().getModFileById(InternalFurnace.ID).getFile().getFilePath();
        if (java.nio.file.Files.isDirectory(path)) return "2-development";
        try (var stream = java.nio.file.Files.newInputStream(path)) {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192]; int count;
            while ((count = stream.read(buffer)) != -1) digest.update(buffer, 0, count);
            return "2-" + java.util.HexFormat.of().formatHex(digest.digest());
        } catch (java.io.IOException | java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException("Cannot identify the Internal Furnace artifact", error);
        }
    }
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(InternalFurnace.ID, "menu"), () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals);
    private static final Map<UUID, int[]> rates = new HashMap<>();
    public record Request(int window, int action, int a, int b, long revision, String text) {
        public static Request read(FriendlyByteBuf buffer) {
            return new Request(buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt(), buffer.readLong(), buffer.readUtf(4096));
        }
        public void write(FriendlyByteBuf buffer) {
            buffer.writeVarInt(window);
            buffer.writeVarInt(action);
            buffer.writeVarInt(a);
            buffer.writeVarInt(b);
            // Netty's writeLong returns ByteBuf, not FriendlyByteBuf: do not chain writeUtf after it.
            buffer.writeLong(revision);
            buffer.writeUtf(text, 4096);
        }
    }
    public record Status(int window, CompoundTag tag) {
        public static Status read(FriendlyByteBuf b) {
            CompoundTag tag;
            int id = b.readVarInt(); tag = b.readNbt();
            if (tag == null) throw new IllegalArgumentException("Missing status");
            return new Status(id, tag);
        }
        public void write(FriendlyByteBuf b) { b.writeVarInt(window).writeNbt(tag); }
    }
    public static void register() {
        CHANNEL.messageBuilder(Request.class, 0, NetworkDirection.PLAY_TO_SERVER)
                .encoder(Request::write).decoder(Request::read).consumerMainThread(FurnaceNetwork::handle).add();
        CHANNEL.messageBuilder(Status.class, 1, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(Status::write).decoder(Status::read).consumerMainThread((message, context) -> {
                    DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> FurnaceClient.accept(message));
                    context.get().setPacketHandled(true);
                }).add();
    }
    public static void send(ServerPlayer player, int window, CompoundTag tag) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new Status(window, tag));
    }
    public static void forget(UUID player) { rates.remove(player); }
    private static void handle(Request request, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get(); context.setPacketHandled(true);
        ServerPlayer player = context.getSender();
        if (player == null || !player.isAlive() || player.isSpectator()) return;
        int tick = player.getServer().getTickCount();
        int[] rate = rates.computeIfAbsent(player.getUUID(), key -> new int[]{tick, 0});
        if (rate[0] != tick) { rate[0] = tick; rate[1] = 0; }
        if (++rate[1] > 8) return;
        if (request.action == 0) { InternalFurnace.open(player, player); return; }
        if (!(player.containerMenu instanceof FurnaceMenu menu) || menu.containerId != request.window || !menu.stillValid(player)) return;
        try {
            if (request.action == 1) {
                if (request.a < 0 || request.a > 5) throw new IllegalArgumentException("Invalid tab");
                menu.setTab(request.a); menu.sync(); return;
            }
            if (menu.readOnly || !menu.data.healthy()) return;
            FurnaceData data = menu.data;
            switch (request.action) {
                case 2 -> data.purchase(player, request.text, request.revision);
                case 3 -> data.paused = !data.paused;
                case 4 -> {
                    ItemStack held = player.getMainHandItem();
                    if (held.isEmpty()) throw new IllegalArgumentException("Hold an item first");
                    if (MnsBridge.locked(held)) held.getOrCreateTag().remove(MnsBridge.PROTECTED);
                    else held.getOrCreateTag().putBoolean(MnsBridge.PROTECTED, true);
                    player.getInventory().setChanged();
                }
                case 5 -> data.rules = RuleCodec.read(request.text, data.profile.level(Upgrade.AUTO_INPUT));
                case 6 -> {
                    int tier = data.profile.level(Upgrade.AUTO_FUEL);
                    if (tier == 0 || request.a < 1 || request.a > 100 || (tier < 3 && request.a != 25))
                        throw new IllegalArgumentException("Fuel control locked or invalid threshold");
                    String[] ids = request.text.split(",", -1);
                    if (ids.length > 16 || ids.length == 0) throw new IllegalArgumentException("Invalid fuel list");
                    List<String> valid = new ArrayList<>();
                    for (String id : ids) {
                        ResourceLocation key = ResourceLocation.tryParse(id);
                        if (key == null || id.length() > 128 || !ForgeRegistries.ITEMS.containsKey(key)) throw new IllegalArgumentException("Unknown fuel");
                        if (FurnaceData.burnTime(new ItemStack(ForgeRegistries.ITEMS.getValue(key))) == 0 || valid.contains(id))
                            throw new IllegalArgumentException("Invalid or duplicate fuel");
                        valid.add(id);
                    }
                    if (tier == 1) Collections.sort(valid);
                    data.fuelPriority = List.copyOf(valid); data.fuelThreshold = request.a; data.autoFuel = request.b == 1;
                }
                case 7 -> data.reorder(request.a, request.b);
                case 8 -> {
                    if (data.profile.level(Upgrade.QUEUE) < 4 || request.a < 0 || request.a >= data.queueSlots())
                        throw new IllegalArgumentException("Queue pause locked");
                    data.queuePaused[request.a] = !data.queuePaused[request.a];
                }
                case 9 -> { data.cancelChamber(request.a); data.paused = true; }
                case 10 -> menu.confirm(request.revision);
                case 11 -> menu.cancelConfirmation();
                default -> throw new IllegalArgumentException("Unknown action");
            }
            menu.broadcastChanges(); menu.sync();
        } catch (RuntimeException error) {
            player.displayClientMessage(Component.translatable("message.internal_furnace.rejected", error.getMessage()), false);
        }
    }
}
