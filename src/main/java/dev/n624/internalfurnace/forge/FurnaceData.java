package dev.n624.internalfurnace.forge;

import dev.n624.internalfurnace.InternalFurnace;
import dev.n624.internalfurnace.core.*;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.*;
import net.minecraftforge.common.ForgeHooks;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.*;

/** Player-owned server state. Not exposed as a Forge ITEM_HANDLER capability. */
public final class FurnaceData {
    public Profile profile = Profile.locked();
    public ThermalEngine thermal = new ThermalEngine(0, 0);
    public final SimpleContainer queue = new SimpleContainer(27);
    public final SimpleContainer fuel = new SimpleContainer(18);
    public final SimpleContainer output = new SimpleContainer(18);
    public final ItemStack[] active = emptyStacks(4), finished = emptyStacks(4);
    public final long[] progress = new long[4], finishedXp = new long[4], outputXp = new long[18];
    public final boolean[] queuePaused = new boolean[27];
    public final String[] fingerprint = new String[]{"", "", "", ""};
    public Rules.Settings rules = Rules.Settings.disabled();
    public boolean paused, autoFuel;
    public List<String> fuelPriority = List.of("minecraft:charcoal", "minecraft:coal");
    public int fuelThreshold = 25;
    public long xpFraction, completed, burned;
    private final Job[] cache = new Job[4];
    private int cachedGeneration = -1;
    private CompoundTag quarantine;

    public record Job(ItemStack result, long duration, ThermalEngine.Mode mode, long xp, String signature) {}
    public boolean healthy() { return quarantine == null; }
    public boolean unlocked() { return healthy() && profile.rank() > 0; }
    public int queueSlots() { return profile.value(Upgrade.QUEUE); }
    public int fuelSlots() { return profile.value(Upgrade.FUEL_STORAGE); }
    public int outputSlots() { return profile.value(Upgrade.OUTPUT); }
    public int chambers() { return profile.value(Upgrade.CHAMBERS); }
    public long capacity() { return profile.value(Upgrade.CAPACITY) * ThermalEngine.UNIT; }

