package dev.n624.internalfurnace.core;

import java.util.ArrayList;
import java.util.List;

/** Transactional client editor. The server independently revalidates its serialized result. */
public final class RuleEditorModel {
    private final int tier;
    private Rules.Settings settings;

    public RuleEditorModel(Rules.Settings initial, int tier) {
        initial.validate(tier);
        this.tier = tier;
        this.settings = initial;
    }
    public int tier() { return tier; }
    public Rules.Settings snapshot() { return settings; }
    private void commit(List<Rules.Rule> rules) {
        commit(new Rules.Settings(settings.enabled(), settings.allowNamed(), settings.allowEnchanted(), rules));
    }
    private void commit(Rules.Settings candidate) {
        candidate.validate(tier); // Failed edits never partly mutate the current document.
        settings = candidate;
    }
    public void flags(boolean enabled, boolean allowNamed, boolean allowEnchanted) {
        commit(new Rules.Settings(enabled, allowNamed, allowEnchanted, settings.rules()));
    }
    public int addRule(Rules.Condition condition) {
        List<Rules.Rule> rules = new ArrayList<>(settings.rules());
        // New rules protect by default. Enabling destructive routing needs an explicit action change.
        rules.add(new Rules.Rule(Rules.Action.PROTECT, List.of(List.of(condition))));
        commit(rules);
        return rules.size() - 1;
    }
    public void removeRule(int rule) {
        List<Rules.Rule> rules = new ArrayList<>(settings.rules()); rules.remove(rule); commit(rules);
    }
    public void action(int rule, Rules.Action action) {
        replaceRule(rule, new Rules.Rule(action, settings.rules().get(rule).any()));
    }
    public void replace(int rule, int group, int condition, Rules.Condition value) {
        List<List<Rules.Condition>> groups = groups(rule);
        groups.get(group).set(condition, value); replaceGroups(rule, groups);
    }
    public void addAnd(int rule, int group, Rules.Condition condition) {
        List<List<Rules.Condition>> groups = groups(rule);
        groups.get(group).add(condition); replaceGroups(rule, groups);
    }
    public void addOr(int rule, Rules.Condition condition) {
        List<List<Rules.Condition>> groups = groups(rule);
        groups.add(new ArrayList<>(List.of(condition))); replaceGroups(rule, groups);
    }
    public void removeCondition(int rule, int group, int condition) {
        List<List<Rules.Condition>> groups = groups(rule);
        groups.get(group).remove(condition);
        if (groups.get(group).isEmpty()) groups.remove(group);
        if (groups.isEmpty()) removeRule(rule); else replaceGroups(rule, groups);
    }
    private List<List<Rules.Condition>> groups(int rule) {
        List<List<Rules.Condition>> groups = new ArrayList<>();
        for (List<Rules.Condition> group : settings.rules().get(rule).any()) groups.add(new ArrayList<>(group));
        return groups;
    }
    private void replaceGroups(int rule, List<List<Rules.Condition>> groups) {
        replaceRule(rule, new Rules.Rule(settings.rules().get(rule).action(), groups));
    }
    private void replaceRule(int rule, Rules.Rule replacement) {
        List<Rules.Rule> rules = new ArrayList<>(settings.rules()); rules.set(rule, replacement); commit(rules);
    }
    public static String defaultValue(Rules.Field field) {
        return switch (field) {
            case ITEM -> "minecraft:iron_sword";
            case CATEGORY -> "iron_gear";
            case TAG -> "minecraft:logs";
            case DURABILITY_LT -> "25";
            case ENCHANTED, NAMED -> "false";
            // No guessed mod-specific rarity IDs. The UI can copy the actual held gear value.
            case RARITY -> "select_from_held_gear";
            case LEVEL_LT -> "40";
            case KIND -> "armor";
        };
    }
}
