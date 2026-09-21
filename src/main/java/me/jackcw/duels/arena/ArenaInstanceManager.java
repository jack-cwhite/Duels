package me.jackcw.duels.arena;

import me.jackcw.jcore.storage.YamlRepository;
import org.bukkit.Location;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntPredicate;

/**
 * Owns the registered physical copies of every arena template.
 *
 * <p>An instance is "in use" when it is currently allocated to a match - see
 * {@link ArenaAllocator#isAllocated(int)} - which is a different question from
 * whether its owning {@link Arena} template has any instances at all. The
 * former blocks editing a specific instance; the latter blocks deleting the
 * template out from under it (see {@link ArenaManager#deleteArena(int)}).
 */
public final class ArenaInstanceManager
{
    private final YamlRepository<ArenaInstance> repository;
    private final Map<Integer, ArenaInstance> instances = new HashMap<>();
    private IntPredicate activeCheck = id -> false;

    public ArenaInstanceManager(YamlRepository<ArenaInstance> repository)
    {
        this.repository = repository;

        for (ArenaInstance instance : repository.findAll())
            instances.put(instance.getId(), instance);
    }

    public void setActiveCheck(IntPredicate activeCheck)
    {
        this.activeCheck = activeCheck != null ? activeCheck : id -> false;
    }

    public boolean isActive(int id)
    {
        return activeCheck.test(id);
    }

    public ArenaInstance createInstance(int arenaId)
    {
        int id = repository.reserveId();
        ArenaInstance instance = new ArenaInstance(id, arenaId);

        repository.save(instance);
        instances.put(id, instance);

        return instance;
    }

    public ArenaInstanceMutationResult deleteInstance(int id)
    {
        ArenaInstance instance = instances.get(id);

        if (instance == null)
            return ArenaInstanceMutationResult.notFound();

        if (activeCheck.test(id))
            return ArenaInstanceMutationResult.inUse();

        instances.remove(id);
        repository.delete(id);

        return ArenaInstanceMutationResult.success(instance);
    }

    public ArenaInstanceMutationResult setSpawn(int id, int spawnNumber, Location location)
    {
        if (spawnNumber != 1 && spawnNumber != 2)
            throw new IllegalArgumentException("Spawn number must be 1 or 2");

        ArenaInstance instance = instances.get(id);

        if (instance == null)
            return ArenaInstanceMutationResult.notFound();

        if (activeCheck.test(id))
            return ArenaInstanceMutationResult.inUse();

        if (spawnNumber == 1)
            instance.setSpawn1(location);
        else
            instance.setSpawn2(location);

        save(instance);

        return ArenaInstanceMutationResult.success(instance);
    }

    public ArenaInstanceMutationResult setBoundsCorner(int id, int corner, Location location)
    {
        if (corner != 1 && corner != 2)
            throw new IllegalArgumentException("Bounds corner must be 1 or 2");

        ArenaInstance instance = instances.get(id);

        if (instance == null)
            return ArenaInstanceMutationResult.notFound();

        if (activeCheck.test(id))
            return ArenaInstanceMutationResult.inUse();

        if (corner == 1)
            instance.setBoundsCorner1(location);
        else
            instance.setBoundsCorner2(location);

        save(instance);

        return ArenaInstanceMutationResult.success(instance);
    }

    public ArenaInstance getInstance(int id)
    {
        return instances.get(id);
    }

    public List<ArenaInstance> getInstances()
    {
        List<ArenaInstance> result = new ArrayList<>(instances.values());
        result.sort(Comparator.comparingInt(ArenaInstance::getId));

        return result;
    }

    public List<ArenaInstance> getInstancesForArena(int arenaId)
    {
        List<ArenaInstance> result = new ArrayList<>();

        for (ArenaInstance instance : instances.values())
            if (instance.getArenaId() == arenaId)
                result.add(instance);

        result.sort(Comparator.comparingInt(ArenaInstance::getId));

        return result;
    }

    /**
     * How many of an arena's instances have both spawns configured.
     *
     * <p>Readiness belongs to an instance rather than to the template, so
     * admin-facing listings report counts instead of a single yes/no for the
     * arena - "3 instances, 2 ready" is actionable where "Ready: Yes" hides
     * the third instance that still needs spawns.
     */
    public int countReady(int arenaId)
    {
        int ready = 0;

        for (ArenaInstance instance : instances.values())
            if (instance.getArenaId() == arenaId && instance.isReady())
                ready++;

        return ready;
    }

    /**
     * How many of an arena's instances are ready and not currently hosting a
     * match, which is the number of duels the arena could still take right now.
     */
    public int countFree(int arenaId)
    {
        int free = 0;

        for (ArenaInstance instance : instances.values())
            if (instance.getArenaId() == arenaId && instance.isReady() && !isActive(instance.getId()))
                free++;

        return free;
    }

    public boolean hasInstances(int arenaId)
    {
        for (ArenaInstance instance : instances.values())
            if (instance.getArenaId() == arenaId)
                return true;

        return false;
    }

    public void save(ArenaInstance instance)
    {
        instances.put(instance.getId(), instance);
        repository.save(instance);
    }
}
