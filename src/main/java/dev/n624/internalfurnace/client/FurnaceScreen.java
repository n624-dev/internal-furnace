package dev.n624.internalfurnace.client;

import dev.n624.internalfurnace.core.Catalog;
import dev.n624.internalfurnace.core.Upgrade;
import dev.n624.internalfurnace.forge.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/** Dedicated six-tab menu. No client-side computation can authorize a purchase or item conversion. */
public final class FurnaceScreen extends AbstractContainerScreen<FurnaceMenu> {
    private final List<Button> queueButtons = new ArrayList<>(), upgradeButtons = new ArrayList<>(), automationButtons = new ArrayList<>();
    private final List<Button> chamberButtons = new ArrayList<>();
    private final List<AbstractWidget> mainWidgets = new ArrayList<>();
    private RuleEditorPanel ruleEditor;
    private Button buy, pause, protect;
    private EditBox rulesBox, fuelsBox, thresholdBox;
    private int track, selectedQueue;
    private boolean fuelEnabled;
    public FurnaceScreen(FurnaceMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title); imageWidth = 320; imageHeight = 240; inventoryLabelY = 146;
    }
    private Component text(String key) { return Component.translatable("gui.internal_furnace." + key); }
    private <T extends AbstractWidget> T mainWidget(T widget) { mainWidgets.add(widget); return addRenderableWidget(widget); }
    private Button button(int x, int y, int width, Component label, Runnable callback) {
        return mainWidget(Button.builder(label, b -> callback.run()).bounds(leftPos + x, topPos + y, width, 18).build());
    }
    private void send(int action, int a, int b, long revision, String value) {
        FurnaceNetwork.CHANNEL.sendToServer(new FurnaceNetwork.Request(menu.containerId, action, a, b, revision, value));
    }
    @Override protected void init() {
        String draftRules = rulesBox == null ? null : rulesBox.getValue();
        String draftFuels = fuelsBox == null ? null : fuelsBox.getValue();
        String draftThreshold = thresholdBox == null ? null : thresholdBox.getValue();
        boolean draftFuelEnabled = fuelEnabled;
        super.init(); queueButtons.clear(); upgradeButtons.clear(); automationButtons.clear(); chamberButtons.clear(); mainWidgets.clear();
        String[] tabs = {"queue", "fuel", "output", "upgrades", "automation", "statistics"};
        for (int i = 0; i < tabs.length; i++) {
            final int page = i;
            button(8 + i * 52, 24, 50, text(tabs[i]), () -> {
                setFocused(null); menu.setTab(page); send(1, page, 0, 0, "");
                if (page == 4) loadAutomationFields();
            });
        }
        queueButtons.add(button(8, 49, 24, Component.literal("<"), () -> selectedQueue = Math.max(0, selectedQueue - 1)));
        queueButtons.add(button(34, 49, 24, Component.literal(">"), () -> selectedQueue = Math.min(menu.data.queueSlots() - 1, selectedQueue + 1)));
        queueButtons.add(button(60, 49, 34, text("first"), () -> send(7, selectedQueue, 0, 0, "")));
        queueButtons.add(button(96, 49, 34, text("last"), () -> send(7, selectedQueue, menu.data.queueSlots() - 1, 0, "")));
        queueButtons.add(button(132, 49, 36, text("pause"), () -> send(8, selectedQueue, 0, 0, "")));
        upgradeButtons.add(button(8, 49, 24, Component.literal("<"), () -> track = Math.floorMod(track - 1, Catalog.tracks().size())));
        upgradeButtons.add(button(144, 49, 24, Component.literal(">"), () -> track = (track + 1) % Catalog.tracks().size()));
        buy = button(8, 217, 160, text("buy"), () -> send(2, 0, 0, menu.data.profile.revision(), Catalog.tracks().get(track)));
        upgradeButtons.add(buy);
        rulesBox = mainWidget(new EditBox(font, leftPos + 8, topPos + 93, 160, 18, text("rules"))); rulesBox.setMaxLength(4096);
        fuelsBox = mainWidget(new EditBox(font, leftPos + 8, topPos + 171, 160, 18, text("fuels"))); fuelsBox.setMaxLength(2048);
        thresholdBox = mainWidget(new EditBox(font, leftPos + 118, topPos + 194, 50, 18, text("threshold"))); thresholdBox.setMaxLength(3);
        automationButtons.add(button(8, 49, 160, text("editor.open"), () -> {
            try {
                String json = rulesBox.getValue().isBlank() ? "{}" : rulesBox.getValue();
                ruleEditor.open(RuleCodec.read(json, menu.data.profile.level(Upgrade.AUTO_INPUT)), menu.data.profile.level(Upgrade.AUTO_INPUT));
                setFocused(null);
            } catch (RuntimeException invalid) {
                minecraft.player.displayClientMessage(Component.translatable("message.internal_furnace.rejected", invalid.getMessage()), false);
            }
        }));
        automationButtons.add(button(8, 115, 160, text("save_rules"), () -> {
            try {
                var parsed = RuleCodec.read(rulesBox.getValue(), menu.data.profile.level(Upgrade.AUTO_INPUT));
                send(5, 0, 0, 0, RuleCodec.write(parsed));
            } catch (RuntimeException invalid) {
                minecraft.player.displayClientMessage(Component.translatable("message.internal_furnace.rejected", invalid.getMessage()), false);
            }
        }));
        automationButtons.add(button(8, 194, 105, text("toggle_fuel"), () -> fuelEnabled = !fuelEnabled));
        automationButtons.add(button(8, 217, 160, text("save_fuel"), () -> {
            try { send(6, Integer.parseInt(thresholdBox.getValue()), fuelEnabled ? 1 : 0, 0, fuelsBox.getValue()); }
            catch (NumberFormatException ignored) { thresholdBox.setValue("25"); }
        }));
        pause = button(181, 193, 130, text("pause_all"), () -> send(3, 0, 0, 0, ""));
        protect = button(181, 215, 130, text("protect_held"), () -> send(4, 0, 0, 0, ""));
        for (int i = 0; i < 4; i++) {
            final int chamber = i;
            chamberButtons.add(button(291, 85 + i * 24, 20, Component.literal("x"), () -> send(9, chamber, 0, 0, "")));
        }
        if (ruleEditor == null) ruleEditor = new RuleEditorPanel(font, () -> minecraft.player.getMainHandItem(), settings -> {
            String encoded = RuleCodec.write(settings);
            if (encoded.length() > 4096) throw new IllegalArgumentException("Rules exceed 4096 characters");
            rulesBox.setValue(encoded); send(5, 0, 0, 0, encoded);
        }, () -> setFocused(null));
        ruleEditor.attach(leftPos, topPos, widget -> addRenderableWidget(widget));
        loadAutomationFields();
        // A resize must not silently overwrite unsent changes with the last server snapshot.
        if (draftRules != null) { rulesBox.setValue(draftRules); fuelsBox.setValue(draftFuels); thresholdBox.setValue(draftThreshold); fuelEnabled = draftFuelEnabled; }
    }
    private void loadAutomationFields() {
        rulesBox.setValue(menu.status.getString("rules")); fuelsBox.setValue(menu.status.getString("fuelPriority"));
        int threshold = menu.status.getInt("fuelThreshold"); thresholdBox.setValue(Integer.toString(threshold == 0 ? 25 : threshold));
        fuelEnabled = menu.status.getBoolean("autoFuel");
    }
    @Override protected void containerTick() {
        super.containerTick(); rulesBox.tick(); fuelsBox.tick(); thresholdBox.tick(); ruleEditor.tick();
    }
    @Override public void render(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        boolean editable = !menu.readOnly && menu.status.getBoolean("healthy");
        mainWidgets.forEach(w -> w.visible = true);
        for (int i = 0; i < chamberButtons.size(); i++) chamberButtons.get(i).active = editable && menu.data.profile.rank() > 0 && i < menu.data.chambers();
        selectedQueue = Math.min(selectedQueue, menu.data.queueSlots() - 1);
        queueButtons.forEach(b -> { b.visible = menu.tab == 0; b.active = editable; });
        queueButtons.get(2).active &= menu.data.profile.level(Upgrade.QUEUE) >= 3;
        queueButtons.get(3).active &= menu.data.profile.level(Upgrade.QUEUE) >= 3;
        queueButtons.get(4).active &= menu.data.profile.level(Upgrade.QUEUE) >= 4;
        upgradeButtons.forEach(b -> { b.visible = menu.tab == 3; b.active = editable; });
        automationButtons.forEach(b -> { b.visible = menu.tab == 4; b.active = editable; });
        automationButtons.get(0).active &= menu.data.profile.level(Upgrade.AUTO_INPUT) > 0;
        automationButtons.get(1).active &= menu.data.profile.level(Upgrade.AUTO_INPUT) > 0;
        automationButtons.get(2).active &= menu.data.profile.level(Upgrade.AUTO_FUEL) > 0;
        automationButtons.get(3).active &= menu.data.profile.level(Upgrade.AUTO_FUEL) > 0;
        rulesBox.setVisible(menu.tab == 4); fuelsBox.setVisible(menu.tab == 4); thresholdBox.setVisible(menu.tab == 4);
        rulesBox.setEditable(editable); fuelsBox.setEditable(editable); thresholdBox.setEditable(editable);
        buy.active = editable && canBuy(nextCost()); pause.active = editable; protect.active = editable;
        pause.setMessage(text(menu.data.paused ? "resume_all" : "pause_all"));
        if (ruleEditor.isOpen() && menu.tab != 4) ruleEditor.dismiss();
        if (ruleEditor.isOpen()) mainWidgets.forEach(w -> w.visible = false);
        ruleEditor.refresh(editable);
        renderBackground(gui); super.render(gui, mouseX, mouseY, partialTick);
        ruleEditor.renderInfo(gui);
        if (menu.status.contains("confirmation")) drawWarning(gui); else renderTooltip(gui, mouseX, mouseY);
    }
    @Override protected void renderBg(GuiGraphics gui, float partialTick, int mouseX, int mouseY) {
        gui.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xff242830);
        if (ruleEditor.isOpen()) return;
        gui.fill(leftPos + 176, topPos + 45, leftPos + 314, topPos + 238, 0xff181c22);
        for (var slot : menu.slots) if (slot.isActive())
            {
                gui.fill(leftPos + slot.x - 1, topPos + slot.y - 1, leftPos + slot.x + 17, topPos + slot.y + 17, 0xff181c22);
                gui.fill(leftPos + slot.x, topPos + slot.y, leftPos + slot.x + 16, topPos + slot.y + 16, 0xff555a63);
            }
        if (menu.tab == 0) {
            int x = leftPos + 7 + selectedQueue % 9 * 18, y = topPos + 71 + selectedQueue / 9 * 18;
            gui.fill(x, y, x + 18, y + 1, 0xffffbc66); gui.fill(x, y + 17, x + 18, y + 18, 0xffffbc66);
        }
    }
    private void line(GuiGraphics gui, String value, int x, int y) { gui.drawString(font, value, x, y, 0xffe3e6eb, false); }
    private String tr(String key) { return text(key).getString(); }
    @Override protected void renderLabels(GuiGraphics gui, int mouseX, int mouseY) {
        if (ruleEditor.isOpen()) return;
        gui.drawString(font, title, 8, 7, 0xfff5f5f5, false);
        if (menu.readOnly) line(gui, tr("read_only"), 235, 7);
        if (menu.tab < 3) gui.drawString(font, playerInventoryTitle, 8, 146, 0xffbbbbbb, false);
        line(gui, "Rank " + menu.data.profile.rank() + " / VII", 181, 49);
        line(gui, "Heat " + String.format(java.util.Locale.ROOT, "%.1f", menu.data.thermal.heat() / 1000.0)
                + " / " + menu.data.profile.value(Upgrade.CAPACITY), 181, 62);
        ListTag jobs = menu.status.getList("jobs", Tag.TAG_COMPOUND);
        for (int i = 0; i < jobs.size(); i++) {
            CompoundTag job = jobs.getCompound(i); int y = 84 + i * 24;
            line(gui, (i + 1) + ": " + font.plainSubstrByWidth(job.getString("name"), 96), 181, y);
            gui.fill(181, y + 11, 287, y + 15, 0xff474b52);
            long duration = job.getLong("duration"), progress = job.getLong("progress");
            if (duration > 0) gui.fill(181, y + 11, 181 + (int) Math.min(106, 106 * progress / duration), y + 15, 0xffffab4d);
        }
        line(gui, tr("efficiency") + " " + menu.data.profile.value(Upgrade.EFFICIENCY) / 10.0 + "%", 181, 182);
        if (!menu.status.getBoolean("healthy")) { line(gui, tr("recovery"), 8, 132); return; }
        if (menu.tab == 0) line(gui, tr("selected") + " " + (selectedQueue + 1) + (menu.data.queuePaused[selectedQueue] ? " [PAUSE]" : ""), 8, 133);
        if (menu.data.profile.rank() == 0 && menu.tab < 3) line(gui, tr("locked"), 8, 133);
        if (menu.tab == 3) drawCost(gui);
        if (menu.tab == 4) {
            line(gui, tr("rules_json"), 8, 77);
            line(gui, tr("fuel_state") + " " + (fuelEnabled ? "ON" : "OFF"), 8, 149);
            line(gui, tr("fuel_ids"), 8, 160);
        }
        if (menu.tab == 5) {
            line(gui, tr("completed") + " " + menu.status.getLong("completed"), 8, 52);
            line(gui, tr("burned") + " " + menu.status.getLong("burned"), 8, 66);
            int y = 85;
            for (Upgrade u : Upgrade.values()) {
                line(gui, Component.translatable(u.translationKey()).getString() + ": " + menu.data.profile.level(u) + "/" + u.maximum(), 8, y); y += 13;
            }
        }
    }
    private CompoundTag nextCost() {
        String id = Catalog.tracks().get(track);
        for (Tag t : menu.status.getList("costs", Tag.TAG_COMPOUND)) {
            CompoundTag cost = (CompoundTag) t;
            if (id.equals(cost.getString("track"))) return cost;
        }
        return null;
    }
    private boolean canBuy(CompoundTag cost) {
        if (cost == null || menu.data.profile.rank() < cost.getInt("rank") || menu.status.getInt("combat") < cost.getInt("combat")
                || menu.status.getInt("xp") < cost.getInt("xp")) return false;
        for (Tag tag : cost.getList("items", Tag.TAG_COMPOUND)) {
            CompoundTag item = (CompoundTag) tag; if (item.getInt("owned") < item.getInt("count")) return false;
        }
        return true;
    }
    private void drawCost(GuiGraphics gui) {
        String id = Catalog.tracks().get(track);
        String name = id.equals("rank") ? tr("core") : Component.translatable(Upgrade.byId(id).translationKey()).getString();
        gui.drawCenteredString(font, font.plainSubstrByWidth(name, 108), 88, 54, 0xffeeeeee);
        CompoundTag cost = nextCost();
        if (cost == null) { line(gui, tr("maxed"), 8, 77); return; }
        line(gui, "Rank " + cost.getInt("rank") + " / M&S " + cost.getInt("combat"), 8, 72);
        line(gui, "XP " + cost.getInt("xp") + " / " + tr("step") + " " + cost.getInt("level"), 8, 83);
        int y = 94;
        for (Tag tag : cost.getList("items", Tag.TAG_COMPOUND)) {
            CompoundTag material = (CompoundTag) tag;
            var item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(material.getString("id")));
            if (item != null) {
                ItemStack stack = new ItemStack(item); gui.renderItem(stack, 8, y - 2);
                line(gui, font.plainSubstrByWidth(stack.getHoverName().getString(), 83), 27, y + 2);
            }
            line(gui, material.getInt("owned") + "/" + material.getInt("count"), 112, y + 2); y += 15;
        }
    }
    private void drawWarning(GuiGraphics gui) {
        gui.pose().pushPose(); gui.pose().translate(0, 0, 400);
        int x = leftPos + 20, y = topPos + 62;
        gui.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xde101010);
        gui.fill(x, y, x + 290, y + 120, 0xff4a3030);
        gui.drawCenteredString(font, text("warning"), x + 145, y + 10, 0xffffbb77);
        gui.drawCenteredString(font, font.plainSubstrByWidth(menu.status.getString("warningItem"), 270), x + 145, y + 31, 0xffffffff);
        gui.drawCenteredString(font, text("warning_loss"), x + 145, y + 49, 0xffffffff);
        gui.drawCenteredString(font, "→ " + menu.status.getString("warningResult"), x + 145, y + 66, 0xffffffff);
        gui.fill(x + 15, y + 91, x + 135, y + 112, 0xff606060); gui.fill(x + 155, y + 91, x + 275, y + 112, 0xff9b5339);
        gui.drawCenteredString(font, text("cancel"), x + 75, y + 97, 0xffffffff);
        gui.drawCenteredString(font, text("confirm"), x + 215, y + 97, 0xffffffff);
        gui.pose().popPose();
    }
    @Override public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (menu.status.contains("confirmation")) {
            double x = mouseX - leftPos - 20, y = mouseY - topPos - 62;
            if (button == 0 && y >= 91 && y <= 112) {
                if (x >= 15 && x <= 135) send(11, 0, 0, 0, "");
                if (x >= 155 && x <= 275) send(10, 0, 0, menu.status.getLong("confirmation"), "");
            }
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }
    @Override public boolean mouseReleased(double x, double y, int button) {
        return menu.status.contains("confirmation") || super.mouseReleased(x, y, button);
    }
    @Override public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        return menu.status.contains("confirmation") || super.mouseDragged(x, y, button, dx, dy);
    }
    @Override public boolean keyPressed(int key, int scan, int modifiers) {
        if (menu.status.contains("confirmation")) { if (key == GLFW.GLFW_KEY_ESCAPE) send(11, 0, 0, 0, ""); return true; }
        if (ruleEditor.isOpen() && key == GLFW.GLFW_KEY_ESCAPE) { ruleEditor.dismiss(); return true; }
        if (menu.tab == 4 && key != GLFW.GLFW_KEY_ESCAPE && getFocused() instanceof EditBox box) {
            box.keyPressed(key, scan, modifiers); return true;
        }
        return super.keyPressed(key, scan, modifiers);
    }
}
