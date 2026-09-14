/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.mcmt.modmixins;

import org.cyclops.integrateddynamics.block.BlockCable;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

// MCMT: verified against the deployed jar (integrateddynamics-1.21.1-neoforge-1.34.0.jar), not just the
// linked source clone under reference/repos -- the clone is checked out on branch master-26-lts
// (Minecraft 26.1.2, mod_version 1.35.0), a newer version than what this pack actually ships, so the
// decompile below is what's actually in the pack. The field and both read sites match the clone exactly.
//
// SKIP_NETWORK_INIT is a public static boolean on this singleton Block, toggled around bulk
// cable-placement/removal loops (see NetworkGenerationHelperMixin) to suppress the per-cable network
// rebuild that onPlace/onRemove would otherwise trigger for every cable in the loop. BlockCable is shared
// by every cable in every dimension, and H1 ticks dimensions in parallel, so one worker's bulk-generation
// suppression window can swallow -- or fail to swallow -- another worker's unrelated cable placement in a
// different dimension, causing a double-fire or a skipped network-graph update. Same shape as the vanilla
// RedstoneTorchBlock/RedStoneWireBlock statics already fixed in this fork.
//
// Fix: redirect both reads to IntegratedDynamicsThreadLocals.SKIP_NETWORK_INIT, a ThreadLocal shared with
// NetworkGenerationHelperMixin, which owns the write side. The original field is left in place, unused.
@Mixin(BlockCable.class)
abstract class BlockCableMixin {
    @Redirect(method = {
            "onRemove(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Z)V",
            "onPlace(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Z)V"
    }, at = @At(value = "FIELD", target = "Lorg/cyclops/integrateddynamics/block/BlockCable;SKIP_NETWORK_INIT:Z", opcode = Opcodes.GETSTATIC))
    private boolean mcmt$readSkipNetworkInit() {
        return IntegratedDynamicsThreadLocals.SKIP_NETWORK_INIT.get();
    }
}
