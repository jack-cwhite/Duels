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

        Player attacker = event instanceof EntityDamageByEntityEvent damageByEntity ? resolveAttacker(damageByEntity.getDamager()) : null;
        Match match = matchManager.getMatch(damaged.getUniqueId());

        logExplosionDiagnostic(event, damaged, attacker, match);

        // Isolation applies in both directions. A combatant may not hurt a
        // bystander, even though the bystander is not present in matches.
        if (match == null)
        {
            if (attacker != null && matchManager.getMatch(attacker.getUniqueId()) != null)
            {
                logExplosionOutcome(event, "cancelled: combatant may not damage a bystander");
                event.setCancelled(true);
            }
            return;
        }

        // A null attacker means the damage is environmental (lava, drowning,
        // fall, fire, void, starvation, ...) rather than PvP. That is still a
        // death that should end the match in the opponent's favour - it is
        // only the bystander-protection check below that needs another player
        // to make sense of "invalid target".
        boolean isSelf = attacker != null && damaged.getUniqueId().equals(attacker.getUniqueId());
        boolean isOpponent = attacker != null && attacker.getUniqueId().equals(match.getOpponent(damaged.getUniqueId()));
        boolean isInvalidTarget = attacker != null && !isSelf && !isOpponent;
        boolean isMatchNotInProgress = (match.getState() != MatchState.IN_PROGRESS);

        if (isInvalidTarget || isMatchNotInProgress)
        {
            logExplosionOutcome(event, isInvalidTarget ? "cancelled: attacker is not a participant in this match"
                    : "cancelled: match state is " + match.getState());
            event.setCancelled(true);
            return;
        }

        if (damaged.getHealth() - event.getFinalDamage() > 0)
        {
            logExplosionOutcome(event, "allowed: non-fatal, damage applied normally");
            return;
        }

        logExplosionOutcome(event, "cancelled: fatal hit intercepted, ending match");
        event.setCancelled(true);

        matchManager.endMatch(match, match.getOpponent(damaged.getUniqueId()));
    }

    /**
     * Temporary diagnostics for a long-running "TNT does no damage in duels"
     * report that three rounds of live testing failed to pin down. Explosion
     * damage against a player is rare enough that logging every one is not
     * meaningful spam, and the fields below distinguish the candidate causes
     * that reasoning alone could not: Creative or invulnerability (no event
     * would reach here at all, so absence of these lines is itself the
     * answer), a damage value already reduced to nothing before Duels sees it,
     * and Duels cancelling the event itself. Remove once the report is closed.
     */
    private void logExplosionDiagnostic(EntityDamageEvent event, Player damaged, Player attacker, Match match)
    {
        if (!shouldLogDamage(event))
            return;

        plugin.getLogger().info(String.format(
                "[tnt-debug] %s cause=%s gamemode=%s invulnerable=%s health=%.2f raw=%.2f final=%.2f damager=%s attacker=%s match=%s",
                damaged.getName(),
                event.getCause(),
                damaged.getGameMode(),
                damaged.isInvulnerable(),
                damaged.getHealth(),
                event.getDamage(),
                event.getFinalDamage(),
                event instanceof EntityDamageByEntityEvent byEntity ? byEntity.getDamager().getType() : "none",
                attacker != null ? attacker.getName() : "unresolved",
                match != null ? match.getState() : "none"));
    }

    private void logExplosionOutcome(EntityDamageEvent event, String outcome)
    {
        if (shouldLogDamage(event))
            plugin.getLogger().info("[tnt-debug] -> " + outcome);
    }

    // Widened from explosions only: the report now includes drowning and fire
    // doing nothing either, and hunger never depleting, so the question is no
    // longer about TNT specifically but about whether any damage at all
    // reaches a duellist.
    private boolean shouldLogDamage(EntityDamageEvent event)
    {
        return true;
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

