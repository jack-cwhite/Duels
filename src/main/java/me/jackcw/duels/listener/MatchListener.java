package me.jackcw.duels.listener;

import com.destroystokyo.paper.event.player.PlayerAdvancementCriterionGrantEvent;
import me.jackcw.duels.Duels;
import me.jackcw.duels.arena.BoundaryEnforcer;
import me.jackcw.duels.challenge.Challenge;
import me.jackcw.duels.challenge.ChallengeManager;
import me.jackcw.duels.match.Match;
import me.jackcw.duels.match.MatchConclusion;
import me.jackcw.duels.match.MatchManager;
import me.jackcw.duels.match.MatchState;
import me.jackcw.duels.message.Message;
import me.jackcw.duels.spectator.SpectatorManager;
import me.jackcw.jcore.message.MessageManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
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
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent.RegainReason;
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

        // Isolation applies in both directions. A combatant may not hurt a
        // bystander, even though the bystander is not present in matches.
        if (match == null)
        {
            if (attacker != null && matchManager.getMatch(attacker.getUniqueId()) != null)
                event.setCancelled(true);
            else if (isUnattributedArenaHazard(event.getCause()) && matchManager.isInsideUnsafeArena(damaged.getLocation()))
                event.setCancelled(true);
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
            event.setCancelled(true);
            return;
        }

        if (damaged.getHealth() - event.getFinalDamage() > 0)
            return;

        event.setCancelled(true);

        matchManager.endMatch(match, MatchConclusion.defeat(
                match.getOpponent(damaged.getUniqueId()), event.getCause().name()));
    }

    /**
     * Stops passive food-based healing for the duration of a duel.
     *
     * <p>Vanilla heals a player with a full hunger bar automatically - fast
     * while saturation remains, slowly after it runs out. In a duel that means
     * a fight can be decided by waiting rather than by fighting, and sustained
     * environmental damage (standing in fire, drowning) can be out-healed
     * indefinitely. Lowering the starting saturation shrinks the effect but
     * cannot remove it, because the slow tier needs no saturation at all.
     *
     * <p>Only {@link RegainReason#SATIATED} is blocked. Regeneration from a
     * potion, a golden apple, or anything else a kit deliberately provides is
     * left alone - the intent is to remove healing nobody chose, not healing a
     * player earned.
     *
     * <p>Doing this per-player rather than through the {@code naturalRegeneration}
     * gamerule matters: the gamerule is world-wide and would change survival for
     * everyone on the server, including players nowhere near a duel.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onRegainHealth(EntityRegainHealthEvent event)
    {
        if (!(event.getEntity() instanceof Player player))
            return;

        if (event.getRegainReason() != RegainReason.SATIATED)
            return;

        Match match = matchManager.getMatch(player.getUniqueId());

        if (match != null && match.getState() == MatchState.IN_PROGRESS)
            event.setCancelled(true);
    }

    /**
     * Stops a duel's lava or fire setting a bystander alight in the first place.
     *
     * <p>Cancelling the damage is not enough on its own. Fire ticks are applied
     * separately, so a bystander standing in a duel's lava burned for the whole
     * fight while taking none of the damage - and then took all of it the moment
     * protection lapsed, because they were still on fire after the arena had been
     * restored and the lava was gone. Extinguishing them at that point would work
     * too, but not igniting them is simpler and leaves nothing to clean up.
     *
     * <p>Scoped to players with no match of their own, so a duellist still burns
     * normally: combatants are not protected from their own duel's hazards.
     * Someone who walked in already alight keeps burning, which is correct - that
     * fire is not the duel's doing.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBystanderCombust(EntityCombustEvent event)
    {
        if (!(event.getEntity() instanceof Player player))
            return;

        if (matchManager.getMatch(player.getUniqueId()) != null)
            return;

        if (matchManager.isInsideUnsafeArena(player.getLocation()))
            event.setCancelled(true);
    }

    /**
     * Protects a bystander from a duel's environmental hazards by geometry
     * rather than by attribution.
     *
     * <p>The attacker-based rule above only covers damage Bukkit raises as an
     * {@link EntityDamageByEntityEvent} - it cannot see TNT lit by redstone
     * ({@link TNTPrimed#getSource()} is null in that case, and the Destruction
     * kit ships redstone torches, so that is the ordinary case rather than an
     * edge one), and it cannot see lava, fire, or a magma block either, since
     * none of those raise a "by entity" event at all. Any of them would
     * otherwise reach anyone standing in a hand-built arena in the main world,
     * which is a place an unrelated player can genuinely wander into.
     *
     * <p>Deliberately limited to causes that are never attributable to a
     * specific attacker. Cancelling every kind of damage inside an arena's
     * bounds would let anyone stand in someone else's duel to become
     * invulnerable to that duel's combatants directly hitting them - but that
     * case is already handled above, by the attacker-based check. This check
     * only ever fires for damage nobody could have aimed at a bystander in the
     * first place.
     */
    private boolean isUnattributedArenaHazard(EntityDamageEvent.DamageCause cause)
    {
        return cause == EntityDamageEvent.DamageCause.ENTITY_EXPLOSION
                || cause == EntityDamageEvent.DamageCause.BLOCK_EXPLOSION
                || cause == EntityDamageEvent.DamageCause.LAVA
                || cause == EntityDamageEvent.DamageCause.FIRE
                || cause == EntityDamageEvent.DamageCause.FIRE_TICK
                || cause == EntityDamageEvent.DamageCause.HOT_FLOOR;
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
        EntityDamageEvent lastDamage = player.getLastDamageCause();
        matchManager.endMatch(match, MatchConclusion.defeat(
                winnerId, lastDamage != null ? lastDamage.getCause().name() : null));
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
        matchManager.endMatch(match, MatchConclusion.disconnect(winnerId));
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
