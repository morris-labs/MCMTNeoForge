/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.mcmt.modmixins;

// MCMT: shared ThreadLocal state for the Draconic Evolution fix below. StructureBlock.buildingLock is a
// public static field written from three BlockEntity classes (TileStructureBlock,
// TileEnergyCoreStabilizer, TileEnergyPylon) and read from two Block classes (StructureBlock itself and
// EnergyPylon) -- five different target classes touching the same flag -- so the ThreadLocal that
// replaces it has to live somewhere all five mixins can reach, rather than as a @Unique member of any one
// of them.
final class DraconicEvolutionThreadLocals {
    private DraconicEvolutionThreadLocals() {}

    static final ThreadLocal<Boolean> BUILDING_LOCK = ThreadLocal.withInitial(() -> false);
}
