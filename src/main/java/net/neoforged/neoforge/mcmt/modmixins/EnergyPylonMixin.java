/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.mcmt.modmixins;

import com.brandon3055.draconicevolution.blocks.machines.EnergyPylon;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

// MCMT: a second reader of StructureBlock.buildingLock, not named in the PHASE0-mod-triage.md audit
// notes (which cite only StructureBlock.neighborChanged) -- found by grepping every touch site of the
// field across the mod's decompiled classes, not just the one file the audit named. See
// StructureBlockMixin for the primary read site and the full race description. EnergyPylon.neighborChanged
// gates a structure-revalidation call on !StructureBlock.buildingLock so a concurrent build in this same
// pylon doesn't get revalidated mid-construction; cross-dimension, the same unsynchronized global read
// races against every writer covered by StructureBlockMixin's sibling mixins.
//
// Fix: redirect the read to DraconicEvolutionThreadLocals.BUILDING_LOCK.
@Mixin(EnergyPylon.class)
abstract class EnergyPylonMixin {
    @Redirect(method = "neighborChanged(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Block;Lnet/minecraft/core/BlockPos;Z)V", at = @At(value = "FIELD", target = "Lcom/brandon3055/draconicevolution/blocks/StructureBlock;buildingLock:Z", opcode = Opcodes.GETSTATIC))
    private boolean mcmt$readBuildingLock() {
        return DraconicEvolutionThreadLocals.BUILDING_LOCK.get();
    }
}
