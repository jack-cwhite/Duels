package me.jackcw.duels.diagnostics;

import me.jackcw.duels.Duels;
import me.jackcw.duels.arena.ArenaInstance;
import me.jackcw.duels.arena.BlockBox;
import me.jackcw.duels.arena.BlockChangeRollbackStrategy;
import me.jackcw.duels.arena.DynamicArenaState;
import me.jackcw.duels.arena.DynamicArenaSlotManager;
import me.jackcw.duels.match.Match;
import org.bukkit.Location;
import org.bukkit.Bukkit;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Reports Duels' live internal state, so an administrator can check it instead
 * of inferring it.
 *
 * <p>This exists because of a test-plan item that could not honestly be signed
 * off: "no projectiles, dropped items, temporary effects, spectators, pending
 * players, countdowns or edit sessions remain after their owning flow ends."
 * Every one of those lives in a private collection or is an entity nobody can
 * count by hand, so the check was recorded as an inference from nothing having
 * visibly gone wrong. A verification step that is tedious and imprecise gets
 * signed off on optimism, which makes it worse than no step at all.
 *
 * <p>The design point is the <em>baseline and compare</em> pair rather than the
 * snapshot. Almost every leak worth catching is a difference - a count that
 * should have returned to what it was before a match and did not - so asking an
 * admin to write numbers down and subtract them by hand is where the accuracy is
 * lost. Taking a baseline, playing a duel, and asking for a diff turns the whole
 * item into two commands and a line of output.
 *
 * <p>Kept as a permanent admin tool rather than removed after the test pass.
 * Duels is meant to be installed by strangers, who have the same visibility
 * problem and no access to the source.
 */
public final class DuelsDiagnostics
{
    private final Duels plugin;

    // One baseline per admin, so two people can run their own comparisons at the
    // same time without overwriting each other's. Deliberately not persisted: a
    // baseline is only meaningful against the server uptime it was taken in.
    private final Map<UUID, Snapshot> baselines = new HashMap<>();

    public DuelsDiagnostics(Duels plugin)
    {
        this.plugin = plugin;
    }

    /**
     * A point-in-time count of everything Duels owns that should return to zero,
     * or to its previous value, once the flows using it finish.
     *
     * <p>Entities are counted per arena bounds rather than per world, because a
     * world's entity count moves for reasons that have nothing to do with a
     * duel - a passing mob or another player's dropped item would swamp the
     * signal.
     */
    public record Snapshot(
            int matches,
            int pendingPlayers,
            int pendingRespawnRestores,
            int liveCountdowns,
            int spectatorSessions,
            int editSessions,
            int captureDrafts,
            int savedPlayerStates,
            int dynamicOccupiedSlots,
            int dynamicReservedSlots,
            int dynamicMaximumSlots,
            int pendingChallenges,
            int rematchWindows,
            int boundaryTrackedPlayers,
            boolean boundaryCheckTaskActive,
            int trackedInstances,
            int trackedBlockChanges,
            int resettingInstances,
            int arenaProjectiles,
            int arenaDroppedItems,
            int arenaEffectClouds,
            int arenaPrimedTnt,
            int arenaOtherEntities,
            int pendingTasks)
    {
    }

    public Snapshot snapshot()
    {
        List<Match> matches = plugin.getMatchManager().getActiveMatches();

        int liveCountdowns = 0;

        for (Match match : matches)
            if (match.getCountdown() != null)
                liveCountdowns++;

        EntityCounts entities = countArenaEntities();
        BlockChangeRollbackStrategy rollback = rollbackStrategy();
        DynamicArenaSlotManager.Capacity dynamicCapacity = plugin.getDynamicArenaSlotManager().capacity();

        return new Snapshot(
                matches.size(),
                plugin.getMatchManager().getPendingPlayerCount(),
                plugin.getMatchManager().getPendingRespawnRestoreCount(),
                liveCountdowns,
                plugin.getSpectatorManager().getSessions().size(),
                plugin.getArenaEditManager().getSessions().size(),
                plugin.getArenaEditManager().getCaptureDraftCount(),
                plugin.getPlayerStateManager().getSavedStateCount(),
                dynamicCapacity.occupied(),
                dynamicCapacity.reserved(),
                dynamicCapacity.maximum(),
                plugin.getChallengeManager().getChallengeCount(),
                plugin.getRematchManager().getContextCount(),
                plugin.getBoundaryEnforcer().getTrackedPlayerCount(),
                plugin.getBoundaryEnforcer().isCheckTaskActive(),
                rollback == null ? 0 : rollback.getTrackedInstanceCount(),
                rollback == null ? 0 : rollback.getTrackedChangeCount(),
                rollback == null ? 0 : rollback.getResettingInstanceCount(),
                entities.projectiles,
                entities.droppedItems,
                entities.effectClouds,
                entities.primedTnt,
                entities.other,
                pendingPluginTasks());
    }

