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
import me.jackcw.duels.arena.DynamicArenaSlot;
import me.jackcw.duels.arena.DynamicArenaSlotManager;
import me.jackcw.duels.arena.DynamicArenaRecovery;
import me.jackcw.duels.arena.ArenaProvisioningMode;
import me.jackcw.duels.arena.ArenaTemplateDefinition;
import me.jackcw.duels.arena.RelativeArenaLocation;
import me.jackcw.duels.arena.RelativeBlockPosition;
import me.jackcw.duels.arena.ArenaAccessGuard;
import me.jackcw.duels.arena.MatchInterferenceGuard;
import me.jackcw.duels.arena.ArenaContainmentGuard;
import me.jackcw.duels.arena.BlockChangeRollbackStrategy;
import me.jackcw.duels.arena.ArenaBoundsValidator;
import me.jackcw.duels.arena.BlockBox;
import me.jackcw.duels.challenge.Challenge;
import me.jackcw.duels.kit.Kit;
import me.jackcw.duels.kit.KitEffectMutationResult;
import me.jackcw.duels.diagnostics.DuelsDiagnostics;
import me.jackcw.duels.match.Match;
import me.jackcw.duels.match.MatchResult;
import me.jackcw.duels.match.MatchEndReason;
import me.jackcw.duels.match.MatchState;
import me.jackcw.duels.match.MatchStartResult;
import me.jackcw.duels.spectator.SpectateResult;
import me.jackcw.duels.stats.LeaderboardEntry;
import me.jackcw.duels.stats.LeaderboardMetric;
import me.jackcw.duels.stats.MatchHistoryEntry;
import me.jackcw.duels.stats.PlayerStats;
import me.jackcw.duels.stats.StatsQuery;
import me.jackcw.duels.stats.YamlStatsRepository;
import me.jackcw.jcore.database.Database;
import org.bukkit.ExplosionResult;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FishHook;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityCombustByBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

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
        built.setBoundsCorner1(new Location(world, -10, 60, -10));
        built.setBoundsCorner2(new Location(world, 10, 70, 10));
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
    void exactDuplicateGeneratedSlotIsBackedUpAndRepairedWithoutClearingWorld() throws IOException
    {
        WorldMock world = server.addSimpleWorld("duplicate_slot_world");
        Arena arena = plugin.getArenaManager().createArena("Duplicate", ArenaProvisioningMode.DYNAMIC);
        ArenaInstance first = plugin.getArenaInstanceManager().createProvisionedInstance(
                arena.getId(), 0, 1, new ArenaStructureSize(8, 8, 8));
        ArenaInstance second = plugin.getArenaInstanceManager().createProvisionedInstance(
                arena.getId(), 0, 1, new ArenaStructureSize(8, 8, 8));
        for (ArenaInstance copy : List.of(first, second))
        {
            copy.setSpawn1(new Location(world, 1, 64, 1));
            copy.setSpawn2(new Location(world, 6, 64, 6));
            copy.setBoundsCorner1(new Location(world, 0, 63, 0));
            copy.setBoundsCorner2(new Location(world, 7, 70, 7));
            plugin.getArenaInstanceManager().setDynamicState(copy, DynamicArenaState.READY);
        }

        new DynamicArenaRecovery(plugin).recover();

        assertNotNull(plugin.getArenaInstanceManager().getInstance(first.getId()));
        assertNull(plugin.getArenaInstanceManager().getInstance(second.getId()));
        assertTrue(plugin.getDynamicArenaSlotManager().isOccupied(0));
        try (var files = Files.list(plugin.getDataFolder().toPath()))
        {
            assertTrue(files.anyMatch(path -> path.getFileName().toString().startsWith("arena-instances-before-duplicate-repair-")));
        }
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
        var capacityLore = dynamicMenu.getItem(4).getItemMeta().lore();
        assertNotNull(capacityLore);
        String plainCapacityLore = String.join("\n", capacityLore.stream()
                .map(PlainTextComponentSerializer.plainText()::serialize).toList());
        assertTrue(plainCapacityLore.contains("0/64 occupied"));
        assertTrue(plainCapacityLore.contains("Available for new copies: 64"));

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
    void dynamicSlotCapacityIsBoundedAndVacatingARetiredSlotMakesItReusable()
    {
        DynamicArenaSlotManager slotManager = plugin.getDynamicArenaSlotManager();
        Path layoutPath = plugin.getDataFolder().toPath().resolve("dynamic-layout.yml");
        assertFalse(Files.exists(layoutPath));
        DynamicArenaSlotManager.Capacity initial = slotManager.capacity();

        assertEquals(0, initial.occupied());
        assertEquals(0, initial.reserved());
        assertEquals(64, initial.maximum());
        assertEquals(64, initial.available());
        assertFalse(Files.exists(layoutPath), "reading capacity must not create the dynamic layout on a static-only server");

        List<DynamicArenaSlot> reservations = new ArrayList<>();
        for (int expected = 0; expected < initial.maximum(); expected++)
        {
            DynamicArenaSlot slot = slotManager.reserveNext();
            assertNotNull(slot);
            assertEquals(expected, slot.index());
            reservations.add(slot);
        }

        assertNull(slotManager.reserveNext(), "the hard limit must not silently grow the world");
        assertEquals(initial.maximum(), slotManager.capacity().reserved());
        assertEquals(0, slotManager.capacity().available());
        DuelsDiagnostics.Snapshot full = plugin.getDiagnostics().snapshot();
        assertEquals(64, full.dynamicReservedSlots());
        assertEquals(64, full.dynamicMaximumSlots());
        assertTrue(plugin.getDiagnostics().describe(full).stream()
                .anyMatch(line -> line.contains("64") && line.contains("reserved")));

        slotManager.markOccupied(reservations.getFirst().index());
        for (int index = 1; index < reservations.size(); index++)
            slotManager.releaseReservation(reservations.get(index).index());

        DynamicArenaSlotManager.Capacity occupied = slotManager.capacity();
        assertEquals(1, occupied.occupied());
        assertEquals(0, occupied.reserved());
        assertEquals(63, occupied.available());

        slotManager.markVacant(reservations.getFirst().index());
        assertEquals(64, slotManager.capacity().available());
        assertEquals(0, slotManager.reserveNext().index(), "retirement must make the same slot reusable");
    }

    @Test
    void exhaustedDynamicCapacityReachesTheMatchResultWithoutMutatingPlayers() throws IOException
    {
        DynamicArenaSlotManager slotManager = plugin.getDynamicArenaSlotManager();
        for (int index = 0; index < slotManager.capacity().maximum(); index++)
            assertNotNull(slotManager.reserveNext());

        Arena arena = plugin.getArenaManager().createArena("Castle", ArenaProvisioningMode.DYNAMIC);
        ArenaTemplateDefinition template = new ArenaTemplateDefinition(
                1,
                plugin.getArenaTemplateManager().getProvider().id(),
                "capacity-test.nbt",
                new ArenaStructureSize(1, 1, 1),
                new RelativeArenaLocation(0, 0, 0, 0, 0),
                new RelativeArenaLocation(0, 0, 0, 0, 0),
                new RelativeBlockPosition(0, 0, 0),
                new RelativeBlockPosition(0, 0, 0));
        arena.setTemplateDefinition(template);
        Path templatePath = plugin.getArenaTemplateManager().getStructurePath(template);
        Files.createDirectories(templatePath.getParent());
        Files.writeString(templatePath, "capacity test");

        PlayerMock alice = addPlayer("Alice");
        PlayerMock bob = addPlayer("Bob");
        MatchStartResult result = plugin.getMatchManager()
                .startMatchAsync(alice, bob, ArenaSelection.specific(arena.getId())).join();

        assertEquals(MatchStartResult.Status.ARENA_CAPACITY_REACHED, result.status());
        assertNull(result.match());
        assertNull(plugin.getMatchManager().getMatch(alice.getUniqueId()));
        assertNull(plugin.getMatchManager().getMatch(bob.getUniqueId()));
        assertFalse(plugin.getMatchManager().isPending(alice.getUniqueId()));
        assertFalse(plugin.getMatchManager().isPending(bob.getUniqueId()));
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
                "PlayerOne",
                UUID.randomUUID(),
                "PlayerTwo",
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
                "INSERT INTO duels_matches (id, arena_id, winner_id, started_at, combat_started_at, ended_at, "
                        + "end_reason, ended_state, damage_cause) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                1, 1, winner.toString(), System.currentTimeMillis() - 1000, System.currentTimeMillis() - 500,
                System.currentTimeMillis(), "DEFEAT", "IN_PROGRESS", "ENTITY_ATTACK"
        );
        database.execute(
                "INSERT INTO duels_match_participants (match_id, player_id, player_name, kit_id, won) VALUES (?, ?, ?, ?, ?)",
                1, winner.toString(), "Winner", 1, true
        );
        database.execute(
                "INSERT INTO duels_match_participants (match_id, player_id, player_name, kit_id, won) VALUES (?, ?, ?, ?, ?)",
                1, loser.toString(), "Loser", 1, false
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
        assertNotNull(match.getCombatStartedAt());
        assertTrue(match.getCombatStartedAt() >= match.getStartedAt());
    }

    /** Starts a match with one kit already carrying a Speed II baseline, past kit selection. */
    private Match startMatchWithSpeedBaseline(PlayerMock alice, PlayerMock bob, WorldMock world)
    {
        Arena arena = plugin.getArenaManager().createArena("Colosseum");
        createReadyInstance(arena, world);

        Kit kit = plugin.getKitManager().createKit("Speedster");
        assertEquals(KitEffectMutationResult.Status.SUCCESS,
                plugin.getKitManager().addEffect(kit.getId(), "minecraft:speed", 2).status());

        Match match = plugin.getMatchManager().startMatch(alice, bob);
        assertNotNull(match);

        server.getScheduler().performTicks((plugin.getSettings().kitSelectionSeconds() + 1) * 20L);
        assertEquals(MatchState.GRACE, match.getState());

        return match;
    }

    @Test
    void kitEffectBaselineIsAppliedAfterKitSelection()
    {
        WorldMock world = server.addSimpleWorld("kit_effect_apply_world");
        PlayerMock alice = addPlayer("Alice");
        PlayerMock bob = addPlayer("Bob");
        startMatchWithSpeedBaseline(alice, bob, world);

        PotionEffect speed = alice.getPotionEffect(PotionEffectType.SPEED);
        assertNotNull(speed, "the kit's Speed II baseline must be applied once kit selection ends");
        assertEquals(1, speed.getAmplifier(), "level 2 is amplifier 1");
        assertTrue(speed.isInfinite(), "a kit baseline lasts exactly as long as the kit does, not a fixed duration");
    }

    @Test
    void weakerAttemptCannotDowngradeKitEffectBaseline()
    {
        WorldMock world = server.addSimpleWorld("kit_effect_downgrade_world");
        PlayerMock alice = addPlayer("Alice");
        PlayerMock bob = addPlayer("Bob");
        startMatchWithSpeedBaseline(alice, bob, world);

        alice.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 200, 0));

        PotionEffect speed = alice.getPotionEffect(PotionEffectType.SPEED);
        assertNotNull(speed);
        assertEquals(1, speed.getAmplifier(), "a weaker same-type effect must not downgrade the kit baseline");
    }

    @Test
    void strongerAttemptCannotOverrideKitEffectBaseline()
    {
        WorldMock world = server.addSimpleWorld("kit_effect_override_world");
        PlayerMock alice = addPlayer("Alice");
        PlayerMock bob = addPlayer("Bob");
        startMatchWithSpeedBaseline(alice, bob, world);

        alice.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 200, 2));

        PotionEffect speed = alice.getPotionEffect(PotionEffectType.SPEED);
        assertNotNull(speed);
        assertEquals(1, speed.getAmplifier(),
                "a stronger same-type effect must not override the kit baseline either - it could grant more than the kit was balanced to give");
    }

    @Test
    void kitEffectBaselineCannotBeRemovedWhileTheKitIsHeld()
    {
        WorldMock world = server.addSimpleWorld("kit_effect_removal_world");
        PlayerMock alice = addPlayer("Alice");
        PlayerMock bob = addPlayer("Bob");
        startMatchWithSpeedBaseline(alice, bob, world);

        // Simulates either a milk bucket or natural expiry - both remove the
        // active effect through the same event.
        alice.removePotionEffect(PotionEffectType.SPEED);

        PotionEffect speed = alice.getPotionEffect(PotionEffectType.SPEED);
        assertNotNull(speed, "a kit baseline cannot be removed by milk or anything else while the kit is held");
        assertEquals(1, speed.getAmplifier());
    }

    @Test
    void kitEffectBaselineDoesNotSurviveMatchEnd()
    {
        WorldMock world = server.addSimpleWorld("kit_effect_cleanup_world");
        PlayerMock alice = addPlayer("Alice");
        PlayerMock bob = addPlayer("Bob");
        Match match = startMatchWithSpeedBaseline(alice, bob, world);

        assertNotNull(alice.getPotionEffect(PotionEffectType.SPEED));

        plugin.getMatchManager().endMatch(match, bob.getUniqueId());

        assertNull(alice.getPotionEffect(PotionEffectType.SPEED),
                "a kit baseline must not outlive the match that applied it");
    }

    @Test
    void duelistCannotDamageBystander()
    {
        WorldMock world = server.addSimpleWorld("combat_isolation_world");
        Arena arena = plugin.getArenaManager().createArena("Pit");
        createReadyInstance(arena, world);
        PlayerMock alice = addPlayer("Alice");
        PlayerMock bob = addPlayer("Bob");
        PlayerMock charlie = addPlayer("Charlie");
        Match match = plugin.getMatchManager().startMatch(alice, bob);
        assertNotNull(match);
        match.setState(MatchState.IN_PROGRESS);

        EntityDamageByEntityEvent attack = new EntityDamageByEntityEvent(
                alice, charlie, EntityDamageEvent.DamageCause.ENTITY_ATTACK, 3.0);
        server.getPluginManager().callEvent(attack);
        assertTrue(attack.isCancelled());
    }

    /**
     * A match stops being live before its arena is clean. Rollback starts one
     * tick later and may then span several ticks, so environmental protection
     * must cover that entire gap rather than disappear with the match entry.
     */
    @Test
    void bystanderRemainsProtectedUntilArenaRollbackCompletes()
    {
        WorldMock world = server.addSimpleWorld("post_match_bystander_world");
        Arena arena = plugin.getArenaManager().createArena("Pit");
        createReadyInstance(arena, world);
        PlayerMock alice = addPlayer("Alice");
        PlayerMock bob = addPlayer("Bob");
        PlayerMock charlie = addPlayer("Charlie");
        Match match = startInProgressMatch(alice, bob);
        Location hazardLocation = new Location(world, 0, 64, 5);
        charlie.setLocation(hazardLocation);

        // One more change than a reset batch guarantees that the reset cannot
        // finish on its first rollback tick.
        BlockChangeRollbackStrategy strategy = (BlockChangeRollbackStrategy) plugin.getArenaResetStrategy();
        int changedBlocks = plugin.getSettings().arenaResetBlocksPerTick() + 1;

        for (int i = 0; i < changedBlocks; i++)
        {
            Block block = world.getBlockAt(-32 + i, 63, 0);
            block.setType(Material.STONE);
            strategy.onBlockBreak(new BlockBreakEvent(block, alice));
            block.setType(Material.AIR);
        }

        plugin.getMatchManager().endMatch(match, alice.getUniqueId());

        assertTrue(plugin.getMatchManager().isInsideUnsafeArena(hazardLocation));
        EntityDamageEvent immediatelyAfterMatch = new EntityDamageEvent(
                charlie, EntityDamageEvent.DamageCause.LAVA,
                DamageSource.builder(DamageType.LAVA).withDamageLocation(hazardLocation).build(), 4.0);
        server.getPluginManager().callEvent(immediatelyAfterMatch);
        assertTrue(immediatelyAfterMatch.isCancelled());

        server.getScheduler().performTicks(1L);

        assertTrue(plugin.getMatchManager().isInsideUnsafeArena(hazardLocation),
                "protection must remain while a multi-tick rollback is still running");
        EntityDamageEvent duringRollback = new EntityDamageEvent(
                charlie, EntityDamageEvent.DamageCause.FIRE_TICK,
                DamageSource.builder(DamageType.ON_FIRE).withDamageLocation(hazardLocation).build(), 1.0);
        server.getPluginManager().callEvent(duringRollback);
        assertTrue(duringRollback.isCancelled());

        server.getScheduler().performTicks(5L);

        assertFalse(plugin.getMatchManager().isInsideUnsafeArena(hazardLocation));
        EntityDamageEvent afterRollback = new EntityDamageEvent(
                charlie, EntityDamageEvent.DamageCause.LAVA,
                DamageSource.builder(DamageType.LAVA).withDamageLocation(hazardLocation).build(), 4.0);
        server.getPluginManager().callEvent(afterRollback);
        assertFalse(afterRollback.isCancelled(),
                "an idle arena must not make an unrelated player immune to ordinary hazards");
    }

    /**
     * Cancelling damage alone leaves fire ticks on a bystander. Block-caused
     * combustion is therefore stopped while the arena is unsafe, but not for a
     * combatant or for somebody outside that arena.
     */
    @Test
    void bystanderCombustionIsBlockedOnlyInsideAnUnsafeArena()
    {
        WorldMock world = server.addSimpleWorld("bystander_combustion_world");
        Arena arena = plugin.getArenaManager().createArena("Pit");
        createReadyInstance(arena, world);
        PlayerMock alice = addPlayer("Alice");
        PlayerMock bob = addPlayer("Bob");
        PlayerMock charlie = addPlayer("Charlie");
        Match match = startInProgressMatch(alice, bob);
        Block lava = world.getBlockAt(0, 64, 5);
        charlie.setLocation(lava.getLocation());

        EntityCombustByBlockEvent bystanderCombustion = new EntityCombustByBlockEvent(lava, charlie, 15.0f);
        server.getPluginManager().callEvent(bystanderCombustion);
        assertTrue(bystanderCombustion.isCancelled());

        EntityCombustByBlockEvent combatantCombustion = new EntityCombustByBlockEvent(lava, alice, 15.0f);
        server.getPluginManager().callEvent(combatantCombustion);
        assertFalse(combatantCombustion.isCancelled(), "duellists must still burn normally");

        charlie.setLocation(new Location(world, 500, 64, 500));
        EntityCombustByBlockEvent outsideCombustion = new EntityCombustByBlockEvent(lava, charlie, 15.0f);
        server.getPluginManager().callEvent(outsideCombustion);
        assertFalse(outsideCombustion.isCancelled(), "protection must not leak outside the arena");

        charlie.setLocation(lava.getLocation());
        plugin.getMatchManager().endMatch(match, alice.getUniqueId());

        EntityCombustByBlockEvent duringReset = new EntityCombustByBlockEvent(lava, charlie, 15.0f);
        server.getPluginManager().callEvent(duringReset);
        assertTrue(duringReset.isCancelled());

        server.getScheduler().performTicks(2L);

        EntityCombustByBlockEvent afterReset = new EntityCombustByBlockEvent(lava, charlie, 15.0f);
        server.getPluginManager().callEvent(afterReset);
        assertFalse(afterReset.isCancelled(), "combustion protection must end once the arena is clean");
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

        MatchHistoryEntry pregame = plugin.getStatsManager().getMatchHistory(
                StatsQuery.forPlayer(alice.getUniqueId()), 1, 0).join().getFirst();
        assertEquals(MatchEndReason.DISCONNECT, pregame.endReason());
        assertEquals(MatchState.PREGAME, pregame.endedState());
        assertNull(pregame.playerKitId());
        assertNull(pregame.combatStartedAt());

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

        MatchHistoryEntry grace = plugin.getStatsManager().getMatchHistory(
                StatsQuery.forPlayer(bob.getUniqueId()), 1, 0).join().getFirst();
        assertEquals(MatchEndReason.DISCONNECT, grace.endReason());
        assertEquals(MatchState.GRACE, grace.endedState());
        assertNull(grace.playerKitId(), "this arena has no configured kits, so grace is bare-fisted");
        assertNull(grace.combatStartedAt());
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
        server.getScheduler().performTicks(2L);

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
        server.getScheduler().performTicks(2L);

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
        server.getScheduler().performTicks(2L);

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
        server.getScheduler().performTicks(2L);

        assertTrue(firstCompleted[0]);
        assertEquals(Material.STONE, floor.getType());

        // Nothing was tracked for this second break - it happens after the
        // instance's change log was already cleared by the first reset - so
        // this is only here to prove the second reset does not touch it.
        floor.setType(Material.AIR);

        boolean[] secondCompleted = {false};
        strategy.reset(instance, () -> secondCompleted[0] = true);
        server.getScheduler().performTicks(2L);

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

    /**
     * Bounds are mandatory, so a match cannot start in a boundless arena at all
     * - the instance never becomes ready. The corners are therefore cleared on
     * the live instance after the match is under way, which is the one shape
     * this state can still take: it reaches the defensive check in
     * SpectatorManager rather than letting a null bounds box reach the code that
     * would build a spectator's camera box from it.
     */
    @Test
    void spectatingAnArenaWithoutBoundsIsRefused()
    {
        Match match = startSpectatableMatch("no_bounds_world", "Unbounded");
        ArenaInstance instance = match.getArenaInstance();

        instance.setBoundsCorner1(null);
        instance.setBoundsCorner2(null);

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

    /**
     * The counts {@code /duels diagnostics} reports have to come back to where
     * they started once a duel is over, because that is the whole basis on which
     * an administrator uses the command to judge whether something leaked. If a
     * count legitimately drifts across a clean match, every real leak it would
     * otherwise catch gets dismissed as normal drift.
     *
     * <p>Comparing whole snapshots rather than picked fields is deliberate: a
     * field added later is then covered by this test automatically, which is the
     * case most likely to introduce exactly this kind of drift.
     */
    @Test
    void diagnosticsCountsReturnToTheirBaselineAfterACleanMatch()
    {
        WorldMock world = server.addSimpleWorld("diagnostics_world");
        Arena arena = plugin.getArenaManager().createArena("Colosseum");
        createReadyInstance(arena, world);
        plugin.getKitManager().createKit("Warrior");

        PlayerMock alice = addPlayer("Alice");
        PlayerMock bob = addPlayer("Bob");

        DuelsDiagnostics diagnostics = plugin.getDiagnostics();
        DuelsDiagnostics.Snapshot baseline = diagnostics.snapshot();

        assertEquals(0, baseline.matches());

        Match match = startInProgressMatch(alice, bob);

        DuelsDiagnostics.Snapshot during = diagnostics.snapshot();

        assertEquals(1, during.matches());
        assertFalse(diagnostics.compare(baseline, during).isEmpty());

        plugin.getMatchManager().endMatch(match, alice.getUniqueId());
        server.getScheduler().performTicks(5L);

        assertEquals(baseline, diagnostics.snapshot());
        assertEquals(List.of(), diagnostics.compare(baseline, diagnostics.snapshot()));
    }

    /**
     * An admin deleting the world a duel was accepted in must not cost the
     * duellist their inventory. Losing where they stood is unavoidable; losing
     * what they were carrying is a bug, and discarding the whole snapshot is
     * how the earlier version handled it.
     */
    @Test
    void aSavedStateWhoseWorldIsGoneIsStillRestoredAtTheFallbackSpawn()
    {
        WorldMock hub = server.addSimpleWorld("state_restore_hub");
        WorldMock mine = server.addSimpleWorld("state_restore_mine");
        PlayerMock player = addPlayer("Miner");

        player.teleport(new Location(mine, 20, 40, 20));
        player.getInventory().setItem(0, new ItemStack(Material.DIAMOND_PICKAXE));
        player.setHealth(7.0);

        plugin.getPlayerStateManager().save(player);

        // What the real flow does next: the duel takes over the player, and the
        // world they came from stops existing while they are in it.
        player.getInventory().clear();
        player.setHealth(20.0);
        player.teleport(new Location(hub, 500, 70, 500));
        assertTrue(server.removeWorld(mine));

        assertTrue(plugin.getPlayerStateManager().restore(player));

        assertEquals(Material.DIAMOND_PICKAXE, player.getInventory().getItem(0).getType());
        assertEquals(7.0, player.getHealth());
        assertEquals(hub, player.getWorld());
        assertEquals(hub.getSpawnLocation().getBlockX(), player.getLocation().getBlockX());
        assertEquals(hub.getSpawnLocation().getBlockZ(), player.getLocation().getBlockZ());
        assertFalse(plugin.getPlayerStateManager().has(player));
    }

    /**
     * The ordinary case, kept alongside the one above so a change that always
     * used the fallback would fail rather than looking like an improvement.
     */
    @Test
    void aSavedStateWhoseWorldStillExistsIsRestoredExactlyWhereItWasCaptured()
    {
        WorldMock hub = server.addSimpleWorld("state_intact_hub");
        WorldMock mine = server.addSimpleWorld("state_intact_mine");
        PlayerMock player = addPlayer("Miner");

        Location captured = new Location(mine, 20, 40, 20);
        player.teleport(captured);
        plugin.getPlayerStateManager().save(player);

        player.teleport(new Location(hub, 0, 64, 0));

        assertTrue(plugin.getPlayerStateManager().restore(player));

        assertEquals(mine, player.getWorld());
        assertEquals(captured.getBlockX(), player.getLocation().getBlockX());
        assertEquals(captured.getBlockZ(), player.getLocation().getBlockZ());
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
        plugin.getArenaInstanceManager().setBoundsCorner(instance.getId(), 1, new Location(world, -50, 0, -50));
        plugin.getArenaInstanceManager().setBoundsCorner(instance.getId(), 2, new Location(world, 50, 128, 50));

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

    /**
     * Containment is scoped to "this match's own instance", not "inside some
     * arena", which is what makes it safe to run globally: a player who is not
     * duelling is never restricted, so ordinary building anywhere on the server
     * behaves exactly as it did before the guard existed.
     */
    @Test
    void buildingOutsideAnyMatchIsNotRestricted()
    {
        WorldMock world = server.addSimpleWorld("containment_bystander_world");
        Arena arena = plugin.getArenaManager().createArena("Colosseum");
        createReadyInstance(arena, world);

        PlayerMock charlie = addPlayer("Charlie");
        charlie.setLocation(new Location(world, 500, 64, 500));

        ArenaContainmentGuard guard = new ArenaContainmentGuard(plugin);
        BlockPlaceEvent event = placeEvent(charlie, world.getBlockAt(500, 64, 500));

        guard.onBlockPlace(event);

        assertFalse(event.isCancelled(), "a player not in a match must not be restricted at all");
    }

    /**
     * The direct-action half of containment. A block placed outside the bounds
     * would never be recorded by the rollback - {@code track} refuses
     * out-of-bounds locations - so it would survive the match permanently.
     */
    @Test
    void aDuellistCannotPlaceOrBreakBlocksOutsideTheBounds()
    {
        WorldMock world = server.addSimpleWorld("containment_placement_world");
        Arena arena = plugin.getArenaManager().createArena("Colosseum");
        ArenaInstance instance = createReadyInstance(arena, world);
        plugin.getArenaInstanceManager().setBoundsCorner(instance.getId(), 1, new Location(world, -10, 60, -10));
        plugin.getArenaInstanceManager().setBoundsCorner(instance.getId(), 2, new Location(world, 10, 70, 10));

        PlayerMock alice = addPlayer("Alice");
        PlayerMock bob = addPlayer("Bob");

        Match match = startInProgressMatch(alice, bob);
        alice.setLocation(new Location(world, 5, 64, 5));

        ArenaContainmentGuard guard = new ArenaContainmentGuard(plugin);

        BlockPlaceEvent inside = placeEvent(alice, world.getBlockAt(6, 64, 6));
        guard.onBlockPlace(inside);
        assertFalse(inside.isCancelled(), "placing inside the arena is the normal case and must still work");

        BlockPlaceEvent outside = placeEvent(alice, world.getBlockAt(40, 64, 40));
        guard.onBlockPlace(outside);
        assertTrue(outside.isCancelled(), "a block placed outside the bounds would never be rolled back");

        BlockBreakEvent broken = new BlockBreakEvent(world.getBlockAt(40, 64, 40), alice);
        guard.onBlockBreak(broken);
        assertTrue(broken.isCancelled(), "a block broken outside the bounds would never be restored");

        assertEquals(MatchState.IN_PROGRESS, match.getState());
    }

    /**
     * {@code BoundaryMode.WARNING} lets a combatant physically walk out of the
     * arena and stay out. Without checking the player's own position as well as
     * the target block's, they could stand outside and keep building back into
     * the fight - which is why the check is deliberately two-sided.
     */
    @Test
    void aDuellistStandingOutsideTheBoundsCannotBuildBackInside()
    {
        WorldMock world = server.addSimpleWorld("containment_warning_mode_world");
        Arena arena = plugin.getArenaManager().createArena("Colosseum");
        ArenaInstance instance = createReadyInstance(arena, world);
        plugin.getArenaInstanceManager().setBoundsCorner(instance.getId(), 1, new Location(world, -10, 60, -10));
        plugin.getArenaInstanceManager().setBoundsCorner(instance.getId(), 2, new Location(world, 10, 70, 10));

        PlayerMock alice = addPlayer("Alice");
        PlayerMock bob = addPlayer("Bob");

        startInProgressMatch(alice, bob);
        alice.setLocation(new Location(world, 40, 64, 40));

        ArenaContainmentGuard guard = new ArenaContainmentGuard(plugin);
        BlockPlaceEvent event = placeEvent(alice, world.getBlockAt(5, 64, 5));

        guard.onBlockPlace(event);

        assertTrue(event.isCancelled(), "a player outside the bounds must not be able to build inside them");
    }

    /**
     * An explosion is filtered rather than cancelled. A charge set against the
     * arena wall legitimately destroys the inside face of it, so cancelling the
     * whole event would make boundary TNT behave inconsistently; removing only
     * the out-of-bounds entries leaves the rest of the blast normal.
     */
    @Test
    void explosionsAreTrimmedToTheBlocksInsideTheirOwnArena()
    {
        WorldMock world = server.addSimpleWorld("containment_explosion_world");
        Arena arena = plugin.getArenaManager().createArena("Colosseum");
        ArenaInstance instance = createReadyInstance(arena, world);
        plugin.getArenaInstanceManager().setBoundsCorner(instance.getId(), 1, new Location(world, -10, 60, -10));
        plugin.getArenaInstanceManager().setBoundsCorner(instance.getId(), 2, new Location(world, 10, 70, 10));

        Block inside = world.getBlockAt(9, 64, 9);
        inside.setType(Material.STONE);

        Block outside = world.getBlockAt(11, 64, 11);
        outside.setType(Material.STONE);

        PlayerMock alice = addPlayer("Alice");
        PlayerMock bob = addPlayer("Bob");

        startInProgressMatch(alice, bob);

        ArenaContainmentGuard guard = new ArenaContainmentGuard(plugin);

        Block source = world.getBlockAt(9, 65, 9);
        BlockExplodeEvent event = new BlockExplodeEvent(
                source, source.getState(), new ArrayList<>(List.of(inside, outside)), 1f, ExplosionResult.DESTROY
        );

        guard.onBlockExplode(event);

        assertTrue(event.blockList().contains(inside), "the part of the blast inside the arena is left alone");
        assertFalse(event.blockList().contains(outside), "the part of the blast outside the arena is removed");
    }

    /**
     * Liquid flow is the case with no attributable player at all: emptying a
     * bucket is one event, and every block the lava then creeps into is a
     * separate {@code BlockFromToEvent} with no source entity. Keying on the
     * flowing block's own location is the only thing that can contain it.
     */
    @Test
    void liquidFlowIsStoppedAtTheArenaBoundary()
    {
        WorldMock world = server.addSimpleWorld("containment_flow_world");
        Arena arena = plugin.getArenaManager().createArena("Colosseum");
        ArenaInstance instance = createReadyInstance(arena, world);
        plugin.getArenaInstanceManager().setBoundsCorner(instance.getId(), 1, new Location(world, -10, 60, -10));
        plugin.getArenaInstanceManager().setBoundsCorner(instance.getId(), 2, new Location(world, 10, 70, 10));

        PlayerMock alice = addPlayer("Alice");
        PlayerMock bob = addPlayer("Bob");

        startInProgressMatch(alice, bob);

        ArenaContainmentGuard guard = new ArenaContainmentGuard(plugin);

        BlockFromToEvent inwards = new BlockFromToEvent(world.getBlockAt(9, 64, 9), world.getBlockAt(8, 64, 9));
        guard.onBlockFromTo(inwards);
        assertFalse(inwards.isCancelled(), "flow that stays inside the arena is normal gameplay");

        BlockFromToEvent outwards = new BlockFromToEvent(world.getBlockAt(10, 64, 10), world.getBlockAt(11, 64, 10));
        guard.onBlockFromTo(outwards);
        assertTrue(outwards.isCancelled(), "flow leaving the arena would never be rolled back");

        BlockFromToEvent unrelated = new BlockFromToEvent(world.getBlockAt(200, 64, 200), world.getBlockAt(201, 64, 200));
        guard.onBlockFromTo(unrelated);
        assertFalse(unrelated.isCancelled(), "flow with no connection to a duel must be untouched");
    }

    /**
     * An idle arena - no live match, nothing still resetting - is ordinary
     * ground. This has to hold even though the arena has bounds configured,
     * since arena editing and simply walking through a static arena between
     * fights both depend on entry not being restricted until a match actually
     * claims the instance.
     */
    @Test
    void aBystanderMayFreelyEnterAnIdleArenasBounds()
    {
        WorldMock world = server.addSimpleWorld("access_guard_idle_world");
        Arena arena = plugin.getArenaManager().createArena("Colosseum");
        ArenaInstance instance = createReadyInstance(arena, world);
        plugin.getArenaInstanceManager().setBoundsCorner(instance.getId(), 1, new Location(world, -10, 60, -10));
        plugin.getArenaInstanceManager().setBoundsCorner(instance.getId(), 2, new Location(world, 10, 70, 10));

        PlayerMock charlie = addPlayer("Charlie");
        Location outside = new Location(world, 100, 64, 100);
        charlie.setLocation(outside);

        ArenaAccessGuard guard = new ArenaAccessGuard(plugin);

        Location into = new Location(world, 5, 64, 5);
        PlayerMoveEvent entering = new PlayerMoveEvent(charlie, outside, into);
        guard.onMove(entering);

        assertFalse(entering.isCancelled());
        assertEquals(into.getBlockX(), entering.getTo().getBlockX(),
                "an idle arena with no active match must be freely enterable");
    }

    /**
     * Once a match is actually live in an instance, a bystander walking
     * towards its bounds is turned back the same way a combatant is kept in.
     * Entry is denied by cancelling the move rather than by redirecting it:
     * a player walking in is by definition coming from somewhere outside, so
     * where they already were is the correct place to leave them, and it needs
     * no destination to be computed or remembered.
     */
    @Test
    void aBystanderCannotWalkIntoAnArenaWhileAMatchIsLiveThere()
    {
        WorldMock world = server.addSimpleWorld("access_guard_move_world");
        Arena arena = plugin.getArenaManager().createArena("Colosseum");
        ArenaInstance instance = createReadyInstance(arena, world);
        plugin.getArenaInstanceManager().setBoundsCorner(instance.getId(), 1, new Location(world, -10, 60, -10));
        plugin.getArenaInstanceManager().setBoundsCorner(instance.getId(), 2, new Location(world, 10, 70, 10));

        PlayerMock alice = addPlayer("Alice");
        PlayerMock bob = addPlayer("Bob");
        startInProgressMatch(alice, bob);

        PlayerMock charlie = addPlayer("Charlie");
        Location outside = new Location(world, 100, 64, 100);
        charlie.setLocation(outside);

        ArenaAccessGuard guard = new ArenaAccessGuard(plugin);

        PlayerMoveEvent entering = new PlayerMoveEvent(charlie, outside, new Location(world, 5, 64, 5));
        guard.onMove(entering);

        assertTrue(entering.isCancelled(), "a bystander must not be let into the bounds while a match is live there");
    }

    /**
     * The failure mode that made the first version of eviction unusable: a
     * player who is <em>already</em> inside must be taken out rather than
     * stopped in place, because a move that both starts and ends inside the
     * arena cannot be cancelled without pinning them in the duel and denying
     * every attempt they make to walk out of it.
     *
     * <p>This also makes the guard self-healing. Whatever put someone inside a
     * live arena - logging in there, another plugin, an admin teleport Duels
     * deliberately does not intercept - their first step takes them out.
     */
    @Test
    void aPlayerAlreadyInsideALiveArenaIsTakenOutRatherThanPinnedInPlace()
    {
        WorldMock world = server.addSimpleWorld("access_guard_stuck_world");
        Arena arena = plugin.getArenaManager().createArena("Colosseum");
        ArenaInstance instance = createReadyInstance(arena, world);
        plugin.getArenaInstanceManager().setBoundsCorner(instance.getId(), 1, new Location(world, -10, 60, -10));
        plugin.getArenaInstanceManager().setBoundsCorner(instance.getId(), 2, new Location(world, 10, 70, 10));

        PlayerMock alice = addPlayer("Alice");
        PlayerMock bob = addPlayer("Bob");
        startInProgressMatch(alice, bob);

        PlayerMock charlie = addPlayer("Charlie");
        charlie.setLocation(new Location(world, 5, 64, 5));

        ArenaAccessGuard guard = new ArenaAccessGuard(plugin);

        PlayerMoveEvent walkingAround = new PlayerMoveEvent(
                charlie, new Location(world, 5, 64, 5), new Location(world, 6, 64, 5)
        );
        guard.onMove(walkingAround);

        assertFalse(walkingAround.isCancelled(), "cancelling a move inside the arena would pin the player in the duel");
        assertFalse(instance.contains(charlie.getLocation()), "a player already inside a live arena must be moved out of it");
    }

    /**
     * Covers entry that never fires {@link PlayerMoveEvent} at all - a command
     * teleport here, but the same path also covers warps and ender pearls.
     * {@link PlayerTeleportEvent.TeleportCause#PLUGIN} is the one cause this
     * guard must never second-guess, since Duels' own restorations (a
     * spectator session returning a player to wherever they stood before)
     * rely on it landing unchallenged.
     */
    @Test
    void aBystanderCannotTeleportIntoALiveArenaViaANonPluginTeleport()
    {
        WorldMock world = server.addSimpleWorld("access_guard_teleport_world");
        Arena arena = plugin.getArenaManager().createArena("Colosseum");
        ArenaInstance instance = createReadyInstance(arena, world);
        plugin.getArenaInstanceManager().setBoundsCorner(instance.getId(), 1, new Location(world, -10, 60, -10));
        plugin.getArenaInstanceManager().setBoundsCorner(instance.getId(), 2, new Location(world, 10, 70, 10));

        PlayerMock alice = addPlayer("Alice");
        PlayerMock bob = addPlayer("Bob");
        startInProgressMatch(alice, bob);

        PlayerMock charlie = addPlayer("Charlie");
        Location outside = new Location(world, 100, 64, 100);
        charlie.setLocation(outside);

        ArenaAccessGuard guard = new ArenaAccessGuard(plugin);

        PlayerTeleportEvent commandTeleport = new PlayerTeleportEvent(
                charlie, outside, new Location(world, 5, 64, 5), PlayerTeleportEvent.TeleportCause.COMMAND
        );
        guard.onTeleport(commandTeleport);
        assertTrue(commandTeleport.isCancelled(), "a non-plugin teleport into a live arena's bounds must be denied");

        PlayerTeleportEvent pluginTeleport = new PlayerTeleportEvent(
                charlie, outside, new Location(world, 5, 64, 5), PlayerTeleportEvent.TeleportCause.PLUGIN
        );
        guard.onTeleport(pluginTeleport);
        assertFalse(pluginTeleport.isCancelled(), "a plugin-chosen destination, such as a spectator restore, must never be second-guessed");
    }

    /**
     * The primary way a bystander is ever removed from an instance a match
     * has just claimed: an idle arena is freely walkable, so nothing stops
     * someone standing in it right up until the moment a duel starts around
     * them, and this sweep is what moves them out at exactly that moment.
     *
     * <p>The bystander is walked in through real move events rather than
     * positioned directly, because that is what exposes the bug this test
     * exists for. An earlier version sent evicted players to a remembered
     * "last safe location", and walking through an idle arena is precisely
     * what filled that memory with positions inside the arena - so eviction
     * teleported the player to where they already stood, and the guard then
     * denied every move they made to get out.
     */
    @Test
    void aBystanderWhoWalkedIntoAnIdleArenaIsEvictedWhenAMatchStartsThere()
    {
        WorldMock world = server.addSimpleWorld("access_guard_evict_world");
        Arena arena = plugin.getArenaManager().createArena("Colosseum");
        ArenaInstance instance = createReadyInstance(arena, world);

        PlayerMock charlie = addPlayer("Charlie");
        Location outside = new Location(world, 200, 64, 200);
        charlie.setLocation(outside);

        ArenaAccessGuard guard = plugin.getArenaAccessGuard();

        Location firstStepIn = new Location(world, 20, 64, 20);
        PlayerMoveEvent walkingIn = new PlayerMoveEvent(charlie, outside, firstStepIn);
        guard.onMove(walkingIn);
        assertFalse(walkingIn.isCancelled(), "an idle arena must be freely enterable");
        charlie.setLocation(firstStepIn);

        PlayerMoveEvent walkingAround = new PlayerMoveEvent(charlie, firstStepIn, new Location(world, 21, 64, 20));
        guard.onMove(walkingAround);
        charlie.setLocation(new Location(world, 21, 64, 20));

        assertTrue(instance.contains(charlie.getLocation()));

        PlayerMock alice = addPlayer("Alice");
        PlayerMock bob = addPlayer("Bob");

        Match match = plugin.getMatchManager().startMatch(alice, bob);
        assertNotNull(match);

        assertFalse(instance.contains(charlie.getLocation()),
                "a bystander left standing in the arena must be moved out before the match begins");
    }

    /**
     * Where an evicted bystander lands is configurable because an arena world's
     * own spawn is frequently nothing but void. This covers the configured-hub
     * case: an admin who names a world in {@code fallback-world} expects someone
     * cleared out of a starting match to arrive at that world's spawn rather
     * than at whichever world happens to be first on the server.
     *
     * <p>It shares {@code DuelsSettings.fallbackSpawn()} with the returning
     * player whose captured world has gone, so the two paths cannot disagree
     * about where "somewhere sensible" is.
     */
    @Test
    void anEvictedBystanderLandsAtTheConfiguredFallbackWorldsSpawn()
    {
        WorldMock hub = server.addSimpleWorld("access_guard_hub");
        WorldMock world = server.addSimpleWorld("access_guard_configured_world");

        plugin.core().config().set("fallback-world", hub.getName());
        plugin.getSettings().reload(plugin.core().config(), plugin.getLogger());

        Arena arena = plugin.getArenaManager().createArena("Colosseum");
        ArenaInstance instance = createReadyInstance(arena, world);

        PlayerMock charlie = addPlayer("Charlie");
        charlie.setLocation(new Location(world, 21, 64, 20));
        assertTrue(instance.contains(charlie.getLocation()));

        assertNotNull(plugin.getMatchManager().startMatch(addPlayer("Alice"), addPlayer("Bob")));

        assertEquals(hub, charlie.getWorld());
        assertEquals(hub.getSpawnLocation().getBlockX(), charlie.getLocation().getBlockX());
        assertEquals(hub.getSpawnLocation().getBlockZ(), charlie.getLocation().getBlockZ());
    }

    /**
     * The concrete bug this closes: a bystander's death drops (or anything
     * else that wandered in) during idle time between matches, with no match
     * ever wrapping around that idle time to trigger the end-of-match sweep.
     * {@code prepareForMatch} runs the same broadened sweep at the start of
     * every match instead, so idle-time mess never survives into the next fight.
     */
    @Test
    void strayEntitiesInTheArenaAreClearedBeforeEachMatchNotJustAfterOne()
    {
        WorldMock world = server.addSimpleWorld("access_guard_sweep_world");
        Arena arena = plugin.getArenaManager().createArena("Colosseum");
        createReadyInstance(arena, world);

        Location inside = new Location(world, 5, 64, 5);
        Item strayItem = world.dropItem(inside, new ItemStack(Material.DIAMOND));
        Entity strayMob = world.spawnEntity(inside, EntityType.ZOMBIE);

        assertTrue(world.getEntities().contains(strayItem));
        assertTrue(world.getEntities().contains(strayMob));

        PlayerMock alice = addPlayer("Alice");
        PlayerMock bob = addPlayer("Bob");

        Match match = plugin.getMatchManager().startMatch(alice, bob);
        assertNotNull(match);

        assertFalse(world.getEntities().contains(strayItem),
                "a stray item left over from idle time must not survive into the next match");
        assertFalse(world.getEntities().contains(strayMob),
                "a stray mob left over from idle time must not survive into the next match");
    }

    /**
     * Stops a duellist reaching across their own boundary to collect an item
     * lying just outside it, whether it is a stray death drop or something
     * thrown over the wall by someone helping them from outside.
     */
    @Test
    void aDuellistCannotPickUpAnItemLyingOutsideTheArenaBounds()
    {
        WorldMock world = server.addSimpleWorld("containment_pickup_world");
        Arena arena = plugin.getArenaManager().createArena("Colosseum");
        ArenaInstance instance = createReadyInstance(arena, world);
        plugin.getArenaInstanceManager().setBoundsCorner(instance.getId(), 1, new Location(world, -10, 60, -10));
        plugin.getArenaInstanceManager().setBoundsCorner(instance.getId(), 2, new Location(world, 10, 70, 10));

        PlayerMock alice = addPlayer("Alice");
        PlayerMock bob = addPlayer("Bob");

        startInProgressMatch(alice, bob);
        alice.setLocation(new Location(world, 9, 64, 9));

        ArenaContainmentGuard guard = new ArenaContainmentGuard(plugin);

        Item outsideItem = world.dropItem(new Location(world, 15, 64, 15), new ItemStack(Material.DIAMOND));
        EntityPickupItemEvent outsidePickup = new EntityPickupItemEvent(alice, outsideItem, 1);
        guard.onItemPickup(outsidePickup);
        assertTrue(outsidePickup.isCancelled(), "an item lying outside the arena must not be collectible by a duellist inside it");

        Item insideItem = world.dropItem(new Location(world, 9, 64, 9), new ItemStack(Material.DIAMOND));
        EntityPickupItemEvent insidePickup = new EntityPickupItemEvent(alice, insideItem, 1);
        guard.onItemPickup(insidePickup);
        assertFalse(insidePickup.isCancelled(), "an item inside the arena is normal pickup and must not be blocked");
    }

    /**
     * The other half of the boundary being one-way for objects: the arena wall
     * stops a bystander walking in, but nothing stops them throwing a spare
     * sword over it, and an item that lands inside the bounds passes every
     * check {@link ArenaContainmentGuard} makes.
     *
     * <p>Also asserts the two cases that must keep working, because the failure
     * mode of getting this wrong is confiscating things: a duellist collecting
     * their own dropped gear, and the bystander collecting the item they threw.
     */
    @Test
    void anItemThrownInByANonCombatantCannotBePickedUpByADuellist()
    {
        WorldMock world = server.addSimpleWorld("interference_pickup_world");
        Arena arena = plugin.getArenaManager().createArena("Colosseum");
        createReadyInstance(arena, world);

        PlayerMock alice = addPlayer("Alice");
        PlayerMock bob = addPlayer("Bob");
        PlayerMock charlie = addPlayer("Charlie");

        startInProgressMatch(alice, bob);

        MatchInterferenceGuard guard = new MatchInterferenceGuard(plugin);
        Location insideArena = new Location(world, 0, 64, 0);

        Item thrownIn = world.dropItem(insideArena, new ItemStack(Material.DIAMOND_SWORD));
        guard.onDropItem(new PlayerDropItemEvent(charlie, thrownIn));

        EntityPickupItemEvent foreignPickup = new EntityPickupItemEvent(alice, thrownIn, 1);
        guard.onItemPickup(foreignPickup);
        assertTrue(foreignPickup.isCancelled(),
                "a duellist must not be able to collect an item a non-combatant threw into the duel");

        EntityPickupItemEvent throwerPickup = new EntityPickupItemEvent(charlie, thrownIn, 1);
        guard.onItemPickup(throwerPickup);
        assertFalse(throwerPickup.isCancelled(),
                "the bystander must still be able to collect their own item back - Duels must never cost them it");

        Item ownDrop = world.dropItem(insideArena, new ItemStack(Material.DIAMOND_SWORD));
        guard.onDropItem(new PlayerDropItemEvent(alice, ownDrop));

        EntityPickupItemEvent ownPickup = new EntityPickupItemEvent(alice, ownDrop, 1);
        guard.onItemPickup(ownPickup);
        assertFalse(ownPickup.isCancelled(), "a duellist's own dropped gear must still be collectible");
    }

    /**
     * Covers the marking half of the rule for projectiles, which is what the
     * splash-potion and lingering-cloud rules read. Whether a bystander's arrow
     * can hurt a duellist is a separate question already settled by
     * {@code MatchListener}, which cancels any hit not thrown by the opponent.
     */
    @Test
    void aProjectileLaunchedByANonCombatantIsMarkedAsForeignToTheDuel()
    {
        WorldMock world = server.addSimpleWorld("interference_projectile_world");
        Arena arena = plugin.getArenaManager().createArena("Colosseum");
        createReadyInstance(arena, world);

        PlayerMock alice = addPlayer("Alice");
        PlayerMock bob = addPlayer("Bob");
        PlayerMock charlie = addPlayer("Charlie");

        startInProgressMatch(alice, bob);

        MatchInterferenceGuard guard = new MatchInterferenceGuard(plugin);

        Arrow bystanderArrow = world.spawn(new Location(world, 0, 64, 0), Arrow.class);
        bystanderArrow.setShooter(charlie);
        guard.onProjectileLaunch(new ProjectileLaunchEvent(bystanderArrow));
        assertTrue(guard.isForeignToDuels(bystanderArrow),
                "anything launched by someone outside the duel must be marked as foreign to it");

        Arrow duellistArrow = world.spawn(new Location(world, 0, 64, 0), Arrow.class);
        duellistArrow.setShooter(alice);
        guard.onProjectileLaunch(new ProjectileLaunchEvent(duellistArrow));
        assertFalse(guard.isForeignToDuels(duellistArrow),
                "a duellist's own projectile is part of the duel and must not be marked");
    }

    /**
     * A fishing line that has latched onto a duellist is drawn across their
     * screen until the caster retrieves it, so blocking only the reel leaves
     * the duel visibly interfered with. The hook must never fasten on at all -
     * except when it is the opponent casting, which is ordinary combat.
     */
    @Test
    void aNonCombatantsFishingHookCannotFastenOntoADuellist()
    {
        WorldMock world = server.addSimpleWorld("interference_fishing_world");
        Arena arena = plugin.getArenaManager().createArena("Colosseum");
        createReadyInstance(arena, world);

        PlayerMock alice = addPlayer("Alice");
        PlayerMock bob = addPlayer("Bob");
        PlayerMock charlie = addPlayer("Charlie");

        startInProgressMatch(alice, bob);

        MatchInterferenceGuard guard = new MatchInterferenceGuard(plugin);
        Location castFrom = new Location(world, 0, 64, 0);

        FishHook bystanderHook = world.spawn(castFrom, FishHook.class);
        bystanderHook.setShooter(charlie);

        ProjectileHitEvent bystanderHit = new ProjectileHitEvent(bystanderHook, alice);
        guard.onProjectileHit(bystanderHit);

        assertTrue(bystanderHit.isCancelled(), "a bystander's hook must not be allowed to catch a duellist");
        assertNull(bystanderHook.getHookedEntity(), "the hook must not be left attached to the duellist");
        assertFalse(world.getEntities().contains(bystanderHook),
                "the bobber must be gone, so no line can be drawn to the duellist");

        FishHook opponentHook = world.spawn(castFrom, FishHook.class);
        opponentHook.setShooter(bob);

        ProjectileHitEvent opponentHit = new ProjectileHitEvent(opponentHook, alice);
        guard.onProjectileHit(opponentHit);

        assertFalse(opponentHit.isCancelled(), "the opponent rodding a duellist is ordinary combat and must still work");
    }

    /**
     * Advances a freshly started match through kit selection and the grace
     * period so it is actually {@code IN_PROGRESS}, which is the only state
     * containment and tracking apply to.
     */
    private Match startInProgressMatch(PlayerMock first, PlayerMock second)
    {
        Match match = plugin.getMatchManager().startMatch(first, second);
        assertNotNull(match);

        server.getScheduler().performTicks((plugin.getSettings().kitSelectionSeconds() + 1) * 20L);
        server.getScheduler().performTicks((plugin.getSettings().gracePeriodSeconds() + 1) * 20L);
        assertEquals(MatchState.IN_PROGRESS, match.getState());

        return match;
    }

    private BlockPlaceEvent placeEvent(Player player, Block target)
    {
        return new BlockPlaceEvent(
                target,
                target.getState(),
                target.getRelative(BlockFace.DOWN),
                new ItemStack(Material.STONE),
                player,
                true
        );
    }

    /**
     * The bug this replaced was a coordinate-system mismatch: bounds were
     * compared as raw doubles, but callers pass a mix of player positions (a
     * point anywhere inside a block) and {@code Block.getLocation()} (that
     * block's minimum corner). A box set from a standing position therefore
     * excluded its own minimum face while including its maximum one.
     */
    @Test
    void boundsIncludeBothCornerBlocksWhenSetFromAStandingPosition()
    {
        WorldMock world = server.addSimpleWorld("bounds_snapping_world");
        Arena arena = plugin.getArenaManager().createArena("Colosseum");
        ArenaInstance instance = createReadyInstance(arena, world);

        // Exactly what a standing admin produces: centred in the block on X/Z,
        // and feet resting on top of the block below on Y.
        plugin.getArenaInstanceManager().setBoundsCorner(instance.getId(), 1, new Location(world, 10.5, 64.0, 10.5));
        plugin.getArenaInstanceManager().setBoundsCorner(instance.getId(), 2, new Location(world, 50.5, 80.0, 50.5));

        assertTrue(instance.contains(world.getBlockAt(10, 64, 10).getLocation()),
                "the block the admin stood in when setting corner 1 must be inside the bounds");
        assertTrue(instance.contains(world.getBlockAt(50, 80, 50).getLocation()),
                "the block the admin stood in when setting corner 2 must be inside the bounds");

        assertFalse(instance.contains(world.getBlockAt(9, 64, 10).getLocation()),
                "one block beyond the minimum corner is still outside");
        assertFalse(instance.contains(world.getBlockAt(51, 80, 50).getLocation()),
                "one block beyond the maximum corner is still outside");

        assertTrue(instance.contains(new Location(world, 10.1, 64.0, 10.9)),
                "a player anywhere within the corner block is inside the bounds");
    }

    /**
     * The floor is deliberately not included by standing on it - standing on a
     * block puts the player in the block above. Documented as a test because it
     * is a design decision rather than an oversight: the alternative, silently
     * extending the box a block downwards, makes the volume asymmetric and
     * unpredictable. Admins include the floor by clicking it instead.
     */
    @Test
    void standingOnTheFloorSelectsTheBlockAboveItNotTheFloor()
    {
        WorldMock world = server.addSimpleWorld("bounds_floor_world");
        Arena arena = plugin.getArenaManager().createArena("Colosseum");
        ArenaInstance instance = createReadyInstance(arena, world);

        plugin.getArenaInstanceManager().setBoundsCorner(instance.getId(), 1, new Location(world, 10.5, 64.0, 10.5));
        plugin.getArenaInstanceManager().setBoundsCorner(instance.getId(), 2, new Location(world, 50.5, 80.0, 50.5));

        assertFalse(instance.contains(world.getBlockAt(20, 63, 20).getLocation()),
                "the floor block the admin was standing on is below the selected corner");

        // Clicking the floor block instead is what an admin does to include it,
        // and is what the edit tool now passes through.
        plugin.getArenaInstanceManager().setBoundsCorner(instance.getId(), 1, world.getBlockAt(10, 63, 10).getLocation());

        assertTrue(instance.contains(world.getBlockAt(20, 63, 20).getLocation()),
                "clicking the floor block includes the floor");
    }

    /**
     * Corners are stored block-aligned rather than at the position they were set
     * from, so a corner identifies a block. Without this, "teleport me to corner
     * 1" returned the admin to wherever they were standing, which stops being
     * the corner as soon as a corner can be set by clicking a distant block.
     */
    @Test
    void boundsCornersAreStoredBlockAligned()
    {
        WorldMock world = server.addSimpleWorld("bounds_storage_world");
        Arena arena = plugin.getArenaManager().createArena("Colosseum");
        ArenaInstance instance = createReadyInstance(arena, world);

        plugin.getArenaInstanceManager().setBoundsCorner(instance.getId(), 1, new Location(world, 10.7, 64.0, -10.3, 90f, 45f));

        Location stored = instance.getBoundsCorner1();

        assertEquals(10, stored.getBlockX());
        assertEquals(64, stored.getBlockY());
        assertEquals(-11, stored.getBlockZ(), "flooring, not truncation - a point at -10.3 is inside block -11");
        assertEquals(10.0, stored.getX(), "the stored value is the block, not the position it was set from");
        assertEquals(0f, stored.getYaw(), "a corner marks a position, not a direction to face");
    }

    /**
     * The size readout is the feedback that actually catches a mis-set corner,
     * so the arithmetic needs to be inclusive of both corner blocks: a box from
     * block 10 to block 12 is three blocks wide, not two.
     */
    @Test
    void boundsSizeCountsBothCornerBlocks()
    {
        WorldMock world = server.addSimpleWorld("bounds_size_world");
        Arena arena = plugin.getArenaManager().createArena("Colosseum");
        ArenaInstance instance = createReadyInstance(arena, world);

        assertNotNull(instance.getBoundsSize(), "createReadyInstance sets bounds");

        plugin.getArenaInstanceManager().setBoundsCorner(instance.getId(), 1, new Location(world, 10.5, 64.0, 10.5));
        plugin.getArenaInstanceManager().setBoundsCorner(instance.getId(), 2, new Location(world, 12.5, 64.0, 20.5));

        ArenaStructureSize size = instance.getBoundsSize();

        assertEquals(3, size.x());
        assertEquals(1, size.y());
        assertEquals(11, size.z());
    }

    /**
     * Snapping happens when comparing as well as when storing, so an arena
     * configured before corners were block-aligned picks up the fix on load
     * rather than needing a migration. Setting the corner directly on the
     * instance bypasses the manager, which is how a deserialized legacy record
     * arrives.
     */
    @Test
    void arenasSavedBeforeCornersWereAlignedStillGetTheFullBox()
    {
        WorldMock world = server.addSimpleWorld("bounds_legacy_world");
        Arena arena = plugin.getArenaManager().createArena("Colosseum");
        ArenaInstance instance = createReadyInstance(arena, world);

        instance.setBoundsCorner1(new Location(world, 10.5, 64.0, 10.5));
        instance.setBoundsCorner2(new Location(world, 50.5, 80.0, 50.5));

        assertTrue(instance.contains(world.getBlockAt(10, 64, 10).getLocation()),
                "a legacy half-block corner must still include its own corner block");
    }

    /**
     * The particle frame is drawn from the box's minimum corner to
     * {@code maxCorner*}, so this pins the relationship the renderer depends on:
     * the drawn extent on each axis must equal the number of blocks the box
     * actually contains. Using {@code max*} there outlined a box one block short
     * on every maximum face, which read in game as the whole frame sitting a
     * block below the area being enforced.
     */
    @Test
    void theBoxOutlineExtentMatchesTheNumberOfBlocksContained()
    {
        WorldMock world = server.addSimpleWorld("outline_world");

        BlockBox box = BlockBox.of(new Location(world, 10, 64, 20), new Location(world, 12, 73, 39));

        assertNotNull(box);
        assertEquals(3, box.sizeX());
        assertEquals(10, box.sizeY());
        assertEquals(20, box.sizeZ());

        assertEquals(box.sizeX(), box.maxCornerX() - box.minX(), 1.0e-9);
        assertEquals(box.sizeY(), box.maxCornerY() - box.minY(), 1.0e-9);
        assertEquals(box.sizeZ(), box.maxCornerZ() - box.minZ(), 1.0e-9);

        assertTrue(box.contains(world.getBlockAt(12, 73, 39).getLocation()),
                "the maximum corner block is inside the box it is drawn around");
    }

    /**
     * The normal setup: the admin draws the bounds around the arena's interior,
     * so every boundary block of the box is air and the real walls sit one block
     * outside it. Nothing can escape, so nothing should be reported - a check
     * that only asked "is the box's own outer layer solid?" condemned exactly
     * this arena.
     */
    @Test
    void interiorBoundsInsideASealedRoomReportNoOpenings()
    {
        WorldMock world = server.addSimpleWorld("sealed_interior_world");
        BlockBox interior = BlockBox.of(new Location(world, 0, 64, 0), new Location(world, 4, 67, 4));

        sealRoom(world, interior);

        ArenaBoundsValidator.OpeningReport report = ArenaBoundsValidator.countOpenings(interior);

        assertFalse(report.hasOpenings(), "a sealed room has nothing to warn about, whatever the box is drawn around");
    }

    /**
     * The other way an admin might draw it - around the walls themselves, so the
     * boundary blocks are the solid wall. A liquid can never occupy those, so
     * this is equally safe even though the world just outside the box is open
     * air.
     */
    @Test
    void boundsDrawnAroundTheWallsThemselvesReportNoOpenings()
    {
        WorldMock world = server.addSimpleWorld("sealed_walls_world");
        BlockBox interior = BlockBox.of(new Location(world, 0, 64, 0), new Location(world, 4, 67, 4));

        sealRoom(world, interior);

        BlockBox withWalls = BlockBox.of(new Location(world, -1, 63, -1), new Location(world, 5, 68, 5));

        ArenaBoundsValidator.OpeningReport report = ArenaBoundsValidator.countOpenings(withWalls);

        assertFalse(report.hasOpenings(), "a solid boundary block is not an opening, whatever lies beyond it");
    }

    @Test
    void aDoorwayInTheWallIsReportedAsAnOpening()
    {
        WorldMock world = server.addSimpleWorld("doorway_world");
        BlockBox interior = BlockBox.of(new Location(world, 0, 64, 0), new Location(world, 4, 67, 4));

        sealRoom(world, interior);
        world.getBlockAt(-1, 64, 2).setType(Material.AIR);

        ArenaBoundsValidator.OpeningReport report = ArenaBoundsValidator.countOpenings(interior);

        assertTrue(report.inspected());
        assertEquals(1, report.wallOpenings(), "the boundary block beside the doorway can leak sideways");
        assertEquals(0, report.floorOpenings());
    }

    @Test
    void aHoleInTheFloorIsReportedAsAnOpening()
    {
        WorldMock world = server.addSimpleWorld("floor_hole_world");
        BlockBox interior = BlockBox.of(new Location(world, 0, 64, 0), new Location(world, 4, 67, 4));

        sealRoom(world, interior);
        world.getBlockAt(2, 63, 2).setType(Material.AIR);

        ArenaBoundsValidator.OpeningReport report = ArenaBoundsValidator.countOpenings(interior);

        assertTrue(report.inspected());
        assertEquals(1, report.floorOpenings(), "the boundary block above the hole can leak downward");
        assertEquals(0, report.wallOpenings());
    }

    /**
     * An open-topped arena is a normal design, and liquid cannot escape upward,
     * so the roof must not count towards the warning - otherwise admins learn to
     * ignore it.
     */
    @Test
    void anOpenRoofIsNotReportedAsAnOpening()
    {
        WorldMock world = server.addSimpleWorld("open_roof_world");
        BlockBox interior = BlockBox.of(new Location(world, 0, 64, 0), new Location(world, 4, 67, 4));

        sealRoom(world, interior);

        for (int x = interior.minX(); x <= interior.maxX(); x++)
            for (int z = interior.minZ(); z <= interior.maxZ(); z++)
                world.getBlockAt(x, interior.maxY() + 1, z).setType(Material.AIR);

        ArenaBoundsValidator.OpeningReport report = ArenaBoundsValidator.countOpenings(interior);

        assertFalse(report.hasOpenings(), "an open roof is a design choice, not a containment hole");
    }

    /**
     * Builds a hand-made arena around the given interior: air inside, a solid
     * one-block shell immediately outside it, and open air beyond that. The last
     * part matters - without it the world outside the walls could be solid by
     * default and a leak test would pass for the wrong reason.
     */
    private void sealRoom(WorldMock world, BlockBox interior)
    {
        fillLayer(world, interior.minX() - 2, interior.maxX() + 2, interior.minY() - 2, interior.maxY() + 2,
                interior.minZ() - 2, interior.maxZ() + 2, Material.AIR);

        fillLayer(world, interior.minX() - 1, interior.maxX() + 1, interior.minY() - 1, interior.maxY() + 1,
                interior.minZ() - 1, interior.maxZ() + 1, Material.STONE);

        fillLayer(world, interior.minX(), interior.maxX(), interior.minY(), interior.maxY(),
                interior.minZ(), interior.maxZ(), Material.AIR);
    }

    private void fillLayer(WorldMock world, int minX, int maxX, int minY, int maxY, int minZ, int maxZ, Material material)
    {
        for (int x = minX; x <= maxX; x++)
            for (int y = minY; y <= maxY; y++)
                for (int z = minZ; z <= maxZ; z++)
                    world.getBlockAt(x, y, z).setType(material);
    }

    /**
     * Writes and reads both run on JCore's shared thread pool, so a read
     * submitted straight after a write could previously overtake it and report
     * the score from before the match that had just finished. No ticks are
     * performed and nothing is slept on here deliberately: the point is that a
     * read issued immediately still sees the write.
     */
    @Test
    void aStatsReadIssuedAfterAResultSeesThatResult()
    {
        PlayerMock winner = addPlayer("Winner");
        PlayerMock loser = addPlayer("Loser");

        long endedAt = System.currentTimeMillis();
        plugin.getStatsManager().recordMatch(result(
                1, winner.getUniqueId(), winner.getName(), null,
                loser.getUniqueId(), loser.getName(), null, winner.getUniqueId(),
                endedAt - 1_000L, endedAt - 500L, endedAt,
                MatchEndReason.DEFEAT, MatchState.IN_PROGRESS));

        assertEquals(1, plugin.getStatsManager().getWins(winner.getUniqueId()).join());
        assertEquals(1, plugin.getStatsManager().getLosses(loser.getUniqueId()).join());
    }

    @Test
    void deepStatsComposeArenaKitOpponentAndTimeFilters()
    {
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        UUID charlie = UUID.randomUUID();

        List<MatchResult> results = List.of(
                result(2, alice, "Alice", 4, bob, "Bob", 7,
                        alice, 1_000L, 2_000L, 5_000L, MatchEndReason.DEFEAT, MatchState.IN_PROGRESS),
                result(2, alice, "Alice", 5, bob, "Bob", 7,
                        bob, 6_000L, 7_000L, 10_000L, MatchEndReason.DEFEAT, MatchState.IN_PROGRESS),
                result(3, alice, "Alice", 4, charlie, "Charlie", 8,
                        charlie, 11_000L, null, 12_000L, MatchEndReason.DISCONNECT, MatchState.PREGAME)
        );
        YamlStatsRepository yaml = new YamlStatsRepository(plugin);
        for (MatchResult matchResult : results)
        {
            plugin.getStatsManager().recordMatch(matchResult);
            yaml.recordMatch(matchResult);
        }

        StatsQuery exact = new StatsQuery(alice, bob, 4, 7, 2, null, null);
        PlayerStats exactStats = plugin.getStatsManager().getPlayerStats(exact).join();
        assertEquals(1, exactStats.matches());
        assertEquals(1, exactStats.wins());
        assertEquals(3_000L, exactStats.averageCombatDurationMillis());
        assertEquals(exactStats, yaml.getPlayerStats(exact).join(),
                "SQL and YAML must give the same meaning to every filter combination");

        PlayerStats kitFour = plugin.getStatsManager().getPlayerStats(
                StatsQuery.forPlayer(alice).withPlayerKit(4)).join();
        assertEquals(2, kitFour.matches());
        assertEquals(1, kitFour.disconnectLosses());
        assertEquals(1, kitFour.timedMatches(), "pre-combat forfeits must not enter duration averages");

        PlayerStats recent = plugin.getStatsManager().getPlayerStats(
                StatsQuery.forPlayer(alice).withTimeRange(6_000L, 11_000L)).join();
        assertEquals(1, recent.matches());
        assertEquals(1, recent.losses());

        List<MatchHistoryEntry> history = plugin.getStatsManager().getMatchHistory(
                StatsQuery.forPlayer(alice), 2, 0).join();
        assertEquals(2, history.size());
        assertEquals("Charlie", history.getFirst().opponentName());
        assertNull(history.getFirst().combatDurationMillis());

        assertEquals(bob, plugin.getStatsManager().findPlayer("bOb").join().orElseThrow().id());

        List<LeaderboardEntry> matchLeaders = plugin.getStatsManager().getLeaderboard(
                StatsQuery.leaderboard(), LeaderboardMetric.MATCHES, 10).join();
        assertEquals(alice, matchLeaders.getFirst().playerId());
        assertEquals(matchLeaders, yaml.getLeaderboard(
                StatsQuery.leaderboard(), LeaderboardMetric.MATCHES, 10, 10).join());
        assertTrue(plugin.getStatsManager().getLeaderboard(
                StatsQuery.leaderboard(), LeaderboardMetric.WIN_RATE, 10).join().isEmpty(),
                "the configured ten-match floor must protect the win-rate leaderboard");
    }

    private MatchResult result(int arenaId, UUID player1, String player1Name, Integer kit1,
                               UUID player2, String player2Name, Integer kit2, UUID winner,
                               long startedAt, Long combatStartedAt, long endedAt,
                               MatchEndReason reason, MatchState state)
    {
        return new MatchResult(arenaId, player1, player1Name, player2, player2Name, winner,
                kit1, kit2, startedAt, combatStartedAt, endedAt, reason, state,
                reason == MatchEndReason.DEFEAT ? "ENTITY_ATTACK" : null);
    }
}
