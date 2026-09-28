package me.jackcw.duels.arena;

import me.jackcw.duels.Duels;
import me.jackcw.duels.match.Match;
import me.jackcw.duels.match.MatchManager;
import me.jackcw.duels.message.Message;
import me.jackcw.jcore.message.MessageManager;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Keeps a duel's effect on the world inside the arena it is being fought in.
 *
 * <p>{@link BlockChangeRollbackStrategy} repairs an arena afterwards, but it
 * can only repair what it recorded, and it only records changes inside an
 * active match's bounds. Anything a duel did outside those bounds was
 * therefore permanent - a TNT crater in the world next to the arena, or a lava
 * flow that escaped through a gap, stayed there forever. Nothing restricted
 * placement either, so a duellist could simply build outside the arena.
 *
 * <p>Every rule here is one invariant: <em>a change may only reach the
 * instance its cause belongs to</em>. Expressing it that way rather than as
 * "must be inside some arena" is what also stops one match spilling into a
 * <em>different</em> one - neighbouring slots in the shared
 * {@code duels_dynamic_arenas} world resolve to a different instance, so they
 * are rejected by the same check, with no separate handling.
 *
 * <p>Two categories, deliberately treated differently:
 *
 * <ul>
 *   <li><b>Direct player actions</b> (placing, buckets, flint and steel) are
 *       cancelled <em>and</em> reported, because the player aimed at that
 *       location and silence would look like a bug.</li>
 *   <li><b>Propagation</b> (explosion radius, liquid flow, fire spread) is
 *       cancelled silently. Nobody chose block-by-block where a blast reaches,
 *       so messaging it would spam the chat throughout a normal TNT fight.</li>
 * </ul>
 *
 * <p>Nothing here restricts a player who is not in a match, so ordinary
 * building, TNT and lava elsewhere on the server behave exactly as before.
 * There is deliberately no admin bypass: the only player it could apply to is
 * an admin who is <em>fighting</em>, and exempting one combatant from a rule
 * their opponent is held to is the unfairness this class exists to prevent.
 */
public final class ArenaContainmentGuard implements Listener
{
    private static final long DENIED_MESSAGE_COOLDOWN_MS = 2_000L;

    private final MatchManager matchManager;
    private final MessageManager messageManager;

    // Throttles the denial message per player. Held against the click rate
    // rather than an in/out episode, because unlike leaving the bounds there is
    // no state to come back from - a player mashing right-click against the
    // boundary would otherwise get one message per attempt.
    private final Map<UUID, Long> lastDeniedMessageAt = new HashMap<>();

    public ArenaContainmentGuard(Duels plugin)
    {
        this.matchManager = plugin.getMatchManager();
        this.messageManager = plugin.core().messages();
    }

