package me.jackcw.duels;

import me.jackcw.duels.arena.*;
import me.jackcw.duels.challenge.ChallengeExpiryHandler;
import me.jackcw.duels.challenge.ChallengeManager;
import me.jackcw.duels.commands.DuelCommand;
import me.jackcw.duels.commands.DuelsCommand;
import me.jackcw.duels.diagnostics.DuelsDiagnostics;
import me.jackcw.duels.kit.Kit;
import me.jackcw.duels.kit.KitManager;
import me.jackcw.duels.kit.KitSerializer;
import me.jackcw.duels.listener.ArenaEditListener;
import me.jackcw.duels.listener.MatchListener;
import me.jackcw.duels.listener.PlayerStateListener;
import me.jackcw.duels.listener.SpectatorListener;
import me.jackcw.duels.match.MatchManager;
import me.jackcw.duels.match.MatchResultDispatcher;
import me.jackcw.duels.message.ActionMessenger;
import me.jackcw.duels.menu.admin.*;
import me.jackcw.duels.menu.admin.arena.ArenaDetailMenu;
import me.jackcw.duels.menu.admin.arena.ArenaInstanceDetailMenu;
import me.jackcw.duels.menu.admin.arena.ArenaInstanceListMenu;
import me.jackcw.duels.menu.admin.arena.ArenaKitMenu;
import me.jackcw.duels.menu.admin.arena.ArenaListMenu;
import me.jackcw.duels.menu.admin.arena.ArenaMainMenu;
import me.jackcw.duels.menu.admin.kit.KitDetailMenu;
import me.jackcw.duels.menu.admin.kit.KitEditMenu;
import me.jackcw.duels.menu.admin.kit.KitEffectDetailMenu;
import me.jackcw.duels.menu.admin.kit.KitEffectsMenu;
import me.jackcw.duels.menu.admin.kit.KitListMenu;
import me.jackcw.duels.menu.admin.kit.KitMainMenu;
import me.jackcw.duels.menu.user.KitSelectorMenu;
import me.jackcw.duels.menu.user.KitViewMenu;
import me.jackcw.duels.menu.user.LeaderboardMenu;
import me.jackcw.duels.menu.user.SpectateMenu;
import me.jackcw.duels.menu.user.StatsProfileMenu;
import me.jackcw.duels.player.PlayerStateManager;
import me.jackcw.duels.rematch.RematchManager;
import me.jackcw.duels.spectator.SpectatorManager;
import me.jackcw.duels.stats.MatchRecord;
import me.jackcw.duels.stats.MatchRecordSerializer;
import me.jackcw.duels.stats.StatsManager;
import me.jackcw.duels.stats.StatsResultConsumer;
import me.jackcw.duels.stats.StatsStorageType;
import me.jackcw.jcore.JCore;
import me.jackcw.jcore.storage.YamlFile;
import me.jackcw.jcore.storage.YamlRepository;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.Bukkit;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

public class Duels extends JavaPlugin
{
    private JCore jCore;

    private DuelsSettings settings;
    private ActionMessenger actionMessenger;
    private ArenaManager arenaManager;
    private ArenaInstanceManager arenaInstanceManager;
    private ArenaAllocator arenaAllocator;
    private ArenaTemplateManager arenaTemplateManager;
    private DynamicArenaSlotManager dynamicArenaSlotManager;
    private DynamicArenaWorldManager dynamicArenaWorldManager;
    private DynamicArenaProvisioner dynamicArenaProvisioner;
    private DynamicArenaRecovery dynamicArenaRecovery;
    private ArenaEditManager arenaEditManager;
    private BoundaryEnforcer boundaryEnforcer;
    private ArenaAccessGuard arenaAccessGuard;
    private MatchInterferenceGuard matchInterferenceGuard;
    private DuelsDiagnostics diagnostics;
    private BlockChangeRollbackStrategy arenaResetStrategy;
    private KitManager kitManager;
    private ChallengeManager challengeManager;
    private RematchManager rematchManager;
    private MatchManager matchManager;
    private PlayerStateManager playerStateManager;
    private SpectatorManager spectatorManager;
    private StatsManager statsManager;
    private MatchResultDispatcher matchResultDispatcher;

