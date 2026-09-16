/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.mcmt.serdes.filter;

import java.util.Set;
import net.neoforged.neoforge.mcmt.serdes.SerDesHookType;
import net.neoforged.neoforge.mcmt.serdes.pools.SerDesPool;
import org.jetbrains.annotations.Nullable;

/**
 * Modded block entities that read and clear a nearby {@code ItemEntity} the same way a hopper does, found by
 * chasing a real-world item-count explosion rather than by auditing source ahead of time.
 *
 * <p>Mob Grinding Utils' {@code TileEntityAbsorptionHopper} is the confirmed case: its tick calls
 * {@code getItem()} on an {@code ItemEntity} in range, writes the stack into its own container, then calls
 * {@code setItem()}/{@code remove()} on that same entity — unsynchronised, against a class this platform does
 * not depend on at compile time, so it cannot be named the way {@link HopperFilter} names vanilla's. Left to
 * {@link DefaultFilter}, it chunk-locks; the {@code ItemEntity} it is draining ticks under {@link ItemEntityFilter}'s
 * position lock instead, a different table, so the two never exclude each other. A farm running this block
 * measured 0 to 126,075 item entities in one soak with MCMT on, and stayed bounded with MCMT off.
 *
 * <p>Matched by name rather than {@code isAssignableFrom} because the class isn't on this project's classpath.
 * Not overridable, for the same reason {@link HopperFilter} isn't: a config entry that put this back on
 * {@code FREE} would reopen the exact race.
 */
public final class ModdedHopperFilter implements SerDesFilter {
    private static final Set<String> BLOCK_ENTITY_NAMES = Set.of("mob_grinding_utils.tile.TileEntityAbsorptionHopper");

    private final SerDesPool posLock;

    public ModdedHopperFilter(SerDesPool posLock) {
        this.posLock = posLock;
    }

    @Nullable
    @Override
    public SerDesPool poolFor(SerDesHookType hook, Class<?> type) {
        if (hook == SerDesHookType.BLOCK_ENTITY_TICK && BLOCK_ENTITY_NAMES.contains(type.getName())) {
            return this.posLock;
        }
        return null;
    }
}
