/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.mcmt.modmixins;

import com.brandon3055.draconicevolution.blocks.StructureBlock;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

// MCMT: verified against the deployed jar (Draconic-Evolution-1.21.1-3.1.4.632.jar), not just the linked
// source clone under reference/repos -- the clone matches the deployed jar's mc_version/mod_version
// (1.21.1 / 3.1.4), and the decompile confirms all seven call sites (field, this read, and the six writes
// covered by the four sibling mixins) match the clone exactly, plus one read the clone has but the
// PHASE0-mod-triage.md audit notes didn't name: EnergyPylonMixin below.
//
// StructureBlock.buildingLock is a public static boolean, JVM-global, toggled around a multiblock
// structure build/revert sequence (see TileStructureBlockMixin, TileEnergyCoreStabilizerMixin,
// TileEnergyPylonMixin) to suppress this block's own neighbor-changed revert logic while the structure
// under construction is expected to look temporarily invalid. StructureBlock is shared across every
// dimension, and H1 ticks dimensions in parallel, so one dimension building or reverting a structure can
// race another dimension's concurrent structure build on this single global guard -- same shape as the
// vanilla RedstoneTorchBlock/RedStoneWireBlock statics already fixed in this fork.
//
// The read lives in a private neighborChanged(LevelReader, BlockPos) overload, called from both of the
// public neighborChanged overrides (the LevelReader-only onNeighborChange path and the tick-path
// Level/Block/BlockPos/boolean override) -- redirecting the private overload covers both callers with one
// injection.
//
// Fix: redirect the read to DraconicEvolutionThreadLocals.BUILDING_LOCK, shared with the four other
// mixins that touch this field. The original field is left in place, unused.
@Mixin(StructureBlock.class)
abstract class StructureBlockMixin {
    @Redirect(method = "neighborChanged(Lnet/minecraft/world/level/LevelReader;Lnet/minecraft/core/BlockPos;)V", at = @At(value = "FIELD", target = "Lcom/brandon3055/draconicevolution/blocks/StructureBlock;buildingLock:Z", opcode = Opcodes.GETSTATIC))
    private boolean mcmt$readBuildingLock() {
        return DraconicEvolutionThreadLocals.BUILDING_LOCK.get();
    }
}
