package me.jackcw.duels.reward;

import me.jackcw.duels.match.MatchEndReason;
import org.bukkit.Material;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the reward resolution model: the sparse merge, precedence, multipliers, the
 * disconnect policy, and the difference between a file Duels refuses and a block it
 * skips.
 *
 * <p>The merge is worth testing this heavily because every bug in it is silent - it
 * pays the wrong amount rather than throwing, and nobody notices until a player counts
 * their coins.
 */
class RewardTableTest
{
    /** Arena 1 is Colosseum, arena 2 is Pit; kit 2 is Sumo, kit 3 is Classic. */
    private static final Map<String, Integer> ARENAS = Map.of("1", 1, "colosseum", 1, "2", 2, "pit", 2);
    private static final Map<String, Integer> KITS = Map.of("2", 2, "sumo", 2, "3", 3, "classic", 3);

    private ServerMock server;
    private PluginMock plugin;

    @BeforeEach
    void setup()
    {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
    }

    @AfterEach
    void cleanup()
    {
        MockBukkit.unmock();
    }

    // --- the sparse merge --------------------------------------------------

    @Test
    void anOverrideReplacesOnlyTheFieldsItActuallySets()
    {
        RewardTable table = valid("""
                defaults:
                  win:
                    money: 100.0
                    experience: 25
                    items:
                      - material: DIAMOND
                overrides:
                  arenas:
                    Colosseum:
                      win:
                        money: 200.0
                  kits:
                    Sumo:
                      win:
                        experience: 50
                """);

        RewardResolution resolution = table.resolve(RewardOutcome.WIN, 2, 1, null);

        assertEquals(200.0, money(resolution));
        assertEquals(50, experience(resolution));
        assertEquals(1, resolution.bundle().grantsOfType(RewardGrant.ItemGrant.class).size(),
                "the arena and kit said nothing about items, so the default item survives");
    }

    @Test
    void aKitBeatsAnArenaOnTheSameField()
    {
        RewardTable table = valid("""
                defaults:
                  win:
                    money: 100.0
                overrides:
                  arenas:
                    Colosseum:
                      win:
                        money: 200.0
                  kits:
                    Sumo:
                      win:
                        money: 300.0
                """);

        assertEquals(300.0, money(table.resolve(RewardOutcome.WIN, 2, 1, null)));
    }

    @Test
    void anExplicitlyEmptyListClearsWhatALessSpecificLayerSet()
    {
        RewardTable table = valid("""
                defaults:
                  win:
                    money: 100.0
                    items:
                      - material: DIAMOND
                overrides:
                  kits:
                    Sumo:
                      win:
                        items: []
                """);

        RewardResolution resolution = table.resolve(RewardOutcome.WIN, 2, null, null);

        assertEquals(100.0, money(resolution), "clearing items must not disturb the money");
        assertTrue(resolution.bundle().grantsOfType(RewardGrant.ItemGrant.class).isEmpty());
    }

    @Test
    void aCombinationBeatsBothTheArenaAndTheKit()
    {
        RewardTable table = valid("""
                defaults:
                  win:
                    money: 100.0
                overrides:
                  arenas:
                    Colosseum:
                      win:
                        money: 200.0
                  kits:
                    Sumo:
                      win:
                        money: 300.0
                  combinations:
                    - arena: Colosseum
                      kit: Sumo
                      win:
                        money: 500.0
                """);

        assertEquals(500.0, money(table.resolve(RewardOutcome.WIN, 2, 1, null)));
        assertEquals(300.0, money(table.resolve(RewardOutcome.WIN, 2, 2, null)),
                "the combination names arena 1, so arena 2 falls back to the kit");
    }

    @Test
    void overridesForAnotherOutcomeDoNotLeakIntoThisOne()
    {
        RewardTable table = valid("""
                defaults:
                  win:
                    money: 100.0
                  loss:
                    money: 25.0
                overrides:
                  kits:
                    Sumo:
                      win:
                        money: 999.0
                """);

        assertEquals(25.0, money(table.resolve(RewardOutcome.LOSS, 2, null, null)));
    }

    @Test
    void theTraceNamesEveryLayerThatContributed()
    {
        RewardTable table = valid("""
                defaults:
                  win:
                    money: 100.0
                overrides:
                  arenas:
                    Colosseum:
                      win:
                        money: 200.0
                """);

        assertEquals(List.of("defaults.win", "overrides.arenas.1.win"),
                table.resolve(RewardOutcome.WIN, 2, 1, null).appliedLayers());
    }

