/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.mcmt.modmixins;

import java.util.Collection;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.cyclops.cyclopscore.helper.ItemStackHelpers;
import org.cyclops.integrateddynamics.api.block.cable.ICable;
import org.cyclops.integrateddynamics.api.block.cable.ICableFakeable;
import org.cyclops.integrateddynamics.api.part.IPartContainer;
import org.cyclops.integrateddynamics.core.helper.CableHelpers;
import org.cyclops.integrateddynamics.core.helper.PartHelpers;
import org.cyclops.integrateddynamics.item.ItemBlockCable;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// MCMT: verified against the deployed jar (integrateddynamics-1.21.1-neoforge-1.34.0.jar), not just the
// linked source clone under reference/repos, per the same version-mismatch caveat as BlockCableMixin. The
// deployed jar's call-site set for this flag differs from both the audit notes and the newer source
// clone: neither the clone's checked-out branch nor the shipped jar has any BlockEntity class touching
// removingCable directly -- every external access, in both, goes through isRemovingCable()/
// setRemovingCable(boolean) below, called only from BlockCable, BlockWithEntityGuiCabled and
// BlockContainerCabled.
//
// CableHelpers.removingCable is a private static boolean, exposed as isRemovingCable()/
// setRemovingCable(boolean), toggled around a cable-removal sequence to suppress a redundant network
// rebuild while a cable is mid-removal. CableHelpers is a static utility shared by every cable in every
// dimension, and H1 ticks dimensions in parallel, so one worker's removal-in-progress flag can suppress
// -- or fail to suppress -- another worker's unrelated removal in a different dimension. Same shape as
// the vanilla RedstoneTorchBlock/RedStoneWireBlock statics already fixed in this fork.
//
// Because every external caller goes through isRemovingCable()/setRemovingCable(), redirecting those two
// methods alone covers BlockCable, BlockWithEntityGuiCabled and BlockContainerCabled -- no per-class
// mixin needed for any of them.
//
// removeCable() itself brackets its work with three raw writes to the field directly (true at the start,
// false on early exit, false at the end), bypassing its own setter, with no try/finally around any of
// them -- a review pass found that a redirect on the raw writes alone (this file's prior approach) does
// not fix the exception-safety gap the ThreadLocal migration introduced: with the old shared static
// field, a stuck-true flag self-healed on the next unrelated caller (any thread could overwrite it); with
// a per-thread ThreadLocal, an exception between the true and false writes poisons that one MCMT worker
// permanently -- every later call on that thread, for any dimension, sees the flag stuck true. Fix:
// reimplement removeCable() in full (decompiled from the deployed jar) so the risky middle section is
// wrapped in a genuine try/finally, then cancel the original.
@Mixin(CableHelpers.class)
abstract class CableHelpersMixin {
    @Unique
    private static final ThreadLocal<Boolean> mcmt$removingCable = ThreadLocal.withInitial(() -> false);

    @Inject(method = "isRemovingCable()Z", at = @At("HEAD"), cancellable = true)
    private static void mcmt$readRemovingCable(CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(mcmt$removingCable.get());
    }

    @Inject(method = "setRemovingCable(Z)V", at = @At("HEAD"), cancellable = true)
    private static void mcmt$writeRemovingCable(boolean removingCable, CallbackInfo ci) {
        mcmt$removingCable.set(removingCable);
        ci.cancel();
    }

    @Inject(method = "removeCable(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/entity/player/Player;)V", at = @At("HEAD"), cancellable = true)
    private static void mcmt$removeCableAtomic(Level world, BlockPos pos, @Nullable Player player, CallbackInfo ci) {
        mcmt$removingCable.set(true);
        try {
            ICable cable = CableHelpers.getCable(world, pos, null).orElse(null);
            ICableFakeable cableFakeable = CableHelpers.getCableFakeable(world, pos, null).orElse(null);
            IPartContainer partContainer = PartHelpers.getPartContainer(world, pos, null).orElse(null);
            BlockState blockState = world.getBlockState(pos);
            if (cable != null) {
                Collection<Direction> connectedCables = CableHelpers.getCableConnections(cable);
                CableHelpers.onCableRemoving(world, pos, false, false, blockState, false);
                if (cableFakeable != null && partContainer != null && partContainer.hasParts()) {
                    cableFakeable.setRealCable(false);
                } else {
                    cable.destroy();
                }

                if (player == null) {
                    ItemStackHelpers.spawnItemStack(world, pos, cable.getItemStack());
                } else if (!player.isCreative()) {
                    ItemStackHelpers.spawnItemStackToPlayer(world, pos, cable.getItemStack(), player);
                }

                CableHelpers.onCableRemoved(world, pos, connectedCables);
                ItemBlockCable.playBreakSound(world, pos, blockState);
            }
        } finally {
            mcmt$removingCable.set(false);
        }
        ci.cancel();
    }
}
