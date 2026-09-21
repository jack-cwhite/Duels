package me.jackcw.duels;

import me.jackcw.duels.arena.Arena;
import me.jackcw.duels.arena.ArenaSelection;
import me.jackcw.duels.arena.ArenaAllocationResult;
import me.jackcw.duels.arena.ArenaInstance;
import me.jackcw.duels.arena.ArenaStructureSize;
import me.jackcw.duels.arena.ArenaStructureProvider;
import me.jackcw.duels.arena.ArenaTemplateManager;
import me.jackcw.duels.arena.ArenaTemplateCaptureResult;
import me.jackcw.duels.arena.DynamicArenaState;
import me.jackcw.duels.arena.ArenaProvisioningMode;
import me.jackcw.duels.arena.BlockChangeRollbackStrategy;
import me.jackcw.duels.challenge.Challenge;
import me.jackcw.duels.kit.Kit;
import me.jackcw.duels.match.Match;
import me.jackcw.duels.match.MatchState;
import me.jackcw.duels.spectator.SpectateResult;
import me.jackcw.duels.stats.LeaderboardEntry;
import me.jackcw.jcore.database.Database;
import org.bukkit.ExplosionResult;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class DuelsIntegrationTest
{
    private ServerMock server;
    private Duels plugin;

    @BeforeEach
    void setup()
    {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(Duels.class);
    }

    @AfterEach
    void cleanup()
    {
        MockBukkit.unmock();
    }

    @Test
    void arenaAndKitIdsAreNotReusedAfterDeletion()
    {
        Arena firstArena = plugin.getArenaManager().createArena("First");
        assertTrue(plugin.getArenaManager().deleteArena(firstArena.getId()).isSuccess());
        Arena secondArena = plugin.getArenaManager().createArena("Second");

        Kit firstKit = plugin.getKitManager().createKit("First");
        assertTrue(plugin.getKitManager().deleteKit(firstKit.getId()));
        Kit secondKit = plugin.getKitManager().createKit("Second");

        assertTrue(secondArena.getId() > firstArena.getId());
        assertTrue(secondKit.getId() > firstKit.getId());
    }

    @Test
    void captureDraftWorksAcrossMenuAndEditModeButClearsOnQuit()
    {
        PlayerMock admin = addPlayer("Builder");
        Arena arena = plugin.getArenaManager().createArena("Castle", ArenaProvisioningMode.DYNAMIC);
        ArenaInstance instance = plugin.getArenaInstanceManager().createSource(arena.getId());
        Location corner = admin.getLocation();

        plugin.getArenaEditManager().setStructureCorner(admin, instance.getId(), 1, corner);
        plugin.getArenaEditManager().start(admin, instance);
        assertEquals(corner, plugin.getArenaEditManager().getSession(admin).getStructureCorner1());
        plugin.getArenaEditManager().end(admin);
        assertEquals(corner, plugin.getArenaEditManager().getStructureCorner(admin, instance.getId(), 1));

        plugin.getArenaEditManager().clearCaptureDraft(admin.getUniqueId());
        assertNull(plugin.getArenaEditManager().getStructureCorner(admin, instance.getId(), 1));
    }

    @Test
    void generatedCopyCannotBeEditedOrDeletedOutsideRetirement()
    {
        Arena arena = plugin.getArenaManager().createArena("Castle", ArenaProvisioningMode.DYNAMIC);
        ArenaInstance copy = plugin.getArenaInstanceManager().createProvisionedInstance(
                arena.getId(), 0, 1, new ArenaStructureSize(8, 8, 8));
        Location location = new Location(server.addSimpleWorld("generated_guard_world"), 0, 64, 0);

        assertFalse(plugin.getArenaInstanceManager().setSpawn(copy.getId(), 1, location).isSuccess());
        assertFalse(plugin.getArenaInstanceManager().setBoundsCorner(copy.getId(), 1, location).isSuccess());
        assertFalse(plugin.getArenaInstanceManager().deleteInstance(copy.getId()).isSuccess());
        assertNotNull(plugin.getArenaInstanceManager().getInstance(copy.getId()));

        plugin.getArenaInstanceManager().setDynamicState(copy, DynamicArenaState.RETIRING);
        assertTrue(plugin.getArenaInstanceManager().deleteInstance(copy.getId()).isSuccess());
    }

    @Test
    void dynamicSourceIsNeverAllocatedAsAPlayableCopy()
    {
        WorldMock world = server.addSimpleWorld("exclusive_modes_world");
        Arena staticArena = plugin.getArenaManager().createArena("Built", ArenaProvisioningMode.STATIC);
        Arena dynamicArena = plugin.getArenaManager().createArena("Copied", ArenaProvisioningMode.DYNAMIC);
        ArenaInstance built = plugin.getArenaInstanceManager().createInstance(staticArena.getId());
        ArenaInstance source = plugin.getArenaInstanceManager().createSource(dynamicArena.getId());
        built.setSpawn1(new Location(world, 0, 64, 0));
        built.setSpawn2(new Location(world, 5, 64, 5));
        source.setSpawn1(new Location(world, 20, 64, 20));
        source.setSpawn2(new Location(world, 25, 64, 25));
        plugin.getArenaInstanceManager().save(built);
        plugin.getArenaInstanceManager().save(source);

        assertEquals(0, plugin.getArenaInstanceManager().countReady(dynamicArena.getId()));
        ArenaAllocationResult dynamicResult = plugin.getArenaAllocator().allocate(ArenaSelection.specific(dynamicArena.getId())).join();
        assertEquals(ArenaAllocationResult.Status.TEMPLATE_UNAVAILABLE, dynamicResult.status());
        ArenaAllocationResult staticResult = plugin.getArenaAllocator().allocate(ArenaSelection.specific(staticArena.getId())).join();
        assertEquals(built.getId(), staticResult.instance().getId());
        assertThrows(IllegalStateException.class, () -> plugin.getArenaInstanceManager().createInstance(dynamicArena.getId()));
        assertThrows(IllegalStateException.class, () -> plugin.getArenaInstanceManager().createSource(dynamicArena.getId()));
    }

    @Test
    void existingSingleCopyCanBecomeDynamicSourceWithoutDeletingItsSetup()
    {
        WorldMock world = server.addSimpleWorld("source_conversion_world");
        Arena arena = plugin.getArenaManager().createArena("Old hybrid");
        ArenaInstance copy = plugin.getArenaInstanceManager().createInstance(arena.getId());
        Location spawn = new Location(world, 4, 65, 4);
        copy.setSpawn1(spawn);
        copy.setSpawn2(new Location(world, 9, 65, 9));
        plugin.getArenaInstanceManager().save(copy);

        assertTrue(plugin.getArenaInstanceManager().convertToDynamicSource(arena.getId(), copy.getId()));
        assertEquals(ArenaProvisioningMode.DYNAMIC, arena.getProvisioningMode());
        assertTrue(copy.isSource());
        assertEquals(spawn, copy.getSpawn1());
        assertEquals(0, plugin.getArenaInstanceManager().countFree(arena.getId()));
        assertFalse(plugin.getArenaInstanceManager().convertToDynamicSource(arena.getId(), copy.getId()));
    }

    @Test
    void twoCopyStaticArenaCannotConvertOrExposeStructureTools()
    {
        PlayerMock admin = addPlayer("Builder");
        Arena arena = plugin.getArenaManager().createArena("Two copies");
        ArenaInstance first = plugin.getArenaInstanceManager().createInstance(arena.getId());
        plugin.getArenaInstanceManager().createInstance(arena.getId());

        assertFalse(plugin.getArenaInstanceManager().convertToDynamicSource(arena.getId(), first.getId()));
        assertEquals(ArenaProvisioningMode.STATIC, arena.getProvisioningMode());
        assertFalse(first.isSource());
        assertFalse(plugin.getArenaManager().setProvisioningMode(arena.getId(), ArenaProvisioningMode.DYNAMIC).isSuccess());

        plugin.getArenaEditManager().start(admin, first);
        assertNull(admin.getInventory().getItem(3));
        assertNull(admin.getInventory().getItem(5));
        plugin.getArenaEditManager().end(admin);
    }

    @Test
    void dynamicSourceCannotBeDeletedWhileTemplateOrGeneratedCopiesExist()
    {
        Arena arena = plugin.getArenaManager().createArena("Castle", ArenaProvisioningMode.DYNAMIC);
        ArenaInstance source = plugin.getArenaInstanceManager().createSource(arena.getId());
        ArenaInstance copy = plugin.getArenaInstanceManager().createProvisionedInstance(
                arena.getId(), 0, 1, new ArenaStructureSize(8, 8, 8));
        assertFalse(plugin.getArenaInstanceManager().deleteInstance(source.getId()).isSuccess());
        plugin.getArenaInstanceManager().setDynamicState(copy, DynamicArenaState.RETIRING);
        assertTrue(plugin.getArenaInstanceManager().deleteInstance(copy.getId()).isSuccess());
        assertTrue(plugin.getArenaInstanceManager().deleteInstance(source.getId()).isSuccess());
    }

    @Test
    void structureCaptureUsesInclusiveSelectionSize() throws IOException
    {
        WorldMock world = server.addSimpleWorld("inclusive_capture_world");
        Arena arena = plugin.getArenaManager().createArena("Temple", ArenaProvisioningMode.DYNAMIC);
        ArenaInstance instance = plugin.getArenaInstanceManager().createSource(arena.getId());
        instance.setSpawn1(new Location(world, 11, 65, 21));
        instance.setSpawn2(new Location(world, 12, 65, 22));
        instance.setBoundsCorner1(new Location(world, 10, 64, 20));
        instance.setBoundsCorner2(new Location(world, 12, 67, 24));
        plugin.getArenaInstanceManager().save(instance);

        AtomicReference<Location> capturedOrigin = new AtomicReference<>();
        AtomicReference<ArenaStructureSize> capturedSize = new AtomicReference<>();
        ArenaStructureProvider provider = new ArenaStructureProvider()
        {
            public String id() { return "test-structure"; }

            public void capture(Location origin, ArenaStructureSize size, Path target) throws IOException
            {
                capturedOrigin.set(origin);
                capturedSize.set(size);
                Files.writeString(target, "test structure");
            }

            public ArenaStructureSize readSize(Path source) { return capturedSize.get(); }

            public void place(Path source, org.bukkit.World destination, int x, int y, int z) { }
        };
        ArenaTemplateManager manager = new ArenaTemplateManager(plugin, provider);

        ArenaTemplateCaptureResult result = manager.capture(instance.getId(),
                new Location(world, 12, 67, 24), new Location(world, 10, 64, 20));

        assertEquals(ArenaTemplateCaptureResult.Status.SUCCESS, result.status());
        assertEquals(new Location(world, 10, 64, 20), capturedOrigin.get());
        assertEquals(new ArenaStructureSize(3, 4, 5), capturedSize.get());
        assertEquals(capturedSize.get(), result.template().size());
        assertEquals(2, result.template().boundsCorner2().x());
    }

    @Test
    void bundledMenusReserveTheBottomRowForNavigation()
    {
        var config = plugin.core().files().yaml("menus.yml", true).getConfig();
        assertEquals(3, config.getInt("arena-detail.rows"));
        assertEquals(4, config.getInt("arena-instance-detail.rows"));
        assertEquals(14, config.getInt("arena-instance-detail.items.capture.slot"));
        assertEquals(44, config.getInt("kit-edit.items.save.slot"));
    }

    @Test
    void staticAndDynamicAdminScreensExposeOnlyTheirRelevantActions()
    {
        PlayerMock admin = addPlayer("Admin");
        Arena staticArena = plugin.getArenaManager().createArena("Built");
        Arena dynamicArena = plugin.getArenaManager().createArena("Copied", ArenaProvisioningMode.DYNAMIC);
        ArenaInstance built = plugin.getArenaInstanceManager().createInstance(staticArena.getId());
        ArenaInstance source = plugin.getArenaInstanceManager().createSource(dynamicArena.getId());

        plugin.getArenaDetailMenu().open(admin, staticArena);
        var staticMenu = admin.getOpenInventory().getTopInventory();
        assertEquals(Material.NETHER_STAR, staticMenu.getItem(4).getType());
        assertEquals(Material.STRUCTURE_BLOCK, staticMenu.getItem(14).getType());

        plugin.getArenaDetailMenu().open(admin, dynamicArena);
        var dynamicMenu = admin.getOpenInventory().getTopInventory();
        assertEquals(Material.STRUCTURE_BLOCK, dynamicMenu.getItem(2).getType());
        assertEquals(Material.EMERALD, dynamicMenu.getItem(4).getType());
        assertEquals(Material.FILLED_MAP, dynamicMenu.getItem(14).getType());

        plugin.getArenaInstanceDetailMenu().open(admin, built.getId());
        var staticCopyMenu = admin.getOpenInventory().getTopInventory();
        assertEquals(27, staticCopyMenu.getSize());
        assertNotEquals(Material.ORANGE_CONCRETE, staticCopyMenu.getItem(10).getType());

        plugin.getArenaInstanceDetailMenu().open(admin, source.getId());
        var sourceMenu = admin.getOpenInventory().getTopInventory();
        assertEquals(Material.ORANGE_CONCRETE, sourceMenu.getItem(10).getType());
        assertEquals(Material.WRITABLE_BOOK, sourceMenu.getItem(14).getType());
    }

    @Test
    void oldBundledMenuSlotsUpgradeWithoutTouchingUnrelatedOptions()
    {
        var file = plugin.core().files().yaml("menus.yml", true);
        var config = file.getConfig();
        config.set("arena-detail.rows", 2);
        config.set("arena-instance-detail.rows", 2);
        String[] keys = {"spawn1", "spawn2", "edit-mode", "delete", "bounds1", "bounds2",
                "structure1", "structure2", "capture", "retry", "setup-status"};
        int[] oldSlots = {1, 3, 5, 7, 10, 12, 0, 2, 4, 6, 8};
        for (int i = 0; i < keys.length; i++)
            config.set("arena-instance-detail.items." + keys[i] + ".slot", oldSlots[i]);
        config.set("kit-edit.items.save.slot", 51);
        config.set("arena-detail.items.rename.name", "&aMy custom label");

        plugin.migrateDefaultMenuLayout(file);

        assertEquals(3, config.getInt("arena-detail.rows"));
        assertEquals(4, config.getInt("arena-instance-detail.rows"));
        assertEquals(14, config.getInt("arena-instance-detail.items.capture.slot"));
        assertEquals(44, config.getInt("kit-edit.items.save.slot"));
        assertEquals("&aMy custom label", config.getString("arena-detail.items.rename.name"));
    }

    @Test
    void playersCanHoldMultiplePendingChallengesButNotDuplicatePairs()
    {
        PlayerMock alice = addPlayer("Alice");
        PlayerMock bob = addPlayer("Bob");
        PlayerMock charlie = addPlayer("Charlie");

        assertTrue(plugin.getChallengeManager().createChallenge(alice, bob));
        assertTrue(plugin.getChallengeManager().createChallenge(alice, charlie));
        assertTrue(plugin.getChallengeManager().createChallenge(charlie, bob));
        assertFalse(plugin.getChallengeManager().createChallenge(alice, bob));

        Challenge accepted = plugin.getChallengeManager().findIncoming(bob.getUniqueId(), alice.getUniqueId());
        assertNotNull(accepted);

        // Resolving a challenge deliberately does not consume it - the pair is
        // still occupied until the caller confirms a match actually started.
        assertFalse(plugin.getChallengeManager().createChallenge(alice, bob));

        plugin.getChallengeManager().remove(accepted);
        assertTrue(plugin.getChallengeManager().createChallenge(alice, bob));
    }

    @Test
    void matchKeepsIndependentKitSnapshot()
    {
        WorldMock world = server.addSimpleWorld("kit_snapshot_world");
        Arena arena = new Arena(1, "Test Arena");
        ArenaInstance instance = new ArenaInstance(1, arena.getId());
        instance.setSpawn1(new Location(world, 0, 64, 0));
        instance.setSpawn2(new Location(world, 10, 64, 10));
        Kit kit = new Kit(1, "Sword");
        ItemStack[] contents = new ItemStack[36];
        contents[0] = new ItemStack(Material.IRON_SWORD);
        kit.setContents(contents);

        Match match =
            new Match(
                UUID.randomUUID(),
                UUID.randomUUID(),
                instance,
                new Location(null, 0, 0, 0),
                new Location(null, 1, 0, 0),
                List.of(kit));

        kit.getContents()[0] = new ItemStack(Material.WOODEN_SWORD);

        assertEquals(Material.IRON_SWORD, match.getAvailableKits().getFirst().getContents()[0].getType());
    }

    @Test
    void commandCreatedKitKeepsStorageArmorAndOffhandSeparate()
    {
        PlayerMock player = addPlayer("KitCreator");
        ItemStack[] storage = new ItemStack[36];
        storage[0] = new ItemStack(Material.IRON_SWORD);

        player.getInventory().setStorageContents(storage);
        player.getInventory().setHelmet(new ItemStack(Material.DIAMOND_HELMET));
        player.getInventory().setItemInOffHand(new ItemStack(Material.SHIELD));

        Kit kit = plugin.getKitManager().createKit("Armoured", player);

        assertEquals(36, kit.getContents().length);
        assertEquals(Material.IRON_SWORD, kit.getContents()[0].getType());
        assertEquals(Material.DIAMOND_HELMET, kit.getArmor()[3].getType());
        assertEquals(Material.SHIELD, kit.getOffHand().getType());
    }

    @Test
    void sqlStatsQueriesUseBooleanParameters()
    {
        UUID winner = UUID.randomUUID();
        UUID loser = UUID.randomUUID();
        Database database = plugin.core().database();

        database.execute("DELETE FROM duels_match_participants");
        database.execute("DELETE FROM duels_matches");
        database.execute(
                "INSERT INTO duels_matches (id, arena_id, winner_id, ended_at) VALUES (?, ?, ?, ?)",
                1, 1, winner.toString(), System.currentTimeMillis()
        );
        database.execute(
                "INSERT INTO duels_match_participants (match_id, player_id, kit_id, won) VALUES (?, ?, ?, ?)",
                1, winner.toString(), 1, true
        );
        database.execute(
                "INSERT INTO duels_match_participants (match_id, player_id, kit_id, won) VALUES (?, ?, ?, ?)",
                1, loser.toString(), 1, false
        );

        assertEquals(1, plugin.getStatsManager().getWins(winner).join());
        assertEquals(0, plugin.getStatsManager().getLosses(winner).join());
        assertEquals(1, plugin.getStatsManager().getLosses(loser).join());

        List<LeaderboardEntry> leaderboard = plugin.getStatsManager().getTopPlayers(10).join();
        assertEquals(1, leaderboard.size());
        assertEquals(winner, leaderboard.getFirst().playerId());
        assertEquals(1, leaderboard.getFirst().wins());
    }

    @Test
    void matchProgressesThroughPregameGraceThenInProgress()
    {
        WorldMock world = server.addSimpleWorld("duel_world");
        Arena arena = plugin.getArenaManager().createArena("Colosseum");
        createReadyInstance(arena, world);

        plugin.getKitManager().createKit("Warrior");

        PlayerMock alice = addPlayer("Alice");
        PlayerMock bob = addPlayer("Bob");

        Match match = plugin.getMatchManager().startMatch(alice, bob);
        assertNotNull(match);
        assertEquals(MatchState.PREGAME, match.getState());

        server.getScheduler().performTicks((plugin.getSettings().kitSelectionSeconds() + 1) * 20L);
        assertEquals(MatchState.GRACE, match.getState());
        assertNotNull(match.getAppliedKit(alice.getUniqueId()));
        assertNotNull(match.getAppliedKit(bob.getUniqueId()));

        server.getScheduler().performTicks((plugin.getSettings().gracePeriodSeconds() + 1) * 20L);
        assertEquals(MatchState.IN_PROGRESS, match.getState());
    }

    @Test
    void opponentDisconnectDuringPregameCountsAsForfeit()
    {
        WorldMock world = server.addSimpleWorld("duel_world_pregame");
        Arena arena = plugin.getArenaManager().createArena("Pit");
        createReadyInstance(arena, world);

        PlayerMock alice = addPlayer("Alice");
        PlayerMock bob = addPlayer("Bob");

        Match match = plugin.getMatchManager().startMatch(alice, bob);
        assertEquals(MatchState.PREGAME, match.getState());

        alice.disconnect();

        assertEquals(1, plugin.getStatsManager().getWins(bob.getUniqueId()).join());
        assertEquals(1, plugin.getStatsManager().getLosses(alice.getUniqueId()).join());
        assertNull(plugin.getMatchManager().getMatch(bob.getUniqueId()));

        // the pregame countdown must be cancelled on early match end, not left running
        // to fire its onComplete (re-applying a kit, resetting gear) against a match
        // that has already ended and restored the opponent's original state.
        server.getScheduler().performTicks((plugin.getSettings().kitSelectionSeconds() + 5) * 20L);
        assertEquals(1, plugin.getStatsManager().getWins(bob.getUniqueId()).join());
    }

    @Test
    void opponentDisconnectDuringGraceCountsAsForfeit()
    {
        WorldMock world = server.addSimpleWorld("duel_world_grace");
        Arena arena = plugin.getArenaManager().createArena("Dome");
        createReadyInstance(arena, world);

        PlayerMock alice = addPlayer("Alice");
        PlayerMock bob = addPlayer("Bob");

        Match match = plugin.getMatchManager().startMatch(alice, bob);
        server.getScheduler().performTicks((plugin.getSettings().kitSelectionSeconds() + 1) * 20L);
        assertEquals(MatchState.GRACE, match.getState());

        bob.disconnect();

        assertEquals(1, plugin.getStatsManager().getWins(alice.getUniqueId()).join());
        assertEquals(1, plugin.getStatsManager().getLosses(bob.getUniqueId()).join());
    }

    /**
     * The most important spectator test. Membership of MatchManager's match map
     * is the definition of "combatant", so a spectator leaking into it would
     * make this disconnect award the duel to Bob.
     */
    @Test
    void spectatorDisconnectDoesNotEndTheMatch()
    {
        Match match = startSpectatableMatch("spectator_quit_world", "Colosseum");
        PlayerMock charlie = addPlayer("Charlie");

        assertEquals(SpectateResult.SUCCESS, plugin.getSpectatorManager().start(charlie, match));

        charlie.disconnect();

        assertNotEquals(MatchState.ENDED, match.getState());
        assertNotNull(plugin.getMatchManager().getMatch(match.getPlayer1Id()));
        assertEquals(0, plugin.getStatsManager().getWins(match.getPlayer2Id()).join());
    }

    @Test
    void endingAMatchEjectsItsSpectators()
    {
        Match match = startSpectatableMatch("spectator_eject_world", "Pit");
        PlayerMock charlie = addPlayer("Charlie");
        Location before = charlie.getLocation().clone();

        assertEquals(SpectateResult.SUCCESS, plugin.getSpectatorManager().start(charlie, match));
        assertEquals(GameMode.SPECTATOR, charlie.getGameMode());
        assertEquals(List.of(charlie.getUniqueId()), plugin.getSpectatorManager().spectatorsOf(match));

        plugin.getMatchManager().endMatch(match, match.getPlayer1Id());

        assertFalse(plugin.getSpectatorManager().isSpectating(charlie.getUniqueId()));
        assertEquals(GameMode.SURVIVAL, charlie.getGameMode());
        assertEquals(before.getWorld(), charlie.getLocation().getWorld());
        assertEquals(before.getBlockX(), charlie.getLocation().getBlockX());
        assertEquals(before.getBlockZ(), charlie.getLocation().getBlockZ());
    }

    /**
     * A combatant leaving must still forfeit even with somebody watching - the
     * spectator work must not have weakened the existing rule.
     */
    @Test
    void combatantDisconnectStillForfeitsWhileSpectated()
    {
        Match match = startSpectatableMatch("spectated_forfeit_world", "Dome");
        PlayerMock charlie = addPlayer("Charlie");

        assertEquals(SpectateResult.SUCCESS, plugin.getSpectatorManager().start(charlie, match));

        Player alice = server.getPlayer(match.getPlayer1Id());
        ((PlayerMock) alice).disconnect();

        assertEquals(1, plugin.getStatsManager().getWins(match.getPlayer2Id()).join());
        assertFalse(plugin.getSpectatorManager().isSpectating(charlie.getUniqueId()));
    }

    @Test
    void activeMatchesListsEachMatchOnce()
    {
        WorldMock world = server.addSimpleWorld("active_matches_world");

        for (String name : List.of("First", "Second"))
            createReadyInstance(plugin.getArenaManager().createArena(name), world);

        Match first = plugin.getMatchManager().startMatch(addPlayer("Alice"), addPlayer("Bob"));
        Match second = plugin.getMatchManager().startMatch(addPlayer("Charlie"), addPlayer("Dave"));

        assertNotNull(first);
        assertNotNull(second);

        // Four participants, two matches - the map holds each match twice.
        assertEquals(2, plugin.getMatchManager().getActiveMatches().size());

        plugin.getMatchManager().endMatch(first, first.getPlayer1Id());

        assertEquals(List.of(second), plugin.getMatchManager().getActiveMatches());
    }

    /**
     * The point of Phase 3: two registered instances of the same template let two
     * matches run at once without configuring two separate arenas, and a third request
     * correctly finds nothing free rather than double-claiming an instance.
     */
    @Test
    void twoInstancesOfTheSameArenaAllowTwoConcurrentMatchesButNotAThird()
    {
        WorldMock world = server.addSimpleWorld("two_instance_world");
        Arena arena = plugin.getArenaManager().createArena("Colosseum");
        createReadyInstance(arena, world);
        createReadyInstance(arena, world);

        Match first = plugin.getMatchManager().startMatch(addPlayer("Alice"), addPlayer("Bob"));
        Match second = plugin.getMatchManager().startMatch(addPlayer("Charlie"), addPlayer("Dave"));

        assertNotNull(first);
        assertNotNull(second);
        assertNotEquals(first.getArenaInstance().getId(), second.getArenaInstance().getId());

        Match third = plugin.getMatchManager().startMatch(addPlayer("Erin"), addPlayer("Frank"));
        assertNull(third);

        plugin.getMatchManager().endMatch(first, first.getPlayer1Id());

        Match fourth = plugin.getMatchManager().startMatch(addPlayer("Grace"), addPlayer("Heidi"));
        assertNotNull(fourth);
        assertEquals(first.getArenaInstance().getId(), fourth.getArenaInstance().getId());
    }

    @Test
    void selectedArenaAllocationDoesNotFallBackToAnotherTemplate()
    {
        WorldMock world = server.addSimpleWorld("selected_arena_world");
        Arena desert = plugin.getArenaManager().createArena("Desert");
        Arena castle = plugin.getArenaManager().createArena("Castle");
        createReadyInstance(desert, world);
        createReadyInstance(castle, world);

        Match castleMatch = plugin.getMatchManager().startMatch(
                addPlayer("Alice"), addPlayer("Bob"), castle.getId());

        assertNotNull(castleMatch);
        assertEquals(castle.getId(), castleMatch.getArenaInstance().getArenaId());

        // Castle is occupied. Desert is still free, but an explicit Castle
        // request must not silently allocate a different arena.
        Match secondCastleMatch = plugin.getMatchManager().startMatch(
                addPlayer("Charlie"), addPlayer("Dave"), castle.getId());
        assertNull(secondCastleMatch);

        Match desertMatch = plugin.getMatchManager().startMatch(
                addPlayer("Erin"), addPlayer("Frank"), desert.getId());

        assertNotNull(desertMatch);
        assertEquals(desert.getId(), desertMatch.getArenaInstance().getArenaId());
    }

    /**
     * Phase 3B: a block broken inside the arena's bounds while a match is
     * IN_PROGRESS is tracked, then reverted once the match ends and before the
     * instance is released back to the allocator - so the next match to claim
     * it does not inherit a hole in the floor.
     */
    @Test
    void blockChangesDuringAMatchAreRolledBackAfterItEnds()
    {
        WorldMock world = server.addSimpleWorld("block_rollback_world");
        Arena arena = plugin.getArenaManager().createArena("Colosseum");
        ArenaInstance instance = createReadyInstance(arena, world);
        plugin.getArenaInstanceManager().setBoundsCorner(instance.getId(), 1, new Location(world, -10, 60, -10));
        plugin.getArenaInstanceManager().setBoundsCorner(instance.getId(), 2, new Location(world, 10, 70, 10));

        Block floor = world.getBlockAt(5, 64, 5);
        floor.setType(Material.STONE);

        PlayerMock alice = addPlayer("Alice");
        PlayerMock bob = addPlayer("Bob");

        Match match = plugin.getMatchManager().startMatch(alice, bob);
        assertNotNull(match);

        server.getScheduler().performTicks((plugin.getSettings().kitSelectionSeconds() + 1) * 20L);
        server.getScheduler().performTicks((plugin.getSettings().gracePeriodSeconds() + 1) * 20L);
        assertEquals(MatchState.IN_PROGRESS, match.getState());

        BlockChangeRollbackStrategy strategy = (BlockChangeRollbackStrategy) plugin.getArenaResetStrategy();
        strategy.onBlockBreak(new BlockBreakEvent(floor, alice));
        floor.setType(Material.AIR);

        assertEquals(Material.AIR, floor.getType());

        plugin.getMatchManager().endMatch(match, alice.getUniqueId());
        server.getScheduler().performTicks(1L);

        assertEquals(Material.STONE, floor.getType());
    }

    /**
     * An explosion inside the bounds is rolled back like any other change, but
     * it also has to have its yield zeroed: restoring the blocks does nothing
     * about the item entities the explosion would otherwise have spawned, and
     * those would be left scattered around the repaired arena.
     */
    @Test
    void explosionsInsideTheBoundsAreRolledBackAndDropNothing()
    {
        WorldMock world = server.addSimpleWorld("block_rollback_explosion_world");
        Arena arena = plugin.getArenaManager().createArena("Colosseum");
        ArenaInstance instance = createReadyInstance(arena, world);
        plugin.getArenaInstanceManager().setBoundsCorner(instance.getId(), 1, new Location(world, -10, 60, -10));
        plugin.getArenaInstanceManager().setBoundsCorner(instance.getId(), 2, new Location(world, 10, 70, 10));

        Block inside = world.getBlockAt(5, 64, 5);
        inside.setType(Material.STONE);

        Block outside = world.getBlockAt(50, 64, 50);
        outside.setType(Material.STONE);

        PlayerMock alice = addPlayer("Alice");
        PlayerMock bob = addPlayer("Bob");

        Match match = plugin.getMatchManager().startMatch(alice, bob);
        assertNotNull(match);

        server.getScheduler().performTicks((plugin.getSettings().kitSelectionSeconds() + 1) * 20L);
        server.getScheduler().performTicks((plugin.getSettings().gracePeriodSeconds() + 1) * 20L);
        assertEquals(MatchState.IN_PROGRESS, match.getState());

        BlockChangeRollbackStrategy strategy = (BlockChangeRollbackStrategy) plugin.getArenaResetStrategy();

        Block source = world.getBlockAt(5, 65, 5);
        BlockExplodeEvent inBounds = new BlockExplodeEvent(
                source, source.getState(), new ArrayList<>(List.of(inside)), 1f, ExplosionResult.DESTROY
        );

        strategy.onBlockExplode(inBounds);
        inside.setType(Material.AIR);

        assertEquals(0f, inBounds.getYield(), "an explosion inside a tracked arena must not drop items");

        Block farSource = world.getBlockAt(50, 65, 50);
        BlockExplodeEvent outOfBounds = new BlockExplodeEvent(
                farSource, farSource.getState(), new ArrayList<>(List.of(outside)), 1f, ExplosionResult.DESTROY
        );

        strategy.onBlockExplode(outOfBounds);
        outside.setType(Material.AIR);

        assertEquals(1f, outOfBounds.getYield(), "explosions outside any arena must keep their normal drops");

        plugin.getMatchManager().endMatch(match, alice.getUniqueId());
        server.getScheduler().performTicks(1L);

        assertEquals(Material.STONE, inside.getType());
        assertEquals(Material.AIR, outside.getType(), "a change outside the bounds is not restored");
    }

    /**
     * The hard ceiling exists so an unusually destructive match cannot grow
     * the change log without bound. Past it, further changes are simply left
     * untracked rather than the match being interrupted or an exception
     * thrown - a duel arena tolerates being "mostly restored".
     */
    @Test
    void blockChangeTrackingStopsAtTheConfiguredCeilingPerInstance()
    {
        WorldMock world = server.addSimpleWorld("block_rollback_ceiling_world");
        Arena arena = plugin.getArenaManager().createArena("Colosseum");
        ArenaInstance instance = createReadyInstance(arena, world);
        plugin.getArenaInstanceManager().setBoundsCorner(instance.getId(), 1, new Location(world, -10, 60, -10));
        plugin.getArenaInstanceManager().setBoundsCorner(instance.getId(), 2, new Location(world, 10, 70, 10));

        PlayerMock alice = addPlayer("Alice");
        PlayerMock bob = addPlayer("Bob");

        Match match = plugin.getMatchManager().startMatch(alice, bob);
        assertNotNull(match);

        server.getScheduler().performTicks((plugin.getSettings().kitSelectionSeconds() + 1) * 20L);
        server.getScheduler().performTicks((plugin.getSettings().gracePeriodSeconds() + 1) * 20L);
        assertEquals(MatchState.IN_PROGRESS, match.getState());

        int max = plugin.getSettings().arenaResetMaxTrackedBlockChanges();
        BlockChangeRollbackStrategy strategy = (BlockChangeRollbackStrategy) plugin.getArenaResetStrategy();
        List<Block> broken = new ArrayList<>();

        outer:
        for (int x = -10; x <= 10; x++)
            for (int y = 60; y <= 70; y++)
                for (int z = -10; z <= 10; z++)
                {
                    if (broken.size() > max)
                        break outer;

                    Block block = world.getBlockAt(x, y, z);
                    block.setType(Material.STONE);
                    strategy.onBlockBreak(new BlockBreakEvent(block, alice));
                    block.setType(Material.AIR);
                    broken.add(block);
                }

        // One more change was made than the ceiling allows, so exactly one of
        // them should be left untracked.
        assertEquals(max + 1, broken.size());

        plugin.getMatchManager().endMatch(match, alice.getUniqueId());

        int blocksPerTick = plugin.getSettings().arenaResetBlocksPerTick();
        server.getScheduler().performTicks((long) (max / blocksPerTick + 2));

        for (int i = 0; i < max; i++)
            assertEquals(Material.STONE, broken.get(i).getType(), "change #" + i + " should have been rolled back");

        assertEquals(Material.AIR, broken.get(max).getType(), "change past the ceiling should not have been tracked");
    }

    /**
     * A second reset for the same instance must be a safe no-op: the first
     * reset already removed the instance's change log, so nothing is left to
     * replay and the completion callback fires immediately rather than
     * scheduling a task that would do nothing.
     */
    @Test
    void resettingAnInstanceTwiceDoesNothingTheSecondTime()
    {
        WorldMock world = server.addSimpleWorld("block_rollback_idempotent_world");
        Arena arena = plugin.getArenaManager().createArena("Colosseum");
        ArenaInstance instance = createReadyInstance(arena, world);
        plugin.getArenaInstanceManager().setBoundsCorner(instance.getId(), 1, new Location(world, -10, 60, -10));
        plugin.getArenaInstanceManager().setBoundsCorner(instance.getId(), 2, new Location(world, 10, 70, 10));

        Block floor = world.getBlockAt(5, 64, 5);
        floor.setType(Material.STONE);

        PlayerMock alice = addPlayer("Alice");
        PlayerMock bob = addPlayer("Bob");

        Match match = plugin.getMatchManager().startMatch(alice, bob);
        assertNotNull(match);

        server.getScheduler().performTicks((plugin.getSettings().kitSelectionSeconds() + 1) * 20L);
        server.getScheduler().performTicks((plugin.getSettings().gracePeriodSeconds() + 1) * 20L);
        assertEquals(MatchState.IN_PROGRESS, match.getState());

        BlockChangeRollbackStrategy strategy = (BlockChangeRollbackStrategy) plugin.getArenaResetStrategy();
        strategy.onBlockBreak(new BlockBreakEvent(floor, alice));
        floor.setType(Material.AIR);

        boolean[] firstCompleted = {false};
        strategy.reset(instance, () -> firstCompleted[0] = true);
        server.getScheduler().performTicks(1L);

        assertTrue(firstCompleted[0]);
        assertEquals(Material.STONE, floor.getType());

        // Nothing was tracked for this second break - it happens after the
        // instance's change log was already cleared by the first reset - so
        // this is only here to prove the second reset does not touch it.
        floor.setType(Material.AIR);

        boolean[] secondCompleted = {false};
        strategy.reset(instance, () -> secondCompleted[0] = true);

        assertTrue(secondCompleted[0]);
        assertEquals(Material.AIR, floor.getType());
    }

    @Test
    void aCombatantCannotSpectateAndAnEndedMatchCannotBeSpectated()
    {
        Match match = startSpectatableMatch("spectate_refusal_world", "Ring");
        Player alice = server.getPlayer(match.getPlayer1Id());

        assertEquals(SpectateResult.ALREADY_IN_MATCH, plugin.getSpectatorManager().start(alice, match));

        PlayerMock charlie = addPlayer("Charlie");
        plugin.getMatchManager().endMatch(match, match.getPlayer1Id());

        assertEquals(SpectateResult.MATCH_UNAVAILABLE, plugin.getSpectatorManager().start(charlie, match));
    }

    @Test
    void spectatingAnArenaWithoutBoundsIsRefused()
    {
        WorldMock world = server.addSimpleWorld("no_bounds_world");
        Arena arena = plugin.getArenaManager().createArena("Unbounded");
        createReadyInstance(arena, world);

        Match match = plugin.getMatchManager().startMatch(addPlayer("Alice"), addPlayer("Bob"));
        assertNotNull(match);

        assertEquals(SpectateResult.NO_BOUNDS, plugin.getSpectatorManager().start(addPlayer("Charlie"), match));
    }

    /**
     * The crash and disconnect path: the live session is dropped but its saved
     * row is kept on purpose, so the next join is what actually puts the player
     * back. Without this a crashed server leaves somebody stuck in SPECTATOR
     * inside an arena with no way to fix it themselves.
     */
    @Test
    void aDetachedSpectatorSessionIsFinishedOnTheirNextJoin()
    {
        Match match = startSpectatableMatch("spectator_rejoin_world", "Crater");
        PlayerMock charlie = addPlayer("Charlie");
        Location before = charlie.getLocation().clone();

        assertEquals(SpectateResult.SUCCESS, plugin.getSpectatorManager().start(charlie, match));
        assertTrue(plugin.getSpectatorManager().detach(charlie.getUniqueId()));

        assertFalse(plugin.getSpectatorManager().isSpectating(charlie.getUniqueId()));
        assertEquals(GameMode.SPECTATOR, charlie.getGameMode());

        plugin.getSpectatorManager().restoreOnJoin(charlie);

        assertEquals(GameMode.SURVIVAL, charlie.getGameMode());
        assertEquals(before.getBlockX(), charlie.getLocation().getBlockX());

        // The row is consumed, so a later join does not restore them a second
        // time and drag them out of a duel they have since joined.
        charlie.setGameMode(GameMode.CREATIVE);
        plugin.getSpectatorManager().restoreOnJoin(charlie);
        assertEquals(GameMode.CREATIVE, charlie.getGameMode());
    }

    private Match startSpectatableMatch(String worldName, String arenaName)
    {
        WorldMock world = server.addSimpleWorld(worldName);
        Arena arena = plugin.getArenaManager().createArena(arenaName);
        ArenaInstance instance = createReadyInstance(arena, world);
        plugin.getArenaInstanceManager().setBoundsCorner(instance.getId(), 1, new Location(world, -50, 0, -50));
        plugin.getArenaInstanceManager().setBoundsCorner(instance.getId(), 2, new Location(world, 50, 128, 50));

        Match match = plugin.getMatchManager().startMatch(addPlayer("Alice"), addPlayer("Bob"));
        assertNotNull(match);

        return match;
    }

    private ArenaInstance createReadyInstance(Arena arena, WorldMock world)
    {
        ArenaInstance instance = plugin.getArenaInstanceManager().createInstance(arena.getId());
        plugin.getArenaInstanceManager().setSpawn(instance.getId(), 1, new Location(world, 0, 64, 0));
        plugin.getArenaInstanceManager().setSpawn(instance.getId(), 2, new Location(world, 10, 64, 10));

        return instance;
    }

    /**
     * Players are added through this rather than {@code server.addPlayer(name)}
     * so that every test which starts a match actually runs. Capturing player
     * state calls Paper's {@code calculateTotalExperiencePoints()}, which this
     * version of MockBukkit leaves unimplemented; the exception it throws is a
     * JUnit "test aborted", so affected tests were being reported as skipped
     * rather than failed.
     */
    private PlayerMock addPlayer(String name)
    {
        PlayerMock player = new StateCapturablePlayerMock(server, name);

        server.addPlayer(player);

        return player;
    }

    /**
     * Fills in only the four accessors {@code PlayerState} needs and MockBukkit
     * leaves unimplemented, with the crudest behaviour that still round-trips:
     * capture then restore puts the same value back, which is all the tests
     * rely on.
     */
    private static class StateCapturablePlayerMock extends PlayerMock
    {
        private int totalExperiencePoints;
        private boolean canPickupItems = true;

        private StateCapturablePlayerMock(ServerMock server, String name)
        {
            super(server, name);
        }

        @Override
        public int calculateTotalExperiencePoints()
        {
            return totalExperiencePoints;
        }

        @Override
        public void setExperienceLevelAndProgress(int totalExperiencePoints)
        {
            this.totalExperiencePoints = totalExperiencePoints;
        }

        @Override
        public boolean getCanPickupItems()
        {
            return canPickupItems;
        }

        @Override
        public void setCanPickupItems(boolean canPickupItems)
        {
            this.canPickupItems = canPickupItems;
        }
    }
}
