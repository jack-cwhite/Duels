package me.jackcw.duels.arena;

import me.jackcw.jcore.storage.YamlRepository;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntPredicate;

/**
 * Owns arena templates - the policy an admin sets once and every registered
 * {@link ArenaInstance} of that template shares (name, boundary behaviour,
 * allowed kits, enabled/disabled).
 *
 * <p>None of that policy blocks a running match the way editing a spawn or a
 * bounds corner would, so unlike {@link ArenaInstanceManager}, mutating a
 * template is never refused because a match is in progress on one of its
 * instances - {@code BoundaryEnforcer} already re-reads boundary mode live,
 * for example. The one thing that is refused is deleting a template that
 * still has instances registered against it: those rows would otherwise be
 * orphaned, pointing at an {@code arenaId} that no longer exists.
 */
public final class ArenaManager
{
    private final YamlRepository<Arena> repository;
    private final Map<Integer, Arena> arenas = new HashMap<>();
    private IntPredicate hasInstancesCheck = id -> false;

    public ArenaManager(YamlRepository<Arena> repository)
    {
        this.repository = repository;

        for (Arena arena : repository.findAll())
            arenas.put(arena.getId(), arena);
    }

    public void setHasInstancesCheck(IntPredicate hasInstancesCheck)
    {
        this.hasInstancesCheck = hasInstancesCheck != null ? hasInstancesCheck : id -> false;
    }

    public boolean hasInstances(int id)
    {
        return hasInstancesCheck.test(id);
    }

    public Arena createArena(String name)
    {
        return createArena(name, ArenaProvisioningMode.STATIC);
    }

    public Arena createArena(String name, ArenaProvisioningMode mode)
    {
        int id = repository.reserveId();
        Arena arena = new Arena(id, name);
        arena.setProvisioningMode(mode);

        repository.save(arena);
        arenas.put(id, arena);

        return arena;
    }

    public ArenaMutationResult deleteArena(int id)
    {
        Arena arena = arenas.get(id);

        if (arena == null)
            return ArenaMutationResult.notFound();

        if (hasInstancesCheck.test(id))
            return ArenaMutationResult.inUse();

        arenas.remove(id);
        repository.delete(id);

        return ArenaMutationResult.success(arena);
    }

    public ArenaMutationResult rename(int id, String name)
    {
        Arena arena = arenas.get(id);

        if (arena == null)
            return ArenaMutationResult.notFound();

        arena.setName(name);
        save(arena);

        return ArenaMutationResult.success(arena);
    }

    public ArenaMutationResult toggleEnabled(int id)
    {
        Arena arena = arenas.get(id);

        if (arena == null)
            return ArenaMutationResult.notFound();

        arena.setEnabled(!arena.isEnabled());
        save(arena);

        return ArenaMutationResult.success(arena);
    }

    public ArenaMutationResult setBoundaryMode(int id, BoundaryMode mode, int graceSeconds)
    {
        Arena arena = arenas.get(id);

        if (arena == null)
            return ArenaMutationResult.notFound();

        arena.setBoundaryMode(mode);
        arena.setGraceSeconds(graceSeconds);
        save(arena);

        return ArenaMutationResult.success(arena);
    }

    public ArenaMutationResult setProvisioningMode(int id, ArenaProvisioningMode mode)
    {
        Arena arena = arenas.get(id);

        if (arena == null)
            return ArenaMutationResult.notFound();

        if (hasInstancesCheck.test(id) || arena.getTemplateDefinition() != null)
            return ArenaMutationResult.inUse();

        arena.setProvisioningMode(mode);
        save(arena);

        return ArenaMutationResult.success(arena);
    }

    public ArenaMutationResult toggleKitAllowed(int arenaId, int kitId)
    {
        Arena arena = arenas.get(arenaId);

        if (arena == null)
            return ArenaMutationResult.notFound();

        if (arena.isKitAllowed(kitId))
            arena.disallowKit(kitId);
        else
            arena.allowKit(kitId);

        save(arena);

        return ArenaMutationResult.success(arena);
    }

    public Arena getArena(int id)
    {
        return arenas.get(id);
    }

    public List<Arena> getArenas()
    {
        List<Arena> result = new ArrayList<>(arenas.values());
        result.sort(Comparator.comparingInt(Arena::getId));

        return result;
    }

    public void save(Arena arena)
    {
        arenas.put(arena.getId(), arena);
        repository.save(arena);
    }
}