    /**
     * Every handler runs at {@link EventPriority#LOW}, ahead of
     * {@link BlockChangeRollbackStrategy}'s default-priority tracking. The
     * order matters in one direction: if tracking ran first it would record a
     * block state for a change this guard then prevents, spending a slot of
     * {@code arena-reset-max-tracked-block-changes} on a block that never
     * changed. Deciding what may happen before recording what did happen keeps
     * the rollback deque honest.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event)
    {
        denyIfOutsideOwnArena(event, event.getPlayer(), event.getBlockPlaced());
    }

    /**
     * Breaking is restricted for the same reason placing is: a block broken
     * outside the bounds is refused by the rollback's own location check, so it
     * is gone permanently. "A duel may only affect its own arena" has to cover
     * both directions of change, not just additions.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event)
    {
        denyIfOutsideOwnArena(event, event.getPlayer(), event.getBlock());
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event)
    {
        denyIfOutsideOwnArena(event, event.getPlayer(), event.getBlock());
    }

    /**
     * Filling a bucket is included even though it places nothing: scooping a
     * source block out of the world is still a change to that block, and one
     * the rollback would not restore if it happened outside the bounds.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event)
    {
        denyIfOutsideOwnArena(event, event.getPlayer(), event.getBlock());
    }

    /**
     * Splits by who lit the fire. A player with flint and steel is a direct
     * action and is reported; fire arriving from a burning block is
     * propagation, and is contained silently by the same source-in/
     * destination-out rule as lava.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBlockIgnite(BlockIgniteEvent event)
    {
        if (event.getIgnitingEntity() instanceof Player player)
        {
            denyIfOutsideOwnArena(event, player, event.getBlock());
            return;
        }

        containSpread(event, event.getIgnitingBlock(), event.getBlock());
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBlockBurn(BlockBurnEvent event)
    {
        containSpread(event, event.getIgnitingBlock(), event.getBlock());
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBlockFromTo(BlockFromToEvent event)
    {
        containSpread(event, event.getBlock(), event.getToBlock());
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event)
    {
        containExplosion(event.getLocation(), event.blockList());
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event)
    {
        containExplosion(event.getBlock().getLocation(), event.blockList());
    }

    /**
     * Protects an arena's decoration - item frames, paintings and armour
     * stands - from the duel being fought around it.
     *
     * <p>These are entities, not blocks, so none of the block handlers above
     * see them and {@link BlockChangeRollbackStrategy} cannot record or restore
     * them: an item frame caught in a TNT blast dropped its contents and was
     * gone for good, and the arena came back repaired but stripped. Preventing
     * the damage is the right fix rather than trying to restore it, because it
     * matches what already happens to an arena's walls - anything the admin
     * built and the duellists are not meant to touch simply survives the match.
     *
     * <p>Two events are needed. {@link HangingBreakEvent} covers the frame or
     * painting being destroyed, including by an explosion or by losing the
     * block it was mounted on, while {@link EntityDamageEvent} covers a punch
     * that knocks the <em>item</em> out of a frame without breaking the frame
     * itself. Neither is reported to the player: a blast reaching a wall
     * decoration is propagation, not something anyone aimed at.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHangingBreak(HangingBreakEvent event)
    {
        if (matchManager.getLiveInstanceAt(event.getEntity().getLocation()) != null)
            event.setCancelled(true);
    }

    /**
     * Stops a duellist equipping themselves from an arena's decorative armour
     * stands, or otherwise rearranging their gear.
     *
     * <p>This is a direct action rather than propagation - the player aimed at
     * a specific stand - so it is reported the same way a denied block place or
     * break is, rather than silently, matching the categorisation above.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onArmorStandManipulate(PlayerArmorStandManipulateEvent event)
    {
        if (matchManager.getLiveInstanceAt(event.getRightClicked().getLocation()) == null)
            return;

        event.setCancelled(true);
        sendDeniedMessage(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDecorationDamaged(EntityDamageEvent event)
    {
        if (!(event.getEntity() instanceof Hanging) && !(event.getEntity() instanceof ArmorStand))
            return;

        if (matchManager.getLiveInstanceAt(event.getEntity().getLocation()) != null)
            event.setCancelled(true);
    }

    /**
     * Fire ticks are set separately from damage, so cancelling
     * {@link EntityDamageEvent} alone leaves a stand that lava or a fire charge
     * reached burning for the rest of the match: it takes no damage and is never
     * destroyed, but it is visibly alight, which is not "survives untouched".
     * Cancelling combustion stops the fire ticks ever being applied.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDecorationCombust(EntityCombustEvent event)
    {
        if (!(event.getEntity() instanceof Hanging) && !(event.getEntity() instanceof ArmorStand))
            return;

        if (matchManager.getLiveInstanceAt(event.getEntity().getLocation()) != null)
            event.setCancelled(true);
    }

    /**
     * Stops a duellist reaching across their own boundary to collect an item
     * lying just outside it - a stray death drop from a bystander, or one
     * thrown over the wall by someone helping them from outside. The same
     * rule that keeps a duel's own changes inside its arena also keeps
     * whatever the arena is bordered by from feeding into it.
     *
     * <p>Left silent rather than reported: pickup is automatic on touch, not
     * an aimed action, so a duellist grazing the boundary while fighting
     * would otherwise be messaged every time, unlike a deliberate block place.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onItemPickup(EntityPickupItemEvent event)
    {
        if (!(event.getEntity() instanceof Player player))
            return;

        if (!isAllowedLocation(player, event.getItem().getLocation()))
            event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event)
    {
        lastDeniedMessageAt.remove(event.getPlayer().getUniqueId());
    }

    /**
     * Whether a duellist may change this block.
     *
     * <p>The player's own position is checked as well as the target's, which is
     * not redundant: {@link BoundaryMode#WARNING} lets a combatant walk out of
     * the arena and stay out, and from there they could otherwise still reach
     * back over the boundary and keep building in a fight they have physically
     * left.
     */
    private boolean isAllowedLocation(Player player, Location target)
    {
        Match match = matchManager.getMatch(player.getUniqueId());

        if (match == null || !match.isLive())
            return true;

        ArenaInstance instance = match.getArenaInstance();

        // An arena with no bounds configured has no containment to enforce.
        // Matching the reset strategy here matters: without bounds nothing is
        // tracked or rolled back either, so denying placement would restrict
        // players without protecting anything.
        if (!instance.hasBounds())
            return true;

        return instance.contains(target) && instance.contains(player.getLocation());
    }

