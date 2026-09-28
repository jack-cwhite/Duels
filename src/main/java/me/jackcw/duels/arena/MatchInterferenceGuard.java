package me.jackcw.duels.arena;

import me.jackcw.duels.Duels;
import me.jackcw.duels.match.Match;
import me.jackcw.duels.match.MatchManager;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FishHook;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.AreaEffectCloudApplyEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.LingeringPotionSplashEvent;
import org.bukkit.event.entity.PotionSplashEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.projectiles.ProjectileSource;

import java.util.UUID;

/**
 * Stops someone who is not fighting a duel from reaching into one.
 *
 * <p>{@link ArenaContainmentGuard} stops a duellist reaching out of their
 * arena and {@link ArenaAccessGuard} keeps other people's bodies out of it.
 * Neither covers the remaining direction: a bystander standing at the wall can
 * throw or shoot something the boundary stops them following, and whatever
 * they sent lands in the middle of the duel. A friend tossing a spare sword or
 * a stack of gapples over the wall decides the fight, and a stranger splashing
 * Regeneration in does the same thing more quietly.
 *
 * <p>Damage is already handled elsewhere - {@code MatchListener} cancels any
 * hit on a duellist that did not come from their opponent, which covers arrows,
 * snowballs and harming potions - so what is left is objects and effects that
 * help rather than hurt, plus the fishing rod, which moves a player without
 * damaging them.
 *
 * <p>The rule is enforced by marking what a non-combatant creates rather than
 * by marking what a duel creates, which is a deliberate choice about how this
 * fails. A duel's own items come into existence through several routes - a
 * player dropping gear, arrows they fired, everything dropped on death - and if
 * this class marked those and rejected anything unmarked, then overlooking one
 * route would silently confiscate a duellist's own belongings. Marking the
 * bystander's side instead means overlooking a route leaves an exploit open,
 * which is visible and harmless by comparison.
 *
 * <p>Nothing is ever destroyed or confiscated: a bystander's thrown item stays
 * lying where it landed for them to collect, because Duels causing an ordinary
 * survival player to lose items is a worse outcome than the interference it
 * would be preventing. The duellist simply cannot pick it up.
 *
 * <p>Deliberately not keyed on arena bounds. "A duellist may not be handed
 * things from outside the duel" is true whether or not the arena has bounds
 * configured, and phrasing it around who is in a match rather than around
 * geometry means it also covers an arena whose bounds an admin never set.
 */
public final class MatchInterferenceGuard implements Listener
{
    private final MatchManager matchManager;
    private final NamespacedKey foreignKey;

    public MatchInterferenceGuard(Duels plugin)
    {
        this.matchManager = plugin.getMatchManager();
        this.foreignKey = new NamespacedKey(plugin, "foreign-to-duel");
    }

