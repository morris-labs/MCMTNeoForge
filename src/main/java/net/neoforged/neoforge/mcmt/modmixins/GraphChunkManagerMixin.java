/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.mcmt.modmixins;

import dev.compactmods.feather.MemoryGraph;
import dev.compactmods.feather.edge.GraphEdge;
import dev.compactmods.machines.api.room.spatial.IRoomBoundaries;
import dev.compactmods.machines.api.room.spatial.IRoomChunks;
import dev.compactmods.machines.room.graph.GraphNodes;
import dev.compactmods.machines.room.graph.edge.RoomChunkEdge;
import dev.compactmods.machines.room.graph.node.RoomChunkNode;
import dev.compactmods.machines.room.graph.node.RoomReferenceNode;
import dev.compactmods.machines.room.spatial.GraphChunkManager;
import dev.compactmods.machines.room.spatial.RoomChunks;
import dev.compactmods.machines.util.MathUtil;
import java.lang.ref.Reference;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// MCMT: GraphChunkManager is the process-wide singleton (CompactMachines.chunkManager()) that maps chunk
// positions to the Compact Machines room that owns them -- the closest surviving equivalent of this fork's
// own mod-triage audit's "chunks" map (the audit's named CompactRoomProvider class doesn't exist in the
// deployed jar; see CMKeyedDataFileManagerMixin's header). findRoomByChunk is called every time a player
// (or any entity) changes chunk, from PlayerEventHandler and RoomEventHandler -- both a room's own
// dimension and every other dimension a player can be in call it, so this is directly on MCMT's H1
// cross-dimension path, not just a room-creation-time edge case.
//
// The audit framed this as one HashMap (chunks), but the deployed class actually couples two unsynchronized
// structures: chunks itself (a plain HashMap) and graph, a MemoryGraph wrapping a Guava
// ValueGraphBuilder.directed().build() MutableValueGraph -- Guava's standard graph implementations are
// documented as not thread-safe, and MemoryGraph's own node index only wraps ITS map in a
// ConcurrentHashMap, not the graph itself. calculateChunks writes both (graph.addNode/connectNodes, then
// chunks.put) as one logical unit; findRoomByChunk and get read both together. A type-swap of chunks alone
// would leave the coupled Guava graph -- reached by every one of these methods -- exactly as unsafe as
// before, so this needs the EnderStorageManager-style compound-operation lock, not a map-type swap: the
// whole body of all three methods, not just the map access, runs under chunks' own intrinsic lock.
// findRoomByChunk and get already resolve their return value (Optional<String> via findFirst(),
// IRoomChunks via collect()+orElseThrow()) before returning, so unlike RoomRegistrarData's stream-returning
// accessors, no separate snapshot step is needed here -- the lock covers the whole live computation.
@Mixin(GraphChunkManager.class)
abstract class GraphChunkManagerMixin {
    @Shadow
    @Final
    private MemoryGraph graph;

    @Shadow
    @Final
    private Map<ChunkPos, RoomChunkNode> chunks;

    @Inject(method = "calculateChunks", at = @At("HEAD"), cancellable = true)
    private void mcmt$calculateChunksAtomic(String roomCode, IRoomBoundaries boundaries, CallbackInfo ci) {
        synchronized (chunks) {
            AABB outer = boundaries.outerBounds();
            Set<ChunkPos> allInside = MathUtil.getChunksFromAABB(outer).collect(Collectors.toSet());
            RoomReferenceNode ref = new RoomReferenceNode(roomCode);
            graph.addNode(ref);

            for (ChunkPos c : allInside) {
                RoomChunkNode chunkNode = new RoomChunkNode(UUID.randomUUID(), new RoomChunkNode.Data(c));
                graph.addNode(chunkNode);
                graph.connectNodes(ref, chunkNode, new RoomChunkEdge(ref, chunkNode));
                chunks.put(c, chunkNode);
            }
        }
        ci.cancel();
    }

    @Inject(method = "findRoomByChunk", at = @At("HEAD"), cancellable = true)
    private void mcmt$findRoomByChunkAtomic(ChunkPos chunk, CallbackInfoReturnable<Optional<String>> cir) {
        synchronized (chunks) {
            RoomChunkNode chunkNode = chunks.get(chunk);
            if (chunkNode == null) {
                cir.setReturnValue(Optional.empty());
                return;
            }
            cir.setReturnValue(graph.inboundEdges(chunkNode, RoomReferenceNode.class)
                    .map(GraphEdge::source)
                    .map(Reference::get)
                    .filter(Objects::nonNull)
                    .map(RoomReferenceNode::code)
                    .findFirst());
        }
    }

    @Inject(method = "get", at = @At("HEAD"), cancellable = true)
    private void mcmt$getAtomic(String room, CallbackInfoReturnable<IRoomChunks> cir) {
        synchronized (chunks) {
            Optional<RoomReferenceNode> regNode = graph.nodes(RoomReferenceNode.class).filter(rn -> rn.code().equals(room)).findFirst();
            Set<ChunkPos> roomChunks = graph.outboundEdges(GraphNodes.ROOM_CHUNKS, regNode.orElseThrow())
                    .map(GraphEdge::target)
                    .map(Reference::get)
                    .filter(Objects::nonNull)
                    .peek(chunkNode -> chunks.putIfAbsent(chunkNode.data().chunk(), chunkNode))
                    .map(c -> c.data().chunk())
                    .collect(Collectors.toSet());
            cir.setReturnValue(new RoomChunks(roomChunks));
        }
    }
}
