package me.jackcw.duels.rematch;

import me.jackcw.duels.DuelsSettings;
import me.jackcw.jcore.task.TaskManager;
import org.bukkit.scheduler.BukkitTask;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Owns who is currently allowed to ask for a rematch, and for how long.
 *
 * <p>It deliberately does not create, claim or accept anything. A rematch is a
 * challenge with the arena already decided, so `ChallengeManager` keeps its
 * existing job - pair uniqueness, expiry tasks, claiming during asynchronous
 * arena allocation, and removal - and this class only answers "may these two
 * play each other again, on which arena, and for how much longer". Duplicating
 * the claim/allocation workflow here is the mistake this split exists to avoid,
 * because that workflow is the part that is genuinely hard to get right.
 *
 * <p>All state is runtime-only and main-thread-only. A rematch window that
 * survived a restart would point at an arena whose configuration may have
 * changed and at players who are no longer online, so there is nothing worth
 * persisting.
 */
public final class RematchManager
{
    private static final long TICKS_PER_SECOND = 20L;

    private final TaskManager tasks;
    private final DuelsSettings settings;

    /**
     * Both participants map to the same context instance, so invalidating
     * either player's entry has to remove both - see {@link #discard}.
     */
    private final Map<UUID, RematchContext> byPlayer = new HashMap<>();
    private final Map<RematchContext, BukkitTask> expiryTasks = new HashMap<>();

    public RematchManager(TaskManager tasks, DuelsSettings settings)
    {
        this.tasks = tasks;
        this.settings = settings;
    }

    /**
     * Whether rematches are switched on at all. Read live rather than cached at
     * construction so {@code /duels reload} takes effect without a restart.
     */
    public boolean isEnabled()
    {
        return settings.rematchExpirySeconds() > 0;
    }

    /**
     * Opens a rematch window for a finished match, replacing whatever either
     * player had before.
     *
     * <p>Returns null when rematches are disabled, so the caller does not have
     * to check first.
     */
    public RematchContext register(UUID player1Id, String player1Name, UUID player2Id, String player2Name, int arenaId)
    {
        int seconds = settings.rematchExpirySeconds();

        if (seconds <= 0)
            return null;

        invalidate(player1Id);
        invalidate(player2Id);

        RematchContext context = new RematchContext(
                player1Id, player1Name, player2Id, player2Name, arenaId, Instant.now().plusSeconds(seconds));

        byPlayer.put(player1Id, context);
        byPlayer.put(player2Id, context);
        expiryTasks.put(context, tasks.runSyncLater(() -> discard(context), seconds * TICKS_PER_SECOND));

        return context;
    }

    /**
     * The window this player may currently act on, or null.
     *
     * <p>An expired-but-not-yet-swept context is treated as absent and cleaned
     * up here rather than trusted, because the expiry task runs a tick at a time
     * and a click can land in between.
     */
    public RematchContext get(UUID playerId)
    {
        RematchContext context = byPlayer.get(playerId);

        if (context == null)
            return null;

        if (context.isExpired())
        {
            discard(context);
            return null;
        }

        return context;
    }

    public void invalidate(UUID playerId)
    {
        RematchContext context = byPlayer.get(playerId);

        if (context != null)
            discard(context);
    }

    public void invalidateAll(UUID... playerIds)
    {
        for (UUID playerId : playerIds)
            if (playerId != null)
                invalidate(playerId);
    }

    /**
     * Live rematch windows, counting each pair once. Reported by
     * {@code /duels diagnostics}: a window outliving its expiry task is exactly
     * the kind of leak that is invisible from in game otherwise.
     */
    public int getContextCount()
    {
        return new LinkedHashSet<>(byPlayer.values()).size();
    }

    /** Called on plugin disable; contexts are not meant to survive a restart. */
    public void clear()
    {
        Set<RematchContext> contexts = new LinkedHashSet<>(byPlayer.values());

        for (RematchContext context : contexts)
            discard(context);

        byPlayer.clear();
        expiryTasks.clear();
    }

    /**
     * The single way a context leaves the manager, so its expiry task is always
     * cancelled with it and both players' entries always go together.
     */
    private void discard(RematchContext context)
    {
        BukkitTask expiryTask = expiryTasks.remove(context);

        if (expiryTask != null)
            expiryTask.cancel();

        byPlayer.remove(context.player1Id(), context);
        byPlayer.remove(context.player2Id(), context);
    }
}
