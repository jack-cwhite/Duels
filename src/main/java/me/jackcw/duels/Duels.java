package me.jackcw.duels;

import me.jackcw.duels.arena.*;
import me.jackcw.duels.challenge.ChallengeExpiryHandler;
import me.jackcw.duels.challenge.ChallengeManager;
import me.jackcw.duels.commands.DuelCommand;
import me.jackcw.duels.commands.DuelsCommand;
import me.jackcw.duels.kit.Kit;
import me.jackcw.duels.kit.KitManager;
import me.jackcw.duels.kit.KitSerializer;
import me.jackcw.duels.listener.ArenaEditListener;
import me.jackcw.duels.listener.MatchListener;
import me.jackcw.duels.listener.PlayerStateListener;
import me.jackcw.duels.listener.SpectatorListener;
import me.jackcw.duels.match.MatchManager;
import me.jackcw.duels.menu.admin.*;
import me.jackcw.duels.menu.admin.arena.ArenaDetailMenu;
import me.jackcw.duels.menu.admin.arena.ArenaInstanceDetailMenu;
import me.jackcw.duels.menu.admin.arena.ArenaInstanceListMenu;
import me.jackcw.duels.menu.admin.arena.ArenaKitMenu;
import me.jackcw.duels.menu.admin.arena.ArenaListMenu;
import me.jackcw.duels.menu.admin.arena.ArenaMainMenu;
import me.jackcw.duels.menu.admin.kit.KitDetailMenu;
import me.jackcw.duels.menu.admin.kit.KitEditMenu;
import me.jackcw.duels.menu.admin.kit.KitListMenu;
import me.jackcw.duels.menu.admin.kit.KitMainMenu;
import me.jackcw.duels.menu.user.KitSelectorMenu;
import me.jackcw.duels.menu.user.KitViewMenu;
import me.jackcw.duels.menu.user.LeaderboardMenu;
import me.jackcw.duels.menu.user.SpectateMenu;
import me.jackcw.duels.player.PlayerStateManager;
import me.jackcw.duels.spectator.SpectatorManager;
import me.jackcw.duels.stats.MatchRecord;
import me.jackcw.duels.stats.MatchRecordSerializer;
import me.jackcw.duels.stats.StatsManager;
import me.jackcw.jcore.JCore;
import me.jackcw.jcore.storage.YamlFile;
import me.jackcw.jcore.storage.YamlRepository;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.Bukkit;

import java.util.ArrayList;
import java.util.UUID;

public class Duels extends JavaPlugin
{
    private JCore jCore;

    private DuelsSettings settings;
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
    private BlockChangeRollbackStrategy arenaResetStrategy;
    private KitManager kitManager;
    private ChallengeManager challengeManager;
    private MatchManager matchManager;
    private PlayerStateManager playerStateManager;
    private SpectatorManager spectatorManager;
    private StatsManager statsManager;

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
    private LeaderboardMenu leaderboardMenu;
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

    public ArenaResetStrategy getArenaResetStrategy()
    {
        return arenaResetStrategy;
    }

    public KitManager getKitManager()
    {
        return kitManager;
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
        arenaMainMenu = new ArenaMainMenu(this, arenaListMenu);

        kitEditMenu = new KitEditMenu(this);
        kitDetailMenu = new KitDetailMenu(this, kitEditMenu);
        kitListMenu = new KitListMenu(this, kitDetailMenu);
        kitMainMenu = new KitMainMenu(this, kitListMenu);
        kitViewMenu = new KitViewMenu(this);
        kitSelectorMenu = new KitSelectorMenu(this, kitViewMenu);
        leaderboardMenu = new LeaderboardMenu(this);
        spectateMenu = new SpectateMenu(this);

        adminMainMenu = new AdminMainMenu(this, arenaMainMenu, kitMainMenu);
    }

    /** Upgrade only the old bundled layout; do not overwrite custom positions. */
    void migrateDefaultMenuLayout(YamlFile file)
    {
        boolean changed = false;
        changed |= moveBackToOwnRow(file, "arena-detail", new int[] {0, 2, 4, 6, 8, 10, 12, 14, 16},
                new String[] {"rename", "instances", "create-instance", "delete", "toggle-available", "kits", "boundary", "provisioning", "template"});
        changed |= migrateInstanceDetailLayout(file);

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
                "structure1", "structure2", "capture", "retry", "setup-status"};
        int[] oldSlots = {1, 3, 5, 7, 10, 12, 0, 2, 4, 6, 8};
        int[] newSlots = {1, 3, 19, 25, 5, 7, 10, 12, 14, 23, 21};
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

    private void initializeManagers()
    {
        kitManager = new KitManager(kitRepository);
        arenaManager = new ArenaManager(arenaRepository);
        arenaInstanceManager = new ArenaInstanceManager(arenaInstanceRepository, arenaManager);
        arenaInstanceManager.migrateLegacyDynamicSources(getLogger());
        arenaTemplateManager = new ArenaTemplateManager(this, new PaperArenaStructureProvider());
        dynamicArenaSlotManager = new DynamicArenaSlotManager(this);
        dynamicArenaWorldManager = new DynamicArenaWorldManager(dynamicArenaSlotManager);
        dynamicArenaProvisioner = new DynamicArenaProvisioner(this);
        arenaAllocator = new StaticArenaAllocator(arenaManager, arenaInstanceManager, dynamicArenaProvisioner);
        dynamicArenaRecovery = new DynamicArenaRecovery(this);
        dynamicArenaRecovery.recover();
        arenaEditManager = new ArenaEditManager(this);
        challengeManager = new ChallengeManager(jCore.tasks(), settings, new ChallengeExpiryHandler(jCore.messages())::onExpire);
        playerStateManager = new PlayerStateManager(jCore.files().yaml("playerstates.yml", true), jCore.serializers());
        statsManager = new StatsManager(this);
        matchManager = new MatchManager(this);

        // Ordering matters: SpectatorManager reads MatchManager, and
        // BoundaryEnforcer resolves spectators through SpectatorManager.
        spectatorManager = new SpectatorManager(this, jCore.files().yaml("spectators.yml"));
        boundaryEnforcer = new BoundaryEnforcer(this);
        arenaResetStrategy = new BlockChangeRollbackStrategy(this);

        arenaManager.setHasInstancesCheck(arenaInstanceManager::hasInstances);
        arenaInstanceManager.setActiveCheck(arenaAllocator::isAllocated);
    }

    private void registerEvents()
    {
        getServer().getPluginManager().registerEvents(new PlayerStateListener(this), this);
        getServer().getPluginManager().registerEvents(new MatchListener(this), this);
        getServer().getPluginManager().registerEvents(new ArenaEditListener(this), this);
        getServer().getPluginManager().registerEvents(new SpectatorListener(this), this);
        getServer().getPluginManager().registerEvents(arenaResetStrategy, this);
    }

    private void registerCommands()
    {
        jCore.commands().register(new DuelsCommand(this).build());
        jCore.commands().register(new DuelCommand(this).build());
    }
}
