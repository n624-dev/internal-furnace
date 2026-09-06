package dev.n624.internalfurnace.forge;

import com.robertx22.mine_and_slash.capability.entity.EntityData;
import com.robertx22.mine_and_slash.uncommon.interfaces.data_items.ICommonDataItem;
import dev.n624.internalfurnace.core.Rules;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.*;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

/** All M&S-specific calls are isolated here and require the pinned 6.4.7 runtime. */
public final class MnsBridge {
    public static final String PROTECTED = "internal_furnace_protected";
    private MnsBridge() {}
    public static int combatLevel(ServerPlayer player) {
        // Do not use Load.Unit's synthetic fallback to decide paid unlocks.
        return player.getCapability(EntityData.INSTANCE).map(EntityData::getLevel).orElse(-1);
    }
    public static boolean locked(ItemStack stack) {
        return stack.hasTag() && stack.getTag().getBoolean(PROTECTED);
    }
    public static boolean special(ItemStack stack) {
        if (stack.isEnchanted() || stack.hasCustomHoverName()) return true;
        if (!stack.hasTag()) return false;
        CompoundTag tag = stack.getTag().copy();
        tag.remove("Damage"); tag.remove(PROTECTED);
        return !tag.isEmpty();
    }
    public static Rules.Facts facts(ItemStack stack) {
        String id = ForgeRegistries.ITEMS.getKey(stack.getItem()).toString();
        Set<String> tags = stack.getTags().map(t -> t.location().toString()).collect(Collectors.toSet());
        Set<String> categories = new HashSet<>();
        Item item = stack.getItem();
        String kind = item instanceof ArmorItem ? "armor" : item instanceof SwordItem ? "weapon"
                : item instanceof TieredItem ? "tool" : "other";
        if (kind.equals("armor") || kind.equals("weapon") || kind.equals("tool")) {
            if (id.startsWith("minecraft:iron_") || id.startsWith("minecraft:chainmail_")) categories.add("iron_gear");
            if (id.startsWith("minecraft:golden_")) categories.add("gold_gear");
        }
        if (item.isEdible()) categories.add("food");
        if (tags.stream().anyMatch(t -> t.equals("forge:ores") || t.startsWith("forge:ores/")
                || t.equals("forge:raw_materials") || t.startsWith("forge:raw_materials/"))) categories.add("ore");
        String rarity = "";
        int equipmentLevel = -1;
        boolean readable = true, special = special(stack);
        try {
            ICommonDataItem<?> data = ICommonDataItem.load(stack);
            if (data != null) {
                special = true;
                if (data.getRarity() == null) readable = false;
                else rarity = data.getRarity().GUID();
                equipmentLevel = data.getLevel();
                if (equipmentLevel < 0) readable = false;
            }
        } catch (RuntimeException | LinkageError error) { readable = false; special = true; }
        int durability = stack.isDamageableItem()
                ? (int) (100L * (stack.getMaxDamage() - stack.getDamageValue()) / stack.getMaxDamage()) : -1;
        return new Rules.Facts(id, categories, tags, durability, stack.isEnchanted(), stack.hasCustomHoverName(),
                locked(stack), special, readable, rarity, equipmentLevel, kind);
    }
}
