# Player guide

[日本語](GUIDE.ja.md) · [Home](../README.md)

## Progression

Open the furnace with V or `/internalfurnace`. The furnace starts locked. The Upgrade tab shows the complete cost before purchase. Core ranks I–VII require M&S combat levels **10/20/30/45/60/75/90**. Ranks grant purchase eligibility; performance upgrades are bought separately. Purchased upgrades remain unlocked if your combat level falls.

Materials and vanilla XP **levels** are consumed together on the server. XP 85 means 85 levels, not 85 points. Protected items cannot pay upgrade costs. See the complete [53-row cost table](../src/main/resources/data/internal_furnace/upgrades.psv).

| Upgrade | Initial → maximum |
| --- | --- |
| Chambers | 1 → 4 |
| Speed | 1× → 2× |
| Fuel efficiency | 50% → 90% |
| Heat capacity | 1,600 → 51,200 |
| Idle heat loss | 100% → 10% of baseline |
| Queue | 1 → 27 slots |
| Fuel / output | 1 → 18 slots each |

Auto Input costs use four echo shards in total. Only the final insulation upgrade consumes one Mowzie's Mobs Ice Crystal.

## Six tabs and controls

- **Queue:** add items that have a valid recipe. Select a slot and use First/Last or arrows to reorder once unlocked. Slot pause unlocks at queue IV. Chamber × returns its input to the queue when possible.
- **Fuel:** insert valid fuel. Large fuel is refused if its entire heat value cannot fit.
- **Output:** collect finished items and the corresponding XP. Output cannot accept items.
- **Upgrade:** inspect costs and buy ranks or individual upgrades.
- **Auto:** configure automatic input and fuel. Start with both disabled and explicit rules.
- **Stats:** inspect processing and fuel counters.

Normal left/right clicks, Shift-click, left/right drag distribution, numeric hotbar swap, offhand swap, double-click collection and Q extraction are supported. Locked and hidden slots cannot be used. Output XP is settled once for the number of items actually removed.

Named, enchanted, damaged or M&S equipment may lose its extra data when smelted. Manual insertion of special equipment opens a confirmation showing the recipe result. A confirmation belongs to the current item, destination and recipe and expires; changing those requires a fresh confirmation. Protected equipment is refused even when a normal recipe exists. Use “Protect held item” to toggle the furnace protection marker.

## Recipes and heat

Rank III enables smoking and blasting. Matching recipes are selected in blasting → smoking → smelting order. Results come from Minecraft's RecipeManager; this MOD does not invent extra equipment salvage rewards.

Fuel fills a shared heat reserve. A standard 200-tick smelting recipe costs 200 heat. A standard 100-tick smoking/blasting recipe also costs 200 heat. Higher speed and multiple chambers do not make recipes cheaper. Fuel is converted at the purchased efficiency, capped at 90% of vanilla.

Buckets and other fuel containers must have room to remain. Fuel is not consumed if its heat or container cannot fit. When no chamber makes progress, stored heat cools at the configured rate—even while manually paused. No processing or cooling occurs while logged out or while the server is stopped.

## Automation

The scanner inspects normal inventory slots 9–35, excluding hotbar, armor and offhand. It pauses while other containers are open. Full storage leaves items in the inventory. Auto Input I–V progressively unlock item IDs, categories, detailed conditions, M&S rarity/level and general AND/OR rules. Auto Fuel I–IV add replenishment, priority, thresholds and demand prediction.

The visual editor creates **PROTECT** rules by default. Changes are drafts until Send settings; Discard/Escape leaves the saved settings unchanged. Resizing preserves drafts. Select rule, OR group and AND condition, then choose a field/value. “From held item” copies an actual attribute when available. Enable auto input explicitly and change a rule to INPUT only for items you intend to consume.

PROTECT wins over INPUT regardless of rule order. Unknown M&S data is protected. Tier IV requires an explicit rarity and equipment-level bound; tier V supports more general combinations while retaining M&S safety rules. [Configuration reference](CONFIGURATION.md) includes JSON and limits.

## Saving and death

Rank, upgrades, items, active progress, heat, settings and unclaimed XP are stored on the player.

- `keepInventory=true`: furnace contents and state survive death.
- `keepInventory=false`: internal items drop once, heat and unclaimed furnace XP clear; rank and upgrades remain.

Broken or unknown saved data is quarantined instead of silently reset. Recipe changes invalidate incompatible paid progress while keeping the input recoverable. Server administrators should investigate recovery errors before editing player data.

Administrators with permission level 2 can use `/internalfurnace admin open <player>` for read-only inspection of another player, `setrank <player> <0-7>`, `setupgrade <player> <track> <level>`, and `reset <player> CONFIRM`. Rank/upgrade commands only increase levels. Reset returns contents and does not discard quarantined data.