    /**
     * Counts, per category, every entity standing inside any arena's bounds.
     *
     * <p>Players are excluded. A duellist or spectator is supposed to be in
     * there, and the whole point of the count is to spot what is left behind
     * rather than who is present.
     */
    private EntityCounts countArenaEntities()
    {
        EntityCounts counts = new EntityCounts();

        for (ArenaInstance instance : plugin.getArenaInstanceManager().getInstances())
        {
            BlockBox bounds = instance.getBoundsBox();

            if (bounds == null)
                continue;

            // The far faces need maxCorner rather than max: the maximum block
            // occupies space up to its own outer face, so a BoundingBox built
            // from max would miss anything standing in the last block.
            BoundingBox box = new BoundingBox(
                    bounds.minX(), bounds.minY(), bounds.minZ(),
                    bounds.maxCornerX(), bounds.maxCornerY(), bounds.maxCornerZ());

            for (Entity entity : bounds.world().getNearbyEntities(box))
            {
                if (entity instanceof Player)
                    continue;

                if (entity instanceof Projectile)
                    counts.projectiles++;
                else if (entity instanceof Item)
                    counts.droppedItems++;
                else if (entity instanceof AreaEffectCloud)
                    counts.effectClouds++;
                else if (entity instanceof TNTPrimed)
                    counts.primedTnt++;
                else
                    counts.other++;
            }
        }

        return counts;
    }

    /**
     * Scheduled tasks belonging to this plugin. Counted because countdowns,
     * rollback batches and retirement cleanup are all tasks, and a task that
     * outlives its flow is a leak the entity counts cannot show.
     */
    private int pendingPluginTasks()
    {
        int count = 0;

        for (BukkitTask task : Bukkit.getScheduler().getPendingTasks())
            if (plugin.equals(task.getOwner()))
                count++;

        return count;
    }

    /**
     * The rollback strategy's counters, or {@code null} when the configured
     * strategy is not the block-change one - a schematic-based strategy has no
     * tracked changes to report, and pretending it has zero would read as a
     * meaningful zero rather than as "not applicable".
     */
    private BlockChangeRollbackStrategy rollbackStrategy()
    {
        return plugin.getArenaResetStrategy() instanceof BlockChangeRollbackStrategy strategy ? strategy : null;
    }

