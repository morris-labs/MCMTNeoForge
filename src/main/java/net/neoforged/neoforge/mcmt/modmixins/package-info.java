/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

/**
 * Mixins that fix thread-safety bugs found in third-party mod code under {@link net.neoforged.neoforge.mcmt
 * multi-core tick processing}.
 *
 * <p>MCMT's per-dimension level tick (H1) can run several dimensions concurrently on different worker threads. A
 * mod class shared across every dimension &mdash; typically a {@code Block} or {@code Fluid} singleton &mdash;
 * that keeps unsynchronized mutable state touched from a tick-path method races under H1 the same way several
 * vanilla classes did before they were patched directly. Mod code can't be patched directly, so each fix here is a
 * Sponge Mixin that redirects the unsafe field or operation to a thread-safe equivalent, registered in
 * {@code mcmt.mixins.json}.
 */
@FieldsAreNonnullByDefault
@MethodsReturnNonnullByDefault
@ParametersAreNonnullByDefault
package net.neoforged.neoforge.mcmt.modmixins;

import javax.annotation.ParametersAreNonnullByDefault;
import net.minecraft.FieldsAreNonnullByDefault;
import net.minecraft.MethodsReturnNonnullByDefault;
