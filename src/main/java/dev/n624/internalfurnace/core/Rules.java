package dev.n624.internalfurnace.core;

import java.util.List;
import java.util.Set;

/** Bounded OR-of-AND rules, without scripts, regexes or client-supplied decisions. */
public final class Rules {
    private Rules() {}
    public enum Field {
        ITEM(1), CATEGORY(2), TAG(3), DURABILITY_LT(3), ENCHANTED(3), NAMED(3),
        RARITY(4), LEVEL_LT(4), KIND(4);
        public final int tier;
        Field(int tier) { this.tier = tier; }
    }
    public record Facts(String item, Set<String> categories, Set<String> tags, int durabilityPercent,
                        boolean enchanted, boolean named, boolean locked, boolean special,
                        boolean readable, String rarity, int equipmentLevel, String kind) {
        public Facts {
            categories = Set.copyOf(categories); tags = Set.copyOf(tags);
            if (item == null || rarity == null || kind == null) throw new IllegalArgumentException("Null facts");
        }
    }
    public record Condition(Field field, String value) {
        public Condition {
            if (field == null || value == null || value.isBlank() || value.length() > 128)
                throw new IllegalArgumentException("Invalid condition");
            if (field == Field.DURABILITY_LT || field == Field.LEVEL_LT) {
                int n = Integer.parseInt(value);
                if (n < 0 || n > (field == Field.DURABILITY_LT ? 101 : 1001))
                    throw new IllegalArgumentException("Threshold out of range");
            }
            if ((field == Field.ENCHANTED || field == Field.NAMED) && !Set.of("true", "false").contains(value))
                throw new IllegalArgumentException("Expected boolean");
        }
        public boolean matches(Facts f) {
            return switch (field) {
                case ITEM -> f.item.equals(value);
                case CATEGORY -> f.categories.contains(value);
                case TAG -> f.tags.contains(value);
                case DURABILITY_LT -> f.durabilityPercent >= 0 && f.durabilityPercent < Integer.parseInt(value);
                case ENCHANTED -> f.enchanted == Boolean.parseBoolean(value);
                case NAMED -> f.named == Boolean.parseBoolean(value);
                case RARITY -> !f.rarity.isEmpty() && f.rarity.equals(value);
                case LEVEL_LT -> f.equipmentLevel >= 0 && f.equipmentLevel < Integer.parseInt(value);
                case KIND -> f.kind.equals(value);
            };
        }
    }
    public enum Action { INPUT, PROTECT }
    public record Rule(Action action, List<List<Condition>> any) {
        public Rule {
            if (action == null || any == null || any.isEmpty() || any.size() > 8)
                throw new IllegalArgumentException("Invalid rule groups");
            any = any.stream().map(group -> {
                if (group.isEmpty() || group.size() > 8) throw new IllegalArgumentException("Invalid AND group");
                return List.copyOf(group);
            }).toList();
        }
        public boolean matches(Facts facts) {
            return any.stream().anyMatch(group -> group.stream().allMatch(c -> c.matches(facts)));
        }
        public boolean explicitlyAllowsRarity(Facts facts) {
            return any.stream().anyMatch(group -> group.stream().allMatch(c -> c.matches(facts))
                    && group.stream().anyMatch(c -> c.field == Field.RARITY && c.matches(facts)));
        }
    }
    public record Settings(boolean enabled, boolean allowNamed, boolean allowEnchanted, List<Rule> rules) {
        public Settings {
            if (rules == null || rules.size() > 16) throw new IllegalArgumentException("Too many rules");
            rules = List.copyOf(rules);
            if (rules.stream().mapToInt(r -> r.any.stream().mapToInt(List::size).sum()).sum() > 64)
                throw new IllegalArgumentException("Too many conditions");
        }
        public static Settings disabled() { return new Settings(false, false, false, List.of()); }
        public void validate(int tier) {
            if (tier < 0 || tier > 5 || ((enabled || !rules.isEmpty()) && tier == 0))
                throw new IllegalArgumentException("Auto input is locked");
            if (tier < 3 && (allowNamed || allowEnchanted)) throw new IllegalArgumentException("Safety override locked");
            for (Rule rule : rules) {
                boolean basicMnsSelector = tier == 4 && rule.any.size() == 1 && rule.any.get(0).size() == 2
                        && rule.any.get(0).stream().filter(c -> c.field == Field.RARITY).count() == 1
                        && rule.any.get(0).stream().filter(c -> c.field == Field.LEVEL_LT).count() == 1;
                if (tier < 5 && !basicMnsSelector && (rule.any.size() != 1 || rule.any.get(0).size() != 1))
                    throw new IllegalArgumentException("General compound rules require tier V");
                for (var group : rule.any) for (Condition c : group)
                    if (c.field.tier > tier) throw new IllegalArgumentException("Condition is locked");
            }
        }
        public boolean accepts(Facts facts, int tier) {
            if (!enabled || tier == 0 || facts.locked || !facts.readable) return false;
            // Malformed or downgraded configuration fails closed.
            try { validate(tier); } catch (IllegalArgumentException e) { return false; }
            if ((!allowNamed && facts.named) || (!allowEnchanted && facts.enchanted)) return false;
            if (rules.stream().anyMatch(r -> r.action == Action.PROTECT && r.matches(facts))) return false;
            return rules.stream().anyMatch(r -> r.action == Action.INPUT && r.matches(facts)
                    && (!facts.special || (tier >= 4 && r.explicitlyAllowsRarity(facts))));
        }
    }
}
