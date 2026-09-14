/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.mcmt.modmixins;

import org.cyclops.integrateddynamics.command.CommandGenerateNetwork;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

// MCMT: the write side of BlockCable.SKIP_NETWORK_INIT -- see BlockCableMixin for the read side, the full
// race description, and the deployed-jar-vs-source-clone verification note. This debug/admin command
// (generateEmptyNetwork, clearCables -- both on the nested NetworkGenerationHelper, not
// CommandGenerateNetwork itself) brackets a bulk cable placement or removal loop with
// SKIP_NETWORK_INIT = true before the loop and SKIP_NETWORK_INIT = false in a finally block. Because it
// writes the same public static field BlockCableMixin reads, and BlockCable is shared across every
// dimension, running this command in one dimension while another dimension ticks concurrently under H1
// can race the flag.
//
// Fix: redirect all four writes (true at the top of each try, false in each finally) to
// IntegratedDynamicsThreadLocals.SKIP_NETWORK_INIT.
@Mixin(CommandGenerateNetwork.NetworkGenerationHelper.class)
abstract class NetworkGenerationHelperMixin {
    @Redirect(method = {
            "generateEmptyNetwork(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;I)V",
            "clearCables(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;I)V"
    }, at = @At(value = "FIELD", target = "Lorg/cyclops/integrateddynamics/block/BlockCable;SKIP_NETWORK_INIT:Z", opcode = Opcodes.PUTSTATIC))
    private static void mcmt$writeSkipNetworkInit(boolean value) {
        IntegratedDynamicsThreadLocals.SKIP_NETWORK_INIT.set(value);
    }
}
