/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.mcmt.modmixins;

import codechicken.lib.colour.EnumColour;
import com.brandon3055.brandonscore.lib.datamanager.ManagedBool;
import com.brandon3055.brandonscore.lib.datamanager.ManagedEnum;
import com.brandon3055.brandonscore.lib.datamanager.ManagedPos;
import com.brandon3055.draconicevolution.blocks.StructureBlock;
import com.brandon3055.draconicevolution.blocks.machines.EnergyPylon;
import com.brandon3055.draconicevolution.blocks.tileentity.MultiBlockController;
import com.brandon3055.draconicevolution.blocks.tileentity.TileEnergyPylon;
import com.brandon3055.draconicevolution.blocks.tileentity.TileStructureBlock;
import com.brandon3055.draconicevolution.init.DEContent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.Tags.Blocks;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// MCMT: one of three writers of StructureBlock.buildingLock -- see StructureBlockMixin for the read side
// and the full race description. validateStructure() brackets two block placements (the pylon facing and
// the new structure block) with buildingLock = true / false while scanning for a glass-block face to
// attach to, with no try/finally around them -- the same gap a review pass found in the sibling writers
// (TileStructureBlockMixin.revert(), TileEnergyCoreStabilizerMixin.buildMultiBlock()): redirecting the two
// raw writes alone doesn't give the ThreadLocal migration genuine exception safety. With the old shared
// static field, a stuck-true flag self-healed on the next unrelated caller on any thread; with a per-thread
// ThreadLocal, an exception between the writes poisons that one MCMT worker's flag permanently. Fix:
// reimplement validateStructure() in full (decompiled from the deployed jar) so the two placement calls are
// wrapped in a genuine try/finally, then cancel the original.
@Mixin(TileEnergyPylon.class)
abstract class TileEnergyPylonMixin {
    @Shadow
    protected Level level;

    @Shadow
    @Final
    protected BlockPos worldPosition;

    @Shadow
    @Final
    public ManagedBool structureValid;

    @Shadow
    @Final
    public ManagedPos coreOffset;

    @Shadow
    @Final
    public ManagedEnum<Direction> direction;

    @Shadow
    @Final
    public ManagedEnum<EnumColour> colour;

    @Shadow
    public void debug(Object message) {
        throw new UnsupportedOperationException();
    }

    @Shadow
    public boolean isStructureValid() {
        throw new UnsupportedOperationException();
    }

    @Shadow
    public void selectNextCore() {
        throw new UnsupportedOperationException();
    }

    @Shadow
    public static EnumColour getGlassColour(BlockState state) {
        throw new UnsupportedOperationException();
    }

    @Inject(method = "validateStructure()Z", at = @At("HEAD"), cancellable = true)
    private void mcmt$validateStructureAtomic(CallbackInfoReturnable<Boolean> cir) {
        debug("ValidateStructure, IsValid: " + structureValid.get());
        if (!structureValid.get()) {
            boolean found = false;

            for (Direction dir : Direction.values()) {
                BlockPos pos = worldPosition.relative(dir);
                BlockState testState = level.getBlockState(pos);
                if (testState.is(Blocks.GLASS_BLOCKS)) {
                    colour.set(getGlassColour(testState));
                    DraconicEvolutionThreadLocals.BUILDING_LOCK.set(true);
                    try {
                        level.setBlockAndUpdate(worldPosition, level.getBlockState(worldPosition).setValue(EnergyPylon.FACING, dir));
                        level.setBlockAndUpdate(pos, ((StructureBlock) DEContent.STRUCTURE_BLOCK.get()).defaultBlockState());
                    } finally {
                        DraconicEvolutionThreadLocals.BUILDING_LOCK.set(false);
                    }
                    if (level.getBlockEntity(pos) instanceof TileStructureBlock tile) {
                        tile.blockName.set(BuiltInRegistries.BLOCK.getKey(testState.getBlock()));
                        tile.setController((MultiBlockController) (Object) this);
                        direction.set(dir);
                        found = true;
                        break;
                    }
                }
            }

            debug("Glass block found: " + found);
            if (!found) {
                level.setBlockAndUpdate(worldPosition, level.getBlockState(worldPosition).setValue(EnergyPylon.FACING, Direction.UP));
                colour.set(null);
            }
        }

        structureValid.set(isStructureValid());
        debug("Validate Result: " + structureValid.get());
        if (structureValid.get() && coreOffset.isNull()) {
            selectNextCore();
        } else if (!structureValid.get() && coreOffset.notNull()) {
            coreOffset.set(null);
        }

        cir.setReturnValue(structureValid.get());
    }
}