    @Test
    void anAmountOfZeroProducesNoGrantAtAll()
    {
        RewardTable table = valid("""
                defaults:
                  loss:
                    money: 0
                    experience: 0
                """);

        assertTrue(table.resolve(RewardOutcome.LOSS, null, null, null).bundle().isEmpty());
    }

    // --- multipliers ------------------------------------------------------

    @Test
    void onlyTheHighestMultiplierAppliesAndTheyDoNotStack()
    {
        RewardTable table = valid("""
                defaults:
                  win:
                    money: 100.0
                    experience: 20
                    items:
                      - material: DIAMOND
                multipliers:
                  - permission: duels.rewards.vip
                    factor: 1.5
                  - permission: duels.rewards.mvp
                    factor: 2.0
                """);

        PlayerMock player = playerWith("duels.rewards.vip", "duels.rewards.mvp");
        RewardResolution resolution = table.resolve(RewardOutcome.WIN, null, null, player);

        assertEquals(2.0, resolution.multiplier());
        assertEquals("duels.rewards.mvp", resolution.multiplierPermission());
        assertEquals(200.0, money(resolution));
        assertEquals(40, experience(resolution));
        assertEquals(1, resolution.bundle().grantsOfType(RewardGrant.ItemGrant.class).size(),
                "a 2x must not duplicate items");
    }

    @Test
    void aPenaltyMultiplierBelowOneStillApplies()
    {
        RewardTable table = valid("""
                defaults:
                  win:
                    money: 100.0
                multipliers:
                  - permission: duels.rewards.probation
                    factor: 0.5
                """);

        RewardResolution resolution = table.resolve(RewardOutcome.WIN, null, null,
                playerWith("duels.rewards.probation"));

        assertEquals(0.5, resolution.multiplier());
        assertEquals(50.0, money(resolution));
    }

    @Test
    void aZeroMultiplierSuppressesItemsAndCommandsToo()
    {
        RewardTable table = valid("""
                defaults:
                  win:
                    money: 100.0
                    items:
                      - material: DIAMOND
                    commands:
                      - 'crate give %player% duel 1'
                multipliers:
                  - permission: duels.rewards.excluded
                    factor: 0
                """);

        assertTrue(table.resolve(RewardOutcome.WIN, null, null, playerWith("duels.rewards.excluded"))
                .bundle().isEmpty());
    }

    @Test
    void aPlayerWithNoMultiplierPermissionIsPaidTheBaseAmount()
    {
        RewardTable table = valid("""
                defaults:
                  win:
                    money: 100.0
                multipliers:
                  - permission: duels.rewards.vip
                    factor: 1.5
                """);

        RewardResolution resolution = table.resolve(RewardOutcome.WIN, null, null, playerWith());

        assertFalse(resolution.hasMultiplier());
        assertEquals(100.0, money(resolution));
    }

    // --- disconnect policy ------------------------------------------------

    @Test
    void byDefaultADisconnectPaysTheWinnerOnly()
    {
        RewardTable table = valid("defaults:\n  win:\n    money: 100.0\n");

        assertTrue(table.pays(MatchEndReason.DISCONNECT, RewardOutcome.WIN));
        assertFalse(table.pays(MatchEndReason.DISCONNECT, RewardOutcome.LOSS));
    }

    @Test
    void theDisconnectPolicyIsConfigurableAndAcceptsHyphens()
    {
        RewardTable neither = valid("pay-on-disconnect: neither\n");
        assertFalse(neither.pays(MatchEndReason.DISCONNECT, RewardOutcome.WIN));
        assertFalse(neither.pays(MatchEndReason.DISCONNECT, RewardOutcome.LOSS));

        RewardTable both = valid("pay-on-disconnect: BOTH\n");
        assertTrue(both.pays(MatchEndReason.DISCONNECT, RewardOutcome.WIN));
        assertTrue(both.pays(MatchEndReason.DISCONNECT, RewardOutcome.LOSS));
    }

    @Test
    void walkingOutOfTheArenaBoundaryStillPaysLikeAnyOtherLoss()
    {
        RewardTable table = valid("pay-on-disconnect: NEITHER\n");

        assertTrue(table.pays(MatchEndReason.BOUNDARY_FORFEIT, RewardOutcome.LOSS));
        assertTrue(table.pays(MatchEndReason.DEFEAT, RewardOutcome.LOSS));
    }

    // --- values -----------------------------------------------------------

