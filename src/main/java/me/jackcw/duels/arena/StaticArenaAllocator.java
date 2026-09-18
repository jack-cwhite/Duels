package me.jackcw.duels.arena;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

public final class StaticArenaAllocator implements ArenaAllocator
{
    private final ArenaManager arenaManager;
    private final Set<Integer> allocated = new HashSet<>();

    public StaticArenaAllocator(ArenaManager arenaManager)
    {
        this.arenaManager = arenaManager;
    }

    public Optional<ArenaInstance> allocate()
    {
        for (Arena arena : arenaManager.getArenas())
            if (arena.isReady() && arena.isEnabled() && !allocated.contains(arena.getId()))
            {
                allocated.add(arena.getId());

                return Optional.of(new ArenaInstance(arena.getId(), arena.getSpawn1(), arena.getSpawn2()));
            }

        return Optional.empty();
    }

    public void release(ArenaInstance instance)
    {
        allocated.remove(instance.getArenaId());
    }

    public boolean isAllocated(int arenaId)
    {
        return allocated.contains(arenaId);
    }
}
