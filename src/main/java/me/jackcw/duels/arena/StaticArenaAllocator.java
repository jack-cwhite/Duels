package me.jackcw.duels.arena;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Claims one registered {@link ArenaInstance} per match from the pool an
 * admin has physically built and registered, releasing it when the match
 * ends. This is the "several pre-built physical copies" allocation strategy -
 * see {@code docs/ROADMAP.md} Phase 3 for the alternatives considered.
 */
public final class StaticArenaAllocator implements ArenaAllocator
{
    private final ArenaManager arenaManager;
    private final ArenaInstanceManager arenaInstanceManager;
    private final Set<Integer> allocated = new HashSet<>();

    public StaticArenaAllocator(ArenaManager arenaManager, ArenaInstanceManager arenaInstanceManager)
    {
        this.arenaManager = arenaManager;
        this.arenaInstanceManager = arenaInstanceManager;
    }

    @Override
    public Optional<ArenaInstance> allocate()
    {
        for (ArenaInstance instance : arenaInstanceManager.getInstances())
        {
            if (allocated.contains(instance.getId()) || !instance.isReady())
                continue;

            Arena arena = arenaManager.getArena(instance.getArenaId());

            if (arena == null || !arena.isEnabled())
                continue;

            allocated.add(instance.getId());

            return Optional.of(instance);
        }

        return Optional.empty();
    }

    @Override
    public void release(ArenaInstance instance)
    {
        allocated.remove(instance.getId());
    }

    @Override
    public boolean isAllocated(int instanceId)
    {
        return allocated.contains(instanceId);
    }
}
