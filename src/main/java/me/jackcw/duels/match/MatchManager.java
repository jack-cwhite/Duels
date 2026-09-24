package me.jackcw.duels.match;

import me.jackcw.duels.Duels;
import me.jackcw.duels.DuelsSettings;
import me.jackcw.duels.arena.*;
import me.jackcw.duels.kit.Kit;
import me.jackcw.duels.kit.KitManager;
import me.jackcw.duels.message.Message;
import me.jackcw.duels.player.PlayerStateManager;
import me.jackcw.duels.spectator.SpectatorManager;
import me.jackcw.jcore.countdown.Countdown;
import me.jackcw.jcore.message.MessageManager;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Registry;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class MatchManager
{
    private static final Logger LOGGER = Logger.getLogger(MatchManager.class.getName());

    private final ArenaManager arenaManager;
    private final ArenaAllocator arenaAllocator;
    private final KitManager kitManager;
    private final PlayerStateManager playerStateManager;
    private final MessageManager messageManager;
    private final DuelsSettings settings;
    private final Duels plugin;
    private final Map<UUID, Match> matches = new HashMap<>();
    private final Map<UUID, Location> pendingRespawnRestores = new HashMap<>();
    private final Set<UUID> pendingPlayers = new HashSet<>();

    public MatchManager(Duels plugin)
    {
        this.plugin = plugin;
        this.arenaManager = plugin.getArenaManager();
        this.arenaAllocator = plugin.getArenaAllocator();
        this.kitManager = plugin.getKitManager();
        this.playerStateManager = plugin.getPlayerStateManager();
        this.messageManager = plugin.core().messages();
        this.settings = plugin.getSettings();
    }

    public Match startMatch(Player player1, Player player2)
    {
        return startMatch(player1, player2, null);
    }

    /**
     * Starts a match in a physical instance of one selected arena template.
     *
     * <p>This does not fall back to another template when the requested one is
     * unavailable. A player selecting Castle should either receive Castle or
     * be told that Castle has no capacity; silently choosing a different arena
     * would make the selection meaningless.
     */
    public Match startMatch(Player player1, Player player2, int arenaId)
    {
        return startMatch(player1, player2, Integer.valueOf(arenaId));
    }

    private Match startMatch(Player player1, Player player2, Integer requestedArenaId)
    {
        MatchStartResult result = startMatchAsync(player1, player2,
                requestedArenaId == null ? ArenaSelection.any() : ArenaSelection.specific(requestedArenaId)).getNow(null);
        return result != null && result.status() == MatchStartResult.Status.SUCCESS ? result.match() : null;
    }

    /**
     * Reserves the two players before allocation, then changes neither player
     * until the asynchronous arena operation has completed and been
     * revalidated on the server thread.
     */
    public CompletableFuture<MatchStartResult> startMatchAsync(Player player1, Player player2, ArenaSelection selection)
    {
        if (!validPlayers(player1, player2))
            return CompletableFuture.completedFuture(MatchStartResult.failure(MatchStartResult.Status.INVALID_PLAYERS));

        UUID firstId = player1.getUniqueId();
        UUID secondId = player2.getUniqueId();
        if (pendingPlayers.contains(firstId) || pendingPlayers.contains(secondId)
                || getMatch(firstId) != null || getMatch(secondId) != null)
            return CompletableFuture.completedFuture(MatchStartResult.failure(MatchStartResult.Status.PLAYERS_BUSY));

        pendingPlayers.add(firstId);
        pendingPlayers.add(secondId);

        return arenaAllocator.allocate(selection).handle((allocation, throwable) -> completeOnMain(() ->
        {
            try
            {
                if (throwable != null || allocation == null || allocation.status() != ArenaAllocationResult.Status.SUCCESS)
                    return MatchStartResult.failure(MatchStartResult.Status.ARENA_UNAVAILABLE);

                ArenaInstance instance = allocation.instance();
                if (!validPlayers(player1, player2) || !pendingPlayers.contains(firstId) || !pendingPlayers.contains(secondId)
                        || getMatch(firstId) != null || getMatch(secondId) != null)
                {
                    arenaAllocator.release(instance);
                    return MatchStartResult.failure(MatchStartResult.Status.PLAYERS_BUSY);
                }

                if (instance.isProvisioned())
                    plugin.getArenaInstanceManager().setDynamicState(instance, DynamicArenaState.DIRTY);

                Match match = commitStartedMatch(instance, player1, player2);
                return MatchStartResult.success(match);
            }
            finally
            {
                pendingPlayers.remove(firstId);
                pendingPlayers.remove(secondId);
            }
        })).thenCompose(future -> future);
    }

    public boolean isPending(UUID playerId)
    {
        return pendingPlayers.contains(playerId);
    }

    private Match commitStartedMatch(ArenaInstance arenaInstance, Player player1, Player player2)
    {
        Match match = createMatch(arenaInstance, player1, player2);

        matches.put(player1.getUniqueId(), match);
        matches.put(player2.getUniqueId(), match);

        initializePlayers(player1, player2);

        player1.teleport(arenaInstance.getSpawn1());
        player2.teleport(arenaInstance.getSpawn2());

        if (!match.getAvailableKits().isEmpty())
        {
            plugin.core().menus().open(player1, () -> plugin.getKitSelectorMenu().open(player1));
            plugin.core().menus().open(player2, () -> plugin.getKitSelectorMenu().open(player2));
        }
        else
        {
            messageManager.send(player1, Message.NO_KITS_ALLOWED);
            messageManager.send(player2, Message.NO_KITS_ALLOWED);
        }

        startPregameCountdown(match, player1, player2, !match.getAvailableKits().isEmpty());

        return match;
    }

    private boolean validPlayers(Player player1, Player player2)
    {
        return player1 != null && player2 != null && player1.isOnline() && player2.isOnline()
                && !player1.getUniqueId().equals(player2.getUniqueId());
    }

    private CompletableFuture<MatchStartResult> completeOnMain(java.util.concurrent.Callable<MatchStartResult> work)
    {
        CompletableFuture<MatchStartResult> future = new CompletableFuture<>();
        Runnable run = () ->
        {
            try { future.complete(work.call()); }
            catch (Exception exception) { future.completeExceptionally(exception); }
        };
        if (Bukkit.isPrimaryThread())
            run.run();
        else
            Bukkit.getScheduler().runTask(plugin, run);
        return future;
    }

    private void initializePlayers(Player player1, Player player2)
    {
        // Ordering matters: an arena edit session must be closed before the player
        // state is captured, so the snapshot holds the player's real inventory
        // rather than the edit tools. ArenaEditManager.start now refuses to open a
        // session mid-duel, so this only covers the reverse race - a session opened
        // in the same tick a match commits.
        plugin.getArenaEditManager().end(player1);
        plugin.getArenaEditManager().end(player2);

        storePlayerState(player1, player2);

        prepareForMatch(player1);
        prepareForMatch(player2);
    }

    public Match createMatch(ArenaInstance arenaInstance, Player player1, Player player2)
    {
        Arena arena = arenaManager.getArena(arenaInstance.getArenaId());
        List<Kit> availableKits = ArenaKits.allowedKits(arena, kitManager);

        return new Match(
                player1.getUniqueId(),
                player2.getUniqueId(),
                arenaInstance,
                player1.getLocation(),
                player2.getLocation(),
                availableKits
        );
    }

    public void endMatch(Match match, UUID winnerId)
    {
        if (match == null || match.getState() == MatchState.ENDED)
            return;

        // Transition before any restoration work so that anything observing the
        // match while it unwinds sees ENDED. Without this only abortMatch ever
        // set the state, which left the guard above dead on this path and made
        // getState() unreliable for a match that had already finished.
        match.setState(MatchState.ENDED);

        if (match.getCountdown() != null)
            match.getCountdown().cancel();

        ejectSpectators(match, false);

        MatchResult result = new MatchResult(
                match.getArenaInstance().getArenaId(),
                match.getPlayer1Id(),
                match.getPlayer2Id(),
                winnerId,
                match.getAppliedKit(match.getPlayer1Id()),
                match.getAppliedKit(match.getPlayer2Id()),
                System.currentTimeMillis()
        );

        // A stats-persistence failure must never stop the players being restored
        // and the arena being released - recordMatch already durably logs the
        // result itself on failure, so this is purely to stop that failure from
        // breaking the rest of match cleanup.
        try
        {
            plugin.getStatsManager().recordMatch(result);
        }
        catch (Exception e)
        {
            LOGGER.log(Level.SEVERE, "Failed to record match result; continuing match cleanup. "
                    + "Result was: " + result, e);
        }

        endParticipant(match, match.getPlayer1Id(), winnerId);
        endParticipant(match, match.getPlayer2Id(), winnerId);

        releaseArena(match);
    }

    public Match getMatch(UUID uuid)
    {
        return matches.get(uuid);
    }

    /**
     * Every live match, once each.
     *
     * <p>The backing map is keyed by participant, so each match appears in it
     * twice. {@code Match} deliberately does not override {@code equals}, so a
     * {@code HashSet} deduplicates by identity - which is what is wanted here,
     * since two separate matches in the same arena are still two matches.
     *
     * <p>{@code ENDED} matches are filtered out rather than assumed absent:
     * {@link #endMatch} transitions the state before it unwinds the match, so
     * there is a window in which a finished match is still reachable.
     */
    public List<Match> getActiveMatches()
    {
        return new HashSet<>(matches.values()).stream()
                .filter(match -> match.getState() != MatchState.ENDED)
                .toList();
    }

    public boolean selectKit(UUID playerId, Kit kit)
    {
        Match match = getMatch(playerId);

        if (match == null || match.getState() != MatchState.PREGAME || kit == null)
            return false;

        for (Kit available : match.getAvailableKits())
            if (available.getId() == kit.getId())
            {
                match.recordSelectedKit(playerId, available.copy());
                return true;
            }

        return false;
    }

    public void shutdown(boolean preservePlayerStates)
    {
        pendingPlayers.clear();
        for (Match match : new HashSet<>(matches.values()))
            abortMatch(match, preservePlayerStates);
    }

    public void abortForServerStop(Match match)
    {
        abortMatch(match, true);
    }

    private void abortMatch(Match match, boolean preservePlayerStates)
    {
        if (match.getState() == MatchState.ENDED)
            return;

        match.setState(MatchState.ENDED);

        if (match.getCountdown() != null)
            match.getCountdown().cancel();

        ejectSpectators(match, preservePlayerStates);

        if (preservePlayerStates)
        {
            forgetParticipant(match.getPlayer1Id());
            forgetParticipant(match.getPlayer2Id());
        }
        else
        {
            restoreParticipant(match, match.getPlayer1Id(), null, false);
            restoreParticipant(match, match.getPlayer2Id(), null, false);
        }

        releaseArena(match);
    }

    /**
     * Resolved lazily rather than in the constructor because
     * {@code SpectatorManager} is built after this manager and needs it - the
     * dependency only has to exist by the time a match actually ends.
     */
    private void ejectSpectators(Match match, boolean preserveForRestoreOnJoin)
    {
        SpectatorManager spectatorManager = plugin.getSpectatorManager();

        if (spectatorManager != null)
            spectatorManager.stopAll(match, preserveForRestoreOnJoin);
    }

    /**
     * Resolved lazily for the same reason as {@link #ejectSpectators} -
     * {@code ArenaResetStrategy} is built after this manager.
     *
     * <p>The instance is only handed back to the allocator once the reset
     * strategy reports it is actually clean, since reverting a large number of
     * block changes can take more than one tick and the instance must not be
     * claimable by another match while that is still happening.
     */
    private void releaseArena(Match match)
    {
        ArenaInstance instance = match.getArenaInstance();
        ArenaResetStrategy resetStrategy = plugin.getArenaResetStrategy();

        if (resetStrategy == null)
        {
            markProvisionedInstanceReady(instance);
            arenaAllocator.release(instance);
            return;
        }

        resetStrategy.reset(instance, () ->
        {
            markProvisionedInstanceReady(instance);
            arenaAllocator.release(instance);
        });
    }

    private void markProvisionedInstanceReady(ArenaInstance instance)
    {
        if (!instance.isProvisioned())
            return;

        try
        {
            plugin.getArenaInstanceManager().setDynamicState(instance, DynamicArenaState.READY);
        }
        catch (RuntimeException exception)
        {
            LOGGER.log(Level.SEVERE, "Could not mark provisioned arena instance #" + instance.getId() + " ready after reset", exception);
        }
    }

    private void endParticipant(Match match, UUID playerId, UUID winnerId)
    {
        restoreParticipant(match, playerId, winnerId, true);
    }

    private void restoreParticipant(Match match, UUID playerId, UUID winnerId, boolean sendResult)
    {
        forgetParticipant(playerId);

        Player player = Bukkit.getPlayer(playerId);

        if (player == null)
            return;

        player.closeInventory();
        player.getInventory().clear();

        if (player.isDead())
            pendingRespawnRestores.put(playerId, match.getLocation(playerId));
        else if (!playerStateManager.restore(player))
            player.teleport(match.getLocation(playerId));

        if (!sendResult)
            return;

        Message result = playerId.equals(winnerId) ? Message.MATCH_WIN : Message.MATCH_LOSE;

        messageManager.send(player, result, "player", nameOf(match.getOpponent(playerId)));
    }

    public void handleRespawn(Player player, PlayerRespawnEvent event)
    {
        Location fallback = pendingRespawnRestores.remove(player.getUniqueId());

        if (fallback == null)
            return;

        Location target = playerStateManager.has(player) ? playerStateManager.get(player).getLocation() : fallback;
        event.setRespawnLocation(target);

        plugin.core().tasks().runSyncLater(() ->
        {
            if (!playerStateManager.restore(player))
                player.teleport(fallback);
        }, 1L);
    }

    private void forgetParticipant(UUID playerId)
    {
        matches.remove(playerId);
    }

    private void prepareForMatch(Player player)
    {
        player.closeInventory();

        player.setGameMode(GameMode.SURVIVAL);
        player.setAllowFlight(false);
        player.setFlying(false);
        player.setFlySpeed(0.1f);
        player.setWalkSpeed(0.2f);

        player.getInventory().clear();
        player.getInventory().setArmorContents(new ItemStack[4]);
        player.getInventory().setItemInOffHand(new ItemStack(Material.AIR));

        for (PotionEffect effect : player.getActivePotionEffects())
            player.removePotionEffect(effect.getType());

        Registry.ATTRIBUTE.stream().forEach(attribute ->
        {
            AttributeInstance instance = player.getAttribute(attribute);

            if (instance == null)
                return;

            for (var modifier : List.copyOf(instance.getModifiers()))
                instance.removeModifier(modifier);

        });

        player.setFireTicks(0);
        player.setFreezeTicks(0);
        player.setFallDistance(0f);
        player.setVelocity(new Vector());
        player.setRemainingAir(player.getMaximumAir());
        player.setAbsorptionAmount(0.0);
        player.setInvulnerable(false);
        player.setGlowing(false);
        player.setGravity(true);
        player.setCollidable(true);
        player.setCanPickupItems(true);
        player.setNoDamageTicks(0);
        player.setHealthScaled(false);

        player.setHealth(Math.min(20.0, player.getMaxHealth()));
        player.setFoodLevel(20);

        // Deliberately not the 20f maximum. Food 20 with any saturation left
        // triggers vanilla's saturation regeneration - 1 HP every half-second -
        // and the hunger bar cannot move until saturation drains first. At 20f a
        // duellist healed faster than fire or drowning could hurt them and never
        // got hungry, which quietly made environmental damage survivable. 5f is
        // roughly a decent meal: no fast regen, but still enough buffer that
        // nobody loses a normal-length duel to starvation.
        player.setSaturation(5f);
        player.setExhaustion(0f);
    }

    private void applyKit(Match match, Player player)
    {
        Kit kit = resolveKit(match, player.getUniqueId());

        if (kit == null)
            return;

        kit.apply(player);
        match.recordAppliedKit(player.getUniqueId(), kit.getId());
    }

    private Kit resolveKit(Match match, UUID playerId)
    {
        Kit selected = match.getSelectedKit(playerId);

        if (selected != null)
            return selected;

        List<Kit> availableKits = match.getAvailableKits();

        return availableKits.isEmpty() ? null : availableKits.getFirst();
    }

    private void startPregameCountdown(Match match, Player player1, Player player2, boolean kitSelectionEnabled)
    {
        String actionBarFormat = kitSelectionEnabled
                ? "&eSelect a kit! Selection ends in %d..."
                : "&eGrace period starts in %d...";

        Countdown countdown =
            Countdown.builder(plugin.core().tasks(), settings.kitSelectionSeconds())
                .actionBar(List.of(player1, player2), remaining -> String.format(actionBarFormat, remaining))
                .onComplete(
                    () ->
                    {
                      player1.closeInventory();
                      player2.closeInventory();

                      prepareForMatch(player1);
                      prepareForMatch(player2);

                      if (kitSelectionEnabled) {
                        applyKit(match, player1);
                        applyKit(match, player2);
                      }

                      if (settings.enableGracePeriod())
                          startGracePeriodCountdown(match, player1, player2);
                      else
                          startCombat(match, player1, player2);
                    })
                .build();

        match.setCountdown(countdown);
        countdown.start();
    }

    private void startGracePeriodCountdown(Match match, Player player1, Player player2)
    {
        match.setState(MatchState.GRACE);

        String actionBarFormat = "&cGrace period ends in %d...";

        Countdown countdown = Countdown.builder(plugin.core().tasks(), settings.gracePeriodSeconds())
                .actionBar(List.of(player1, player2), remaining -> String.format(actionBarFormat, remaining))
                .onComplete(() -> startCombat(match, player1, player2))
                .build();

        match.setCountdown(countdown);
        countdown.start();
    }

    private void startCombat(Match match, Player player1, Player player2)
    {
        match.setState(MatchState.IN_PROGRESS);

        messageManager.send(player1, Message.MATCH_START, "player", player2.getName());
        messageManager.send(player2, Message.MATCH_START, "player", player1.getName());
    }

    private void storePlayerState(Player... players)
    {
        for (Player player : players)
            playerStateManager.save(player);
    }

    private String nameOf(UUID uuid)
    {
        Player online = Bukkit.getPlayer(uuid);

        if (online != null)
            return online.getName();

        OfflinePlayer offline = Bukkit.getOfflinePlayer(uuid);

        return offline.getName() != null ? offline.getName() : "that player";
    }
}
