package dev.n624.internalfurnace.client;

import dev.n624.internalfurnace.core.RuleEditorModel;
import dev.n624.internalfurnace.core.Rules;
import dev.n624.internalfurnace.forge.MnsBridge;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Embedded in the container screen: opening the editor never closes the server menu. */
public final class RuleEditorPanel {
    private record Placed(AbstractWidget widget, int x, int y) {}
    private final List<Placed> widgets = new ArrayList<>();
    private final Font font;
    private final Supplier<ItemStack> held;
    private final Consumer<Rules.Settings> save;
    private final Runnable close;
    private final EditBox value;
    private final Button action, enabled, named, enchanted, fieldButton, addRule, addAnd, addOr;
    private RuleEditorModel model;
    private int left, top, rule, group, condition;
    private Rules.Field field = Rules.Field.ITEM;
    private String error = "";
    private boolean open;

    public RuleEditorPanel(Font font, Supplier<ItemStack> held, Consumer<Rules.Settings> save, Runnable close) {
        this.font = font; this.held = held; this.save = save; this.close = close;
        button(108, 49, 20, "<", () -> navigate(0, -1));
        button(130, 49, 20, ">", () -> navigate(0, 1));
        addRule = button(152, 49, 20, "+", () -> {
            applyValue(); rule = model.addRule(new Rules.Condition(Rules.Field.ITEM, heldItemId()));
            group = condition = 0; loadValue();
        });
        button(174, 49, 20, "-", () -> { if (hasRule()) { model.removeRule(rule); clamp(); loadValue(); } });
        action = button(202, 49, 110, "", () -> {
            applyValue(); if (hasRule()) model.action(rule, selectedRule().action() == Rules.Action.INPUT ? Rules.Action.PROTECT : Rules.Action.INPUT);
        });
        button(108, 71, 20, "<", () -> navigate(1, -1));
        button(130, 71, 20, ">", () -> navigate(1, 1));
        addOr = button(152, 71, 52, "+ OR", () -> {
            applyValue(); if (hasRule()) { model.addOr(rule, new Rules.Condition(Rules.Field.ITEM, heldItemId()));
                group = selectedRule().any().size() - 1; condition = 0; loadValue(); }
        });
        enabled = button(210, 71, 102, "", () -> {
            applyValue(); var s = model.snapshot(); model.flags(!s.enabled(), s.allowNamed(), s.allowEnchanted());
        });
        button(108, 93, 20, "<", () -> navigate(2, -1));
        button(130, 93, 20, ">", () -> navigate(2, 1));
        addAnd = button(152, 93, 52, "+ AND", () -> {
            applyValue(); if (hasRule()) {
                Rules.Field next = model.tier() == 4 ? Rules.Field.LEVEL_LT : Rules.Field.ITEM;
                model.addAnd(rule, group, new Rules.Condition(next, RuleEditorModel.defaultValue(next)));
                condition = selectedGroup().size() - 1; loadValue();
            }
        });
        localizedButton(210, 93, 102, "delete_condition", () -> {
            if (hasRule()) { model.removeCondition(rule, group, condition); clamp(); loadValue(); }
        });
        fieldButton = button(8, 115, 152, "", this::cycleField);
        value = new EditBox(font, 0, 0, 144, 18, tr("value")); value.setMaxLength(128);
        widgets.add(new Placed(value, 168, 115));
        localizedButton(8, 137, 100, "from_held", this::copyHeld);
        localizedButton(112, 137, 100, "next_value", this::nextValue);
        localizedButton(216, 137, 96, "apply_value", this::applyValue);
        named = localizedButton(8, 159, 152, "allow_named", () -> {
            applyValue(); var s = model.snapshot(); model.flags(s.enabled(), !s.allowNamed(), s.allowEnchanted());
        });
        enchanted = localizedButton(168, 159, 144, "allow_enchanted", () -> {
            applyValue(); var s = model.snapshot(); model.flags(s.enabled(), s.allowNamed(), !s.allowEnchanted());
        });
        localizedButton(8, 217, 152, "send", () -> { applyValue(); save.accept(model.snapshot()); dismiss(); });
        localizedButton(168, 217, 144, "discard", this::dismiss);
    }
    private static Component tr(String key) { return Component.translatable("gui.internal_furnace.editor." + key); }
    private Button button(int x, int y, int width, String label, Runnable callback) {
        Button result = Button.builder(Component.literal(label), b -> attempt(callback)).bounds(0, 0, width, 18).build();
        widgets.add(new Placed(result, x, y)); return result;
    }
    private Button localizedButton(int x, int y, int width, String key, Runnable callback) {
        Button b = button(x, y, width, "", callback); b.setMessage(tr(key)); return b;
    }
    public void attach(int left, int top, Consumer<AbstractWidget> add) {
        this.left = left; this.top = top;
        for (Placed p : widgets) { p.widget.setX(left + p.x); p.widget.setY(top + p.y); add.accept(p.widget); }
    }
    public boolean isOpen() { return open; }
    public void open(Rules.Settings initial, int tier) {
        model = new RuleEditorModel(initial, tier); rule = group = condition = 0; error = ""; open = true; loadValue();
    }
    public void dismiss() { open = false; close.run(); }
    public void tick() { if (open) value.tick(); }
    private boolean hasRule() { return model != null && !model.snapshot().rules().isEmpty(); }
    private Rules.Rule selectedRule() { return model.snapshot().rules().get(rule); }
    private List<Rules.Condition> selectedGroup() { return selectedRule().any().get(group); }
    private void clamp() {
        if (!hasRule()) { rule = group = condition = 0; return; }
        rule = Math.min(rule, model.snapshot().rules().size() - 1);
        group = Math.min(group, selectedRule().any().size() - 1);
        condition = Math.min(condition, selectedGroup().size() - 1);
    }
    private void loadValue() {
        if (!hasRule()) { field = Rules.Field.ITEM; value.setValue(""); return; }
        var c = selectedGroup().get(condition); field = c.field(); value.setValue(c.value());
    }
    private void applyValue() {
        if (hasRule()) model.replace(rule, group, condition, new Rules.Condition(field, value.getValue().trim()));
    }
    private void navigate(int axis, int direction) {
        applyValue(); if (!hasRule()) return;
        if (axis == 0) { rule = Math.floorMod(rule + direction, model.snapshot().rules().size()); group = condition = 0; }
        if (axis == 1) { group = Math.floorMod(group + direction, selectedRule().any().size()); condition = 0; }
        if (axis == 2) condition = Math.floorMod(condition + direction, selectedGroup().size());
        loadValue();
    }
    private String heldItemId() {
        ItemStack stack = held.get();
        return stack.isEmpty() ? RuleEditorModel.defaultValue(Rules.Field.ITEM) : MnsBridge.facts(stack).item();
    }
    private void copyHeld() {
        ItemStack stack = held.get(); if (stack.isEmpty()) throw new IllegalArgumentException(tr("hold_item").getString());
        Rules.Facts facts = MnsBridge.facts(stack);
        String copied = switch (field) {
            case ITEM -> facts.item();
            case CATEGORY -> facts.categories().stream().sorted().findFirst().orElse("");
            case TAG -> facts.tags().stream().sorted().findFirst().orElse("");
            case DURABILITY_LT -> facts.durabilityPercent() < 0 ? "" : Integer.toString(Math.min(101, facts.durabilityPercent() + 1));
            case ENCHANTED -> Boolean.toString(facts.enchanted());
            case NAMED -> Boolean.toString(facts.named());
            case RARITY -> facts.readable() ? facts.rarity() : "";
            case LEVEL_LT -> facts.equipmentLevel() < 0 ? "" : Integer.toString(Math.min(1001, facts.equipmentLevel() + 1));
            case KIND -> facts.kind();
        };
        if (copied.isBlank()) throw new IllegalArgumentException(tr("no_attribute").getString());
        value.setValue(copied);
    }
    private void cycleField() {
        List<Rules.Field> allowed = Arrays.stream(Rules.Field.values()).filter(f -> f.tier <= model.tier()).toList();
        if (!allowed.isEmpty()) { field = allowed.get((allowed.indexOf(field) + 1) % allowed.size()); value.setValue(RuleEditorModel.defaultValue(field)); }
    }
    private void nextValue() {
        List<String> options = switch (field) {
            case CATEGORY -> List.of("iron_gear", "gold_gear", "ore", "food");
            case KIND -> List.of("armor", "weapon", "tool", "other");
            case ENCHANTED, NAMED -> List.of("false", "true");
            case DURABILITY_LT -> List.of("10", "25", "50", "75", "101");
            case LEVEL_LT -> List.of("20", "30", "40", "60", "80", "1001");
            default -> List.of(RuleEditorModel.defaultValue(field));
        };
        value.setValue(options.get((options.indexOf(value.getValue()) + 1) % options.size()));
    }
    private void attempt(Runnable action) {
        try { action.run(); error = ""; }
        catch (IllegalArgumentException | IndexOutOfBoundsException problem) { error = problem.getMessage(); }
    }
    public void refresh(boolean editable) {
        for (Placed p : widgets) { p.widget.visible = open; p.widget.active = open && editable; }
        if (!open) return;
        value.setEditable(editable && hasRule());
        action.active &= hasRule(); fieldButton.active &= hasRule();
        addRule.active &= model.tier() > 0 && model.snapshot().rules().size() < 16;
        addOr.active &= hasRule() && model.tier() == 5;
        addAnd.active &= hasRule() && (model.tier() == 5 || (model.tier() == 4 && selectedGroup().size() == 1
                && selectedGroup().get(0).field() == Rules.Field.RARITY));
        named.active &= model.tier() >= 3; enchanted.active &= model.tier() >= 3;
        var s = model.snapshot();
        action.setMessage(tr(hasRule() && selectedRule().action() == Rules.Action.INPUT ? "input" : "protect"));
        enabled.setMessage(Component.literal(tr("enabled").getString() + ": " + (s.enabled() ? "ON" : "OFF")));
        named.setMessage(Component.literal(tr("allow_named").getString() + ": " + (s.allowNamed() ? "ON" : "OFF")));
        enchanted.setMessage(Component.literal(tr("allow_enchanted").getString() + ": " + (s.allowEnchanted() ? "ON" : "OFF")));
        fieldButton.setMessage(tr("field." + field.name().toLowerCase(java.util.Locale.ROOT)));
    }
    private void line(GuiGraphics gui, String text, int x, int y, int width, int color) {
        gui.drawString(font, font.plainSubstrByWidth(text, width), left + x, top + y, color, false);
    }
    public void renderInfo(GuiGraphics gui) {
        if (!open) return;
        line(gui, tr("open").getString(), 8, 7, 310, 0xffeeeeee);
        line(gui, tr("rule").getString() + " " + (hasRule() ? rule + 1 : 0) + "/" + model.snapshot().rules().size(), 8, 54, 97, 0xffeeeeee);
        line(gui, "OR " + (hasRule() ? group + 1 : 0) + "/" + (hasRule() ? selectedRule().any().size() : 0), 8, 76, 97, 0xffeeeeee);
        line(gui, "AND " + (hasRule() ? condition + 1 : 0) + "/" + (hasRule() ? selectedGroup().size() : 0), 8, 98, 97, 0xffeeeeee);
        line(gui, error.isEmpty() ? tr("safety").getString() : error, 8, 183, 310, error.isEmpty() ? 0xffffbb77 : 0xffff7777);
        line(gui, tr("tier").getString() + " " + model.tier() + " / V — " + tr("draft").getString(), 8, 199, 310, 0xffbbbbbb);
    }
}