    private void denyIfOutsideOwnArena(Cancellable event, Player player, Block target)
    {
        if (isAllowedLocation(player, target.getLocation()))
            return;

        event.setCancelled(true);
        sendDeniedMessage(player);
    }

    /**
     * Contains anything that travels from one block to another - liquid flow,
     * and fire moving between blocks.
     *
     * <p>Keyed on the source being inside a live arena, so a flow or a fire
     * with no connection to a duel is left entirely alone. Without that
     * condition this would cancel ordinary water and lava physics everywhere
     * on the server, since almost nowhere is inside an arena.
     *
     * <p>A null source means the server did not tell us where the change came
     * from, which happens for some ignition causes. Nothing can be attributed
     * then, so nothing is prevented.
     */
    private void containSpread(Cancellable event, Block source, Block target)
    {
        if (source == null)
            return;

        ArenaInstance instance = matchManager.getLiveInstanceAt(source.getLocation());

        if (instance != null && !instance.contains(target.getLocation()))
            event.setCancelled(true);
    }

    /**
     * Strips the out-of-bounds part of an explosion instead of cancelling it.
     *
     * <p>A charge detonated against the arena wall legitimately destroys the
     * blocks on the inside of it, and cancelling the whole event would leave
     * that wall untouched and the TNT wasted. Removing entries from
     * {@code blockList()} lets the explosion behave normally for the portion
     * that is actually inside the arena.
     *
     * <p>An explosion whose own origin is not inside a live match is left
     * completely alone - this is a duel containment rule, not server-wide grief
     * protection. The reverse direction, an outside explosion reaching into an
     * arena, is already handled by the reset strategy, which records changed
     * blocks by location without caring what caused them.
     */
    private void containExplosion(Location origin, List<Block> blocks)
    {
        ArenaInstance instance = matchManager.getLiveInstanceAt(origin);

        if (instance == null)
            return;

        blocks.removeIf(block -> !instance.contains(block.getLocation()));
    }

    private void sendDeniedMessage(Player player)
    {
        long now = System.currentTimeMillis();
        Long last = lastDeniedMessageAt.get(player.getUniqueId());

        if (last != null && now - last < DENIED_MESSAGE_COOLDOWN_MS)
            return;

        lastDeniedMessageAt.put(player.getUniqueId(), now);
        messageManager.send(player, Message.CANNOT_BUILD_OUTSIDE_ARENA);
    }
}
