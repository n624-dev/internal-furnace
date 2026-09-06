package dev.n624.internalfurnace.test;

import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import java.nio.file.*;

/** Local-only test controls for reproducible language/scale screenshots. Never in the release JAR. */
@Mod.EventBusSubscriber(modid="internal_furnace_test", value=Dist.CLIENT)
public final class RuntimeClient {
    private static int ticks;
    @SubscribeEvent public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || ++ticks % 20 != 0) return;
        Minecraft mc=Minecraft.getInstance();
        Path path=mc.gameDirectory.toPath().resolve("furnace-client-command.txt");
        if (!Files.isRegularFile(path))return;
        try {
            String command=Files.readString(path).strip();Files.delete(path);
            if(command.startsWith("language ")) {
                String code=command.substring(9);
                if(!code.equals("ja_jp")&&!code.equals("en_us"))throw new IllegalArgumentException("Unsupported test language");
                mc.getLanguageManager().setSelected(code);mc.options.languageCode=code;mc.options.save();mc.reloadResourcePacks();
            } else if(command.startsWith("scale ")) {
                int scale=Integer.parseInt(command.substring(6));
                if(scale<1||scale>4)throw new IllegalArgumentException("Unsupported test scale");
                mc.options.guiScale().set(scale);mc.options.save();mc.resizeDisplay();
            } else throw new IllegalArgumentException("Unknown test command");
        } catch(Exception error){dev.n624.internalfurnace.InternalFurnace.LOGGER.error("FURNACE_CLIENT_TEST failed",error);}
    }
}
