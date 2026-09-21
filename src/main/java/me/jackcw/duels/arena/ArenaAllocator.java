package me.jackcw.duels.arena;

import java.util.concurrent.CompletableFuture;

public interface ArenaAllocator
{
    CompletableFuture<ArenaAllocationResult> allocate(ArenaSelection selection);
    void release(ArenaInstance instance);
    boolean isAllocated(int instanceId);
}