    private YamlRepository<Arena> arenaRepository;
    private YamlRepository<ArenaInstance> arenaInstanceRepository;
    private YamlRepository<Kit> kitRepository;

    private AdminMainMenu adminMainMenu;
    private ArenaMainMenu arenaMainMenu;
    private ArenaListMenu arenaListMenu;
    private ArenaKitMenu arenaKitMenu;
    private ArenaDetailMenu arenaDetailMenu;
    private ArenaInstanceListMenu arenaInstanceListMenu;
    private ArenaInstanceDetailMenu arenaInstanceDetailMenu;
    private KitMainMenu kitMainMenu;
    private KitListMenu kitListMenu;
    private KitDetailMenu kitDetailMenu;
    private KitEditMenu kitEditMenu;
    private KitViewMenu kitViewMenu;
    private KitSelectorMenu kitSelectorMenu;
    private KitEffectsMenu kitEffectsMenu;
    private KitEffectDetailMenu kitEffectDetailMenu;
    private LeaderboardMenu leaderboardMenu;
    private StatsProfileMenu statsProfileMenu;
    private SpectateMenu spectateMenu;

    @Override
    public void onEnable()
    {
        jCore = JCore.create(this, DatabaseConfigLoader.load(this));
        jCore.initialize();

        settings = new DuelsSettings(jCore.config(), getLogger());

        initializeSerializers();
        initializeRepositories();
        initializeManagers();
        initializeMenus();

        registerCommands();
        registerEvents();

        // Last, so it reports against the messages.yml the plugin actually
        // ended up with rather than whatever was on disk before defaults were
        // merged in.
        actionMessenger.audit();
    }

    /**
     * Offers Duels' void generator to anything that creates worlds, so an admin
     * can make an empty world to put arenas in with
     * {@code /mv create <name> normal -g Duels} or the equivalent.
     *
     * <p>Duels already generates its dynamic arena world this way. Exposing the
     * same generator costs nothing and means a static-arena world does not
     * require a separate void-generator plugin, which matters because building
     * arenas in a void is the normal way servers do this and Duels is meant to
     * work without extra dependencies.
     */
    @Override
    public ChunkGenerator getDefaultWorldGenerator(String worldName, String id)
    {
        return new VoidArenaChunkGenerator();
    }

    @Override
    public void onDisable()
    {
        if (matchManager != null)
            matchManager.shutdown(isServerStopping());

        // After the matches, which eject their own spectators - this catches
        // anyone left over, such as a spectator of a match that has already
        // been cleaned up.
        if (spectatorManager != null)
            spectatorManager.shutdown(isServerStopping());

        if (boundaryEnforcer != null)
            boundaryEnforcer.shutdown();

        // Rematch windows are runtime-only; cancelling their tasks here keeps a
        // reload-style disable from leaving orphaned scheduled work behind.
        if (rematchManager != null)
            rematchManager.clear();

        if (arenaEditManager != null)
            for (UUID uuid : new ArrayList<>(arenaEditManager.getSessions().keySet()))
                arenaEditManager.end(uuid);

        if (jCore != null)
            jCore.shutdown();
    }

    public JCore core()
    {
        return jCore;
    }

    public DuelsSettings getSettings()
    {
        return settings;
    }

    public ActionMessenger getActionMessenger()
    {
        return actionMessenger;
    }

