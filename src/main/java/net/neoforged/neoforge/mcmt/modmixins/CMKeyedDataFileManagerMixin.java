/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.mcmt.modmixins;

import dev.compactmods.machines.LoggingUtil;
import dev.compactmods.machines.data.CMDataFile;
import dev.compactmods.machines.data.CodecHolder;
import dev.compactmods.machines.data.DataFileUtil;
import dev.compactmods.machines.data.manager.CMKeyedDataFileManager;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.common.IOUtilities;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// MCMT: CompactMachines' CompactRoomProvider (the class this project's own mod-triage audit named) doesn't
// exist in the deployed jar -- that class only ever existed on an old 1.19.x source branch. In the shipped
// 7.0.81 (MC 1.21.1) architecture, room state is split across several per-concern managers, and three of
// them -- CMServerRoomDataAttachmentAccessor (per-room metadata, the modern equivalent of the audit's
// "metadata" map), RoomSpawnManagers (the audit's "roomSpawns"), and CMServerRoomUpgradeDataAccessor (room
// upgrade data, not separately named by the audit but the same shape) -- all delegate their get-or-create
// logic to this one generic class. CMKeyedDataFileManager.data(key) is a get -> create -> load -> put
// sequence on a raw HashMap (not even a Collections.synchronizedMap), keyed by room code: the exact
// EnderStorageManager/DimStorageManager non-atomic check-then-act shape already fixed elsewhere in this
// fork, except here the underlying map has no synchronization on individual calls either, so two rooms
// (each its own dimension, per MCMT's H1) requesting data for the same room code at once don't just risk a
// last-write-wins overwrite -- concurrent structural modification of a plain HashMap can corrupt the table
// outright. Every accessor that reaches CompactMachines.roomData(String)/roomUpgradeData(...) or
// RoomSpawnManagers.get(...) funnels through here, so fixing this one class covers every current keyed
// data type without a per-caller fix. The whole compound operation is made atomic by acquiring cache's own
// intrinsic lock; hasData and save are guarded the same way so a reader or the periodic/shutdown save pass
// never observes the raw HashMap mid-resize while another dimension's tick thread is inside data().
@Mixin(CMKeyedDataFileManager.class)
abstract class CMKeyedDataFileManagerMixin<Key, T extends CMDataFile & CodecHolder<T>> {
    @Shadow
    @Final
    protected MinecraftServer server;

    @Shadow
    @Final
    private BiFunction<MinecraftServer, Key, T> creator;

    @Shadow
    @Final
    private HashMap<Key, T> cache;

    @Shadow
    public String getFileKey(Key key) {
        throw new UnsupportedOperationException();
    }

    @Inject(method = "data", at = @At("HEAD"), cancellable = true)
    private void mcmt$dataAtomic(Key key, CallbackInfoReturnable<T> cir) {
        synchronized (cache) {
            T existing = cache.get(key);
            if (existing == null) {
                T inst = creator.apply(server, key);
                Path dir = inst.getDataLocation(server);
                DataFileUtil.ensureDirExists(dir);
                File file = dir.resolve(getFileKey(key) + ".dat").toFile();
                existing = file.exists() ? DataFileUtil.loadFileWithCodec(file, inst.codec()) : inst;
                cache.put(key, existing);
            }
            cir.setReturnValue(existing);
        }
    }

    @Inject(method = "hasData", at = @At("HEAD"), cancellable = true)
    private void mcmt$hasDataAtomic(Key key, CallbackInfoReturnable<Boolean> cir) {
        synchronized (cache) {
            cir.setReturnValue(cache.containsKey(key));
        }
    }

    @Inject(method = "save", at = @At("HEAD"), cancellable = true)
    private void mcmt$saveAtomic(CallbackInfo ci) {
        // Encoding only touches in-memory state guarded by cache's lock, so it happens inside the
        // synchronized block; the actual disk write is the slow part and doesn't need the lock held, so
        // it happens after release -- otherwise a save would block every dimension's room lookups
        // (RoomRegistrarDataMixin et al., all guarded by their own object's lock, not this one, but the
        // same shape) for the full duration of file I/O, not just the in-memory snapshot.
        List<Map.Entry<Path, CompoundTag>> pending = new ArrayList<>();
        synchronized (cache) {
            cache.forEach((key, data) -> {
                CompoundTag fullData = new CompoundTag();
                fullData.putString("version", data.getDataVersion());
                // CompoundTag.store(String, Codec, Object), the one-line call the deployed mod jar uses,
                // doesn't exist on this repo's CompoundTag -- the mod was built against a newer MC/NeoForge
                // than this fork's current patch base. Expand it to the encode call it wraps, using the
                // same encodeStart(...).getOrThrow() convention this fork's own vanilla code already uses
                // (see AreaEffectCloud.addAdditionalSaveData) rather than swallowing an encode failure into
                // a silently truncated save.
                Tag encoded = data.codec().encodeStart(NbtOps.INSTANCE, data).getOrThrow();
                fullData.put("data", encoded);
                pending.add(Map.entry(data.getDataLocation(server).resolve(getFileKey(key) + ".dat"), fullData));
            });
        }
        for (Map.Entry<Path, CompoundTag> entry : pending) {
            try {
                IOUtilities.writeNbtCompressed(entry.getValue(), entry.getKey());
            } catch (IOException e) {
                LoggingUtil.modLog().error("Failed to write data: " + e.getMessage(), e);
            }
        }
        ci.cancel();
    }
}
