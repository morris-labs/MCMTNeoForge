/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.mcmt.serdes.filter;

import net.minecraft.world.entity.ExperienceOrb;
import net.neoforged.neoforge.mcmt.serdes.SerDesHookType;
import net.neoforged.neoforge.mcmt.serdes.pools.SerDesPool;
import org.jetbrains.annotations.Nullable;

/**
 * Experience orbs, which merge with each other and so must not tick beside a neighbour.
 *
 * <p>An {@code ExperienceOrb} tick calls {@code scanForEntities}, which scans a half-block box for other orbs
 * eligible to merge and folds one into the other: the survivor's {@code count} grows, the absorbed orb is
 * discarded. Two workers ticking both halves of that pair at once can each read stale state and each discard
 * (or fail to discard) an orb, the same duplication/loss shape {@link ItemEntityFilter} already fixes for item
 * entities -- found the same way, too: not a crash, just a real live server's
 * {@code PersistentEntitySectionManager} logging "wasn't found in section ... (destroying due to DISCARDED)"
 * thousands of times under a high-throughput mob farm, with the entity index left out of sync with what
 * actually merged.
 *
 * <p>{@link VanillaFilter} would otherwise wave experience orbs through as vanilla code, for the same reason
 * item entities were missing before {@link ItemEntityFilter}: the explicit list was assembled from what
 * crashed, and a lost or duplicated orb does not crash.
 *
 * <p>The pool is a block-position lock, same reasoning as {@link ItemEntityFilter}: two orbs merge only within
 * about half a block of each other (the scan box, and {@code tryMergeToExisting}'s one-block spawn-time box),
 * so their locked plus-shapes always overlap when they can merge and never do when they are several blocks
 * apart.
 */
public final class ExperienceOrbFilter implements SerDesFilter {
    private final SerDesPool posLock;

    public ExperienceOrbFilter(SerDesPool posLock) {
        this.posLock = posLock;
    }

    @Nullable
    @Override
    public SerDesPool poolFor(SerDesHookType hook, Class<?> type) {
        if (hook == SerDesHookType.ENTITY_TICK && ExperienceOrb.class.isAssignableFrom(type)) {
            return this.posLock;
        }
        return null;
    }
}