    /**
     * Re-reads the two files an administrator edits by hand, then re-checks
     * messages.yml for the problems {@link ActionMessenger#audit()} reports.
     *
     * <p>Kits, arenas and menus.yml are deliberately not reloaded: they are
     * live-edited through the GUI and commands and persisted on every change,
     * so re-reading them would only replace memory with what it just wrote.
     *
     * <p>This lives here rather than being written out at each call site
     * because both {@code /duels reload} and the admin menu's reload button
     * need exactly the same steps, and a copy that forgot to re-audit would
     * silently stop reporting a broken button until the next restart.
     */
    public void reloadConfiguration()
    {
        jCore.config().reload();
        settings.reload(jCore.config(), getLogger());
        if (!rematchManager.isEnabled())
        {
            rematchManager.clear();
            challengeManager.removeAllRematches();
        }
        jCore.messages().reload();

        actionMessenger.forgetWarnings();
        actionMessenger.audit();
    }

    public ArenaManager getArenaManager()
    {
        return arenaManager;
    }

    public ArenaInstanceManager getArenaInstanceManager()
    {
        return arenaInstanceManager;
    }

    public ArenaAllocator getArenaAllocator()
    {
        return arenaAllocator;
    }

    public ArenaTemplateManager getArenaTemplateManager()
    {
        return arenaTemplateManager;
    }

    public DynamicArenaSlotManager getDynamicArenaSlotManager()
    {
        return dynamicArenaSlotManager;
    }

    public DynamicArenaWorldManager getDynamicArenaWorldManager()
    {
        return dynamicArenaWorldManager;
    }

    public DynamicArenaProvisioner getDynamicArenaProvisioner()
    {
        return dynamicArenaProvisioner;
    }

    public ArenaEditManager getArenaEditManager()
    {
        return arenaEditManager;
    }

    public BoundaryEnforcer getBoundaryEnforcer()
    {
        return boundaryEnforcer;
    }

    public ArenaAccessGuard getArenaAccessGuard()
    {
        return arenaAccessGuard;
    }

    public MatchInterferenceGuard getMatchInterferenceGuard()
    {
        return matchInterferenceGuard;
    }

    public DuelsDiagnostics getDiagnostics()
    {
        return diagnostics;
    }

    public ArenaResetStrategy getArenaResetStrategy()
    {
        return arenaResetStrategy;
    }

    public KitManager getKitManager()
    {
        return kitManager;
    }

    public RematchManager getRematchManager()
    {
        return rematchManager;
    }

    public ChallengeManager getChallengeManager()
    {
        return challengeManager;
    }

    public MatchManager getMatchManager()
    {
        return matchManager;
    }

    public PlayerStateManager getPlayerStateManager()
    {
        return playerStateManager;
    }

    public StatsManager getStatsManager()
    {
        return statsManager;
    }

    public MatchResultDispatcher getMatchResultDispatcher()
    {
        return matchResultDispatcher;
    }

    public AdminMainMenu getAdminMainMenu()
    {
        return adminMainMenu;
    }

    public ArenaMainMenu getArenaMainMenu()
    {
        return arenaMainMenu;
    }

    public ArenaListMenu getArenaListMenu()
    {
        return arenaListMenu;
    }

    public ArenaDetailMenu getArenaDetailMenu()
    {
        return arenaDetailMenu;
    }

    public ArenaInstanceListMenu getArenaInstanceListMenu()
    {
        return arenaInstanceListMenu;
    }

    public ArenaInstanceDetailMenu getArenaInstanceDetailMenu()
    {
        return arenaInstanceDetailMenu;
    }

    public KitMainMenu getKitMainMenu()
    {
        return kitMainMenu;
    }

    public KitListMenu getKitListMenu()
    {
        return kitListMenu;
    }

    public KitDetailMenu getKitDetailMenu()
    {
        return kitDetailMenu;
    }

    public KitEditMenu getKitEditMenu()
    {
        return kitEditMenu;
    }

    public KitSelectorMenu getKitSelectorMenu()
    {
        return kitSelectorMenu;
    }

    public LeaderboardMenu getLeaderboardMenu()
    {
        return leaderboardMenu;
    }

    public StatsProfileMenu getStatsProfileMenu()
    {
        return statsProfileMenu;
    }

