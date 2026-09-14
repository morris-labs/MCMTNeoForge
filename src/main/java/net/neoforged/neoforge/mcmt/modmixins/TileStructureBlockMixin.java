/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.mcmt.modmixins;

import com.brandon3055.brandonscore.lib.datamanager.ManagedBool;
import com.brandon3055.draconicevolution.blocks.tileentity.TileStructureBlock;
import com.brandon3055.draconicevolution.init.DEContent;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// MCMT: one of three writers of StructureBlock.buildingLock -- see StructureBlockMixin for the read side
// and the full race description. revert() brackets scheduling a reversion tick with
// buildingLock = true / false, with no try/finally around the two calls in between -- a review pass found
// that redirecting the two raw writes (this file's prior approach) doesn't give the ThreadLocal migration
// genuine exception safety: with the old shared static field, a stuck-true flag self-healed on the next
// unrelated caller on any thread; with a per-thread ThreadLocal, an exception between the writes poisons
// that one MCMT worker's flag permanently. Fix: reimplement revert() in full (decompiled from the deployed
// jar) so the two calls are wrapped in a genuine try/finally, then cancel the original.
@Mixin(TileStructureBlock.class)
abstract class TileStructureBlockMixin {
    @Shadow
    protected Level level;

    @Shadow
    @Final
    protected BlockPos worldPosition;

    @Shadow
    @Final
    public ManagedBool reverting;

    @Inject(method = "revert()V", at = @At("HEAD"), cancellable = true)
    private void mcmt$revertAtomic(CallbackInfo ci) {
        if (!level.isClientSide) {
            DraconicEvolutionThreadLocals.BUILDING_LOCK.set(true);
            try {
                level.scheduleTick(worldPosition, (Block) DEContent.STRUCTURE_BLOCK.get(), 1);
                reverting.set(true);
            } finally {
                DraconicEvolutionThreadLocals.BUILDING_LOCK.set(false);
            }
        }
        ci.cancel();
    }
}
