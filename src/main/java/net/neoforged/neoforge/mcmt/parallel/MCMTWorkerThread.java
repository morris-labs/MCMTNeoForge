/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.mcmt.parallel;

import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import net.neoforged.fml.util.thread.SidedThreadGroups;

/**
 * A worker of the MCMT tick pool.
 *
 * <p>The only reason this subclass exists is so that "am I a tick worker?" is an {@code instanceof} check rather
 * than a set lookup or a {@link ThreadLocal} read. That question is asked from
 * {@code BlockableEventLoop.isSameThread} and from {@code Level.getProfiler()}, both of which are called several
 * times per entity per tick, so it has to be as close to free as possible.
 *
 * <p>JMT-MCMT used a {@code Map<String, Set<Thread>>} tracker for this. It could not subclass the worker thread
 * because its pool was created from a coremod during class loading, where touching extra classes was unsafe. We
 * have no such constraint.
 *
 * <p>Constructed in {@link SidedThreadGroups#SERVER} rather than inheriting whatever thread group happened to
 * create the pool. Third-party mod code that guards against off-main-thread mutation sometimes checks
 * membership in this exact group -- NeoForge's own documented mechanism for "is this a legitimate server
 * thread" -- to decide whether to apply a mutation immediately; without this a worker fails that check even
 * though MCMT's own locking already makes the mutation safe. Found via a real instance: Sophisticated
 * Storage's {@code StorageBlockEntity.setChanged()} silently no-ops for a caller outside this group, so an
 * MCMT-ticked storage block entity never marked itself dirty for saving. The worker's name also deliberately
 * contains "server" for the same reason, against mods that check the thread's name instead of its group (see
 * the constructor below).
 */
public final class MCMTWorkerThread extends ForkJoinWorkerThread {
    MCMTWorkerThread(ForkJoinPool pool, String name) {
        // false: do not force the system classloader, matching the context-classloader behavior of the plain
        // single-arg super(pool) constructor this replaces.
        super(SidedThreadGroups.SERVER, pool, false);
        this.setName(name);
    }
}
