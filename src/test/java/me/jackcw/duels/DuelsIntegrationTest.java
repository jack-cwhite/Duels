package me.jackcw.duels;

import me.jackcw.duels.arena.Arena;
import me.jackcw.duels.arena.ArenaInstance;
import me.jackcw.duels.challenge.Challenge;
import me.jackcw.duels.kit.Kit;
import me.jackcw.duels.match.Match;
import me.jackcw.duels.match.MatchState;
import me.jackcw.duels.stats.LeaderboardEntry;
import me.jackcw.jcore.database.Database;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import java.util.List;
import java.util.UUID;

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
    void playersCanHoldMultiplePendingChallengesButNotDuplicatePairs()
    {
        PlayerMock alice = server.addPlayer("Alice");
        PlayerMock bob = server.addPlayer("Bob");
        PlayerMock charlie = server.addPlayer("Charlie");

        assertTrue(plugin.getChallengeManager().createChallenge(alice, bob));
        assertTrue(plugin.getChallengeManager().createChallenge(alice, charlie));
        assertTrue(plugin.getChallengeManager().createChallenge(charlie, bob));
        assertFalse(plugin.getChallengeManager().createChallenge(alice, bob));

        Challenge accepted = plugin.getChallengeManager().acceptChallenge(bob.getUniqueId(), alice.getUniqueId());
        assertNotNull(accepted);
        assertTrue(plugin.getChallengeManager().createChallenge(alice, bob));
    }

    @Test
    void matchKeepsIndependentKitSnapshot()
    {
        WorldMock world = server.addSimpleWorld("kit_snapshot_world");
        Arena arena = new Arena(1, "Test Arena");
        arena.setSpawn1(new Location(world, 0, 64, 0));
        arena.setSpawn2(new Location(world, 10, 64, 10));
        Kit kit = new Kit(1, "Sword");
        ItemStack[] contents = new ItemStack[36];
        contents[0] = new ItemStack(Material.IRON_SWORD);
        kit.setContents(contents);

        Match match =
            new Match(
                UUID.randomUUID(),
                UUID.randomUUID(),
                new ArenaInstance(arena.getId(), arena.getSpawn1(), arena.getSpawn2()),
                new Location(null, 0, 0, 0),
                new Location(null, 1, 0, 0),
                List.of(kit));

        kit.getContents()[0] = new ItemStack(Material.WOODEN_SWORD);

        assertEquals(Material.IRON_SWORD, match.getAvailableKits().getFirst().getContents()[0].getType());
    }

    @Test
    void commandCreatedKitKeepsStorageArmorAndOffhandSeparate()
    {
        PlayerMock player = server.addPlayer("KitCreator");
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
        arena.setSpawn1(new Location(world, 0, 64, 0));
        arena.setSpawn2(new Location(world, 10, 64, 10));

        plugin.getKitManager().createKit("Warrior");

        PlayerMock alice = server.addPlayer("Alice");
        PlayerMock bob = server.addPlayer("Bob");

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
        arena.setSpawn1(new Location(world, 0, 64, 0));
        arena.setSpawn2(new Location(world, 10, 64, 10));

        PlayerMock alice = server.addPlayer("Alice");
        PlayerMock bob = server.addPlayer("Bob");

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
        arena.setSpawn1(new Location(world, 0, 64, 0));
        arena.setSpawn2(new Location(world, 10, 64, 10));

        PlayerMock alice = server.addPlayer("Alice");
        PlayerMock bob = server.addPlayer("Bob");

        Match match = plugin.getMatchManager().startMatch(alice, bob);
        server.getScheduler().performTicks((plugin.getSettings().kitSelectionSeconds() + 1) * 20L);
        assertEquals(MatchState.GRACE, match.getState());

        bob.disconnect();

        assertEquals(1, plugin.getStatsManager().getWins(alice.getUniqueId()).join());
        assertEquals(1, plugin.getStatsManager().getLosses(bob.getUniqueId()).join());
    }
}
