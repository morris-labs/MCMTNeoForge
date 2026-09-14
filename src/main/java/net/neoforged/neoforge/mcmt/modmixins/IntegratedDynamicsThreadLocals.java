/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.mcmt.modmixins;

// MCMT: shared ThreadLocal state for the IntegratedDynamics fixes below. BlockCable.SKIP_NETWORK_INIT is
// a public static field read in BlockCableMixin and written in NetworkGenerationHelperMixin -- two
// different target classes touching the same flag -- so the ThreadLocal that replaces it has to live
// somewhere both mixins can reach, rather than as a @Unique member of either one.
final class IntegratedDynamicsThreadLocals {
    private IntegratedDynamicsThreadLocals() {}

    static final ThreadLocal<Boolean> SKIP_NETWORK_INIT = ThreadLocal.withInitial(() -> false);
}
