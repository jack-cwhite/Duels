package me.jackcw.duels.match;

import me.jackcw.duels.Duels;
import me.jackcw.duels.DuelsSettings;
import me.jackcw.duels.arena.Arena;
import me.jackcw.duels.arena.ArenaKits;
import me.jackcw.duels.arena.ArenaManager;
import me.jackcw.duels.kit.Kit;
import me.jackcw.duels.kit.KitManager;
import me.jackcw.duels.message.Message;
import me.jackcw.duels.player.PlayerStateManager;
import me.jackcw.jcore.countdown.Countdown;
import me.jackcw.jcore.message.MessageManager;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Registry;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class MatchManager
{
    private final ArenaManager arenaManager;
    private final KitManager kitManager;
    private final PlayerStateManager playerStateManager;
    private final MessageManager messageManager;
    private final DuelsSettings settings;
    private final Duels plugin;
    private final Map<UUID, Match> matches = new HashMap<>();

    public MatchManager(Duels plugin)
    {
        this.plugin = plugin;
        this.arenaManager = plugin.getArenaManager();
        this.kitManager = plugin.getKitManager();
        this.playerStateManager = plugin.getPlayerStateManager();
        this.messageManager = plugin.getJCore().messages();
        this.settings = plugin.getSettings();
    }

    public Match startMatch(Player player1, Player player2)
    {
        if (player1 == null
                || player2 == null
                || !player1.isOnline()
                || !player2.isOnline()
                || player1.getUniqueId().equals(player2.getUniqueId())
                || getMatch(player1.getUniqueId()) != null
                || getMatch(player2.getUniqueId()) != null)
            return null;

        Arena arena = findFreeArena();

        if (arena == null)
            return null;

        player1.closeInventory();
        player2.closeInventory();

        playerStateManager.save(player1);
        playerStateManager.save(player2);

        List<Kit> availableKits = ArenaKits.allowedKits(arena, kitManager);

        Match match = new Match(
                player1.getUniqueId(),
                player2.getUniqueId(),
                arena,
                player1.getLocation(),
                player2.getLocation(),
                availableKits
        );

        matches.put(player1.getUniqueId(), match);
        matches.put(player2.getUniqueId(), match);

        boolean kitSelectionEnabled = !availableKits.isEmpty();

        match.setState(kitSelectionEnabled ? MatchState.KIT_SELECTION : MatchState.COUNTDOWN);

        prepareForMatch(player1);
        prepareForMatch(player2);

        player1.teleport(arena.getSpawn1());
        player2.teleport(arena.getSpawn2());

        if (kitSelectionEnabled)
        {
            plugin.getJCore().menus().open(player1, () -> plugin.getKitSelectorMenu().open(player1));
            plugin.getJCore().menus().open(player2, () -> plugin.getKitSelectorMenu().open(player2));
        }
        else
        {
            messageManager.send(player1, Message.NO_KITS_ALLOWED);
            messageManager.send(player2, Message.NO_KITS_ALLOWED);
        }

        startCountdown(match, player1, player2, kitSelectionEnabled);

        return match;
    }

    public void endMatch(Match match, UUID winnerId)
    {
        if (match == null || match.getState() == MatchState.ENDED)
            return;

        boolean recordStats = match.getState() == MatchState.IN_PROGRESS;
        match.setState(MatchState.ENDED);

        if (match.getCountdown() != null)
            match.getCountdown().cancel();

        if (recordStats)
        {
            plugin.getStatsManager().recordMatch(
                    match, winnerId,
                    match.getAppliedKit(match.getPlayer1Id()),
                    match.getAppliedKit(match.getPlayer2Id())
            );
        }

        endParticipant(match, match.getPlayer1Id(), winnerId);
        endParticipant(match, match.getPlayer2Id(), winnerId);
    }

    public Match getMatch(UUID uuid)
    {
        return matches.get(uuid);
    }

    public boolean selectKit(UUID playerId, Kit kit)
    {
        Match match = getMatch(playerId);

        if (match == null || match.getState() != MatchState.KIT_SELECTION || kit == null)
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

        if (!playerStateManager.restore(player))
            player.teleport(match.getLocation(playerId));

        if (!sendResult)
            return;

        Message result = playerId.equals(winnerId) ? Message.MATCH_WIN : Message.MATCH_LOSE;

        messageManager.send(player, result, "player", nameOf(match.getOpponent(playerId)));
    }

    private void forgetParticipant(UUID playerId)
    {
        matches.remove(playerId);
    }

    private Arena findFreeArena()
    {
        for (Arena arena : arenaManager.getArenas())
            if (arena.isReady() && arena.isEnabled() && !isArenaInUse(arena.getId()))
                return arena;

        return null;
    }

    public boolean isArenaInUse(int arenaId)
    {
        for (Match match : matches.values())
            if (match.getArena().getId() == arenaId)
                return true;

        return false;
    }

    private void prepareForMatch(Player player)
    {
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
        player.setSaturation(20f);
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

    private void startCountdown(Match match, Player player1, Player player2, boolean kitSelectionEnabled)
    {
        String actionBarFormat = kitSelectionEnabled
                ? "&eSelect a kit! Fight starts in %d..."
                : "&eFight starts in %d...";

        Countdown countdown = Countdown.builder(plugin.getJCore().tasks(), settings.kitSelectionSeconds())
                .actionBar(List.of(player1, player2), remaining -> String.format(actionBarFormat, remaining))
                .onComplete(() ->
                {
                    player1.closeInventory();
                    player2.closeInventory();

                    prepareForMatch(player1);
                    prepareForMatch(player2);

                    applyKit(match, player1);
                    applyKit(match, player2);

                    match.setState(MatchState.IN_PROGRESS);
                    messageManager.send(player1, Message.MATCH_START, "player", player2.getName());
                    messageManager.send(player2, Message.MATCH_START, "player", player1.getName());
                })
                .build();

        match.setCountdown(countdown);
        countdown.start();
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
