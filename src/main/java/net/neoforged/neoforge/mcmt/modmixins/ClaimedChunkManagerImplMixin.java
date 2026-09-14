/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.mcmt.modmixins;

import dev.ftb.mods.ftbchunks.api.ClaimedChunk;
import dev.ftb.mods.ftbchunks.data.ClaimedChunkImpl;
import dev.ftb.mods.ftbchunks.data.ClaimedChunkManagerImpl;
import dev.ftb.mods.ftblibrary.math.ChunkDimPos;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// MCMT: verified against the deployed jar (ftb-chunks-neoforge-2101.1.21.jar), not just the linked
// source clone under reference/repos -- the clone (branch main) has already diverged in several
// unrelated ways, but ClaimedChunkManagerImpl.getForceLoadedChunks() is the same shape in both, and the
// decompile below is what's actually in the pack.
//
// getForceLoadedChunks() lazily builds and caches a per-dimension force-load index in
// forceLoadedChunkCache, a plain HashMap-backed field with no lock and no volatile: a classic
// check-then-act (null check, build, publish) on a field of this per-server singleton manager. Under
// MCMT's H1, two dimensions ticking concurrently can both observe a stale null and race to build and
// assign the field -- one build's entries can be silently lost when the other's assignment overwrites
// the field mid-loop (the loop body re-reads the field on every iteration, not a cached local), or a
// reader can see the new field reference before the JVM has made the new HashMap's own internal writes
// visible, since a plain field write and a plain field read establish no happens-before edge between
// threads. Force-loaded chunks are this mod's named H1-risk trigger (see MCMT-PLAN.md): FTB Chunks
// force-loads a player's base by design, and any dimension a player leaves keeps ticking -- and keeps
// checking force-load status -- concurrently with wherever they went.
//
// This is also a hot read path: every per-chunk force-load check (isChunkForceLoaded,
// getForceLoadedChunks(dimension)) funnels through here, so a lock taken on every call would serialize
// dimensions against each other on exactly the check H1 exists to parallelize. Fix: an eager, lock-free
// rebuild instead of a lock. Every rebuild happens entirely into a local variable, so a reader never
// observes a partially built map; the finished snapshot publishes through a mixin-owned volatile field
// (the real field is declared HashMap, and @Shadow can't add volatile to an existing declaration, so
// this uses its own field alongside the original rather than retrofitting it). Two threads racing the
// null check now just do redundant, independent work instead of racing the same mutable map -- both
// builds are equivalent snapshots, and whichever publishes last wins, with no correctness dependency on
// which one that is. clearForceLoadedCache() is mirrored so this cache invalidates exactly when the
// mod's own cache does.
//
// A later review found this only ever guarded the derived cache, not the map the cache is built from.
// claimedChunks itself (a plain HashMap, the manager's actual claim registry) is mutated directly with no
// lock at all by registerClaim/unregisterClaim, and getAllClaimedChunks() -- called both externally and by
// mcmt$buildForceLoadedChunks() above -- returned Collections.unmodifiableCollection(claimedChunks.values()),
// a live view over that same map, not a copy. So even with the cache rebuild made race-free against
// itself, its own source read could still land mid-registerClaim/unregisterClaim on another dimension:
// unmodifiableCollection blocks structural writes through the view, it doesn't block them on the backing
// map from another thread, and iterating a HashMap while it's being structurally modified is undefined
// behavior (up to and including an infinite loop or ConcurrentModificationException), not just a stale
// read. Claims are cross-dimension by construction -- a claimed chunk's dimension can differ from the
// dimension whose player action triggers the claim/unclaim -- so this is the same H1 collision shape as
// the frequency race above, on the map one layer down.
//
// The lock-free volatile-snapshot design for forceLoadedChunkCache stays: that hazard was always about two
// threads racing to publish equivalent, independently-computed snapshots, which is harmless regardless of
// how claimedChunks itself is protected. claimedChunks is a different problem -- it's the live, mutable
// source of truth, not a derived cache, so it needs real mutual exclusion, not a snapshot-and-race
// tolerance. Fixed by giving claimedChunks its own intrinsic lock (the same shape as this fork's
// EnderStorageManager/DimStorageManager/CMRoomRegistrar fixes) across every method that reads or writes it
// -- getChunk, registerClaim, unregisterClaim -- and by turning getAllClaimedChunks() from a live view into
// a locked defensive copy, so no caller (including the cache rebuild above) can ever observe
// claimedChunks's internals directly, locked or not.
//
// getChunk is targeted by full descriptor, not bare name: ClaimedChunkManager declares it returning the
// wider ClaimedChunk, and ClaimedChunkManagerImpl overrides with a covariant-narrower ClaimedChunkImpl, so
// javac emits a synthetic bridge method alongside the concrete one -- verified against the deployed jar's
// bytecode (ACC_BRIDGE, ACC_SYNTHETIC on the ClaimedChunk-returning copy). registerClaim/unregisterClaim
// have no such risk: neither is declared on the ClaimedChunkManager interface, so neither has a bridge, and
// getAllClaimedChunks()'s covariant Collection<ClaimedChunkImpl> return erases to the same raw
// ()Ljava/util/Collection; as the interface's Collection<? extends ClaimedChunk>, so it needs no bridge
// either -- confirmed by the same disassembly. Those three stay bare-name targets.
@Mixin(ClaimedChunkManagerImpl.class)
abstract class ClaimedChunkManagerImplMixin {
    @Unique
    private volatile Map<ResourceKey<Level>, Long2ObjectMap<UUID>> mcmt$forceLoadedChunkCache;

