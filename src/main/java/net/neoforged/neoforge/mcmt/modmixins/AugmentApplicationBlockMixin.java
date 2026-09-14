/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.mcmt.modmixins;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import rearth.oritech.block.blocks.augmenter.AugmentApplicationBlock;

// MCMT: the audit that found this bug read reference/repos/Oritech, which is checked out on branch
// 26.1 (Minecraft 26.1.2, mod_version 2.0.0-exp7) and names the class
// rearth.oritech.block.blocks.augmenter.CyberneticAugmentationCenterBlock. The jar actually deployed
// in this pack (oritech-neoforge-1.21.1-1.2.10.jar) doesn't contain that class at all -- decompiling it
// directly (Vineflower) found the real, currently-shipped equivalent is AugmentApplicationBlock, same
// package, same field, same bug, renamed. This mixin targets the deployed class.
//
// AugmentApplicationBlock keeps lastContact, a plain HashMap<Player, Long>, as an instance field of the
// Block itself. A Block is a singleton shared by every placed instance of it across every dimension, and
// entityInside reads (getOrDefault) then writes (put) this map once per tick for every player standing
// on the block. Under MCMT's H1, two dimensions tick concurrently, so two different players in two
// different dimensions can call entityInside on this same shared map from two worker threads at once. A
// given player entity is only ever ticked by one thread at a time, so this isn't a lost-update race on
// one entry -- it's concurrent structural modification of a plain HashMap from different keys, which can
// corrupt its internal table (lost entries, or a resize that loops forever), the same shape as vanilla's
// RedstoneTorchBlock/RedStoneWireBlock races already fixed in this fork.
//
// Fix: redirect both map operations to a mixin-owned ConcurrentHashMap. The original lastContact field
// is left in place, unused -- it's declared HashMap, not Map, so a @Shadow can't retype it to hold a
// ConcurrentHashMap instance instead.
@Mixin(AugmentApplicationBlock.class)
abstract class AugmentApplicationBlockMixin {
    @Unique
    private final ConcurrentHashMap<Player, Long> mcmt$lastContact = new ConcurrentHashMap<>();

    @WrapOperation(method = "entityInside(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/entity/Entity;)V", at = @At(value = "INVOKE", target = "Ljava/util/HashMap;getOrDefault(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"))
    private Object mcmt$threadSafeGetLastContact(
            HashMap<Player, Long> instance, Object key, Object defaultValue, Operation<Object> original) {
        return mcmt$lastContact.getOrDefault(key, (Long) defaultValue);
    }

    @WrapOperation(method = "entityInside(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/entity/Entity;)V", at = @At(value = "INVOKE", target = "Ljava/util/HashMap;put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"))
    private Object mcmt$threadSafePutLastContact(
            HashMap<Player, Long> instance, Object key, Object value, Operation<Object> original) {
        return mcmt$lastContact.put((Player) key, (Long) value);
    }
}
