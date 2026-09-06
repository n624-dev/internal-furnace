package dev.n624.internalfurnace.core;

import java.util.List;
import java.util.Random;
import java.util.Set;

public final class EditorTests {
    private static int checks;
    private static void check(boolean value) { checks++; if (!value) throw new AssertionError("check " + checks); }
    private static Rules.Condition item() { return new Rules.Condition(Rules.Field.ITEM, "minecraft:iron_sword"); }
    private static void rejected(RuleEditorModel model, Runnable edit) {
        Rules.Settings before = model.snapshot();
        try { edit.run(); throw new AssertionError("Invalid edit accepted"); }
        catch (IllegalArgumentException | IndexOutOfBoundsException expected) { check(model.snapshot().equals(before)); }
    }
    public static void main(String[] args) {
        RuleEditorModel locked = new RuleEditorModel(Rules.Settings.disabled(), 0);
        rejected(locked, () -> locked.addRule(item()));
        rejected(locked, () -> locked.flags(true, false, false));
        for (int tier = 1; tier <= 5; tier++) {
            RuleEditorModel model = new RuleEditorModel(Rules.Settings.disabled(), tier);
            check(model.addRule(item()) == 0);
            check(model.snapshot().rules().get(0).action() == Rules.Action.PROTECT);
            check(!model.snapshot().enabled());
            Rules.Settings before = model.snapshot();
            model.action(0, Rules.Action.INPUT);
            check(before.rules().get(0).action() == Rules.Action.PROTECT);
            if (tier < 3) rejected(model, () -> model.flags(true, true, true));
            for (Rules.Field field : Rules.Field.values()) {
                Rules.Condition condition = new Rules.Condition(field, RuleEditorModel.defaultValue(field));
                if (field.tier > tier) rejected(model, () -> model.replace(0, 0, 0, condition));
                else { model.replace(0, 0, 0, condition); check(model.snapshot().rules().get(0).any().get(0).get(0).equals(condition)); }
            }
            model.replace(0, 0, 0, item());
            if (tier < 5) {
                rejected(model, () -> model.addAnd(0, 0, item()));
                rejected(model, () -> model.addOr(0, item()));
            }
            for (int n = 1; n < 16; n++) model.addRule(item());
            rejected(model, () -> model.addRule(item()));
            rejected(model, () -> model.removeRule(16));
            for (int n = 15; n >= 0; n--) model.removeCondition(n, 0, 0);
            check(model.snapshot().rules().isEmpty());
        }
        RuleEditorModel basic = new RuleEditorModel(Rules.Settings.disabled(), 4);
        basic.addRule(new Rules.Condition(Rules.Field.RARITY, "common"));
        basic.addAnd(0, 0, new Rules.Condition(Rules.Field.LEVEL_LT, "40"));
        check(basic.snapshot().rules().get(0).any().get(0).size() == 2);
        rejected(basic, () -> basic.addAnd(0, 0, item()));
        basic.action(0, Rules.Action.INPUT); basic.flags(true, false, false);
        Rules.Facts gear = new Rules.Facts("minecraft:iron_sword", Set.of("iron_gear"), Set.of(), 20,
                false, false, false, true, true, "common", 30, "weapon");
        check(basic.snapshot().accepts(gear, 4));
        basic.addRule(item()); // Safety rule has priority regardless of list position.
        check(!basic.snapshot().accepts(gear, 4));
        RuleEditorModel advanced = new RuleEditorModel(Rules.Settings.disabled(), 5);
        advanced.addRule(item());
        for (int g = 0; g < 8; g++) {
            if (g > 0) advanced.addOr(0, item());
            for (int c = 1; c < 8; c++) advanced.addAnd(0, g, item());
        }
        check(advanced.snapshot().rules().get(0).any().size() == 8);
        rejected(advanced, () -> advanced.addAnd(0, 0, item()));
        rejected(advanced, () -> advanced.addOr(0, item()));
        rejected(advanced, () -> advanced.addRule(item())); // 64-condition global bound.
        Rules.Settings frozen = advanced.snapshot();
        advanced.removeCondition(0, 0, 0);
        check(frozen.rules().get(0).any().get(0).size() == 8);
        advanced.addRule(item());
        Random random = new Random(14);
        for (int n = 0; n < 20_000; n++) {
            int count = random.nextInt(65), mode = random.nextInt(2), slots = random.nextInt(28);
            int[] free = new int[slots];
            for (int i = 0; i < slots; i++) free[i] = random.nextInt(65);
            DragDistribution.Plan plan = DragDistribution.plan(count, free, mode, false);
            int moved = 0;
            for (int i = 0; i < slots; i++) {
                int amount = plan.added()[i];
                check(amount >= 0 && amount <= free[i]);
                check(mode != 1 || amount <= 1); moved += amount;
            }
            check(count == moved + plan.remaining()); check(plan.remaining() >= 0);
        }
        check(DragDistribution.plan(8, new int[]{64, 64, 64}, 0, false).remaining() == 2);
        check(DragDistribution.plan(1, new int[]{10, 2}, 2, true).remaining() == 1);
        try { DragDistribution.plan(64, new int[]{64}, 2, false); throw new AssertionError("Creative bypass"); }
        catch (IllegalArgumentException expected) { check(true); }
        System.out.println("PASS: " + checks + " editor/drag checks; 20,000 seeded drag cases");
    }
}
