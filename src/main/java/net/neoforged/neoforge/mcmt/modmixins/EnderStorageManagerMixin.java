/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.mcmt.modmixins;

import codechicken.enderstorage.api.AbstractEnderStorage;
import codechicken.enderstorage.api.EnderStoragePlugin;
import codechicken.enderstorage.api.Frequency;
import codechicken.enderstorage.api.StorageType;
import codechicken.enderstorage.manager.EnderStorageManager;
import java.util.List;
import java.util.Map;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// MCMT: EnderStorageManager.getStorage() does a get -> create -> put on storageMap, a
// Collections.synchronizedMap. That wrapper makes each individual get/put call atomic, but not the
// three-call sequence: two levels requesting the same frequency at the same instant can both miss the
// map, both call createEnderStorage (and, on the server, both load the same saved tag into a separate
// storage instance), and both put -- the last write wins in storageMap, silently discarding the other
// thread's storage and its in-flight mutations, while storageList ends up holding both instances. Ender
// Storage frequencies are cross-dimension by design, and MCMT ticks levels in parallel (H1), so two
// levels colliding on a frequency is the expected case this fixes, not an edge case. The whole
// check-then-act is made atomic by acquiring storageMap's own intrinsic lock -- the standard safe way to
// compose operations on a Collections.synchronizedMap, since every accessor already synchronizes on the
// map object itself.
@Mixin(EnderStorageManager.class)
abstract class EnderStorageManagerMixin {
    @Shadow
    private Map<String, AbstractEnderStorage> storageMap;

    @Shadow
    private Map<StorageType<?>, List<AbstractEnderStorage>> storageList;

    @Shadow
    private static Map<StorageType<?>, EnderStoragePlugin<?>> plugins;

    @Shadow
    @Final
    public boolean client;

    @Shadow
    private CompoundTag saveTag;

    @Inject(method = "getStorage", at = @At("HEAD"), cancellable = true)
    private void mcmt$getStorageAtomic(Frequency freq, StorageType<?> type, CallbackInfoReturnable<AbstractEnderStorage> cir) {
        synchronized (storageMap) {
            String key = freq + ",type=" + type.name();
            AbstractEnderStorage storage = storageMap.get(key);
            if (storage == null) {
                storage = plugins.get(type).createEnderStorage((EnderStorageManager) (Object) this, freq);
                if (!client && saveTag.contains(key)) {
                    storage.loadFromTag(saveTag.getCompound(key), ServerLifecycleHooks.getCurrentServer().registryAccess());
                }
                storageMap.put(key, storage);
                storageList.get(type).add(storage);
            }
            cir.setReturnValue(storage);
        }
    }
}