    @Shadow
    private Map<ChunkDimPos, ClaimedChunkImpl> claimedChunks;

    @Shadow
    public Collection<ClaimedChunkImpl> getAllClaimedChunks() {
        throw new UnsupportedOperationException();
    }

    @Inject(method = "getForceLoadedChunks()Ljava/util/Map;", at = @At("HEAD"), cancellable = true)
    private void mcmt$getForceLoadedChunksSafely(
            CallbackInfoReturnable<Map<ResourceKey<Level>, Long2ObjectMap<UUID>>> cir) {
        Map<ResourceKey<Level>, Long2ObjectMap<UUID>> cache = mcmt$forceLoadedChunkCache;
        if (cache == null) {
            cache = mcmt$buildForceLoadedChunks();
            mcmt$forceLoadedChunkCache = cache;
        }
        cir.setReturnValue(Collections.unmodifiableMap(cache));
    }

    @Inject(method = "clearForceLoadedCache()V", at = @At("TAIL"))
    private void mcmt$clearForceLoadedChunksSafely(CallbackInfo ci) {
        mcmt$forceLoadedChunkCache = null;
    }

    @Unique
    private Map<ResourceKey<Level>, Long2ObjectMap<UUID>> mcmt$buildForceLoadedChunks() {
        Map<ResourceKey<Level>, Long2ObjectMap<UUID>> built = new HashMap<>();
        for (ClaimedChunkImpl chunk : getAllClaimedChunks()) {
            if (chunk.isActuallyForceLoaded()) {
                Long2ObjectMap<UUID> pos2idMap = built.computeIfAbsent(chunk.getPos().dimension(), ignored -> new Long2ObjectOpenHashMap<>());
                pos2idMap.put(ChunkPos.asLong(chunk.getPos().x(), chunk.getPos().z()), chunk.getTeamData().getTeamId());
            }
        }
        return built.isEmpty() ? Collections.emptyMap() : built;
    }

    // MCMT: getChunk is a covariant-return override of ClaimedChunkManager#getChunk (which returns the
    // wider ClaimedChunk), so javac emits a synthetic ACC_BRIDGE getChunk(ChunkDimPos)Ldev/ftb/mods/ftbchunks/api/ClaimedChunk;
    // alongside the concrete method -- confirmed against the deployed jar's bytecode, not just the source
    // clone. A bare-name target risks matching both; the full descriptor pins this to the concrete method
    // only, the same disambiguation convention CableHelpersMixin uses for isRemovingCable().
    @Inject(method = "getChunk(Ldev/ftb/mods/ftblibrary/math/ChunkDimPos;)Ldev/ftb/mods/ftbchunks/data/ClaimedChunkImpl;", at = @At("HEAD"), cancellable = true)
    private void mcmt$getChunkAtomic(ChunkDimPos pos, CallbackInfoReturnable<ClaimedChunkImpl> cir) {
        synchronized (claimedChunks) {
            cir.setReturnValue(claimedChunks.get(pos));
        }
    }

    @Inject(method = "getAllClaimedChunks", at = @At("HEAD"), cancellable = true)
    private void mcmt$getAllClaimedChunksSnapshot(CallbackInfoReturnable<Collection<ClaimedChunkImpl>> cir) {
        synchronized (claimedChunks) {
            cir.setReturnValue(List.copyOf(claimedChunks.values()));
        }
    }

    @Inject(method = "registerClaim", at = @At("HEAD"), cancellable = true)
    private void mcmt$registerClaimAtomic(ChunkDimPos pos, ClaimedChunk chunk, CallbackInfo ci) {
        if (chunk instanceof ClaimedChunkImpl impl) {
            synchronized (claimedChunks) {
                claimedChunks.put(pos, impl);
            }
        }
        ci.cancel();
    }

    @Inject(method = "unregisterClaim", at = @At("HEAD"), cancellable = true)
    private void mcmt$unregisterClaimAtomic(ChunkDimPos pos, CallbackInfo ci) {
        synchronized (claimedChunks) {
            claimedChunks.remove(pos);
        }
        ci.cancel();
    }
}
