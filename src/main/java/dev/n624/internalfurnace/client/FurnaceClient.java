package dev.n624.internalfurnace.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.n624.internalfurnace.InternalFurnace;
import dev.n624.internalfurnace.forge.FurnaceMenu;
import dev.n624.internalfurnace.forge.FurnaceNetwork;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import org.lwjgl.glfw.GLFW;

public final class FurnaceClient {
    private FurnaceClient() {}
    private static final KeyMapping OPEN = new KeyMapping("key.internal_furnace.open", InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_V, "key.categories.internal_furnace");
    public static void accept(FurnaceNetwork.Status message) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && mc.player.containerMenu instanceof FurnaceMenu menu && menu.containerId == message.window())
            menu.acceptStatus(message.tag());
    }
    @Mod.EventBusSubscriber(modid = InternalFurnace.ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class Setup {
        @SubscribeEvent public static void setup(FMLClientSetupEvent event) { event.enqueueWork(() -> MenuScreens.register(InternalFurnace.MENU.get(), FurnaceScreen::new)); }
        @SubscribeEvent public static void keys(RegisterKeyMappingsEvent event) { event.register(OPEN); }
    }
    @Mod.EventBusSubscriber(modid = InternalFurnace.ID, value = Dist.CLIENT)
    public static final class Events {
        @SubscribeEvent public static void tick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END) return;
            Minecraft mc = Minecraft.getInstance();
            while (OPEN.consumeClick()) if (mc.player != null && mc.screen == null)
                FurnaceNetwork.CHANNEL.sendToServer(new FurnaceNetwork.Request(-1, 0, 0, 0, 0, ""));
        }
    }
}