    public SpectateMenu getSpectateMenu()
    {
        return spectateMenu;
    }

    public SpectatorManager getSpectatorManager()
    {
        return spectatorManager;
    }

    public boolean isServerStopping()
    {
        try
        {
            return Bukkit.isStopping();
        }
        catch (RuntimeException ignored)
        {
            return false;
        }
    }

    private void initializeSerializers()
    {
        jCore.serializers().register(Arena.class, new ArenaSerializer(jCore.serializers()));
        jCore.serializers().register(ArenaInstance.class, new ArenaInstanceSerializer(jCore.serializers()));
        jCore.serializers().register(Kit.class, new KitSerializer(jCore.serializers()));
        jCore.serializers().register(MatchRecord.class, new MatchRecordSerializer());
    }

    private void initializeRepositories()
    {
        arenaRepository = new YamlRepository<>(
                jCore.files().yaml("arenas.yml"), jCore.serializers(), "arenas", Arena.class, Arena::getId
        );

        arenaInstanceRepository = new YamlRepository<>(
                jCore.files().yaml("arena-instances.yml"), jCore.serializers(), "instances", ArenaInstance.class, ArenaInstance::getId
        );

        kitRepository = new YamlRepository<>(
                jCore.files().yaml("kits.yml"), jCore.serializers(), "kits", Kit.class, Kit::getId
        );

        ArenaInstanceMigrator.migrate(
                jCore.files().yaml("arenas.yml"), arenaInstanceRepository, jCore.serializers(), getLogger()
        );
    }

    private void initializeMenus()
    {
        YamlFile menusFile = jCore.files().yaml("menus.yml", true);
        menusFile.updateDefaults();
        migrateDefaultMenuLayout(menusFile);

        jCore.menus().configure(menusFile);

        arenaKitMenu = new ArenaKitMenu(this);
        arenaInstanceDetailMenu = new ArenaInstanceDetailMenu(this);
        arenaInstanceListMenu = new ArenaInstanceListMenu(this, arenaInstanceDetailMenu);
        arenaDetailMenu = new ArenaDetailMenu(this, arenaKitMenu, arenaInstanceListMenu, arenaInstanceDetailMenu);
        arenaListMenu = new ArenaListMenu(this, arenaDetailMenu);
        arenaMainMenu = new ArenaMainMenu(this, arenaListMenu, arenaDetailMenu);

        kitEditMenu = new KitEditMenu(this);
        kitEffectDetailMenu = new KitEffectDetailMenu(this);
        kitEffectsMenu = new KitEffectsMenu(this, kitEffectDetailMenu);
        kitDetailMenu = new KitDetailMenu(this, kitEditMenu, kitEffectsMenu);
        kitListMenu = new KitListMenu(this, kitDetailMenu);
        kitMainMenu = new KitMainMenu(this, kitListMenu);
        kitViewMenu = new KitViewMenu(this);
        kitSelectorMenu = new KitSelectorMenu(this, kitViewMenu);
        statsProfileMenu = new StatsProfileMenu(this);
        leaderboardMenu = new LeaderboardMenu(this);
        spectateMenu = new SpectateMenu(this);

        adminMainMenu = new AdminMainMenu(this, arenaMainMenu, kitMainMenu);
    }

    /** Upgrade only the old bundled layout; do not overwrite custom positions. */
    void migrateDefaultMenuLayout(YamlFile file)
    {
        boolean changed = false;
        changed |= moveBackToOwnRow(file, "arena-detail", new int[] {0, 2, 4, 6, 8, 10, 12},
                new String[] {"rename", "instances", "create-instance", "delete", "toggle-available", "kits", "boundary"});
        changed |= migrateInstanceDetailLayout(file);
        changed |= migrateArenaListStatusLore(file);

        if (file.getConfig().getInt("kit-edit.items.save.slot", -1) == 51)
        {
            file.getConfig().set("kit-edit.items.save.slot", 44);
            changed = true;
        }

        if (changed)
        {
            file.save();
            getLogger().info("Moved default menu actions off the Back navigation row; custom layouts were left intact.");
        }
    }

