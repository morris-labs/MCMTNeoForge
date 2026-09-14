/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.mcmt.modmixins;

import dev.compactmods.machines.room.graph.node.RoomRegistrationNode;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// MCMT: RoomRegistrarData is the room-code -> RoomRegistrationNode (boundaries, default color) registry
// backing CMRoomRegistrar, the closest surviving equivalent of this fork's own mod-triage audit's
// "metadata" map -- that audit named a CompactRoomProvider class that no longer exists in the deployed
// 7.0.81 jar (see CMKeyedDataFileManagerMixin's header for the full explanation). The class is
// package-private, so it's targeted by name rather than imported. registrationNodes is an
// Object2ReferenceArrayMap (fastutil's array-scan map, no concurrency support whatsoever), read and
// written by every method below with no lock. Room creation (put, from a dimension building a new machine)
// racing a room lookup (get/isRegistered, reachable from any dimension resolving a room code) can corrupt
// the backing array under concurrent structural modification, not just lose an update. Fixed by acquiring
// registrationNodes' own intrinsic lock across each compound operation.
//
// allRoomCodes/allRoomData return Streams backed directly by the live map's key/value views -- lazily
// evaluated, so a lock held only for the duration of this method call would protect the call but not the
// caller's later iteration, the exact "weakly-consistent iteration" gap a synchronized-block fix has to
// account for that a plain type-swap to a concurrent map would get for free. Both are fixed by copying
// under the lock and streaming the copy, so the returned Stream never touches the live map again.
//
// Open risk, not fixed here: RoomRegistrarData.CODEC's reverse xmap (the serialize direction, run from
// CMSingletonDataFileManager.save()) reads registrationNodes.values() directly inside a static lambda,
// bypassing every method guarded below. A mid-game autosave running on the main thread while H1 workers are
// still ticking dimensions could still race a concurrent put/registerDirty against that read. Flagging for
// the coordinator rather than intercepting the lambda: it isn't a named instance method Mixin can target by
// name, only by fragile compiler-generated method or call-site matching, and this project's own project
// notes already treat "shutdown saves" as a separate, lower-risk-window concern from live H1 ticking.
// (Resolved separately in CMSingletonDataFileManagerMixin, which reimplements save() to take this same
// lock -- exposed cross-class via RoomRegistrarDataAccessor -- before encoding.)
//
// getNextBoundaries(RoomTemplate) is CMRoomRegistrar.createNew's other read of this map (alongside the
// instanceCache write CMRoomRegistrarMixin guards): it computes a new room's placement from
// registrationNodes.size(), with no lock, racing put() the same way isRegistered/get do above. The rest of
// the method (MathUtil.getRegionPositionByIndex, AABBAligner.floor, ...) lives in separate mod/library
// packages this mixin can't reach without also depending on their visibility, and doesn't touch
// registrationNodes at all, so rather than reimplement the whole method, this redirects just the one
// Map.size() call onto registrationNodes' own intrinsic lock -- the same lock every other method here uses.
@Mixin(targets = "dev.compactmods.machines.room.RoomRegistrarData")
abstract class RoomRegistrarDataMixin {
    @Shadow
    private Map<String, RoomRegistrationNode> registrationNodes;

    @Inject(method = "isRegistered", at = @At("HEAD"), cancellable = true)
    private void mcmt$isRegisteredAtomic(String room, CallbackInfoReturnable<Boolean> cir) {
        synchronized (registrationNodes) {
            cir.setReturnValue(registrationNodes.containsKey(room));
        }
    }

    @Inject(method = "get", at = @At("HEAD"), cancellable = true)
    private void mcmt$getAtomic(String room, CallbackInfoReturnable<Optional<RoomRegistrationNode>> cir) {
        synchronized (registrationNodes) {
            cir.setReturnValue(Optional.ofNullable(registrationNodes.get(room)));
        }
    }

    @Inject(method = "put", at = @At("HEAD"), cancellable = true)
    private void mcmt$putAtomic(RoomRegistrationNode node, CallbackInfo ci) {
        synchronized (registrationNodes) {
            registrationNodes.put(node.code(), node);
        }
        ci.cancel();
    }

    @Inject(method = "count", at = @At("HEAD"), cancellable = true)
    private void mcmt$countAtomic(CallbackInfoReturnable<Long> cir) {
        synchronized (registrationNodes) {
            cir.setReturnValue((long) registrationNodes.size());
        }
    }

    @Inject(method = "allRoomCodes", at = @At("HEAD"), cancellable = true)
    private void mcmt$allRoomCodesSnapshot(CallbackInfoReturnable<Stream<String>> cir) {
        synchronized (registrationNodes) {
            cir.setReturnValue(List.copyOf(registrationNodes.keySet()).stream());
        }
    }

    @Inject(method = "allRoomData", at = @At("HEAD"), cancellable = true)
    private void mcmt$allRoomDataSnapshot(CallbackInfoReturnable<Stream<RoomRegistrationNode>> cir) {
        synchronized (registrationNodes) {
            cir.setReturnValue(List.copyOf(registrationNodes.values()).stream());
        }
    }

    @Redirect(method = "getNextBoundaries", at = @At(value = "INVOKE", target = "Ljava/util/Map;size()I"))
    private int mcmt$getNextBoundariesSizeAtomic(Map<String, RoomRegistrationNode> map) {
        synchronized (registrationNodes) {
            return map.size();
        }
    }
}