    /**
     * The snapshot as display lines. Written as plain formatted text rather than
     * message keys: this is a table of numbers an admin reads once while
     * testing, not user-facing copy anybody would want to translate or restyle,
     * and twenty keys in {@code messages.yml} would obscure the ones that matter.
     */
    public List<String> describe(Snapshot snapshot)
    {
        List<String> lines = new ArrayList<>();

        lines.add("&e&lDuels diagnostics");
        lines.add(line("Live matches", snapshot.matches()));
        lines.add(line("Live countdowns", snapshot.liveCountdowns()));
        lines.add(line("Pending players", snapshot.pendingPlayers()));
        lines.add(line("Pending respawn restores", snapshot.pendingRespawnRestores()));
        lines.add(line("Pending challenges", snapshot.pendingChallenges()));
        lines.add(line("Open rematch windows", snapshot.rematchWindows()));
        lines.add(line("Spectator sessions", snapshot.spectatorSessions()));
        lines.add(line("Edit sessions", snapshot.editSessions()));
        lines.add(line("Capture drafts", snapshot.captureDrafts()));
        lines.add(line("Saved player states", snapshot.savedPlayerStates()));
        lines.add("&7Fallback spawn: &f" + describeLocation(plugin.getSettings().fallbackSpawn()));
        lines.add("&7Dynamic arena slots: &f" + snapshot.dynamicOccupiedSlots() + "&7 occupied, &f"
                + snapshot.dynamicReservedSlots() + "&7 reserved, &f"
                + (snapshot.dynamicMaximumSlots() - snapshot.dynamicOccupiedSlots() - snapshot.dynamicReservedSlots())
                + "&7 available / &f" + snapshot.dynamicMaximumSlots() + "&7 maximum");
        lines.add(line("Boundary-tracked players", snapshot.boundaryTrackedPlayers()));
        lines.add(line("Boundary check task active", snapshot.boundaryCheckTaskActive()));
        lines.add(line("Instances holding rollback data", snapshot.trackedInstances()));
        lines.add(line("Tracked block changes", snapshot.trackedBlockChanges()));
        lines.add(line("Instances resetting", snapshot.resettingInstances()));
        lines.add(line("Scheduled plugin tasks", snapshot.pendingTasks()));
        lines.add("&7In arena bounds (players excluded):");
        lines.add(line("  Projectiles", snapshot.arenaProjectiles()));
        lines.add(line("  Dropped items", snapshot.arenaDroppedItems()));
        lines.add(line("  Effect clouds", snapshot.arenaEffectClouds()));
        lines.add(line("  Primed TNT", snapshot.arenaPrimedTnt()));
        lines.add(line("  Other entities", snapshot.arenaOtherEntities()));

        addLiveDetail(lines);

        return lines;
    }

    /**
     * The identities behind the counts: which matches are running and what state
     * each arena instance is in.
     *
     * <p>Not part of {@link Snapshot}, because a name or a state is not something
     * you subtract. The counts answer "did something leak"; this answers "which
     * one", which is the next question every time the answer to the first is yes.
     */
    private void addLiveDetail(List<String> lines)
    {
        List<Match> matches = plugin.getMatchManager().getActiveMatches();

        if (!matches.isEmpty())
        {
            lines.add("&7Matches:");

            for (Match match : matches)
                lines.add("&7  instance &f" + match.getArenaInstance().getId()
                        + " &7(arena &f" + match.getArenaInstance().getArenaId() + "&7) "
                        + "&f" + match.getState()
                        + " &7- &f" + nameOf(match.getPlayer1Id()) + " &7vs &f" + nameOf(match.getPlayer2Id()));
        }

        Map<DynamicArenaState, Integer> dynamicStates = new EnumMap<>(DynamicArenaState.class);

        for (ArenaInstance instance : plugin.getArenaInstanceManager().getInstances())
            if (instance.isProvisioned() && instance.getDynamicState() != null)
                dynamicStates.merge(instance.getDynamicState(), 1, Integer::sum);

        if (!dynamicStates.isEmpty())
        {
            lines.add("&7Dynamic instances:");

            dynamicStates.forEach((state, count) -> lines.add("&7  " + state + ": &f" + count));
        }
    }

    /**
     * Offline duellists are shown by UUID rather than skipped: a match holding a
     * player who is no longer online is exactly the kind of stuck state this
     * command is for, so hiding it would defeat the purpose.
     */
    private static String nameOf(UUID playerId)
    {
        Player player = Bukkit.getPlayer(playerId);

        return player != null ? player.getName() : playerId.toString();
    }

    public void saveBaseline(UUID adminId, Snapshot snapshot)
    {
        baselines.put(adminId, snapshot);
    }

    public Snapshot getBaseline(UUID adminId)
    {
        return baselines.get(adminId);
    }