    private boolean moveBackToOwnRow(YamlFile file, String menu, int[] slots, String[] keys)
    {
        if (file.getConfig().getInt(menu + ".rows", -1) != 2)
            return false;

        for (int i = 0; i < keys.length; i++)
            if (file.getConfig().getInt(menu + ".items." + keys[i] + ".slot", -1) != slots[i])
                return false;

        file.getConfig().set(menu + ".rows", 3);
        return true;
    }

    private boolean migrateInstanceDetailLayout(YamlFile file)
    {
        String menu = "arena-instance-detail";
        String[] keys = {"spawn1", "spawn2", "edit-mode", "delete", "bounds1", "bounds2",
                "structure1", "structure2", "capture", "setup-status"};
        int[] oldSlots = {1, 3, 5, 7, 10, 12, 0, 2, 4, 8};
        int[] newSlots = {1, 3, 19, 25, 5, 7, 10, 12, 14, 21};
        if (file.getConfig().getInt(menu + ".rows", -1) != 2)
            return false;

        for (int i = 0; i < keys.length; i++)
            if (file.getConfig().getInt(menu + ".items." + keys[i] + ".slot", -1) != oldSlots[i])
                return false;

        file.getConfig().set(menu + ".rows", 4);
        for (int i = 0; i < keys.length; i++)
            file.getConfig().set(menu + ".items." + keys[i] + ".slot", newSlots[i]);
        return true;
    }

    /**
     * updateDefaults() only adds missing keys, so a saved file that already has
     * arena-list.entry.lore keeps its old text forever - including the removed
     * {instances}/{ready}/{free} placeholders - unless we replace it here.
     */
    private boolean migrateArenaListStatusLore(YamlFile file)
    {
        List<String> oldLore = List.of(
                "&7ID: &f{id}",
                "&7Type: &f{mode}",
                "&7Playable copies: &f{instances}",
                "&7Ready: &f{ready}",
                "&7Free right now: &f{free}",
                "&7Enabled: {enabled}",
                "&eClick to manage");

        if (!oldLore.equals(file.getConfig().getStringList("arena-list.entry.lore")))
            return false;

        file.getConfig().set("arena-list.entry.lore", List.of(
                "&7ID: &f{id}",
                "&7Type: &f{mode}",
                "&7{status}",
                "&7Enabled: {enabled}",
                "&eClick to manage"));
        return true;
    }

