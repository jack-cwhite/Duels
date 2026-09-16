package me.jackcw.duels.arena;

import me.jackcw.jcore.storage.YamlRepository;
import org.bukkit.Location;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntPredicate;

public final class ArenaManager
{
    private final YamlRepository<Arena> repository;
    private final Map<Integer, Arena> arenas = new HashMap<>();
    private IntPredicate activeCheck = id -> false;

    public ArenaManager(YamlRepository<Arena> repository)
    {
        this.repository = repository;

        for (Arena arena : repository.findAll())
            arenas.put(arena.getId(), arena);
    }

    public void setActiveCheck(IntPredicate activeCheck)
    {
        this.activeCheck = activeCheck != null ? activeCheck : id -> false;
    }

    public boolean isActive(int id)
    {
        return activeCheck.test(id);
    }

    public Arena createArena(String name)
    {
        int id = repository.reserveId();
        Arena arena = new Arena(id, name);

        repository.save(arena);
        arenas.put(id, arena);

        return arena;
    }

    public ArenaMutationResult deleteArena(int id)
    {
        Arena arena = arenas.get(id);

        if (arena == null)
            return ArenaMutationResult.notFound();

        if (activeCheck.test(id))
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

        if (activeCheck.test(id))
            return ArenaMutationResult.inUse();

        arena.setName(name);
        save(arena);

        return ArenaMutationResult.success(arena);
    }

    public ArenaMutationResult toggleEnabled(int id)
    {
        Arena arena = arenas.get(id);

        if (arena == null)
            return ArenaMutationResult.notFound();

        if (activeCheck.test(id))
            return ArenaMutationResult.inUse();

        arena.setEnabled(!arena.isEnabled());
        save(arena);

        return ArenaMutationResult.success(arena);
    }

    public ArenaMutationResult setSpawn(int id, int spawnNumber, Location location)
    {
        if (spawnNumber != 1 && spawnNumber != 2)
            throw new IllegalArgumentException("Spawn number must be 1 or 2");

        Arena arena = arenas.get(id);

        if (arena == null)
            return ArenaMutationResult.notFound();

        if (activeCheck.test(id))
            return ArenaMutationResult.inUse();

        if (spawnNumber == 1)
            arena.setSpawn1(location);
        else
            arena.setSpawn2(location);

        save(arena);

        return ArenaMutationResult.success(arena);
    }

    public ArenaMutationResult toggleKitAllowed(int arenaId, int kitId)
    {
        Arena arena = arenas.get(arenaId);

        if (arena == null)
            return ArenaMutationResult.notFound();

        if (activeCheck.test(arenaId))
            return ArenaMutationResult.inUse();

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
