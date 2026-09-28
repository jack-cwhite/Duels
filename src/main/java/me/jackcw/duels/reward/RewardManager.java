package me.jackcw.duels.reward;

import me.jackcw.duels.Duels;
import me.jackcw.duels.arena.Arena;
import me.jackcw.duels.kit.Kit;
import me.jackcw.jcore.storage.YamlFile;
import org.bukkit.permissions.Permissible;

import java.util.List;
import java.util.logging.Logger;

/**
 * Owns the loaded {@link RewardTable} and the reading of rewards.yml.
 *
 * <p>Nothing here grants anything. This slice deliberately stops at working out what
 * a result <em>would</em> pay, so the whole resolution model can be configured,
 * previewed and argued with in game before any money moves.
 */
public final class RewardManager
{
    private final Duels plugin;
    private final Logger logger;
    private final YamlFile file;

    private RewardTable table = RewardTable.disabled();
    private RewardTableLoader.Result lastLoad;

    public RewardManager(Duels plugin)
    {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.file = plugin.core().files().yaml("rewards.yml", true);
        reload();
    }

    /**
     * Re-reads rewards.yml and replaces the table wholesale.
     *
     * <p>Replacing rather than mutating is what lets a duel that is mid-resolution keep
     * the table it started with. It also means a rejected file leaves rewards off rather
     * than leaving the previous table quietly in place: an admin who breaks the file and
     * reloads should see rewards stop, not see their edit appear to do nothing.
     */
    public void reload()
    {
        file.reload();
        file.updateDefaults();

        lastLoad = RewardTableLoader.load(file.getConfig(), this::resolveArena, this::resolveKit);
        table = lastLoad.table();

        for (String warning : lastLoad.warnings())
            logger.warning("rewards.yml: " + warning);

        if (!lastLoad.isValid())
        {
            logger.severe("rewards.yml was refused and no rewards will be paid until it is fixed. "
                    + lastLoad.errors().size() + " problem(s):");

            for (String error : lastLoad.errors())
                logger.severe("  - " + error);

            return;
        }

        if (!table.isEnabled())
            logger.info("Duels rewards are switched off in rewards.yml.");
    }

    public RewardTable table()
    {
        return table;
    }

    /** The errors and warnings from the most recent load, for {@code /duels rewards}. */
    public RewardTableLoader.Result lastLoad()
    {
        return lastLoad;
    }

    public RewardResolution resolve(RewardOutcome outcome, Integer kitId, Integer arenaId, Permissible player)
    {
        return table.resolve(outcome, kitId, arenaId, player);
    }

    /**
     * Accepts either an arena id or an arena name, trying the id first.
     *
     * <p>Both are supported because an id is stable across renames while a name is what
     * an admin actually recognises, and forcing one or the other would make this file
     * harder to edit than the problem justifies. The id is tried first so a numeric key
     * means what it looks like - but a server that has named an arena "1" still resolves,
     * because the name lookup runs when no arena holds that id.
     */
    private Integer resolveArena(String token)
    {
        Integer id = parseId(token);

        if (id != null && plugin.getArenaManager().getArena(id) != null)
            return id;

        for (Arena arena : plugin.getArenaManager().getArenas())
            if (arena.getName().equalsIgnoreCase(token))
                return arena.getId();

        return null;
    }

    private Integer resolveKit(String token)
    {
        Integer id = parseId(token);

        if (id != null && plugin.getKitManager().getKit(id) != null)
            return id;

        for (Kit kit : plugin.getKitManager().getKits())
            if (kit.getName().equalsIgnoreCase(token))
                return kit.getId();

        return null;
    }

    private static Integer parseId(String token)
    {
        try
        {
            return Integer.valueOf(token.trim());
        }
        catch (NumberFormatException exception)
        {
            return null;
        }
    }

    /** Exposed for {@code /duels rewards} so it can name the arenas and kits an override covers. */
    public List<Arena> arenas()
    {
        return plugin.getArenaManager().getArenas();
    }

    public List<Kit> kits()
    {
        return plugin.getKitManager().getKits();
    }
}
