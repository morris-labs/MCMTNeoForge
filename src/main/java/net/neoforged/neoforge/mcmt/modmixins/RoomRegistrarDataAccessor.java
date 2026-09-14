/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.mcmt.modmixins;

import dev.compactmods.machines.room.graph.node.RoomRegistrationNode;
import java.util.Map;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

// MCMT: lets CMSingletonDataFileManagerMixin synchronize on RoomRegistrarData's own registrationNodes
// lock object from outside the class, the same lock RoomRegistrarDataMixin's put/get/etc. already use.
// See CMSingletonDataFileManagerMixin's header for why that's needed.
@Mixin(targets = "dev.compactmods.machines.room.RoomRegistrarData")
interface RoomRegistrarDataAccessor {
    @Accessor("registrationNodes")
    Map<String, RoomRegistrationNode> mcmt$registrationNodes();
}