    private void initializeManagers()
    {
        actionMessenger = new ActionMessenger(jCore.messages(), settings, getLogger());
        kitManager = new KitManager(kitRepository);
        arenaManager = new ArenaManager(arenaRepository);

        // Dynamic arena instance records store world-relative locations, so the
        // dynamic arena world must already be loaded before we deserialize them
        // below - otherwise YamlRepository can't resolve the world reference and
        // silently drops the entry (and with it, that slot's occupancy). A
        // missing/corrupt dynamic world must not take the rest of the plugin
        // down with it: static arenas, commands and menus should still come up,
        // with dynamic provisioning simply unavailable (and its instance
        // records skipped-and-logged by YamlRepository) until it's restored.
        dynamicArenaSlotManager = new DynamicArenaSlotManager(this);
        dynamicArenaWorldManager = new DynamicArenaWorldManager(dynamicArenaSlotManager, getLogger());
        if (dynamicArenaSlotManager.hasPersistedLayout())
        {
            try
            {
                dynamicArenaWorldManager.getOrCreateWorld();
            }
            catch (RuntimeException exception)
            {
                getLogger().log(Level.SEVERE, "Could not load the dynamic arena world; dynamic arenas will be "
                        + "unavailable until this is resolved and the server is restarted. Static arenas are unaffected.", exception);
            }
        }

        arenaInstanceManager = new ArenaInstanceManager(arenaInstanceRepository, arenaManager, getLogger());
        arenaInstanceManager.migrateLegacyDynamicSources(getLogger());
        arenaTemplateManager = new ArenaTemplateManager(this, new PaperArenaStructureProvider());
        dynamicArenaProvisioner = new DynamicArenaProvisioner(this);
        arenaAllocator = new StaticArenaAllocator(arenaManager, arenaInstanceManager, dynamicArenaProvisioner);
        dynamicArenaRecovery = new DynamicArenaRecovery(this);
        dynamicArenaRecovery.recover();
        arenaEditManager = new ArenaEditManager(this);
        challengeManager = new ChallengeManager(jCore.tasks(), settings, new ChallengeExpiryHandler(jCore.messages())::onExpire);
        rematchManager = new RematchManager(jCore.tasks(), settings);
        playerStateManager = new PlayerStateManager(jCore.files().yaml("playerstates.yml", true), jCore.serializers(), settings);
        statsManager = new StatsManager(this);

        // StatsManager already registered its migration by this point, but
        // JCore only connects and runs migrations on first real use - left
        // alone, a bad database.yml would not surface until the first match
        // ends and the result is silently lost. Stats are a core feature, not
        // an optional one a plugin might never touch, so it is worth forcing
        // that first connection now instead, where a failure lands in the
        // startup console rather than after someone's already played a duel.
        if (settings.statsStorage() == StatsStorageType.SQL)
        {
            try
            {
                jCore.database();
                statsManager.validateStorage();
            }
            catch (RuntimeException exception)
            {
                getLogger().log(Level.SEVERE, "Could not connect to the stats database or run its migrations; "
                        + "match results will fail to save until this is resolved. Check database.yml. If this is "
                        + "the pre-Phase-5 test schema, delete the test database/schema and restart.", exception);
            }
        }

        // Registration order is delivery order, and stats go first as the
        // authoritative record of what happened. Note that this orders the
        // *calls*, not their completion: each consumer does its own work
        // asynchronously, so no consumer may assume an earlier one has finished
        // writing. If one ever genuinely needs to, it has to say so explicitly
        // rather than rely on this ordering.
        matchResultDispatcher = new MatchResultDispatcher();
        matchResultDispatcher.register(new StatsResultConsumer(statsManager));

        matchManager = new MatchManager(this);

        // Ordering matters: SpectatorManager reads MatchManager, and
        // BoundaryEnforcer resolves spectators through SpectatorManager.
        spectatorManager = new SpectatorManager(this, jCore.files().yaml("spectators.yml"));
        boundaryEnforcer = new BoundaryEnforcer(this);
        arenaAccessGuard = new ArenaAccessGuard(this);
        matchInterferenceGuard = new MatchInterferenceGuard(this);
        arenaResetStrategy = new BlockChangeRollbackStrategy(this);

        // Last: it reads every manager above it and owns none of them.
        diagnostics = new DuelsDiagnostics(this);

        arenaManager.setHasInstancesCheck(arenaInstanceManager::hasInstances);
        arenaInstanceManager.setActiveCheck(arenaAllocator::isAllocated);
    }

    private void registerEvents()
    {
        getServer().getPluginManager().registerEvents(new PlayerStateListener(this), this);
        getServer().getPluginManager().registerEvents(new MatchListener(this), this);
        getServer().getPluginManager().registerEvents(new ArenaEditListener(this), this);
        getServer().getPluginManager().registerEvents(new SpectatorListener(this), this);
        getServer().getPluginManager().registerEvents(new ArenaContainmentGuard(this), this);
        getServer().getPluginManager().registerEvents(arenaAccessGuard, this);
        getServer().getPluginManager().registerEvents(matchInterferenceGuard, this);
        getServer().getPluginManager().registerEvents(arenaResetStrategy, this);
    }

    private void registerCommands()
    {
        jCore.commands().register(new DuelsCommand(this).build());
        jCore.commands().register(new DuelCommand(this).build());
    }
}
