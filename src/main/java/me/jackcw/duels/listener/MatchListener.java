package me.jackcw.duels.listener;

import com.destroystokyo.paper.event.player.PlayerAdvancementCriterionGrantEvent;
import me.jackcw.duels.Duels;
import me.jackcw.duels.arena.BoundaryEnforcer;
import me.jackcw.duels.challenge.Challenge;
import me.jackcw.duels.challenge.ChallengeManager;
import me.jackcw.duels.match.Match;
import me.jackcw.duels.match.MatchManager;
import me.jackcw.duels.match.MatchState;
import me.jackcw.duels.message.Message;
import me.jackcw.duels.spectator.SpectatorManager;
import me.jackcw.jcore.message.MessageManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.EvokerFangs;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Tameable;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.AreaEffectCloudApplyEvent;
import org.bukkit.event.entity.PotionSplashEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

import java.util.List;
import java.util.UUID;

public final class MatchListener implements Listener
{
    private final Duels plugin;
    private final MessageManager messageManager;
    private final MatchManager matchManager;
    private final ChallengeManager challengeManager;
    private final BoundaryEnforcer boundaryEnforcer;
    private final SpectatorManager spectatorManager;

    public MatchListener(Duels plugin)
    {
        this.plugin = plugin;
        this.messageManager = plugin.core().messages();
        this.matchManager = plugin.getMatchManager();
        this.challengeManager = plugin.getChallengeManager();
        this.boundaryEnforcer = plugin.getBoundaryEnforcer();
        this.spectatorManager = plugin.getSpectatorManager();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageEvent event)
    {
        if (!(event.getEntity() instanceof Player damaged))
            return;

        Match match = matchManager.getMatch(damaged.getUniqueId());

        if (match == null)
            return;

        Player attacker = event instanceof EntityDamageByEntityEvent damageByEntity ? resolveAttacker(damageByEntity.getDamager()) : null;

        if (attacker == null)
            return;

        boolean isSelf = damaged.getUniqueId().equals(attacker.getUniqueId());
        boolean isOpponent = attacker.getUniqueId().equals(match.getOpponent(damaged.getUniqueId()));
        boolean isInvalidTarget = !isSelf && !isOpponent;
        boolean isMatchNotInProgress = (match.getState() != MatchState.IN_PROGRESS);

        if (isInvalidTarget || isMatchNotInProgress)
        {
            event.setCancelled(true);
            return;
        }

        if (damaged.getHealth() - event.getFinalDamage() > 0)
            return;

        event.setCancelled(true);

        matchManager.endMatch(match, match.getOpponent(damaged.getUniqueId()));
    }

    private Player resolveAttacker(Entity damager)
    {
        if (damager instanceof Player player)
            return player;

        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player shooter)
            return shooter;

        if (damager instanceof Tameable tameable && tameable.getOwner() instanceof Player owner)
            return owner;

        if (damager instanceof TNTPrimed tnt && tnt.getSource() instanceof Player source)
            return source;

        if (damager instanceof AreaEffectCloud cloud && cloud.getSource() instanceof Player source)
            return source;

        if (damager instanceof EvokerFangs fangs && fangs.getOwner() instanceof Player owner)
            return owner;

        return null;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPotionSplash(PotionSplashEvent event)
    {
        Player source = event.getPotion().getShooter() instanceof Player player ? player : null;

        for (var affected : event.getAffectedEntities())
            if (affected instanceof Player target && shouldBlockEffect(source, target))
                event.setIntensity(target, 0.0);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onAreaEffectCloud(AreaEffectCloudApplyEvent event)
    {
        Player source = event.getEntity().getSource() instanceof Player player ? player : null;

        event.getAffectedEntities().removeIf(entity ->
                entity instanceof Player target && shouldBlockEffect(source, target));
    }

    private boolean shouldBlockEffect(Player source, Player target)
    {
        // Spectator gamemode already makes a player immune to this, so it is
        // defence in depth rather than the mechanism - but it also means a
        // spectator is never treated as an uninvolved third party standing in
        // the splash radius, which is what the rest of this method is about.
        if (spectatorManager.isSpectating(target.getUniqueId()))
            return true;

        Match targetMatch = matchManager.getMatch(target.getUniqueId());
        Match sourceMatch = source != null ? matchManager.getMatch(source.getUniqueId()) : null;

        if (targetMatch == null)
            return sourceMatch != null;

        if (targetMatch.getState() != MatchState.IN_PROGRESS)
            return true;

        if (source == null || sourceMatch != targetMatch)
            return true;

        UUID sourceId = source.getUniqueId();
        return !sourceId.equals(target.getUniqueId())
                && !sourceId.equals(targetMatch.getOpponent(target.getUniqueId()));
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event)
    {
        Player player = event.getPlayer();
        Match match = matchManager.getMatch(player.getUniqueId());

        if (match == null)
            return;

        event.getDrops().clear();

        UUID winnerId = match.getOpponent(player.getUniqueId());
        matchManager.endMatch(match, winnerId);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event)
    {
        boundaryEnforcer.handleMove(event);
    }

    /**
     * Advancements earned incidentally inside a duel are suppressed before they
     * are granted rather than revoked afterwards.
     *
     * <p>Revoking on {@code PlayerAdvancementDoneEvent} cannot work: that event
     * is not cancellable and fires after the toast and broadcast, and because
     * the underlying trigger is still satisfied the criterion is immediately
     * re-awarded - a grant/revoke/grant loop that spams the player. Paper's
     * criterion-grant event is cancellable and fires first, so nothing is ever
     * awarded and there is nothing to re-award.
     */
    @EventHandler(ignoreCancelled = true)
    public void onAdvancementCriterionGrant(PlayerAdvancementCriterionGrantEvent event)
    {
        if (matchManager.getMatch(event.getPlayer().getUniqueId()) != null)
            event.setCancelled(true);
    }

    @EventHandler
    public void onPlayerRespawn(PlayerRespawnEvent event)
    {
        matchManager.handleRespawn(event.getPlayer(), event);
    }

    @EventHandler
    public void onPlayerQuitWhileInMatch(PlayerQuitEvent event)
    {
        Player player = event.getPlayer();
        Match match = matchManager.getMatch(player.getUniqueId());

        boundaryEnforcer.forget(player.getUniqueId());

        // A spectator's session is dropped but its saved row is kept, so they
        // are put back by the join handler next time rather than teleported now
        // - the player is already on their way out, and this runs whether or
        // not they were in a match, so it must happen before the return below.
        spectatorManager.detach(player.getUniqueId());

        if (match == null)
            return;

        if (plugin.isServerStopping())
        {
            matchManager.abortForServerStop(match);
            return;
        }

        UUID winnerId = match.getOpponent(player.getUniqueId());
        matchManager.endMatch(match, winnerId);
    }

    @EventHandler
    public void onPlayerQuitWithActiveChallenges(PlayerQuitEvent event)
    {
        UUID uuid = event.getPlayer().getUniqueId();
        List<Challenge> challenges = challengeManager.getChallenges(uuid);

        if (challenges.isEmpty())
            return;

        for (Challenge challenge : challenges)
        {
            challengeManager.remove(challenge);

            boolean leaverWasChallenger = challenge.getChallenger().equals(uuid);
            UUID otherId = leaverWasChallenger ? challenge.getChallenged() : challenge.getChallenger();
            Player other = Bukkit.getPlayer(otherId);

            if (other != null)
                messageManager.send(other, Message.CHALLENGE_CANCELLED_DISCONNECT, "player", event.getPlayer().getName());
        }
    }
}

