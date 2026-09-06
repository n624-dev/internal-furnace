# Configuration

[日本語](CONFIGURATION.ja.md) · [Home](../README.md)

## Costs and server settings

The built-in [upgrades.psv](../src/main/resources/data/internal_furnace/upgrades.psv) contains all 53 purchases. To override it, put a complete replacement at `config/internal-furnace-upgrades.psv`, then restart the server. Format:

```text
track|tier|requiredRank|requiredMnsLevel|xpLevels|item=count,...
```

Unknown items, missing/duplicate rows and invalid numbers are rejected. Up to eight material kinds per row are allowed. Performance caps remain in code. Auto Input uses M&S levels **60/65/75/80/90** and XP costs **35/45/55/70/85**; these are separate requirements.

Forge creates the per-world file `serverconfig/internal_furnace-server.toml`. `inventoryScanTicks` defaults to 10; `idleLossMilliheat` defaults to 1000 (1 heat/tick before insulation). One heat is 1000 milliheat. Baseline cooling and most prices are initial balance defaults, not a promise that every server economy is balanced.

## Input rules

Prefer the visual editor. The JSON field remains available for advanced configuration:

```json
{"enabled":true,"allowNamed":false,"allowEnchanted":false,"rules":[{"action":"INPUT","any":[[{"field":"RARITY","value":"common"},{"field":"LEVEL_LT","value":"40"}]]}]}
```

This example selects explicitly readable common M&S equipment below level 40 and requires Auto Input IV or V. Use the actual rarity identifier from held-item inspection rather than inventing a display name.

`any` contains OR groups. Conditions within a group are ANDed. Supported fields: ITEM, CATEGORY, TAG, DURABILITY_LT, ENCHANTED, NAMED, RARITY, LEVEL_LT, KIND. The server validates fields against the purchased tier.

- Categories: `iron_gear`, `gold_gear`, `ore`, `food`.
- Kinds: `armor`, `weapon`, `tool`, `other`.
- Numeric bounds mean strictly less than.
- Maximum 16 rules, 64 total conditions, eight OR groups/rule and eight conditions/group; JSON limit 4096 characters.
- PROTECT always wins. Scripts and regular expressions are not evaluated.
- New editor rules protect by default; input remains disabled until explicitly enabled.

The fuel editor supports priorities and heat thresholds as their upgrades unlock. Predictive fueling estimates known work but consumes whole fuel items, so leftover heat is possible.
