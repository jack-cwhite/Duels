package me.jackcw.duels.arena;

import java.util.Optional;

public interface ArenaAllocator
{
    Optional<ArenaInstance> allocate();
    void release(ArenaInstance instance);
    boolean isAllocated(int arenaId);
}
