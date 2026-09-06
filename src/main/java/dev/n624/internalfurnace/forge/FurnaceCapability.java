package dev.n624.internalfurnace.forge;

import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraftforge.common.capabilities.*;
import net.minecraftforge.common.util.LazyOptional;
import org.jetbrains.annotations.Nullable;

public final class FurnaceCapability implements ICapabilitySerializable<CompoundTag> {
    public static final Capability<FurnaceData> TYPE = CapabilityManager.get(new CapabilityToken<>() {});
    private final FurnaceData data = new FurnaceData();
    private LazyOptional<FurnaceData> optional = LazyOptional.of(() -> data);
    @Override public <T> LazyOptional<T> getCapability(Capability<T> capability, @Nullable Direction side) {
        // Player.Clone temporarily revives the provider after death invalidated its original handle.
        // CapabilityProvider gates access while invalid; revived access needs a fresh handle to the same data.
        if (capability == TYPE && !optional.isPresent()) optional = LazyOptional.of(() -> data);
        return TYPE.orEmpty(capability, optional);
    }
    @Override public CompoundTag serializeNBT() { return data.save(); }
    @Override public void deserializeNBT(CompoundTag tag) { data.load(tag); }
    public void invalidate() { optional.invalidate(); }
}
