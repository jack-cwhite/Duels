package me.jackcw.duels;

import me.jackcw.duels.arena.Arena;
import me.jackcw.duels.arena.ArenaManager;
import me.jackcw.duels.arena.ArenaSerializer;
import me.jackcw.duels.challenge.ChallengeExpiryHandler;
import me.jackcw.duels.challenge.ChallengeManager;
import me.jackcw.duels.commands.DuelCommand;
import me.jackcw.duels.commands.DuelsCommand;
import me.jackcw.duels.kit.Kit;
import me.jackcw.duels.kit.KitManager;
import me.jackcw.duels.kit.KitSerializer;
import me.jackcw.duels.listener.MatchListener;
import me.jackcw.duels.listener.PlayerJoinLeaveListener;
import me.jackcw.duels.match.MatchManager;
import me.jackcw.duels.menu.admin.*;
import me.jackcw.duels.menu.admin.arena.ArenaDetailMenu;
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
import me.jackcw.duels.player.PlayerStateManager;
import me.jackcw.duels.stats.MatchRecord;
import me.jackcw.duels.stats.MatchRecordSerializer;
import me.jackcw.duels.stats.StatsManager;
import me.jackcw.jcore.JCore;
import me.jackcw.jcore.storage.YamlFile;
import me.jackcw.jcore.storage.YamlRepository;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.Bukkit;

public class Duels extends JavaPlugin
{
    private JCore jCore;

    private DuelsSettings settings;
    private ArenaManager arenaManager;
    private KitManager kitManager;
    private ChallengeManager challengeManager;
    private MatchManager matchManager;
    private PlayerStateManager playerStateManager;
    private StatsManager statsManager;

    private YamlRepository<Arena> arenaRepository;
    private YamlRepository<Kit> kitRepository;

    private AdminMainMenu adminMainMenu;
    private ArenaMainMenu arenaMainMenu;
    private ArenaListMenu arenaListMenu;
    private ArenaKitMenu arenaKitMenu;
    private ArenaDetailMenu arenaDetailMenu;
    private KitMainMenu kitMainMenu;
    private KitListMenu kitListMenu;
    private KitDetailMenu kitDetailMenu;
    private KitEditMenu kitEditMenu;
    private KitViewMenu kitViewMenu;
    private KitSelectorMenu kitSelectorMenu;
    private LeaderboardMenu leaderboardMenu;

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

        if (jCore != null)
            jCore.shutdown();
    }

    public JCore getJCore()
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

    public KitSelectorMenu getKitSelectorMenu()
    {
        return kitSelectorMenu;
    }

    public LeaderboardMenu getLeaderboardMenu()
    {
        return leaderboardMenu;
    }

    public boolean isServerStopping()
    {
        try
        {
            return Bukkit.isStopping();
        }
        catch (RuntimeException ignored)
        {
            // Some lightweight server test doubles do not expose shutdown
            // state. A normal plugin disable is the safe fallback there.
            return false;
        }
    }

    private void initializeSerializers()
    {
        jCore.serializers().register(Arena.class, new ArenaSerializer(jCore.serializers()));
        jCore.serializers().register(Kit.class, new KitSerializer(jCore.serializers()));
        jCore.serializers().register(MatchRecord.class, new MatchRecordSerializer());
    }

    private void initializeRepositories()
    {
        arenaRepository = new YamlRepository<>(
                jCore.files().yaml("arenas.yml"), jCore.serializers(), "arenas", Arena.class, Arena::getId
        );

        kitRepository = new YamlRepository<>(
                jCore.files().yaml("kits.yml"), jCore.serializers(), "kits", Kit.class, Kit::getId
        );
    }

    private void initializeMenus()
    {
        YamlFile menusFile = jCore.files().yaml("menus.yml", true);
        menusFile.updateDefaults();

        jCore.menus().configure(menusFile);

        arenaKitMenu = new ArenaKitMenu(this);
        arenaDetailMenu = new ArenaDetailMenu(this, arenaKitMenu);
        arenaListMenu = new ArenaListMenu(this, arenaDetailMenu);
        arenaMainMenu = new ArenaMainMenu(this, arenaListMenu);

        kitEditMenu = new KitEditMenu(this);
        kitDetailMenu = new KitDetailMenu(this, kitEditMenu);
        kitListMenu = new KitListMenu(this, kitDetailMenu);
        kitMainMenu = new KitMainMenu(this, kitListMenu);
        kitViewMenu = new KitViewMenu(this);
        kitSelectorMenu = new KitSelectorMenu(this, kitViewMenu);
        leaderboardMenu = new LeaderboardMenu(this);

        adminMainMenu = new AdminMainMenu(this, arenaMainMenu, kitMainMenu);
    }

    private void initializeManagers()
    {
        kitManager = new KitManager(kitRepository);
        arenaManager = new ArenaManager(arenaRepository);
        challengeManager = new ChallengeManager(jCore.tasks(), settings, new ChallengeExpiryHandler(jCore.messages())::onExpire);
        playerStateManager = new PlayerStateManager(jCore.files().yaml("playerstates.yml", true), jCore.serializers());
        statsManager = new StatsManager(this);
        matchManager = new MatchManager(this);

        // Wired after construction rather than taken as an ArenaManager
        // constructor dependency, since MatchManager itself depends on
        // ArenaManager - a constructor cycle isn't possible here.
        arenaManager.setActiveCheck(matchManager::isArenaInUse);
    }

    private void registerEvents()
    {
        getServer().getPluginManager().registerEvents(new PlayerJoinLeaveListener(this), this);
        getServer().getPluginManager().registerEvents(new MatchListener(this), this);
    }

    private void registerCommands()
    {
        jCore.commands().register(new DuelsCommand(this).build());
        jCore.commands().register(new DuelCommand(this).build());
    }
}
