# Arena Containment Design

Status: planned, not yet implemented. Written for a follow-up session/agent to
implement directly.

## Problem

Two related gaps exist in how a duel's world effects are confined to its own
arena:

1. **Outward damage isn't stopped.** TNT, lava/water flow, and fire spread can
   reach past an arena's bounds during a match. Anything outside bounds is
   never tracked by `BlockChangeRollbackStrategy`, so any change out there is
   permanent - the arena reset only repairs what it recorded.
2. **Placement isn't restricted at all.** A player in an active match can
   currently place blocks, empty/fill buckets, or light fires anywhere on the
   server, in or out of their arena's bounds. Nothing checks this today.

Both are the same underlying gap: nothing currently enforces that a duel's
effect on the world stays inside its own arena.

## Existing behaviour (read before implementing)

- `ArenaInstance.contains(Location)` - the box test already used everywhere
  bounds matter. Returns `false` if bounds aren't set or the location is in a
  different world.
- `BlockChangeRollbackStrategy.resolveInProgressInstance(Location)` - already
  answers "which in-progress match owns this location". Used today only to
  decide what to *track* for rollback, never to prevent anything. It also
  consults a `resettingInstanceIds` set for the one-tick window between a
  match ending and its rollback starting - that quirk is specific to rollback
  timing (a fatal TNT hit ends the match before `EntityExplodeEvent` fires)
  and should stay local to that class; it is not a containment concern, because
  by the time a match has ended there is nothing left to *prevent*, only things
  left to *undo*.
- `BoundaryEnforcer` - owns player-*movement* enforcement (grace periods,
  scheduled re-checks, teleport-back, forfeit). It already has the shape of
  "does this player have a bounded instance right now" via its private
  `boundedInstance(Match)` / `resolve(UUID)`. Its `BoundaryMode.WARNING` lets a
  player physically leave the bounds with no teleport-back - this matters
  below.
- `ArenaResetStrategy` is an interface with exactly one implementation today
  (`BlockChangeRollbackStrategy`). Containment must not be built inside that
  implementation - it is a policy question ("what are we willing to let
  happen"), independent of *how* damage is later undone. A future
  template/WorldEdit-based reset strategy would need the same containment.
- Every relevant event (`BlockPlaceEvent`, `PlayerBucketEmptyEvent`,
  `PlayerBucketFillEvent`, `BlockIgniteEvent`, `EntityExplodeEvent`,
  `BlockExplodeEvent`, `BlockFromToEvent`, `BlockBurnEvent`) is currently only
  touched by `BlockChangeRollbackStrategy`, and only for tracking - nothing
  cancels or filters anything today.
- Dynamic arenas: all provisioned arena instances currently live in **one
  shared world**, `duels_dynamic_arenas`, tiled into a grid by
  `DynamicArenaLayout` (`slotsPerRow`, `slotWidth`, `slotLength`,
  `slotPadding`). They are not separate worlds per instance. The "feels like
  your own instance" experience relies entirely on the arena template being
  fully enclosed and the padding between slots being wide enough. Nothing
  currently stops a large enough explosion from reaching a neighbouring
  occupied slot.

## Requirements

1. A block change outward-caused by a match (TNT, lava/water flow, fire) may
   only affect blocks inside that match's own arena bounds.
2. A player in an active match may not place a block, empty/fill a bucket, or
   ignite a block outside their arena's bounds.
3. The check in (2) must also fail if the *player's own current location* is
   outside bounds, even if the target block would technically be inside -
   this closes the gap where `BoundaryMode.WARNING` lets a player physically
   wander out and then build back in from outside.
4. None of this may affect anything unrelated to an active match - ordinary
   world building/TNT/lava elsewhere on the server must behave exactly as
   before.
5. As a consequence of (1), a match's explosion must not reach a
   *different* active match's arena either (see dynamic arena note below) -
   this falls out of the same rule rather than needing separate handling.

## Feedback: message vs silent

This distinction matters and should not be collapsed into one rule:

- **Direct player actions** - `BlockPlaceEvent`, `PlayerBucketEmptyEvent`,
  `PlayerBucketFillEvent`, a player-lit `BlockIgniteEvent` - get a message on
  cancellation. The player deliberately tried to act at that location, so
  telling them why it failed is useful feedback, in the same spirit as the
  existing `Message.OUT_OF_BOUNDS_WARNING` for movement.
- **Propagation** - explosion block-list filtering, `BlockFromToEvent`,
  fire spread (`BlockBurnEvent`, `BlockIgniteEvent` with `IgniteCause.SPREAD`)
  - is **silent**. Nobody chose block-by-block where a blast or a flow
    physically reaches; messaging every suppressed block of a normal TNT
    fight would just be noise.

## Architecture decision

**New class: `ArenaContainmentGuard implements Listener`**, package
`me.jackcw.duels.arena`, registered in `Duels.java` alongside
`BoundaryEnforcer`, `MatchListener`, and `BlockChangeRollbackStrategy`.

Not placed in either existing class:

- Not `BlockChangeRollbackStrategy`, because that class is one specific
  `ArenaResetStrategy` implementation, and containment is a policy that must
  survive a future second implementation of that interface.
- Not `BoundaryEnforcer`, because that class's entire shape (grace periods,
  scheduled re-checks, teleport-back, forfeit) is about the *consequences of
  player movement*. Containment is immediate cancellation on block events - a
  different event family with no grace-period concept. Merging them would
  give one class two unrelated responsibilities.