    /**
     * Marked at {@link EventPriority#MONITOR} so the mark only lands on drops
     * that other plugins have actually allowed to happen.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDropItem(PlayerDropItemEvent event)
    {
        markIfNotDuelling(event.getPlayer(), event.getItemDrop());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onProjectileLaunch(ProjectileLaunchEvent event)
    {
        if (event.getEntity().getShooter() instanceof Player shooter)
            markIfNotDuelling(shooter, event.getEntity());
    }

    /**
     * A lingering potion applies its effects through a cloud it spawns rather
     * than through itself, so the mark has to be carried across to the cloud
     * here or {@link #onAreaEffectCloudApply} would have nothing to read.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLingeringPotionSplash(LingeringPotionSplashEvent event)
    {
        if (isForeign(event.getEntity()))
            mark(event.getAreaEffectCloud());
    }

    /**
     * Silent, like {@link ArenaContainmentGuard}'s own pickup rule: picking up
     * is attempted continuously while a player stands over an item, so a
     * message here would repeat several times a second.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onItemPickup(EntityPickupItemEvent event)
    {
        if (isDuelling(event.getEntity()) && isForeign(event.getItem()))
            event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPotionSplash(PotionSplashEvent event)
    {
        if (!isForeign(event.getEntity()))
            return;

        // Zero intensity rather than cancelling the splash, so bystanders
        // caught in the same cloud of vapour are still affected normally - the
        // duel is what this protects, not everyone standing near it.
        for (LivingEntity affected : event.getAffectedEntities())
            if (isDuelling(affected))
                event.setIntensity(affected, 0.0D);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onAreaEffectCloudApply(AreaEffectCloudApplyEvent event)
    {
        if (isForeign(event.getEntity()))
            event.getAffectedEntities().removeIf(this::isDuelling);
    }

    /**
     * Stops the hook fastening onto a duellist in the first place.
     *
     * <p>Blocking the reel alone is not enough. Unlike an arrow, whose effect on
     * a duel is entirely in its damage, a fishing line that has latched on stays
     * drawn across the hooked player's screen until the caster retrieves it, and
     * that is a duel being visibly interfered with even when nothing can move
     * them. Whether an entity is hooked is ordinary server-side state rather
     * than something only the client knows, so clearing it here is enough - this
     * does not need packet work.
     *
     * <p>The bobber is removed rather than left floating because whether
     * cancelling this event alone prevents the attachment depends on how the
     * server orders the hit against the field being set, and removing the entity
     * the line is drawn to does not depend on that ordering at all. Nothing is
     * lost by it: a bobber is not an item, and the caster keeps their rod.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onProjectileHit(ProjectileHitEvent event)
    {
        if (!(event.getEntity() instanceof FishHook hook) || !(event.getHitEntity() instanceof Player hooked))
            return;

        if (!isShieldedFrom(hook.getShooter(), hooked))
            return;

        event.setCancelled(true);
        hook.setHookedEntity(null);
        hook.remove();
    }

    /**
     * The reel itself, kept as well as {@link #onProjectileHit} so that a hook
     * attached by any route this class does not see still cannot drag a
     * duellist. A fishing rod is the one way to move a player without damaging
     * them, so the damage rules covering every other projectile miss it, and
     * being pulled out of position mid-fight decides a duel as well as a hit.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event)
    {
        if (event.getCaught() instanceof Player hooked && isShieldedFrom(event.getPlayer(), hooked))
            event.setCancelled(true);
    }

    /**
     * Whether this player is in a live duel and the given shooter is not the
     * opponent they agreed to fight. One duellist rodding the other is ordinary
     * combat and must keep working.
     */
    private boolean isShieldedFrom(ProjectileSource shooter, Player hooked)
    {
        if (!isDuelling(hooked))
            return false;

        Match match = matchManager.getMatch(hooked.getUniqueId());
        UUID opponentId = match.getOpponent(hooked.getUniqueId());

        return !(shooter instanceof Player caster && caster.getUniqueId().equals(opponentId));
    }

    private void markIfNotDuelling(Player creator, Entity entity)
    {
        if (!isDuelling(creator))
            mark(entity);
    }

    private void mark(Entity entity)
    {
        entity.getPersistentDataContainer().set(foreignKey, PersistentDataType.BYTE, (byte) 1);
    }

    /**
     * Whether this object was created by someone who was not in a duel at the
     * time.
     *
     * <p>Read from the entity's own persistent data rather than from a set held
     * in memory so that a reload or restart mid-flight cannot turn a marked
     * object back into a legitimate one, and so nothing has to be cleaned up
     * when the item despawns or the arrow lands.
     */
    private boolean isForeign(Entity entity)
    {
        return entity.getPersistentDataContainer().has(foreignKey, PersistentDataType.BYTE);
    }

    private boolean isDuelling(Entity entity)
    {
        if (!(entity instanceof Player player))
            return false;

        Match match = matchManager.getMatch(player.getUniqueId());

        return match != null && match.isLive();
    }

    /**
     * Exposed so the item-pickup and splash rules can be exercised without
     * having to reproduce a real throw across a wall in a test.
     */
    public boolean isForeignToDuels(Entity entity)
    {
        return isForeign(entity);
    }
}
