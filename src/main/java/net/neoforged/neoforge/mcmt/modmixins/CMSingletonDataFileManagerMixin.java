/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.mcmt.modmixins;

import dev.compactmods.machines.LoggingUtil;
import dev.compactmods.machines.data.CMDataFile;
import dev.compactmods.machines.data.CodecHolder;
import dev.compactmods.machines.data.DataFileUtil;
import dev.compactmods.machines.data.manager.CMSingletonDataFileManager;
import java.io.IOException;
import java.nio.file.Path;
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

// MCMT: closes the gap RoomRegistrarDataMixin's header flags. RoomRegistrarData is the single instance
// CMSingletonDataFileManager<RoomRegistrarData> wraps for CMRoomRegistrar (see CMRoomRegistrar's own
// constructor, which builds one CMSingletonDataFileManager<RoomRegistrarData>("room_registrations", ...)).
// save() reads this.instance.codec() and encodes it -- for RoomRegistrarData that runs RoomRegistrarData
// .CODEC's reverse xmap, `x -> List.copyOf(x.registrationNodes.values())`, a direct read of
// registrationNodes with no lock. RoomRegistrarDataMixin already guards every other read/write of that
// map with registrationNodes' own intrinsic lock, but a static Codec lambda isn't an instance method
// Mixin can target by name, so save() was the one path left able to read the map while an H1 worker
// thread is inside a locked put() on a different dimension's room creation -- a mid-game or shutdown
// autosave (main thread) racing a concurrent structural modification (worker thread).
//
// Fixed by reimplementing save() to acquire that same registrationNodes lock -- exposed across mixin
// classes via RoomRegistrarDataAccessor, the standard Sponge Mixin accessor-interface pattern this
// codebase's own neoforge.mixins.json already uses (BlockEntityTypeAccessor, MappedRegistryAccessor) --
// before encoding, when the wrapped instance is a RoomRegistrarData. CMSingletonDataFileManager also
// backs other CMDataFile types with no known cross-dimension race, so the lock is skipped for those
// rather than synchronizing speculatively on a lock nothing else contends for.
@Mixin(CMSingletonDataFileManager.class)
abstract class CMSingletonDataFileManagerMixin<T extends CMDataFile & CodecHolder<T>> {
    @Shadow
    @Final
    protected MinecraftServer server;

    @Shadow
    @Final
    private String dataKey;

    @Shadow
    private T instance;

    @Inject(method = "save", at = @At("HEAD"), cancellable = true)
    private void mcmt$saveAtomic(CallbackInfo ci) {
        if (instance == null) {
            ci.cancel();
            return;
        }
        // Only the encode step reads instance's live state, so only it needs the lock; the disk write
        // (the slow part) happens after release, the same split CMKeyedDataFileManagerMixin.save() uses,
        // so a save doesn't hold up every dimension's room lookups (guarded by this same lock via
        // RoomRegistrarDataAccessor) for the duration of file I/O.
        CompoundTag fullData;
        if (instance instanceof RoomRegistrarDataAccessor accessor) {
            synchronized (accessor.mcmt$registrationNodes()) {
                fullData = mcmt$encode();
            }
        } else {
            fullData = mcmt$encode();
        }
        try {
            IOUtilities.writeNbtCompressed(fullData, instance.getDataLocation(server).resolve(dataKey + ".dat"));
        } catch (IOException e) {
            LoggingUtil.modLog().error("Failed to write data: " + e.getMessage(), e);
        }
        ci.cancel();
    }

    private CompoundTag mcmt$encode() {
        Path dir = instance.getDataLocation(server);
        DataFileUtil.ensureDirExists(dir);
        CompoundTag fullData = new CompoundTag();
        fullData.putString("version", instance.getDataVersion());
        // CompoundTag.store(String, Codec, Object), the one-line call the deployed mod jar uses, doesn't
        // exist on this repo's CompoundTag -- see CMKeyedDataFileManagerMixin's header for why. Expand it
        // to the encode call it wraps, using this fork's own encodeStart(...).getOrThrow() convention
        // rather than swallowing an encode failure into a silently truncated save.
        Tag encoded = instance.codec().encodeStart(NbtOps.INSTANCE, instance).getOrThrow();
        fullData.put("data", encoded);
        return fullData;
    }
}
