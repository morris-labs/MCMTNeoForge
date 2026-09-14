/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.mcmt.modmixins;

import com.brandon3055.brandonscore.lib.datamanager.ManagedBool;
import com.brandon3055.brandonscore.lib.datamanager.ManagedEnum;
import com.brandon3055.brandonscore.lib.datamanager.ManagedPos;
import com.brandon3055.brandonscore.utils.FacingUtils;
import com.brandon3055.draconicevolution.blocks.StructureBlock;
import com.brandon3055.draconicevolution.blocks.machines.EnergyCoreStabilizer;
import com.brandon3055.draconicevolution.blocks.tileentity.MultiBlockController;
import com.brandon3055.draconicevolution.blocks.tileentity.TileEnergyCoreStabilizer;
import com.brandon3055.draconicevolution.blocks.tileentity.TileStructureBlock;
import com.brandon3055.draconicevolution.init.DEContent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction.Axis;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// MCMT: one of three writers of StructureBlock.buildingLock -- see StructureBlockMixin for the read side
// and the full race description. buildMultiBlock(Axis) brackets placing a ring of structure blocks
// around an energy core stabilizer with buildingLock = true / false, with no try/finally around the loop
// in between -- a review pass found that redirecting the two raw writes (this file's prior approach)
// doesn't give the ThreadLocal migration genuine exception safety: with the old shared static field, a
// stuck-true flag self-healed on the next unrelated caller on any thread; with a per-thread ThreadLocal,
// an exception mid-loop poisons that one MCMT worker's flag permanently. Fix: reimplement
// buildMultiBlock(Axis) in full (decompiled from the deployed jar) so the loop and the two trailing writes
// are wrapped in a genuine try/finally, then cancel the original.
@Mixin(TileEnergyCoreStabilizer.class)
abstract class TileEnergyCoreStabilizerMixin {
    @Shadow
    protected Level level;

    @Shadow
    @Final
    protected BlockPos worldPosition;

    @Shadow
    @Final
    public ManagedPos coreOffset;

    @Shadow
    @Final
    public ManagedBool isValidMultiBlock;

    @Shadow
    @Final
    public ManagedEnum<Axis> multiBlockAxis;

    @Inject(method = "buildMultiBlock(Lnet/minecraft/core/Direction$Axis;)V", at = @At("HEAD"), cancellable = true)
    private void mcmt$buildMultiBlockAtomic(Axis axis, CallbackInfo ci) {
        coreOffset.set(null);
        DraconicEvolutionThreadLocals.BUILDING_LOCK.set(true);
        try {
            for (BlockPos offset : FacingUtils.getAroundAxis(axis)) {
                level.setBlockAndUpdate(worldPosition.offset(offset), ((StructureBlock) DEContent.STRUCTURE_BLOCK.get()).defaultBlockState());
                if (level.getBlockEntity(worldPosition.offset(offset)) instanceof TileStructureBlock tile) {
                    tile.blockName.set(DEContent.ENERGY_CORE_STABILIZER.getId());
                    tile.setController((MultiBlockController) (Object) this);
                }
            }

            level.setBlockAndUpdate(worldPosition, level.getBlockState(worldPosition).setValue(EnergyCoreStabilizer.LARGE, true));
            isValidMultiBlock.set(true);
            multiBlockAxis.set(axis);
        } finally {
            DraconicEvolutionThreadLocals.BUILDING_LOCK.set(false);
        }
        ci.cancel();
    }
}
