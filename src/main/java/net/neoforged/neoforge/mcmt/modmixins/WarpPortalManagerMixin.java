/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.mcmt.modmixins;

import java.util.WeakHashMap;
import net.blay09.mods.waystones.block.entity.WarpPortalBlockEntity;
import net.blay09.mods.waystones.core.WarpPortalManager;
import net.blay09.mods.waystones.core.WaystonePermissionManager;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// MCMT: verified against the deployed jar (waystones-neoforge-1.21.1-21.1.40.jar), which decompiles
// identically to the audit notes here.
//
// portalCooldowns is a static WeakHashMap<Entity, Integer>, not thread-safe -- WeakHashMap resizes and
// purges stale weak references on both get() and put(), so two threads calling it concurrently can
// corrupt its internal table, not just race the cooldown value. canUsePortal is the map's only call
// site, and it's a compound check-then-act: read the entity's last-use tick, decide whether the
// cooldown has elapsed, then write the new tick -- the same "get, decide, put" shape already fixed for
// EnderStorageManager/DimStorageManager's storageMap. Warp portals are explicitly cross-dimension (a
// portal in one dimension warps a player toward a waystone that can be in another), and H1 ticks
// dimensions in parallel, so the same entity using two portals in quick succession -- or, more
// plausibly, two different entities racing the map's internal resize -- is reachable under normal play,
// not just a contrived worst case.
//
// Fix: reimplement canUsePortal's body inside a block synchronized on portalCooldowns itself, the same
// shape and same rationale as EnderStorageManagerMixin/DimStorageManagerMixin -- every access to this
// map now goes through this one method, so locking on the field is sufficient without a separate lock
// object.
@Mixin(WarpPortalManager.class)
abstract class WarpPortalManagerMixin {
    @Shadow
    @Final
    private static WeakHashMap<Entity, Integer> portalCooldowns;

    @Inject(method = "canUsePortal", at = @At("HEAD"), cancellable = true)
    private static void mcmt$canUsePortalAtomic(
            Entity entity, WarpPortalBlockEntity portal, CallbackInfoReturnable<Boolean> cir) {
        synchronized (portalCooldowns) {
            Integer cooldown = portalCooldowns.get(entity);
            if (cooldown != null && entity.tickCount - cooldown < 20) {
                cir.setReturnValue(false);
                return;
            }
            portalCooldowns.put(entity, entity.tickCount);
            cir.setReturnValue(!WaystonePermissionManager.isEntityDeniedTeleports(entity));
        }
    }
}