**Shared helper (small refactor first):** extract
`MatchManager.getInProgressInstanceAt(Location)` from
`BlockChangeRollbackStrategy.resolveInProgressInstance`, keeping only the
strict "match is `IN_PROGRESS`" half there. Leave the `resettingInstanceIds`
grace-window check local to `BlockChangeRollbackStrategy` (rollback-specific,
not a containment concern - see above). `BlockChangeRollbackStrategy` then
calls the shared method plus its own grace check on top;
`ArenaContainmentGuard` only ever needs the shared method. This avoids two
classes each keeping an independent copy of "which instance owns this
location".

## Design

### A. Player-authored placement/interaction

One shared check:

```
boolean isAllowedLocation(Player player, Location target)
```

- No match for this player, or match not `IN_PROGRESS`, or the instance has
  no bounds configured -> allow (nothing to enforce).
- Otherwise require **both**:
  - `instance.contains(target)`, and
  - `instance.contains(player.getLocation())`.
- If either fails: cancel the event and send the message (throttled the same
  way `BoundaryEnforcer` already throttles `OUT_OF_BOUNDS_WARNING`, so
  spam-clicking doesn't spam the chat).

Hook into:
- `BlockPlaceEvent` - check `event.getBlockPlaced().getLocation()`.
- `PlayerBucketEmptyEvent` - check `event.getBlock().getLocation()`.
- `PlayerBucketFillEvent` - check `event.getBlock().getLocation()`.
- `BlockIgniteEvent` where `event.getIgnitingEntity() instanceof Player` -
  check `event.getBlock().getLocation()`.

No admin bypass. The restriction only ever applies to a player who is
currently one of the two participants in an `IN_PROGRESS` match - an admin
who is merely nearby or spectating is never affected, since they aren't in a
match. The only case a bypass could matter for is an admin *duelling as a
participant*, and giving one combatant an exemption their opponent doesn't
have is exactly the kind of unfairness this feature exists to prevent. Do not
add a bypass permission.

### B. Propagation / spillover

Same underlying rule, phrased as "a change may only reach the instance its
cause belongs to", applied silently:

- **`EntityExplodeEvent` / `BlockExplodeEvent`**: resolve the explosion's own
  location via `getInProgressInstanceAt`. If it resolves to instance `X`,
  filter `event.blockList()` down to only blocks where `X.contains(block.getLocation())`.
  If the explosion's location doesn't resolve to any in-progress instance
  (TNT unrelated to any active match), leave the event untouched entirely.
  Filtering rather than cancelling the whole event matters: a TNT stack
  detonated right at the boundary should still behave normally for the
  portion legitimately inside.
- **`BlockFromToEvent`**: if the *source* block (`event.getBlock()`) resolves
  to instance `X` and the *destination* (`event.getToBlock()`) is not inside
  `X`, cancel. If the source doesn't resolve to any instance, leave it alone -
  this is what keeps ordinary world liquid physics elsewhere on the server
  unaffected.
- **`BlockBurnEvent` / `BlockIgniteEvent(IgniteCause.SPREAD)`**: same
  source-in/destination-out shape. Verify during implementation what API each
  event exposes for identifying the igniting/source block on the current
  Paper version - this wasn't confirmed while planning and needs checking
  against the actual Javadoc rather than assumed.

### Dynamic arena neighbour spillover falls out of (B) for free

Because the explosion-filtering rule is "must resolve to *this* instance",
not "must be inside *some* arena", a match's TNT reaching into a
*neighbouring* active match's slot in `duels_dynamic_arenas` is rejected by
the exact same check - the neighbour's blocks resolve to a different instance
ID, not this one. No separate handling needed for this case.

## Explicitly out of scope for this change (flagged for later)

**True per-instance isolation for dynamic arenas.** Right now all dynamic
arenas share one world; isolation is only as good as slot padding and
template enclosure. A stronger guarantee (e.g. a separate world per instance,
or per-instance chunk isolation) is a bigger architectural change with a real
cost - constantly creating/deleting worlds is comparatively resource-heavy,
and needs to be weighed against how big a problem the shared-world model
actually turns out to be in practice. This containment fix does not attempt
to solve that; it only stops one match's blocks from physically altering a
neighbour's. Revisit as a separate roadmap item if it becomes a real problem
after this fix ships.

**Not addressed, accepted as-is:**
- Falling sand/gravel settling diagonally outside bounds after its support
  breaks right at the edge. Rare and low-impact; not worth hooking
  `EntityChangeBlockEvent`/`BlockPhysicsEvent` for.
- Hanging entities (item frames/paintings via `HangingPlaceEvent`) and
  placeable entities (boats/minecarts via `PlayerInteractEvent`). No current
  kit places these. Extend the containment guard to cover them only if a kit
  ever does.
- Damage *into* an active arena from outside is already handled correctly by
  existing behaviour and needs no change: `resolveInProgressInstance`
  (soon `getInProgressInstanceAt`) only looks at a block's own location, not
  who caused the change, so an outsider's TNT drifting into a live arena is
  already tracked and rolled back today.

## Implementation steps

1. Extract `MatchManager.getInProgressInstanceAt(Location)` from
   `BlockChangeRollbackStrategy.resolveInProgressInstance`; update
   `BlockChangeRollbackStrategy` to call it and keep only its
   `resettingInstanceIds` grace check locally.
2. Add a `Message` key for denied placement/interaction (e.g.
   `CANNOT_BUILD_OUTSIDE_ARENA`), with config default text, following the
   existing `Message` enum pattern.
3. Create `ArenaContainmentGuard implements Listener`; register it in
   `Duels.java` next to the other match-related listeners.
4. Implement `isAllowedLocation(Player, Location)` and a small throttle for
   the message (mirror `BoundaryEnforcer`'s `outOfBoundsSince` pattern rather
   than inventing a new one).
5. Hook `BlockPlaceEvent`, `PlayerBucketEmptyEvent`, `PlayerBucketFillEvent`,
   player-caused `BlockIgniteEvent` through that check.
6. Hook `EntityExplodeEvent`/`BlockExplodeEvent` block-list filtering.
7. Hook `BlockFromToEvent` cancellation.
8. Investigate the current Paper API for `BlockBurnEvent` and
   `BlockIgniteEvent(SPREAD)` source-block identification, then hook both.
9. Write/extend integration tests (see below).
10. Add class-level and method-level comments explaining the "same instance"
    rule and why it also covers dynamic-arena neighbour spillover, following
    the project's existing comment density (explain non-obvious *why*, not
    *what*).