    /**
     * The differences between a baseline and now, as display lines, omitting
     * everything that did not change.
     *
     * <p>Omitting the unchanged rows is the point: after a duel has ended, a
     * correct comparison is an empty list, so anything printed at all is
     * something to look at. A full table with eighteen zeroes and one non-zero
     * row buries exactly the row that matters.
     */
    public List<String> compare(Snapshot baseline, Snapshot now)
    {
        List<String> lines = new ArrayList<>();

        addDelta(lines, "Live matches", baseline.matches(), now.matches());
        addDelta(lines, "Live countdowns", baseline.liveCountdowns(), now.liveCountdowns());
        addDelta(lines, "Pending players", baseline.pendingPlayers(), now.pendingPlayers());
        addDelta(lines, "Pending respawn restores", baseline.pendingRespawnRestores(), now.pendingRespawnRestores());
        addDelta(lines, "Pending challenges", baseline.pendingChallenges(), now.pendingChallenges());
        addDelta(lines, "Open rematch windows", baseline.rematchWindows(), now.rematchWindows());
        addDelta(lines, "Spectator sessions", baseline.spectatorSessions(), now.spectatorSessions());
        addDelta(lines, "Edit sessions", baseline.editSessions(), now.editSessions());
        addDelta(lines, "Capture drafts", baseline.captureDrafts(), now.captureDrafts());
        addDelta(lines, "Saved player states", baseline.savedPlayerStates(), now.savedPlayerStates());
        addDelta(lines, "Dynamic occupied slots", baseline.dynamicOccupiedSlots(), now.dynamicOccupiedSlots());
        addDelta(lines, "Dynamic slot reservations", baseline.dynamicReservedSlots(), now.dynamicReservedSlots());
        addDelta(lines, "Dynamic maximum slots", baseline.dynamicMaximumSlots(), now.dynamicMaximumSlots());
        addDelta(lines, "Boundary-tracked players", baseline.boundaryTrackedPlayers(), now.boundaryTrackedPlayers());
        addDelta(lines, "Boundary check task active", baseline.boundaryCheckTaskActive(), now.boundaryCheckTaskActive());
        addDelta(lines, "Instances holding rollback data", baseline.trackedInstances(), now.trackedInstances());
        addDelta(lines, "Tracked block changes", baseline.trackedBlockChanges(), now.trackedBlockChanges());
        addDelta(lines, "Instances resetting", baseline.resettingInstances(), now.resettingInstances());
        addDelta(lines, "Scheduled plugin tasks", baseline.pendingTasks(), now.pendingTasks());
        addDelta(lines, "Projectiles in bounds", baseline.arenaProjectiles(), now.arenaProjectiles());
        addDelta(lines, "Dropped items in bounds", baseline.arenaDroppedItems(), now.arenaDroppedItems());
        addDelta(lines, "Effect clouds in bounds", baseline.arenaEffectClouds(), now.arenaEffectClouds());
        addDelta(lines, "Primed TNT in bounds", baseline.arenaPrimedTnt(), now.arenaPrimedTnt());
        addDelta(lines, "Other entities in bounds", baseline.arenaOtherEntities(), now.arenaOtherEntities());

        return lines;
    }

    private static void addDelta(List<String> lines, String label, int before, int after)
    {
        if (before == after)
            return;

        int delta = after - before;

        lines.add("&7" + label + ": &f" + before + " &7-> &f" + after
                + " &7(" + (delta > 0 ? "&c+" : "&a") + delta + "&7)");
    }

    private static void addDelta(List<String> lines, String label, boolean before, boolean after)
    {
        if (before == after)
            return;

        lines.add("&7" + label + ": &f" + before + " &7-> &f" + after);
    }

    /**
     * Shared with {@code DuelsCommand} so a location reads the same wherever
     * Duels reports one, and tolerates a null world because a location that
     * came off disk may name a world the server no longer has - which is
     * exactly the case an admin is trying to diagnose when they ask.
     */
    public static String describeLocation(Location location)
    {
        if (location == null)
            return "unknown";

        String world = location.getWorld() == null ? "<missing world>" : location.getWorld().getName();

        return world + " " + location.getBlockX() + ", " + location.getBlockY() + ", " + location.getBlockZ();
    }

    private static String line(String label, int value)
    {
        return "&7" + label + ": &f" + value;
    }

    private static String line(String label, boolean value)
    {
        return "&7" + label + ": &f" + value;
    }

    private static final class EntityCounts
    {
        private int projectiles;
        private int droppedItems;
        private int effectClouds;
        private int primedTnt;
        private int other;
    }
}
