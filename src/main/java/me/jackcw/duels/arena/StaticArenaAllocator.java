package me.jackcw.duels.arena;

import java.util.HashSet;
import java.util.concurrent.CompletableFuture;
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
    private final DynamicArenaProvisioner provisioner;
    private final Set<Integer> allocated = new HashSet<>();

    public StaticArenaAllocator(ArenaManager arenaManager, ArenaInstanceManager arenaInstanceManager, DynamicArenaProvisioner provisioner)
    {
        this.arenaManager = arenaManager;
        this.arenaInstanceManager = arenaInstanceManager;
        this.provisioner = provisioner;
    }

    @Override
    public CompletableFuture<ArenaAllocationResult> allocate(ArenaSelection selection)
    {
        ArenaSelection requested = selection != null ? selection : ArenaSelection.any();
        ArenaInstance existing = allocateExisting(requested.arenaId());
        if (existing != null)
            return CompletableFuture.completedFuture(ArenaAllocationResult.success(existing));

        if (!requested.isAny())
        {
            Arena arena = arenaManager.getArena(requested.arenaId());
            if (arena == null)
                return CompletableFuture.completedFuture(ArenaAllocationResult.failure(ArenaAllocationResult.Status.ARENA_NOT_FOUND));
            if (!arena.isEnabled())
                return CompletableFuture.completedFuture(ArenaAllocationResult.failure(ArenaAllocationResult.Status.ARENA_DISABLED));
            return provision(arena);
        }

        for (Arena arena : arenaManager.getArenas())
            if (arena.isEnabled() && arena.canProvisionDynamically())
                return provision(arena);

        return CompletableFuture.completedFuture(ArenaAllocationResult.failure(ArenaAllocationResult.Status.NO_ARENA_AVAILABLE));
    }

    private ArenaInstance allocateExisting(Integer requestedArenaId)
    {
        for (ArenaInstance instance : arenaInstanceManager.getInstances())
        {
            if (requestedArenaId != null && instance.getArenaId() != requestedArenaId)
                continue;

            if (allocated.contains(instance.getId()) || !instance.isReady())
                continue;

            Arena arena = arenaManager.getArena(instance.getArenaId());

            if (arena == null || !arena.isEnabled())
                continue;

            allocated.add(instance.getId());

            if (instance.isProvisioned())
                provisioner.retainChunks(instance);

            return instance;
        }

        return null;
    }

    private CompletableFuture<ArenaAllocationResult> provision(Arena arena)
    {
        if (!arena.canProvisionDynamically())
            return CompletableFuture.completedFuture(ArenaAllocationResult.failure(ArenaAllocationResult.Status.TEMPLATE_UNAVAILABLE));

        return provisioner.provision(arena).thenApply(result ->
        {
            if (result.status() != DynamicArenaProvisionResult.Status.SUCCESS)
                return ArenaAllocationResult.failure(switch (result.status())
                {
                    case TEMPLATE_UNAVAILABLE -> ArenaAllocationResult.Status.TEMPLATE_UNAVAILABLE;
                    case CAPACITY_REACHED -> ArenaAllocationResult.Status.CAPACITY_REACHED;
                    case FAILED -> ArenaAllocationResult.Status.PROVISIONING_FAILED;
                    case SUCCESS -> throw new IllegalStateException("Success handled above");
                });

            ArenaInstance instance = result.instance();
            allocated.add(instance.getId());
            provisioner.retainChunks(instance);
            return ArenaAllocationResult.success(instance);
        });
    }

    @Override
    public void release(ArenaInstance instance)
    {
        allocated.remove(instance.getId());
        if (instance.isProvisioned())
            provisioner.releaseChunks(instance);
    }

    @Override
    public boolean isAllocated(int instanceId)
    {
        return allocated.contains(instanceId);
    }
}
