package me.jackcw.duels.arena;

import me.jackcw.jcore.storage.YamlRepository;
import org.bukkit.Location;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.IntPredicate;
import java.util.logging.Logger;

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
    private final ArenaManager arenaManager;
    private final Map<Integer, ArenaInstance> instances = new HashMap<>();
    private IntPredicate activeCheck = id -> false;

    public ArenaInstanceManager(YamlRepository<ArenaInstance> repository, ArenaManager arenaManager)
    {
        this.repository = repository;
        this.arenaManager = arenaManager;

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
        Arena arena = arenaManager.getArena(arenaId);
        if (arena == null || arena.getProvisioningMode() != ArenaProvisioningMode.STATIC)
            throw new IllegalStateException("Only STATIC arenas can register hand-built playable copies");
        int id = repository.reserveId();
        ArenaInstance instance = new ArenaInstance(id, arenaId);

        repository.save(instance);
        instances.put(id, instance);

        return instance;
    }

    public ArenaInstance createProvisionedInstance(int arenaId, int slotIndex, int templateRevision, ArenaStructureSize structureSize)
    {
        Arena arena = arenaManager.getArena(arenaId);
        if (arena == null || arena.getProvisioningMode() != ArenaProvisioningMode.DYNAMIC)
            throw new IllegalStateException("Only DYNAMIC arenas can provision copies");
        int id = repository.reserveId();
        ArenaInstance instance = ArenaInstance.provisioned(id, arenaId, slotIndex, templateRevision, structureSize);
        repository.save(instance);
        instances.put(id, instance);
        return instance;
    }

    public ArenaInstance createSource(int arenaId)
    {
        Arena arena = arenaManager.getArena(arenaId);
        if (arena == null || arena.getProvisioningMode() != ArenaProvisioningMode.DYNAMIC || getSource(arenaId) != null
                || getInstancesForArena(arenaId).stream().anyMatch(instance -> instance.getOrigin() == ArenaInstanceOrigin.MANUAL))
            throw new IllegalStateException("A DYNAMIC arena may have only one source");
        int id = repository.reserveId();
        ArenaInstance source = new ArenaInstance(id, arenaId);
        source.markSource();
        save(source);
        return source;
    }

    public ArenaInstance getSource(int arenaId)
    {
        for (ArenaInstance instance : instances.values())
            if (instance.getArenaId() == arenaId && instance.isSource())
                return instance;
        return null;
    }

    public void promoteToSource(ArenaInstance instance)
    {
        if (instance == null || instance.isProvisioned() || getSource(instance.getArenaId()) != null
                || arenaManager.getArena(instance.getArenaId()) == null
                || arenaManager.getArena(instance.getArenaId()).getProvisioningMode() != ArenaProvisioningMode.DYNAMIC)
            throw new IllegalStateException("Cannot promote this copy to a source");
        instance.markSource();
        save(instance);
    }

    /** Upgrade the old hybrid DYNAMIC arena: its single manual copy becomes a non-playable source. */
    public void migrateLegacyDynamicSources(Logger logger)
    {
        for (Arena arena : arenaManager.getArenas())
        {
            if (arena.getProvisioningMode() != ArenaProvisioningMode.DYNAMIC || getSource(arena.getId()) != null)
                continue;
            List<ArenaInstance> manual = getInstancesForArena(arena.getId()).stream()
                    .filter(instance -> instance.getOrigin() == ArenaInstanceOrigin.MANUAL).toList();
            if (manual.size() == 1)
            {
                promoteToSource(manual.getFirst());
                logger.info("Migrated DYNAMIC arena #" + arena.getId() + " copy #" + manual.getFirst().getId() + " to a non-playable source");
            }
            else if (manual.size() > 1)
                logger.warning("DYNAMIC arena #" + arena.getId() + " has multiple legacy manual copies; none will be playable. Choose one source and resolve the others before capture.");
        }
    }

    /** Explicit, non-destructive upgrade for an existing one-copy STATIC arena. */
    public boolean convertToDynamicSource(int arenaId, int sourceId)
    {
        Arena arena = arenaManager.getArena(arenaId);
        ArenaInstance source = instances.get(sourceId);
        if (arena == null || arena.getProvisioningMode() != ArenaProvisioningMode.STATIC || source == null
                || source.getArenaId() != arenaId || source.getOrigin() != ArenaInstanceOrigin.MANUAL
                || isActive(sourceId) || arena.getTemplateDefinition() != null
                || getInstancesForArena(arenaId).size() != 1)
            return false;

        // Save mode first: a crash between writes leaves the manual copy
        // non-playable, and startup migration finishes the promotion.
        arena.setProvisioningMode(ArenaProvisioningMode.DYNAMIC);
        arenaManager.save(arena);
        promoteToSource(source);
        return true;
    }

    public void setDynamicState(ArenaInstance instance, DynamicArenaState state)
    {
        if (instance == null || !instance.isProvisioned())
            throw new IllegalArgumentException("Only provisioned arena instances have a dynamic state");

        instance.setDynamicState(state);
        save(instance);
    }

    public ArenaInstanceMutationResult deleteInstance(int id)
    {
        ArenaInstance instance = instances.get(id);

        if (instance == null)
            return ArenaInstanceMutationResult.notFound();

        if (activeCheck.test(id))
            return ArenaInstanceMutationResult.inUse();

        if (instance.isSource())
        {
            Arena arena = arenaManager.getArena(instance.getArenaId());
            if (arena != null && (arena.getTemplateDefinition() != null || hasProvisionedInstances(instance.getArenaId())))
                return ArenaInstanceMutationResult.inUse();
        }

        // Only the retirement flow may remove a generated copy; it clears the
        // physical slot before freeing the persisted allocation.
        if (instance.isProvisioned() && instance.getDynamicState() != DynamicArenaState.RETIRING)
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

        if (activeCheck.test(id) || instance.isProvisioned())
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

        if (activeCheck.test(id) || instance.isProvisioned())
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

    public List<ArenaInstance> getProvisionedInstances()
    {
        List<ArenaInstance> result = new ArrayList<>();
        for (ArenaInstance instance : instances.values())
            if (instance.isProvisioned())
                result.add(instance);
        result.sort(Comparator.comparingInt(ArenaInstance::getId));
        return result;
    }

    /** Remove only a redundant persisted row; never clear its shared physical slot. */
    public boolean discardIdenticalDuplicate(ArenaInstance survivor, ArenaInstance duplicate)
    {
        if (survivor == null || duplicate == null || survivor.getId() == duplicate.getId()
                || instances.get(survivor.getId()) != survivor || instances.get(duplicate.getId()) != duplicate
                || isActive(survivor.getId()) || isActive(duplicate.getId())
                || !identicalReadyCopy(survivor, duplicate))
            return false;
        repository.delete(duplicate.getId());
        instances.remove(duplicate.getId());
        return true;
    }

    public static boolean identicalReadyCopy(ArenaInstance first, ArenaInstance second)
    {
        return first != null && second != null && first.isProvisioned() && second.isProvisioned()
                && first.getDynamicState() == DynamicArenaState.READY
                && second.getDynamicState() == DynamicArenaState.READY
                && first.getArenaId() == second.getArenaId()
                && Objects.equals(first.getDynamicSlotIndex(), second.getDynamicSlotIndex())
                && Objects.equals(first.getTemplateRevision(), second.getTemplateRevision())
                && Objects.equals(first.getStructureSize(), second.getStructureSize())
                && Objects.equals(first.getSpawn1(), second.getSpawn1())
                && Objects.equals(first.getSpawn2(), second.getSpawn2())
                && Objects.equals(first.getBoundsCorner1(), second.getBoundsCorner1())
                && Objects.equals(first.getBoundsCorner2(), second.getBoundsCorner2());
    }

    public boolean hasDuplicateSlot(ArenaInstance instance)
    {
        return instance != null && instance.isProvisioned() && getProvisionedInstances().stream()
                .anyMatch(other -> other.getId() != instance.getId()
                        && Objects.equals(other.getDynamicSlotIndex(), instance.getDynamicSlotIndex()));
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
            if (instance.getArenaId() == arenaId && isPlayable(instance) && instance.isReady())
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
            if (instance.getArenaId() == arenaId && isPlayable(instance) && instance.isReady() && !isActive(instance.getId()))
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

    public boolean hasProvisionedInstances(int arenaId)
    {
        for (ArenaInstance instance : instances.values())
            if (instance.getArenaId() == arenaId && instance.isProvisioned())
                return true;
        return false;
    }

    public boolean isPlayable(ArenaInstance instance)
    {
        Arena arena = arenaManager.getArena(instance.getArenaId());
        return arena != null && ((arena.getProvisioningMode() == ArenaProvisioningMode.STATIC
                && instance.getOrigin() == ArenaInstanceOrigin.MANUAL)
                || (arena.getProvisioningMode() == ArenaProvisioningMode.DYNAMIC && instance.isProvisioned()));
    }

    public void save(ArenaInstance instance)
    {
        instances.put(instance.getId(), instance);
        repository.save(instance);
    }
}
