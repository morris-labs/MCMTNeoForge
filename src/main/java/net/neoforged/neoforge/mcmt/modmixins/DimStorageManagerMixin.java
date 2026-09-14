/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.mcmt.modmixins;

import edivad.dimstorage.api.AbstractDimStorage;
import edivad.dimstorage.api.DimStoragePlugin;
import edivad.dimstorage.api.Frequency;
import edivad.dimstorage.manager.DimStorageManager;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// MCMT: same author, same shape as EnderStorageManagerMixin -- verified independently against the
// deployed jar (DimStorage-1.21.1-9.1.1.jar), not just the linked source clone under reference/repos,
// because the clone's getStorage(Frequency, String) had already diverged from the deployed
// getStorage(HolderLookup.Provider, Frequency, String); the decompile below is what's actually in the
// pack. get -> create -> put on storageMap (a Collections.synchronizedMap) is a non-atomic
// check-then-act for the same reason as EnderStorage: DimStorage frequencies are explicitly
// cross-dimension, and MCMT ticks dimensions in parallel (H1), so two dimensions can both miss the map
// on the same frequency and race to create/overwrite the entry. Fixed the same way, by holding
// storageMap's own intrinsic lock across the whole compound operation.
@Mixin(DimStorageManager.class)
abstract class DimStorageManagerMixin {
    @Shadow
    @Final
    private Map<String, AbstractDimStorage> storageMap;

    @Shadow
    @Final
    private Map<String, List<AbstractDimStorage>> storageList;

    @Shadow
    @Final
    private static HashMap<String, DimStoragePlugin> PLUGINS;

    @Shadow
    @Final
    private boolean client;

    @Shadow
    private CompoundTag saveTag;

    @Shadow
    private static String buildKey(Frequency frequency, String type) {
        throw new UnsupportedOperationException();
    }

    @Inject(method = "getStorage", at = @At("HEAD"), cancellable = true)
    private void mcmt$getStorageAtomic(
            HolderLookup.Provider registries, Frequency freq, String type, CallbackInfoReturnable<AbstractDimStorage> cir) {
        synchronized (storageMap) {
            String key = buildKey(freq, type);
            AbstractDimStorage storage = storageMap.get(key);
            if (storage == null) {
                storage = PLUGINS.get(type).createDimStorage((DimStorageManager) (Object) this, freq);
                if (!client && saveTag.contains(key)) {
                    storage.loadFromTag(registries, saveTag.getCompound(key));
                }
                storageMap.put(key, storage);
                storageList.get(type).add(storage);
            }
            cir.setReturnValue(storage);
        }
    }
}
