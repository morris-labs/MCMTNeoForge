/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.mcmt.modmixins;

import com.google.common.collect.SetMultimap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.blay09.mods.waystones.api.Waystone;
import net.blay09.mods.waystones.api.WaystoneVisibility;
import net.blay09.mods.waystones.core.PlayerWaystoneManager;
import net.blay09.mods.waystones.core.WaystoneIndexManager;
import net.blay09.mods.waystones.core.WaystoneManagerImpl;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.PlayerTeam;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// MCMT: verified against the deployed jar (waystones-neoforge-1.21.1-21.1.40.jar), which decompiles
// identically to the audit notes here -- no stale line numbers or renamed members to reconcile.
//
// waystonesByTeamName (a Guava SetMultimap, not thread-safe) and globalWaystones (a plain
// LinkedHashSet, not thread-safe) are two static collections that together form the server's whole
// waystone index. Waystones are cross-dimension by design -- a player in one dimension warps to a
// waystone registered from another -- and every read/write path here is static, so H1 ticking two
// dimensions in parallel puts both collections in play from two threads at once: one dimension placing
// or breaking a waystone (add/remove) while another opens a teleport list (getGlobalTargets/
// getTeamTargets, which iterate the live collections). Unlike EnderStorageManager/DimStorageManager,
// the hazard here isn't a single get-then-create-then-put race -- it's unsynchronized structural
// mutation (LinkedHashSet resize, SetMultimap's backing map/value-set churn) racing unsynchronized
// iteration, which can corrupt either collection or throw ConcurrentModificationException, not just
// produce a stale read.
//
// Fix: one shared lock (mcmt$lock) rather than locking on either field individually, because
// visibilityChanged/playerTeamChanged/rebuildIndex touch both collections as one logical unit (e.g.
// rebuildIndex clears both, then repopulates via add) and a single lock is what makes that atomic.
// Only the five methods that directly touch the fields need reimplementing -- add, remove,
// rebuildIndex, getGlobalTargets and getTeamTargets. The other public entry points (visibilityChanged,
// waystoneRemoved, playerTeamChanged, getTargets) only call those five, so once the five are guarded,
// callers inherit the safety without needing their own mixin: rebuildIndex's reimplementation calls
// the shadowed add(), which resolves to add()'s own synchronized-and-cancelled injection and
// re-enters mcmt$lock (Java intrinsic locks are reentrant), so the loop body isn't duplicated here.
@Mixin(WaystoneIndexManager.class)
abstract class WaystoneIndexManagerMixin {
    @Unique
    private static final Object mcmt$lock = new Object();

    @Shadow
    @Final
    private static SetMultimap<String, UUID> waystonesByTeamName;

    @Shadow
    @Final
    private static Set<UUID> globalWaystones;

    @Shadow
    private static void add(MinecraftServer server, Waystone waystone) {
        throw new UnsupportedOperationException();
    }

    @Inject(method = "rebuildIndex", at = @At("HEAD"), cancellable = true)
    private static void mcmt$rebuildIndexAtomic(MinecraftServer server, CallbackInfo ci) {
        synchronized (mcmt$lock) {
            waystonesByTeamName.clear();
            globalWaystones.clear();
            for (Waystone waystone : WaystoneManagerImpl.get(server).getWaystones().toList()) {
                add(server, waystone);
            }
        }
        ci.cancel();
    }

    @Inject(method = "add", at = @At("HEAD"), cancellable = true)
    private static void mcmt$addAtomic(MinecraftServer server, Waystone waystone, CallbackInfo ci) {
        synchronized (mcmt$lock) {
            if (waystone.getVisibility() == WaystoneVisibility.GLOBAL) {
                globalWaystones.add(waystone.getWaystoneUid());
            } else if (waystone.getVisibility() == WaystoneVisibility.TEAM) {
                PlayerWaystoneManager.getOwnerUsername(waystone, server)
                        .map(ownerUsername -> server.getScoreboard().getPlayersTeam(ownerUsername))
                        .<String>map(PlayerTeam::getName)
                        .ifPresent(teamName -> waystonesByTeamName.put(teamName, waystone.getWaystoneUid()));
            }
        }
        ci.cancel();
    }

    @Inject(method = "remove", at = @At("HEAD"), cancellable = true)
    private static void mcmt$removeAtomic(Waystone waystone, CallbackInfo ci) {
        synchronized (mcmt$lock) {
            globalWaystones.remove(waystone.getWaystoneUid());
            waystonesByTeamName.values().remove(waystone.getWaystoneUid());
        }
        ci.cancel();
    }

    @Inject(method = "getGlobalTargets", at = @At("HEAD"), cancellable = true)
    private static void mcmt$getGlobalTargetsAtomic(ServerPlayer player, CallbackInfoReturnable<Collection<Waystone>> cir) {
        synchronized (mcmt$lock) {
            if (globalWaystones.isEmpty()) {
                cir.setReturnValue(List.of());
                return;
            }
            WaystoneManagerImpl store = WaystoneManagerImpl.get(player.level().getServer());
            ArrayList<Waystone> result = new ArrayList<>();
            for (UUID waystoneId : globalWaystones) {
                store.getWaystoneById(waystoneId)
                        .filter(waystone -> waystone.getVisibility() == WaystoneVisibility.GLOBAL)
                        .ifPresent(result::add);
            }
            cir.setReturnValue(result);
        }
    }

    @Inject(method = "getTeamTargets", at = @At("HEAD"), cancellable = true)
    private static void mcmt$getTeamTargetsAtomic(ServerPlayer player, CallbackInfoReturnable<Collection<Waystone>> cir) {
        PlayerTeam team = player.getTeam();
        if (team == null) {
            cir.setReturnValue(List.of());
            return;
        }
        synchronized (mcmt$lock) {
            Set<UUID> waystoneIds = waystonesByTeamName.get(team.getName());
            if (waystoneIds.isEmpty()) {
                cir.setReturnValue(List.of());
                return;
            }
            WaystoneManagerImpl store = WaystoneManagerImpl.get(player.level().getServer());
            ArrayList<Waystone> result = new ArrayList<>();
            for (UUID waystoneId : waystoneIds) {
                store.getWaystoneById(waystoneId)
                        .filter(waystone -> waystone.getVisibility() == WaystoneVisibility.TEAM)
                        .ifPresent(result::add);
            }
            cir.setReturnValue(result);
        }
    }
}