    private static ItemStack[] emptyStacks(int n) {
        ItemStack[] a = new ItemStack[n]; Arrays.fill(a, ItemStack.EMPTY); return a;
    }
    public Job resolve(ServerPlayer owner, ItemStack input) {
        if (!unlocked() || input.isEmpty()) return null;
        SimpleContainer ingredients = new SimpleContainer(input.copy());
        RecipeManager manager = owner.serverLevel().getRecipeManager();
        AbstractCookingRecipe recipe = null;
        ThermalEngine.Mode mode = ThermalEngine.Mode.SMELTING;
        if (profile.rank() >= 3) {
            recipe = manager.getRecipeFor(RecipeType.BLASTING, ingredients, owner.level()).orElse(null);
            if (recipe != null) mode = ThermalEngine.Mode.BLASTING;
            else {
                recipe = manager.getRecipeFor(RecipeType.SMOKING, ingredients, owner.level()).orElse(null);
                if (recipe != null) mode = ThermalEngine.Mode.SMOKING;
            }
        }
        if (recipe == null) recipe = manager.getRecipeFor(RecipeType.SMELTING, ingredients, owner.level()).orElse(null);
        if (recipe == null || recipe.getCookingTime() <= 0 || recipe.getCookingTime() > 1_000_000) return null;
        ItemStack result = recipe.assemble(ingredients, owner.level().registryAccess());
        if (result.isEmpty() || result.getCount() > result.getMaxStackSize() || result.getCount() > 64) return null;
        long xp;
        try { xp = ExperienceLedger.recipe(recipe.getExperience()); }
        catch (IllegalArgumentException invalid) { return null; }
        long duration = recipe.getCookingTime() * ThermalEngine.UNIT;
        String signature = recipe.getId() + "|" + mode + "|" + duration + "|" + xp + "|" + result.save(new CompoundTag());
        return new Job(result.copy(), duration, mode, xp, signature);
    }
    public static int burnTime(ItemStack stack) {
        if (stack.isEmpty() || MnsBridge.locked(stack) || MnsBridge.special(stack)) return 0;
        return Math.max(0, ForgeHooks.getBurnTime(stack, RecipeType.SMELTING));
    }
    public static ItemStack insert(SimpleContainer dest, int limit, ItemStack source, boolean simulate) {
        ItemStack left = source.copy();
        for (int pass = 0; pass < 2; pass++) for (int slot = 0; slot < limit && !left.isEmpty(); slot++) {
            ItemStack current = dest.getItem(slot);
            if ((pass == 0 && current.isEmpty()) || (pass == 1 && !current.isEmpty())) continue;
            if (!current.isEmpty() && !ItemStack.isSameItemSameTags(current, left)) continue;
            int amount = Math.min(left.getCount(), Math.min(64, left.getMaxStackSize()) - current.getCount());
            if (amount <= 0) continue;
            if (!simulate) {
                ItemStack replacement = left.copy(); replacement.setCount(current.getCount() + amount);
                dest.setItem(slot, replacement);
            }
            left.shrink(amount);
        }
        return left;
    }
    private void flush(int chamber) {
        ItemStack stack = finished[chamber];
        for (int slot = 0; slot < outputSlots() && !stack.isEmpty(); slot++) {
            ItemStack existing = output.getItem(slot);
            if (!existing.isEmpty() && !ItemStack.isSameItemSameTags(existing, stack)) continue;
            int amount = Math.min(stack.getCount(), Math.min(64, stack.getMaxStackSize()) - existing.getCount());
            if (amount <= 0) continue;
            long credit = ExperienceLedger.split(finishedXp[chamber], amount, stack.getCount());
            ItemStack replacement = stack.copy(); replacement.setCount(existing.getCount() + amount);
            output.setItem(slot, replacement); outputXp[slot] += credit;
            stack.shrink(amount); finishedXp[chamber] -= credit;
        }
        if (stack.isEmpty()) { finished[chamber] = ItemStack.EMPTY; finishedXp[chamber] = 0; }
    }
    public void tick(ServerPlayer owner) {
        if (!unlocked() || !owner.isAlive() || owner.isSpectator()) return;
        if (cachedGeneration != InternalFurnace.recipeGeneration) {
            Arrays.fill(cache, null); cachedGeneration = InternalFurnace.recipeGeneration;
        }
        if (owner.tickCount % InternalFurnace.SCAN_TICKS.get() == 0 && !paused
                && (owner.containerMenu == owner.inventoryMenu || owner.containerMenu instanceof FurnaceMenu)) automate(owner);
        ThermalEngine.Work[] work = new ThermalEngine.Work[chambers()];
        long nextTickHeat = 0;
        for (int i = 0; i < chambers(); i++) {
            flush(i);
            if (paused || !finished[i].isEmpty()) continue;
            if (active[i].isEmpty()) {
                for (int q = 0; q < queueSlots(); q++) {
                    if (queuePaused[q] || queue.getItem(q).isEmpty()) continue;
                    Job candidate = resolve(owner, queue.getItem(q));
                    if (candidate == null) continue;
                    active[i] = queue.removeItem(q, 1);
                    cache[i] = candidate; progress[i] = 0; fingerprint[i] = candidate.signature;
                    break;
                }
            }
            if (active[i].isEmpty()) continue;
            if (cache[i] == null) {
                cache[i] = resolve(owner, active[i]);
                if (cache[i] == null) continue; // Keep the original, recoverable input after a recipe removal.
                if (!cache[i].signature.equals(fingerprint[i])) progress[i] = 0;
                fingerprint[i] = cache[i].signature;
            }
            Job job = cache[i];
            long remaining = Math.max(0, job.duration - progress[i]);
            work[i] = new ThermalEngine.Work(remaining, job.mode, true);
            nextTickHeat += Math.min(remaining, profile.value(Upgrade.SPEED)) * job.mode.heatPerProgress;
        }
        if (nextTickHeat > 0 && (thermal.heat() < nextTickHeat || (autoFuel && owner.tickCount % 20 == 0))) {
            int tier = profile.level(Upgrade.AUTO_FUEL);
            long target = nextTickHeat;
            if (autoFuel && tier >= 3) target = Math.max(target, capacity() * fuelThreshold / 100);
            if (autoFuel && tier >= 4) target = Math.min(capacity(), pendingHeat(owner));
            if (thermal.heat() < target) consumeFuel(target, autoFuel && tier >= 4);
        }
        int loss = Math.max(1, InternalFurnace.IDLE_LOSS.get() * profile.value(Upgrade.INSULATION) / 1000);
        long[] gains = thermal.advance(work, profile.value(Upgrade.SPEED), loss);
        for (int i = 0; i < work.length; i++) {
            if (work[i] == null) continue;
            progress[i] += gains[i];
            Job job = cache[i];
            if (progress[i] >= job.duration) {
                finished[i] = job.result.copy(); finishedXp[i] = job.xp;
                active[i] = ItemStack.EMPTY; progress[i] = 0; cache[i] = null; fingerprint[i] = "";
                completed++; flush(i);
            }
        }
    }
    private long pendingHeat(ServerPlayer player) {
        long needed = 0;
        for (int i = 0; i < chambers(); i++) if (cache[i] != null && finished[i].isEmpty())
            needed += Math.max(0, cache[i].duration - progress[i]) * cache[i].mode.heatPerProgress;
        // Only queried while the predictive refill controller actually needs a decision.
        for (int i = 0; i < queueSlots(); i++) if (!queuePaused[i]) {
            Job job = resolve(player, queue.getItem(i));
            if (job != null) needed += job.duration * job.mode.heatPerProgress * queue.getItem(i).getCount();
            if (needed >= capacity()) return capacity();
        }
        return needed;
    }
    private void consumeFuel(long target, boolean predictive) {
        int selected = -1; long bestExcess = Long.MAX_VALUE;
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < fuelSlots(); i++) order.add(i);
        if (autoFuel && profile.level(Upgrade.AUTO_FUEL) >= 2) order.sort(Comparator.comparingInt(i -> {
            String id = ForgeRegistries.ITEMS.getKey(fuel.getItem(i).getItem()).toString();
            int priority = fuelPriority.indexOf(id); return priority < 0 ? Integer.MAX_VALUE : priority;
        }));
        for (int i : order) {
            ItemStack stack = fuel.getItem(i);
            int ticks = burnTime(stack);
            if (ticks <= 0 || ticks > 1_000_000) continue;
            long generated = ThermalEngine.fuelHeat(ticks, profile.value(Upgrade.EFFICIENCY));
            if (!thermal.canCharge(generated, capacity())) continue;
            ItemStack remainder = stack.hasCraftingRemainingItem() ? stack.getCraftingRemainingItem() : ItemStack.EMPTY;
            if (!remainder.isEmpty() && remainder.getCount() > Math.min(64, remainder.getMaxStackSize())) continue;
            if (!remainder.isEmpty() && stack.getCount() > 1 && !insert(output, outputSlots(), remainder, true).isEmpty()) continue;
            if (!predictive) { selected = i; break; }
            long excess = Math.abs(generated - Math.max(1, target - thermal.heat()));
            if (excess < bestExcess) { selected = i; bestExcess = excess; }
        }
        if (selected < 0) return;
        ItemStack stack = fuel.getItem(selected);
        long generated = ThermalEngine.fuelHeat(burnTime(stack), profile.value(Upgrade.EFFICIENCY));
        ItemStack remainder = stack.hasCraftingRemainingItem() ? stack.getCraftingRemainingItem() : ItemStack.EMPTY;
        // Capacity/remainder space were checked before consuming anything.
        if (!thermal.charge(generated, capacity())) return;
        stack.shrink(1);
        if (stack.isEmpty()) fuel.setItem(selected, remainder);
        else if (!remainder.isEmpty()) insert(output, outputSlots(), remainder, false);
        fuel.setChanged(); burned++;
    }
    private void automate(ServerPlayer player) {
        // Never take held/equipped/offhand items. Explicit rules are disabled by default.
        for (int slot = 9; slot < 36; slot++) {
            ItemStack original = player.getInventory().getItem(slot);
            if (original.isEmpty()) continue;
            if (rules.accepts(MnsBridge.facts(original), profile.level(Upgrade.AUTO_INPUT)) && resolve(player, original) != null) {
                ItemStack left = insert(queue, queueSlots(), original, false);
                if (left.getCount() != original.getCount()) player.getInventory().setItem(slot, left);
            }
        }
        if (!autoFuel || profile.level(Upgrade.AUTO_FUEL) == 0) return;
        List<String> ordered = new ArrayList<>(fuelPriority);
        if (profile.level(Upgrade.AUTO_FUEL) == 1) Collections.sort(ordered);
        for (String id : ordered) for (int slot = 9; slot < 36; slot++) {
            ItemStack original = player.getInventory().getItem(slot);
            if (original.isEmpty() || !ForgeRegistries.ITEMS.getKey(original.getItem()).toString().equals(id) || burnTime(original) == 0) continue;
            ItemStack left = insert(fuel, fuelSlots(), original, false);
            if (left.getCount() != original.getCount()) player.getInventory().setItem(slot, left);
        }
    }
    public void claimExperience(ServerPlayer player, int slot, int amount, int countBefore) {
        long credit = ExperienceLedger.split(outputXp[slot], amount, countBefore);
        outputXp[slot] -= credit;
        long total = xpFraction + credit;
        int whole = Math.toIntExact(total / ExperienceLedger.UNIT);
        xpFraction = total % ExperienceLedger.UNIT;
        if (whole > 0) player.giveExperiencePoints(whole);
    }
    public boolean paymentEligible(ItemStack stack) {
        if (stack.isEmpty() || MnsBridge.locked(stack) || stack.isEnchanted() || stack.hasCustomHoverName()) return false;
        // This exact catalyst is intentionally consumed at insulation V; names/enchantments/locks are still excluded.
        return !MnsBridge.special(stack) || ForgeRegistries.ITEMS.getKey(stack.getItem()).toString().equals("mowziesmobs:ice_crystal");
    }
    public Map<String, Integer> stock(ServerPlayer player) {
        Map<String, Integer> result = new HashMap<>();
        for (int i = 0; i < 36; i++) {
            ItemStack item = player.getInventory().getItem(i);
            if (paymentEligible(item)) result.merge(ForgeRegistries.ITEMS.getKey(item.getItem()).toString(), item.getCount(), Integer::sum);
        }
        return result;
    }
    public void purchase(ServerPlayer player, String track, long revision) {
        if (!healthy()) throw new IllegalArgumentException("Saved state needs recovery");
        Catalog.Purchase plan = InternalFurnace.catalog.plan(profile, track, revision, MnsBridge.combatLevel(player), player.experienceLevel, stock(player));
        ItemStack[] before = new ItemStack[36], after = new ItemStack[36];
        for (int i = 0; i < 36; i++) { before[i] = player.getInventory().getItem(i).copy(); after[i] = before[i].copy(); }
        for (var material : plan.consume().entrySet()) {
            int remaining = material.getValue();
            for (int i = 0; i < 36 && remaining > 0; i++) {
                if (!paymentEligible(after[i]) || !ForgeRegistries.ITEMS.getKey(after[i].getItem()).toString().equals(material.getKey())) continue;
                int take = Math.min(remaining, after[i].getCount()); after[i].shrink(take); remaining -= take;
            }
            if (remaining != 0) throw new IllegalArgumentException("Inventory changed");
        }
        for (int i = 0; i < 36; i++) if (!ItemStack.matches(before[i], player.getInventory().getItem(i)))
            throw new IllegalArgumentException("Inventory changed");
        int level = player.experienceLevel, total = player.totalExperience;
        float progressBefore = player.experienceProgress;
        Profile previous = profile;
        try {
            for (int i = 0; i < 36; i++) player.getInventory().setItem(i, after[i]);
            player.giveExperienceLevels(-plan.xpLevels());
            profile = plan.after(); Arrays.fill(cache, null);
            player.getInventory().setChanged();
        } catch (RuntimeException failed) {
            for (int i = 0; i < 36; i++) player.getInventory().setItem(i, before[i]);
            player.experienceLevel = level; player.totalExperience = total; player.experienceProgress = progressBefore;
            profile = previous; throw failed;
        }
    }
    public void reorder(int from, int to) {
        if (profile.level(Upgrade.QUEUE) < 3 || from < 0 || to < 0 || from >= queueSlots() || to >= queueSlots())
            throw new IllegalArgumentException("Queue reorder unavailable");
        ItemStack moved = queue.getItem(from); boolean flag = queuePaused[from];
        int direction = from < to ? 1 : -1;
        for (int i = from; i != to; i += direction) { queue.setItem(i, queue.getItem(i + direction)); queuePaused[i] = queuePaused[i + direction]; }
        queue.setItem(to, moved); queuePaused[to] = flag;
    }
    public void cancelChamber(int chamber) {
        if (chamber < 0 || chamber >= chambers()) throw new IllegalArgumentException("Invalid chamber");
        ItemStack original = active[chamber];
        if (!insert(queue, queueSlots(), original, true).isEmpty()) throw new IllegalArgumentException("Queue is full");
        insert(queue, queueSlots(), original, false);
        active[chamber] = ItemStack.EMPTY; progress[chamber] = 0; cache[chamber] = null; fingerprint[chamber] = "";
    }
    /** Ownership is removed here before items are handed to drops/reset recovery. */
    public List<ItemStack> drain() {
        if (!healthy()) throw new IllegalArgumentException("Cannot discard quarantined data");
        List<ItemStack> items = new ArrayList<>();
        for (SimpleContainer container : List.of(queue, fuel, output)) for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack item = container.removeItemNoUpdate(i); if (!item.isEmpty()) items.add(item);
        }
        for (int i = 0; i < 4; i++) {
            if (!active[i].isEmpty()) items.add(active[i]);
            if (!finished[i].isEmpty()) items.add(finished[i]);
            active[i] = ItemStack.EMPTY; finished[i] = ItemStack.EMPTY;
        }
        thermal.clear(); Arrays.fill(progress, 0); Arrays.fill(finishedXp, 0); Arrays.fill(outputXp, 0);
        Arrays.fill(cache, null); Arrays.fill(fingerprint, ""); xpFraction = 0;
        return items;
    }
    public CompoundTag save() {
        if (quarantine != null) return quarantine.copy();
        CompoundTag tag = new CompoundTag(); tag.putInt("schema", 1);
        tag.putInt("rank", profile.rank()); tag.putLong("revision", profile.revision());
        CompoundTag levels = new CompoundTag(); profile.levels().forEach((u, n) -> levels.putInt(u.id(), n)); tag.put("upgrades", levels);
        tag.putLong("heat", thermal.heat()); tag.putInt("cursor", thermal.cursor());
        tag.put("queue", writeItems(queue)); tag.put("fuel", writeItems(fuel)); tag.put("output", writeItems(output));
        ListTag jobs = new ListTag();
        for (int i = 0; i < 4; i++) {
            CompoundTag job = new CompoundTag(); job.put("input", active[i].save(new CompoundTag())); job.put("output", finished[i].save(new CompoundTag()));
            job.putLong("progress", progress[i]); job.putLong("xp", finishedXp[i]); job.putString("signature", fingerprint[i]); jobs.add(job);
        }
        tag.put("jobs", jobs); tag.putLongArray("outputXp", outputXp); tag.putLong("xpFraction", xpFraction);
        tag.putLong("completed", completed); tag.putLong("burned", burned); tag.putBoolean("paused", paused);
        int[] pausedSlots = java.util.stream.IntStream.range(0, 27).filter(i -> queuePaused[i]).toArray(); tag.putIntArray("pausedSlots", pausedSlots);
        tag.putString("rules", RuleCodec.write(rules)); tag.putBoolean("autoFuel", autoFuel); tag.putInt("fuelThreshold", fuelThreshold);
        ListTag priorities = new ListTag(); fuelPriority.forEach(id -> priorities.add(StringTag.valueOf(id))); tag.put("fuelPriority", priorities);
        return tag;
    }
    public void load(CompoundTag tag) {
        try {
            if (tag.getInt("schema") != 1) throw new IllegalArgumentException("Unknown save schema");
            EnumMap<Upgrade, Integer> levels = new EnumMap<>(Upgrade.class);
            CompoundTag upgrades = tag.getCompound("upgrades");
            for (String key : upgrades.getAllKeys()) levels.put(Upgrade.byId(key), upgrades.getInt(key));
            profile = new Profile(tag.getInt("rank"), levels, tag.getLong("revision"));
            thermal = new ThermalEngine(tag.getLong("heat"), tag.getInt("cursor"));
            readItems(queue, tag.getList("queue", Tag.TAG_COMPOUND));
            readItems(fuel, tag.getList("fuel", Tag.TAG_COMPOUND));
            readItems(output, tag.getList("output", Tag.TAG_COMPOUND));
            ListTag jobs = tag.getList("jobs", Tag.TAG_COMPOUND);
            if (jobs.size() != 4) throw new IllegalArgumentException("Invalid jobs");
            for (int i = 0; i < 4; i++) {
                CompoundTag job = jobs.getCompound(i); active[i] = readStack(job.getCompound("input")); finished[i] = readStack(job.getCompound("output"));
                if (active[i].getCount() > 1 || (!active[i].isEmpty() && !finished[i].isEmpty()) || finished[i].getCount() > Math.min(64, finished[i].getMaxStackSize())) throw new IllegalArgumentException("Invalid job count");
                progress[i] = bounded(job.getLong("progress"), 1_000_000_000L); finishedXp[i] = bounded(job.getLong("xp"), 1_000_000_000_000L);
                if ((active[i].isEmpty() && progress[i] != 0) || (finished[i].isEmpty() && finishedXp[i] != 0)) throw new IllegalArgumentException("Orphaned job state");
                fingerprint[i] = job.getString("signature");
                if (fingerprint[i].length() > 131072) throw new IllegalArgumentException("Signature too large");
                cache[i] = null;
            }
            long[] credits = tag.getLongArray("outputXp"); if (credits.length != 18) throw new IllegalArgumentException("Invalid XP ledger");
            for (int i = 0; i < 18; i++) {
                outputXp[i] = bounded(credits[i], 1_000_000_000_000L);
                if (output.getItem(i).isEmpty() && outputXp[i] != 0) throw new IllegalArgumentException("Orphaned XP");
            }
            xpFraction = bounded(tag.getLong("xpFraction"), ExperienceLedger.UNIT - 1);
            completed = bounded(tag.getLong("completed"), Long.MAX_VALUE); burned = bounded(tag.getLong("burned"), Long.MAX_VALUE);
            paused = tag.getBoolean("paused"); Arrays.fill(queuePaused, false);
            for (int i : tag.getIntArray("pausedSlots")) { if (i < 0 || i >= 27) throw new IllegalArgumentException("Invalid paused slot"); queuePaused[i] = true; }
            rules = RuleCodec.read(tag.getString("rules"), profile.level(Upgrade.AUTO_INPUT));
            autoFuel = tag.getBoolean("autoFuel"); fuelThreshold = (int) bounded(tag.getInt("fuelThreshold"), 100);
            if (fuelThreshold < 1) throw new IllegalArgumentException("Invalid threshold");
            ListTag priorities = tag.getList("fuelPriority", Tag.TAG_STRING);
            if (priorities.size() > 16) throw new IllegalArgumentException("Too many fuels");
            List<String> names = new ArrayList<>();
            for (int i = 0; i < priorities.size(); i++) {
                String name = priorities.getString(i);
                if (name.length() > 128 || ResourceLocation.tryParse(name) == null) throw new IllegalArgumentException("Invalid fuel ID");
                names.add(name);
            }
            fuelPriority = List.copyOf(names); quarantine = null;
        } catch (RuntimeException error) {
            quarantine = tag.copy();
            InternalFurnace.LOGGER.error("Internal Furnace saved data is quarantined; original NBT preserved", error);
        }
    }
    private static ItemStack readStack(CompoundTag tag) {
        ItemStack stack = ItemStack.of(tag);
        String id = tag.getString("id");
        if (stack.isEmpty() && !id.isEmpty() && !id.equals("minecraft:air")) throw new IllegalArgumentException("Unknown or invalid saved item: " + id);
        return stack;
    }
    private static long bounded(long n, long max) { if (n < 0 || n > max) throw new IllegalArgumentException("Invalid saved number"); return n; }
    private static ListTag writeItems(SimpleContainer inventory) {
        ListTag items = new ListTag();
        for (int i = 0; i < inventory.getContainerSize(); i++) if (!inventory.getItem(i).isEmpty()) {
            CompoundTag entry = inventory.getItem(i).save(new CompoundTag()); entry.putInt("slot", i); items.add(entry);
        }
        return items;
    }
    private static void readItems(SimpleContainer inventory, ListTag list) {
        if (list.size() > inventory.getContainerSize()) throw new IllegalArgumentException("Too many stored stacks");
        inventory.clearContent(); Set<Integer> seen = new HashSet<>();
        for (int n = 0; n < list.size(); n++) {
            CompoundTag entry = list.getCompound(n); int slot = entry.getInt("slot"); ItemStack stack = readStack(entry);
            if (slot < 0 || slot >= inventory.getContainerSize() || !seen.add(slot) || stack.isEmpty()
                    || stack.getCount() > Math.min(64, stack.getMaxStackSize())) throw new IllegalArgumentException("Invalid stored stack");
            inventory.setItem(slot, stack);
        }
    }
}