    @Test
    void commandsAreStoredWithoutALeadingSlash()
    {
        RewardTable table = valid("""
                defaults:
                  win:
                    commands:
                      - '/crate give %player% duel 1'
                      - 'broadcast %player% won'
                """);

        List<RewardGrant.CommandGrant> commands = table.resolve(RewardOutcome.WIN, null, null, null)
                .bundle().grantsOfType(RewardGrant.CommandGrant.class);

        assertEquals(List.of("crate give %player% duel 1", "broadcast %player% won"),
                commands.stream().map(RewardGrant.CommandGrant::command).toList());
    }

    @Test
    void itemsUseTheSameFormatAsEveryOtherItemInDuels()
    {
        RewardTable table = valid("""
                defaults:
                  win:
                    items:
                      - material: DIAMOND
                        amount: 3
                        name: '&bDuel Prize'
                """);

        List<RewardGrant.ItemGrant> items = table.resolve(RewardOutcome.WIN, null, null, null)
                .bundle().grantsOfType(RewardGrant.ItemGrant.class);

        assertEquals(1, items.size());
        assertEquals(Material.DIAMOND, items.getFirst().item().getType());
        assertEquals(3, items.getFirst().item().getAmount());
    }

    @Test
    void switchingRewardsOffPaysNothingWhileLeavingTheFileIntact()
    {
        RewardTable table = valid("""
                enabled: false
                defaults:
                  win:
                    money: 100.0
                """);

        assertFalse(table.isEnabled());
        assertTrue(table.resolve(RewardOutcome.WIN, null, null, null).bundle().isEmpty());
    }

    // --- validation: errors reject the whole file --------------------------

    @Test
    void aMisspelledKeyRejectsTheWholeFileRatherThanPayingTheDefault()
    {
        RewardTableLoader.Result result = load("""
                defaults:
                  win:
                    monney: 500.0
                """);

        assertFalse(result.isValid());
        assertTrue(result.errors().getFirst().contains("defaults.win.monney"), result.errors().toString());
        assertTrue(result.table().resolve(RewardOutcome.WIN, null, null, null).bundle().isEmpty(),
                "a refused file must pay nothing at all, not partially apply");
    }

    @Test
    void aNegativeAmountIsRejected()
    {
        assertFalse(load("defaults:\n  win:\n    money: -50.0\n").isValid());
        assertFalse(load("defaults:\n  win:\n    experience: -1\n").isValid());
    }

    @Test
    void theSameArenaConfiguredTwiceUnderDifferentKeysIsRejected()
    {
        RewardTableLoader.Result result = load("""
                overrides:
                  arenas:
                    1:
                      win:
                        money: 100.0
                    Colosseum:
                      win:
                        money: 200.0
                """);

        assertFalse(result.isValid());
    }

    @Test
    void aRepeatedMultiplierPermissionIsRejected()
    {
        RewardTableLoader.Result result = load("""
                multipliers:
                  - permission: duels.rewards.vip
                    factor: 1.5
                  - permission: duels.rewards.vip
                    factor: 3.0
                """);

        assertFalse(result.isValid());
    }

    @Test
    void anUnknownDisconnectPolicyIsRejected()
    {
        assertFalse(load("pay-on-disconnect: SOMETIMES\n").isValid());
    }

    @Test
    void anOutcomeWrittenAsASingleValueIsRejected()
    {
        assertFalse(load("defaults:\n  win: 100\n").isValid());
    }

    @Test
    void aCombinationMissingItsKitIsRejected()
    {
        RewardTableLoader.Result result = load("""
                overrides:
                  combinations:
                    - arena: Colosseum
                      win:
                        money: 500.0
                """);

        assertFalse(result.isValid());
    }

    // --- validation: warnings skip one block ------------------------------

    @Test
    void anOverrideNamingADeletedArenaIsSkippedWithAWarningNotARefusal()
    {
        RewardTableLoader.Result result = load("""
                defaults:
                  win:
                    money: 100.0
                overrides:
                  arenas:
                    Atlantis:
                      win:
                        money: 900.0
                """);

        assertTrue(result.isValid(), "deleting one arena must not switch off the whole economy");
        assertEquals(1, result.warnings().size());
        assertTrue(result.warnings().getFirst().contains("Atlantis"));
        assertEquals(0, result.table().arenaOverrideCount());
        assertEquals(100.0, money(result.table().resolve(RewardOutcome.WIN, null, null, null)));
    }