## Testing plan

- Place a block outside bounds during an `IN_PROGRESS` match -> cancelled,
  message sent.
- `BoundaryMode.WARNING`, player physically outside bounds, target block
  inside bounds -> still cancelled (tests the player-location half of the
  check specifically, not just the target-block half).
- TNT detonated straddling the boundary -> blocks inside bounds destroyed and
  tracked/rolled back exactly as today; blocks outside bounds untouched; no
  message sent for either.
- Lava bucket emptied at the bounds edge -> flow stops at the boundary;
  nothing beyond it changes; no message sent for the flow stopping (though
  the initial bucket-empty itself, if attempted outside bounds, is messaged
  per the direct-action rule).
- Two adjacent occupied dynamic-arena slots, both `IN_PROGRESS` -> TNT in one
  never affects blocks tracked as belonging to the other.
- Unrelated TNT/lava/building elsewhere on the server, no active match
  nearby -> completely unaffected (regression check that this feature never
  becomes general-purpose grief prevention outside of duels).
- An outsider's TNT reaching into a live arena from outside -> still tracked
  and rolled back as before (regression check on existing behaviour, not new
  behaviour).

## Definition of done

Nothing a duel does - placement, TNT, lava, fire - can permanently or
temporarily affect a block outside its own arena's bounds, or inside a
different arena's bounds, while the existing rollback behaviour for changes
that stay inside bounds is unchanged. Direct player actions outside bounds
are messaged; propagation effects are silently prevented. Dynamic-arena
per-instance world isolation is explicitly deferred as a separate future
item.
