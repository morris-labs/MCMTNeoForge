/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.mcmt.modmixins;

import dev.compactmods.machines.api.dimension.CompactDimension;
import dev.compactmods.machines.api.room.RoomInstance;
import dev.compactmods.machines.room.CMRoomRegistrar;
import dev.compactmods.machines.room.graph.node.RoomRegistrationNode;
import java.util.Map;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// MCMT: CMRoomRegistrar is the process-wide singleton (CompactMachines.roomRegistrar(), set once by
// reloadServices) that resolves a room code to its RoomInstance -- every dimension's code that touches a
// Compact Machines room, including the room's own dimension, goes through it. getOrMakeRoomInstance does a
// containsKey -> get / construct -> put check-then-act on instanceCache, a fastutil Object2ObjectArrayMap
// (an array-scan map with no concurrency support at all, not even the incidental safety a
// Collections.synchronizedMap wrapper gives). Two dimensions resolving the same room code at once -- the
// room's own dimension ticking alongside its parent, the exact scenario Compact Machines rooms create --
// can corrupt the backing array via concurrent structural modification, not just race a cache miss.
// RoomInstance is an immutable record with no identity-coupled state of its own (its mutable room data is
// looked up fresh through CompactMachines.roomData(), fixed separately in CMKeyedDataFileManagerMixin), so
// two threads building a redundant instance on a genuine cache miss is harmless -- the fix only needs to
// make the map access itself safe, done by acquiring instanceCache's own intrinsic lock across the whole
// compound operation, the same shape as this fork's EnderStorageManager/DimStorageManager fixes.
//
// createNew -- the room-creation entry point, reached whenever a player places a new machine -- bypassed
// all of that: it builds the freshly created RoomInstance and writes it to instanceCache with a bare
// `this.instanceCache.put(inst.code(), inst)`, no lock, so a room creation on one dimension could still
// corrupt the same backing array getOrMakeRoomInstanceAtomic above guards on every other dimension. The
// rest of createNew (building the RoomInstance, registering the RoomRegistrationNode, calculating chunks)
// doesn't touch instanceCache and doesn't need to be under this lock, so rather than reimplement the whole
// method, this redirects just that one Map.put call onto instanceCache's own intrinsic lock -- the same
// lock object getOrMakeRoomInstanceAtomic uses, so the two can never race each other.
@Mixin(CMRoomRegistrar.class)
abstract class CMRoomRegistrarMixin {
    @Shadow
    @Final
    private MinecraftServer server;

    @Shadow
    @Final
    private Map<String, RoomInstance> instanceCache;

    @Inject(method = "getOrMakeRoomInstance", at = @At("HEAD"), cancellable = true)
    private void mcmt$getOrMakeRoomInstanceAtomic(RoomRegistrationNode regNode, CallbackInfoReturnable<RoomInstance> cir) {
        synchronized (instanceCache) {
            RoomInstance inst = instanceCache.get(regNode.code());
            if (inst == null) {
                inst = new RoomInstance(server, CompactDimension.LEVEL_KEY, regNode.code(), regNode.defaultMachineColor(), regNode);
                instanceCache.put(regNode.code(), inst);
            }
            cir.setReturnValue(inst);
        }
    }

    @Redirect(method = "createNew", at = @At(value = "INVOKE", target = "Ljava/util/Map;put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"))
    private Object mcmt$createNewCacheAtomic(Map<String, RoomInstance> map, Object code, Object inst) {
        synchronized (instanceCache) {
            return map.put((String) code, (RoomInstance) inst);
        }
    }
}
