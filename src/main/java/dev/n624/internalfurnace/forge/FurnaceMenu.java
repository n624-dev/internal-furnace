package dev.n624.internalfurnace.forge;

import dev.n624.internalfurnace.InternalFurnace;
import dev.n624.internalfurnace.core.*;
import net.minecraft.nbt.*;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.ItemStack;

import java.util.*;
import java.util.function.Supplier;

public final class FurnaceMenu extends AbstractContainerMenu {
    private static final int PLAYER_START = 63, OUTPUT_START = 45;
    public final FurnaceData data;
    public final UUID ownerId;
    public final boolean readOnly;
    public final ServerPlayer target;
    public int tab;
    public CompoundTag status = new CompoundTag();
    private Pending pending;
    private boolean allowSpecial;
    private long nonce;
    private final Player viewer;
    private int accountingDepth;
    private int dragMode = -1, dragTab;
    private long dragExpires;
    private CompoundTag dragSource;
    private final Set<Integer> dragSlots = new LinkedHashSet<>();
    private record Pending(int slot, int button, ClickType type, CompoundTag expected,
                           Map<Integer, CompoundTag> destinations, long expires, long nonce, int tab,
                           List<Integer> dragSlots, int dragMode, String recipe) {}

    public FurnaceMenu(int id, Inventory inventory, FriendlyByteBuf buffer) {
        this(id, inventory, null, buffer.readUUID(), buffer.readBoolean());
    }
    public FurnaceMenu(int id, Inventory inventory, ServerPlayer target) {
        this(id, inventory, target, target.getUUID(), !inventory.player.getUUID().equals(target.getUUID()));
    }
    private FurnaceMenu(int id, Inventory inventory, ServerPlayer target, UUID ownerId, boolean readOnly) {
        super(InternalFurnace.MENU.get(), id);
        this.target = target; this.viewer = inventory.player; this.ownerId = ownerId; this.readOnly = readOnly;
        this.data = target == null ? new FurnaceData() : InternalFurnace.data(target);
        addStorage(data.queue, 27, 0); addStorage(data.fuel, 18, 1); addStorage(data.output, 18, 2);
        for (int row = 0; row < 3; row++) for (int col = 0; col < 9; col++)
            addSlot(new Slot(inventory, col + row * 9 + 9, 8 + col * 18, 158 + row * 18) {
                @Override public boolean isActive() { return tab < 3; }
            });
        for (int col = 0; col < 9; col++) addSlot(new Slot(inventory, col, 8 + col * 18, 216) {
            @Override public boolean isActive() { return tab < 3; }
        });
    }
    private void addStorage(SimpleContainer storage, int maximum, int page) {
        for (int i = 0; i < maximum; i++) {
            final int storageIndex = i;
            addSlot(new Slot(storage, i, 8 + (i % 9) * 18, 72 + (i / 9) * 18) {
                @Override public boolean isActive() {
                    int unlocked = page == 0 ? data.queueSlots() : page == 1 ? data.fuelSlots() : data.outputSlots();
                    return tab == page && (target == null ? status.getBoolean("healthy") : data.healthy()) && (storageIndex < unlocked || !getItem().isEmpty());
                }
                @Override public boolean mayPlace(ItemStack stack) {
                    if (readOnly || !data.unlocked() || !isActive() || MnsBridge.locked(stack)) return false;
                    if (page == 2) return false;
                    if (page == 1) return storageIndex < data.fuelSlots() && FurnaceData.burnTime(stack) > 0;
                    if (storageIndex >= data.queueSlots() || (target != null && MnsBridge.special(stack) && !allowSpecial)) return false;
                    // The client may preview a drag; the server still requires explicit special-item confirmation.
                    return target == null || data.resolve(target, stack) != null;
                }
                @Override public boolean mayPickup(Player player) { return !readOnly && data.healthy() && isActive(); }
            });
        }
    }
    @Override public boolean stillValid(Player player) {
        if (target == null) return true;
        return player.isAlive() && target.isAlive()
                && (player.getUUID().equals(ownerId) || (player instanceof ServerPlayer admin && admin.hasPermissions(2)))
                && target.getServer().getPlayerList().getPlayer(ownerId) == target
                && InternalFurnace.data(target) == data;
    }
    public void setTab(int page) {
        if (page < 0 || page > 5) throw new IllegalArgumentException("Invalid tab");
        tab = page; cancelConfirmation(); resetDrag();
    }
    private long clock() { return target == null ? viewer.tickCount : target.level().getGameTime(); }
    private ItemStack incoming(int slot, int button, ClickType type) {
        if ((type == ClickType.PICKUP && slot >= 0 && slot < 27) || type == ClickType.QUICK_CRAFT) return getCarried();
        if (type == ClickType.SWAP && slot >= 0 && slot < 27 && (button >= 0 && button < 9 || button == 40))
            return viewer.getInventory().getItem(button);
        if (type == ClickType.QUICK_MOVE && tab == 0 && slot >= PLAYER_START && slot < slots.size()) return slots.get(slot).getItem();
        return ItemStack.EMPTY;
    }
    private boolean specialInput(ItemStack stack) {
        return target != null && !stack.isEmpty() && MnsBridge.special(stack) && !allowSpecial && !MnsBridge.locked(stack);
    }
    private boolean validButton(int button, ClickType type) {
        return switch (type) {
            case PICKUP, QUICK_MOVE, PICKUP_ALL, THROW -> button == 0 || button == 1;
            case SWAP -> button >= 0 && button < 9 || button == 40;
            case CLONE -> button == 2 && viewer.getAbilities().instabuild;
            case QUICK_CRAFT -> button >= 0 && button <= 10;
        };
    }
    @Override public void clicked(int slot, int button, ClickType type, Player player) {
        if (player.isSpectator() || (target != null && !stillValid(player))) return;
        if (pending != null && clock() > pending.expires) pending = null;
        if (pending != null || !validButton(button, type) || tab >= 3) return;
        if (type == ClickType.QUICK_CRAFT) { drag(slot, button); return; }
        resetDrag();
        if (slot >= slots.size() || (slot < 0 && slot != -999) || (slot >= 0 && !slots.get(slot).isActive())) return;
        if (slot == -999 && type != ClickType.PICKUP) return;
        if (readOnly && slot >= 0 && slot < PLAYER_START) return;
        ItemStack source = incoming(slot, button, type);
        if (specialInput(source)) {
            requestConfirmation(slot, button, type, source, List.of(), -1); return;
        }
        accounting(() -> { super.clicked(slot, button, type, player); return null; });
    }
    private void requestConfirmation(int slot, int button, ClickType type, ItemStack source, List<Integer> targets, int mode) {
        FurnaceData.Job job = data.resolve(target, source);
        if (readOnly || job == null) return;
        Map<Integer, CompoundTag> destinations = new HashMap<>();
        if (type == ClickType.PICKUP || type == ClickType.SWAP) destinations.put(slot, slots.get(slot).getItem().save(new CompoundTag()));
        for (int index : targets) destinations.put(index, slots.get(index).getItem().save(new CompoundTag()));
        pending = new Pending(slot, button, type, source.save(new CompoundTag()), Map.copyOf(destinations),
                clock() + 200, ++nonce, tab, List.copyOf(targets), mode, job.signature());
        sync();
    }
    public void confirm(long suppliedNonce) {
        Pending request = pending; pending = null; resetDrag();
        if (request == null || request.nonce != suppliedNonce || target == null || readOnly || !stillValid(viewer)
                || request.tab != tab || clock() > request.expires
                || !request.expected.equals(incoming(request.slot, request.button, request.type).save(new CompoundTag())))
            throw new IllegalArgumentException("Confirmation expired or item changed");
        for (var entry : request.destinations.entrySet())
            if (!slots.get(entry.getKey()).isActive() || !entry.getValue().equals(slots.get(entry.getKey()).getItem().save(new CompoundTag())))
                throw new IllegalArgumentException("Destination changed; confirm again");
        FurnaceData.Job recipe = data.resolve(target, ItemStack.of(request.expected));
        if (recipe == null || !recipe.signature().equals(request.recipe)) throw new IllegalArgumentException("Recipe changed; confirm again");
        allowSpecial = true;
        try {
            accounting(() -> {
                if (request.type == ClickType.QUICK_CRAFT) distribute(request.dragSlots, request.dragMode);
                else super.clicked(request.slot, request.button, request.type, viewer);
                return null;
            });
        } finally { allowSpecial = false; }
    }
    public void cancelConfirmation() { pending = null; resetDrag(); }
    private void resetDrag() { dragMode = -1; dragSource = null; dragSlots.clear(); }
    private boolean mayDragTo(int index, ItemStack source, boolean preview) {
        if (index < 0 || index >= slots.size()) return false;
        Slot slot = slots.get(index);
        if (!slot.isActive() || (readOnly && index < PLAYER_START)) return false;
        boolean old = allowSpecial;
        if (preview) allowSpecial = true;
        try {
            ItemStack current = slot.getItem();
            return slot.mayPlace(source) && (current.isEmpty() || ItemStack.isSameItemSameTags(current, source))
                    && current.getCount() < Math.min(64, slot.getMaxStackSize(source));
        } finally { allowSpecial = old; }
    }
    @Override public boolean canDragTo(Slot slot) {
        return slot.isActive() && (!readOnly || slot.index >= PLAYER_START);
    }
    private void drag(int slot, int button) {
        int header = button & 3, mode = (button >> 2) & 3;
        ItemStack carried = getCarried();
        if (header > 2 || mode > 2 || (mode == 2 && !viewer.getAbilities().instabuild)
                || carried.isEmpty() || carried.getCount() > 64) { resetDrag(); return; }
        if (header == 0) {
            resetDrag(); dragMode = mode; dragTab = tab; dragSource = carried.save(new CompoundTag()); dragExpires = clock() + 200; return;
        }
        if (mode != dragMode || dragTab != tab || clock() > dragExpires || dragSource == null
                || !dragSource.equals(carried.save(new CompoundTag()))) { resetDrag(); return; }
        if (header == 1) {
            if (mayDragTo(slot, carried, true) && (mode == 2 || dragSlots.size() < carried.getCount())) dragSlots.add(slot);
            return;
        }
        List<Integer> targets = dragSlots.stream().filter(i -> mayDragTo(i, carried, true)).toList();
        resetDrag();
        if (targets.isEmpty()) return;
        if (specialInput(carried) && targets.stream().anyMatch(i -> i < 27)) {
            requestConfirmation(-999, button, ClickType.QUICK_CRAFT, carried, targets, mode); return;
        }
        accounting(() -> { distribute(targets, mode); return null; });
    }
    private void distribute(List<Integer> targets, int mode) {
        ItemStack source = getCarried();
        List<Integer> valid = targets.stream().filter(i -> mayDragTo(i, source, false)).toList();
        int[] free = valid.stream().mapToInt(i -> Math.min(64, slots.get(i).getMaxStackSize(source)) - slots.get(i).getItem().getCount()).toArray();
        DragDistribution.Plan plan = DragDistribution.plan(source.getCount(), free, mode, viewer.getAbilities().instabuild);
        int[] amounts = plan.added();
        for (int n = 0; n < valid.size(); n++) if (amounts[n] > 0) {
            Slot slot = slots.get(valid.get(n)); ItemStack placed = source.copy();
            placed.setCount(slot.getItem().getCount() + amounts[n]); slot.set(placed); slot.setChanged();
        }
        ItemStack remainder = source.copy(); remainder.setCount(plan.remaining()); setCarried(remainder);
    }
    /** Settle only at the outermost click, covering take, shift, swap, throw and collect-all exactly once. */
    private <T> T accounting(Supplier<T> operation) {
        int[] before = null;
        if (accountingDepth++ == 0 && target != null) {
            before = new int[18];
            for (int i = 0; i < before.length; i++) before[i] = data.output.getItem(i).getCount();
        }
        try { return operation.get(); }
        finally {
            accountingDepth--;
            if (before != null) for (int i = 0; i < before.length; i++) {
                int taken = before[i] - data.output.getItem(i).getCount();
                if (taken > 0) data.claimExperience(target, i, taken, before[i]);
            }
        }
    }
    @Override public boolean canTakeItemForPickAll(ItemStack stack, Slot slot) {
        return slot.isActive() && slot.mayPickup(viewer) && (!readOnly || slot.index >= PLAYER_START);
    }
    @Override public ItemStack quickMoveStack(Player player, int slotIndex) {
        return accounting(() -> quickMove(player, slotIndex));
    }
    private ItemStack quickMove(Player player, int slotIndex) {
        if (slotIndex < 0 || slotIndex >= slots.size()) return ItemStack.EMPTY;
        Slot slot = slots.get(slotIndex);
        if (!slot.isActive() || !slot.hasItem() || !slot.mayPickup(player) || (readOnly && slotIndex < PLAYER_START)) return ItemStack.EMPTY;
        ItemStack source = slot.getItem(), before = source.copy();
        if (slotIndex < PLAYER_START) {
            if (!moveItemStackTo(source, PLAYER_START, slots.size(), true)) return ItemStack.EMPTY;
        } else {
            if (readOnly || !data.unlocked()) return ItemStack.EMPTY;
            int start, end;
            if (tab == 0) { start = 0; end = data.queueSlots(); }
            else if (tab == 1) { start = 27; end = 27 + data.fuelSlots(); }
            else return ItemStack.EMPTY;
            if (!moveItemStackTo(source, start, end, false)) return ItemStack.EMPTY;
        }
        if (source.isEmpty()) slot.set(ItemStack.EMPTY); else slot.setChanged();
        slot.onTake(player, source); return before;
    }
    @Override protected boolean moveItemStackTo(ItemStack source, int start, int end, boolean reverse) {
        boolean moved = false;
        for (int pass = 0; pass < 2 && !source.isEmpty(); pass++) {
            for (int n = 0; n < end - start && !source.isEmpty(); n++) {
                Slot slot = slots.get(reverse ? end - n - 1 : start + n);
                ItemStack current = slot.getItem();
                // Unlike vanilla's occupied-stack fast path, always enforce placement/protection gates.
                if (!slot.isActive() || !slot.mayPlace(source) || (pass == 0 && current.isEmpty()) || (pass == 1 && !current.isEmpty())) continue;
                if (!current.isEmpty() && !ItemStack.isSameItemSameTags(current, source)) continue;
                int count = Math.min(source.getCount(), Math.min(64, slot.getMaxStackSize(source)) - current.getCount());
                if (count <= 0) continue;
                ItemStack placed = source.copy(); placed.setCount(current.getCount() + count);
                slot.set(placed); source.shrink(count); slot.setChanged(); moved = true;
            }
        }
        return moved;
    }
    @Override public void removed(Player player) { cancelConfirmation(); super.removed(player); }
    @Override public void broadcastChanges() {
        super.broadcastChanges();
        if (target != null && viewer instanceof ServerPlayer && viewer.tickCount % 10 == 0) sync();
    }
    public void sync() {
        if (target == null || !(viewer instanceof ServerPlayer recipient)) return;
        CompoundTag tag = new CompoundTag(); tag.putInt("tab", tab); tag.putBoolean("healthy", data.healthy());
        tag.putInt("rank", data.profile.rank()); tag.putLong("revision", data.profile.revision());
        CompoundTag levels = new CompoundTag(); data.profile.levels().forEach((u, n) -> levels.putInt(u.id(), n)); tag.put("levels", levels);
        tag.putLong("heat", data.thermal.heat()); tag.putBoolean("paused", data.paused);
        tag.putIntArray("pausedSlots", java.util.stream.IntStream.range(0, 27).filter(i -> data.queuePaused[i]).toArray());
        tag.putLong("completed", data.completed); tag.putLong("burned", data.burned);
        tag.putInt("combat", MnsBridge.combatLevel(target)); tag.putInt("xp", target.experienceLevel);
        tag.putString("rules", RuleCodec.write(data.rules)); tag.putBoolean("autoFuel", data.autoFuel);
        tag.putInt("fuelThreshold", data.fuelThreshold); tag.putString("fuelPriority", String.join(",", data.fuelPriority));
        ListTag jobs = new ListTag();
        for (int i = 0; i < data.chambers(); i++) {
            CompoundTag job = new CompoundTag();
            job.putString("name", shortText(data.active[i].isEmpty() ? data.finished[i].getHoverName().getString() : data.active[i].getHoverName().getString()));
            job.putLong("progress", data.progress[i]);
            FurnaceData.Job current = data.resolve(target, data.active[i]);
            job.putLong("duration", current == null ? 0 : current.duration()); jobs.add(job);
        }
        tag.put("jobs", jobs);
        ListTag costs = new ListTag(); var owned = data.stock(target);
        for (String track : Catalog.tracks()) {
            Catalog.Cost cost = InternalFurnace.catalog.next(data.profile, track);
            if (cost == null) continue;
            CompoundTag row = new CompoundTag(); row.putString("track", track); row.putInt("level", cost.level());
            row.putInt("rank", cost.rank()); row.putInt("combat", cost.combatLevel()); row.putInt("xp", cost.xpLevels());
            ListTag items = new ListTag();
            cost.items().forEach((id, count) -> { CompoundTag material = new CompoundTag(); material.putString("id", id); material.putInt("count", count); material.putInt("owned", owned.getOrDefault(id, 0)); items.add(material); });
            row.put("items", items); costs.add(row);
        }
        tag.put("costs", costs);
        if (pending != null && clock() > pending.expires) pending = null;
        if (pending != null) {
            tag.putLong("confirmation", pending.nonce);
            ItemStack input = ItemStack.of(pending.expected); tag.putString("warningItem", shortText(input.getHoverName().getString()));
            FurnaceData.Job recipe = data.resolve(target, input);
            tag.putString("warningResult", recipe == null ? "?" : shortText(recipe.result().getHoverName().getString()) + " x" + recipe.result().getCount());
        }
        status = tag; FurnaceNetwork.send(recipient, containerId, tag);
    }
    private static String shortText(String text) { return text.length() <= 96 ? text : text.substring(0, 96); }
    public void acceptStatus(CompoundTag tag) {
        if (target != null) return;
        EnumMap<Upgrade, Integer> levels = new EnumMap<>(Upgrade.class);
        for (Upgrade u : Upgrade.values()) levels.put(u, tag.getCompound("levels").getInt(u.id()));
        data.profile = new Profile(tag.getInt("rank"), levels, tag.getLong("revision"));
        data.thermal = new ThermalEngine(tag.getLong("heat"), 0); data.paused = tag.getBoolean("paused");
        java.util.Arrays.fill(data.queuePaused, false);
        for (int i : tag.getIntArray("pausedSlots")) if (i >= 0 && i < 27) data.queuePaused[i] = true;
        if (tab != tag.getInt("tab")) { resetDrag(); pending = null; }
        tab = tag.getInt("tab"); status = tag;
    }
}
