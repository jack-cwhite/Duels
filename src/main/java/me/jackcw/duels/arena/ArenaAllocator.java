package me.jackcw.duels.arena;

import java.util.Optional;

public interface ArenaAllocator
{
    Optional<ArenaInstance> allocate();
    Optional<ArenaInstance> allocate(int arenaId);
    void release(ArenaInstance instance);
    boolean isAllocated(int instanceId);
}
