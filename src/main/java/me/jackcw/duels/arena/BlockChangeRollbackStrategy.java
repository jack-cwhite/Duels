package me.jackcw.duels.arena;

import me.jackcw.duels.Duels;
import me.jackcw.duels.match.Match;
import me.jackcw.duels.match.MatchManager;
import me.jackcw.duels.match.MatchState;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The default, dependency-free {@link ArenaResetStrategy}: records every block
 * changed inside a match's bounds while it is {@link MatchState#IN_PROGRESS},
 * then replays those changes in reverse once the match ends.
 *
 * <p>Changes are tracked per {@link ArenaInstance} rather than per {@link Match}
 * - only one match can hold a given instance at a time, and this avoids
 * needing a stable match identity to key a map by, which {@code Match} does
 * not have.
 */
public final class BlockChangeRollbackStrategy implements Listener, ArenaResetStrategy
{
    private final Duels plugin;
    private final MatchManager matchManager;
    private final int maxTrackedChangesPerInstance;
    private final int blocksPerTick;

    private final Map<Integer, Deque<BlockState>> changesByInstanceId = new HashMap<>();

    public BlockChangeRollbackStrategy(Duels plugin)
    {
        this.plugin = plugin;
        this.matchManager = plugin.getMatchManager();
        this.maxTrackedChangesPerInstance = plugin.getSettings().arenaResetMaxTrackedBlockChanges();
        this.blocksPerTick = plugin.getSettings().arenaResetBlocksPerTick();
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event)
    {
        track(event.getBlockReplacedState());
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event)
    {
        track(event.getBlock().getState());
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event)
    {
        if (trackExplosion(event.blockList()))
            event.setYield(0f);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event)
    {
        if (trackExplosion(event.blockList()))
            event.setYield(0f);
    }

    /**
     * Suppressing the yield matters as much as recording the blocks: the
     * rollback puts the destroyed blocks back, but the item entities the
     * explosion spawned are not part of that and would be left lying around
     * the repaired arena as debris. A yield of zero means they are never
     * created in the first place.
     *
     * <p>Yield is a property of the whole explosion rather than of individual
     * blocks, so an explosion straddling the bounds edge suppresses drops for
     * its outside-bounds blocks too. That is preferred over leaving debris
     * inside the arena, and only affects blocks a duellist blew up anyway.
     */
    private boolean trackExplosion(List<Block> blocks)
    {
        boolean trackedAny = false;

        for (Block block : blocks)
            trackedAny |= track(block.getState());

        return trackedAny;
    }

    @Override
    public void reset(ArenaInstance instance, Runnable onComplete)
    {
        Deque<BlockState> changes = changesByInstanceId.remove(instance.getId());

        if (changes == null || changes.isEmpty())
        {
            onComplete.run();
            return;
        }

        BukkitTask[] taskHolder = new BukkitTask[1];

        taskHolder[0] = plugin.core().tasks().runSyncTimer(() ->
        {
            for (int i = 0; i < blocksPerTick && !changes.isEmpty(); i++)
                changes.pop().update(true, false);

            if (changes.isEmpty())
            {
                taskHolder[0].cancel();
                onComplete.run();
            }
        }, 0L, 1L);
    }

    private boolean track(BlockState previousState)
    {
        ArenaInstance instance = resolveInProgressInstance(previousState.getLocation());

        if (instance == null)
            return false;

        Deque<BlockState> changes = changesByInstanceId.computeIfAbsent(instance.getId(), id -> new ArrayDeque<>());

        // Past the ceiling, further changes are simply not tracked - the match
        // still plays out normally, but the arena may be left partially
        // changed. A hard cap exists so a chaotic TNT fight cannot grow this
        // map without bound.
        if (changes.size() >= maxTrackedChangesPerInstance)
            return false;

        changes.push(previousState);
        return true;
    }

    private ArenaInstance resolveInProgressInstance(Location location)
    {
        for (Match match : matchManager.getActiveMatches())
        {
            if (match.getState() != MatchState.IN_PROGRESS)
                continue;

            ArenaInstance instance = match.getArenaInstance();

            if (instance.contains(location))
                return instance;
        }

        return null;
    }
}