    @Test
    void aCombinationNamingADeletedKitIsSkippedWithAWarning()
    {
        RewardTableLoader.Result result = load("""
                overrides:
                  combinations:
                    - arena: Colosseum
                      kit: Ghost
                      win:
                        money: 500.0
                """);

        assertTrue(result.isValid());
        assertEquals(1, result.warnings().size());
        assertEquals(0, result.table().combinationOverrideCount());
    }

    @Test
    void aTypoInsideASkippedBlockIsStillReported()
    {
        RewardTableLoader.Result result = load("""
                overrides:
                  combinations:
                    - arena: Colosseum
                      kit: Ghost
                      win:
                        monney: 500.0
                """);

        assertFalse(result.isValid(), "the block is ignored, but the typo in it would outlive the missing kit");
    }

    // --- the shipped file --------------------------------------------------

    @Test
    void theOverrideCountsReportWhatWasLoaded()
    {
        RewardTable table = valid("""
                overrides:
                  arenas:
                    Colosseum:
                      win:
                        money: 1.0
                    Pit:
                      win:
                        money: 2.0
                  kits:
                    Sumo:
                      win:
                        money: 3.0
                  combinations:
                    - arena: Pit
                      kit: Classic
                      win:
                        money: 4.0
                """);

        assertEquals(2, table.arenaOverrideCount());
        assertEquals(1, table.kitOverrideCount());
        assertEquals(1, table.combinationOverrideCount());
    }

    /**
     * The default file is the one almost every server will run, and it is written by hand
     * in a format that refuses unknown keys - so a stray comment marker or a misspelled
     * example would ship rewards switched off for everybody.
     */
    @Test
    void theRewardsFileDuelsShipsWithLoadsCleanly() throws Exception
    {
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream("rewards.yml"))
        {
            assertNotNull(stream, "rewards.yml is missing from the plugin resources");

            RewardTableLoader.Result result = load(new String(stream.readAllBytes(), StandardCharsets.UTF_8));

            assertTrue(result.isValid(), () -> "the shipped rewards.yml is invalid: " + result.errors());
            assertTrue(result.warnings().isEmpty(), () -> result.warnings().toString());
            assertTrue(result.table().isEnabled());
            assertEquals(PayOnDisconnect.WINNER_ONLY, result.table().payOnDisconnect());
            assertEquals(100.0, money(result.table().resolve(RewardOutcome.WIN, null, null, null)));
            assertEquals(25.0, money(result.table().resolve(RewardOutcome.LOSS, null, null, null)));
        }
    }

    @Test
    void aFileThatConfiguresNothingResolvesToNothingRatherThanFailing()
    {
        RewardTableLoader.Result result = load("enabled: true\n");

        assertTrue(result.isValid());
        RewardResolution resolution = result.table().resolve(RewardOutcome.WIN, 2, 1, null);
        assertTrue(resolution.bundle().isEmpty());
        assertTrue(resolution.appliedLayers().isEmpty());
        assertNull(resolution.multiplierPermission());
    }

    // --- helpers -----------------------------------------------------------

    private RewardTable valid(String yaml)
    {
        RewardTableLoader.Result result = load(yaml);
        assertTrue(result.isValid(), () -> "expected a clean load but got " + result.errors());
        return result.table();
    }

    private RewardTableLoader.Result load(String yaml)
    {
        YamlConfiguration configuration = new YamlConfiguration();

        try
        {
            configuration.loadFromString(yaml);
        }
        catch (InvalidConfigurationException exception)
        {
            throw new IllegalArgumentException("Test YAML is not valid", exception);
        }

        return RewardTableLoader.load(configuration, resolver(ARENAS), resolver(KITS));
    }

    private static RewardTableLoader.ResourceResolver resolver(Map<String, Integer> known)
    {
        return token -> known.get(token.toLowerCase(Locale.ROOT));
    }

    private PlayerMock playerWith(String... permissions)
    {
        PlayerMock player = server.addPlayer();

        for (String permission : permissions)
            player.addAttachment(plugin, permission, true);

        return player;
    }

    private static double money(RewardResolution resolution)
    {
        List<RewardGrant.MoneyGrant> grants = resolution.bundle().grantsOfType(RewardGrant.MoneyGrant.class);
        return grants.isEmpty() ? 0.0 : grants.getFirst().amount();
    }

    private static int experience(RewardResolution resolution)
    {
        List<RewardGrant.ExperienceGrant> grants =
                resolution.bundle().grantsOfType(RewardGrant.ExperienceGrant.class);
        return grants.isEmpty() ? 0 : grants.getFirst().amount();
    }
}
