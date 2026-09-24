# Arena Containment - In-Game Test Plan

Verifies `ArenaContainmentGuard` (commit `20466ae`) and the lava bystander fix
(`5348961`) on a live server. The integration tests cover the logic; this plan
covers what MockBukkit cannot simulate - real fire spread, real liquid flow,
real explosion radii, and whether the feedback actually feels right in chat.

## Setup

1. One hand-built arena with an enclosed floor, four walls and a roof, using
   the block-change rollback reset strategy (not a provisioned/template arena -
   template pasting would repair anything the rollback missed and hide
   failures).
2. Bounds set to the inside faces of that enclosure.
3. A kit containing **TNT, flint and steel, a lava bucket, a water bucket, and
   redstone torches**. Redstone-lit TNT specifically matters: it is the case
   where `TNTPrimed.getSource()` is null, which is why containment is decided by
   geometry rather than by who lit it.
4. Three accounts: two duellists (**A**, **B**) and one **bystander** (**C**)
   who is never in a match.
5. Place a few obvious marker blocks (coloured wool) **just outside** each wall
   and one on the roof, so outward damage is visible at a glance.

## Part 1 - Direct player actions (expect a message)

The message is throttled to one per two seconds, so spam-clicking should
produce a trickle, not a wall of text.

| # | Action | Expected |
|---|---|---|
| 1.1 | A places a block well inside the arena | Succeeds. Removed again when the match ends. |
| 1.2 | A stands inside, aims over the wall, places a block outside | Cancelled. `You can only build inside the arena bounds.` |
| 1.3 | A spam-right-clicks that same outside spot for ~10s | At most one message every 2s. |
| 1.4 | A breaks one of the marker blocks outside the wall | Cancelled, same message. Marker survives. |
| 1.5 | A empties a lava bucket outside the bounds | Cancelled, same message. No lava appears. |
| 1.6 | A fills a bucket from a source outside the bounds | Cancelled, same message. Source survives. |
| 1.7 | A flint-and-steels a block outside the bounds | Cancelled, same message. No fire. |
| 1.8 | Repeat 1.2 as **B** | Same result - no participant is exempt. |

### 1.9 - WARNING boundary mode (the two-sided check)

Set the arena's boundary mode to `WARNING`, so leaving the bounds only warns
instead of teleporting back.

1. A walks physically outside the bounds.
2. A tries to place a block **inside** the bounds, reaching back over the
   boundary.

Expected: **cancelled** with the message, even though the target block is
inside. This is the half of the check that tests the player's own location, and
it is the only scenario that exercises it.

## Part 2 - Propagation (expect silence)

Nothing in this part should produce a chat message. Watch chat throughout - a
message here is a bug.

| # | Action | Expected |
|---|---|---|
| 2.1 | TNT detonated in the middle of the arena | Normal crater. No item drops. Fully restored at match end. |
| 2.2 | TNT placed against the inside of a wall and lit **with a redstone torch** | Inside face of the wall is destroyed; markers outside are untouched; the wall is rebuilt at match end. No message. |
| 2.3 | TNT on the roof, lit by redstone | Roof damaged inside the bounds only; nothing above/outside changes. |
| 2.4 | Lava bucket emptied next to a wall with a gap or doorway | Flow stops at the boundary. Nothing outside changes. Arena restored afterwards. |
| 2.5 | Water bucket emptied at the boundary | Same - flow contained. |
| 2.6 | Flint and steel on a flammable block inside, near a wall | Fire spreads inside the bounds but does not cross it. No blocks outside catch or burn away. |
| 2.7 | A long TNT-and-lava fight, ~1 minute | Chat stays clean of containment messages. Arena restored (allowing for the documented `arena-reset-max-tracked-block-changes` ceiling). |

## Part 3 - Regressions (nothing unrelated may change)

This is the most important part. Containment must never become server-wide
grief protection.

| # | Action | Expected |
|---|---|---|
| 3.1 | While A and B duel, **C** builds freely right outside the arena wall | Completely unrestricted. No message. |
| 3.2 | C detonates TNT outside the arena, away from any bounds | Normal vanilla explosion, normal item drops. |
| 3.3 | C empties a lava bucket outside the arena and lets it flow | Flows normally. Not cancelled at any boundary. |
| 3.4 | C builds/breaks/TNTs somewhere far away with no match running at all | Entirely normal. |
| 3.5 | C's TNT outside the arena blasts *into* the live arena | Blocks inside the arena are still tracked and **restored** at match end (existing behaviour, unchanged). |
| 3.6 | A duel in an arena with **no bounds configured** | Nothing is restricted and nothing is rolled back - unchanged from before. |

## Part 4 - Dynamic arenas (shared world)

Requires two simultaneous provisioned matches in adjacent grid slots of
`duels_dynamic_arenas`.

| # | Action | Expected |
|---|---|---|
| 4.1 | Match 1 detonates TNT hard against the wall facing Match 2's slot | Match 2's arena is physically unchanged. |
| 4.2 | Match 1 floods lava toward Match 2's slot | Flow stops at Match 1's bounds. |
| 4.3 | Both matches end | Each arena is restored independently; neither reset damages the other. |

Note: this only tests that one match cannot *alter* a neighbour. Neighbouring
slots being **visible** to each other is a separate, deferred item.

## Part 5 - Bystander hazard damage (the lava fix)

Re-verifies `5348961`, which extended bystander protection beyond explosions.

| # | Action | Expected |
|---|---|---|
| 5.1 | C stands inside the arena bounds (walked in, or spectating in survival) while A empties lava on them | C takes **no** damage. |
| 5.2 | Same with fire / burning | C takes no damage. |
| 5.3 | Same with a magma block / hot floor | C takes no damage. |
| 5.4 | A and B damage **each other** with lava and fire | Damage applies normally - combatants are not protected from each other. |
| 5.5 | C takes lava damage somewhere unrelated, outside all bounds | Normal damage. The protection is geometric, not global. |

## Known issue to expect during this run

**Bounds corners are not snapped to block coordinates.** `ArenaInstance.contains()`
compares raw doubles against `Block.getLocation()`, which returns a block's
minimum corner. A corner set from a standing position therefore excludes the
whole min-side face of the box - roughly half a block in X and Z, and a full
block in Y - while including the max side.

Symptom to watch for, and **not** a containment bug:

- Breaking or placing on the **lowest-X or lowest-Z wall**, or on the **floor**,
  may be wrongly denied with the out-of-arena message while the player is
  clearly inside the arena.
- A player standing in that same sliver may get a spurious out-of-bounds
  boundary warning.

If Part 1 or Part 2 fails only along the min-X / min-Z / floor faces, that is
this coordinate issue rather than the containment rule. `ArenaTemplateManager`
already snaps correctly via `blockCoordinate(double)`; bounds need the same
treatment. Fix separately and re-run the affected rows.

## Sign-off

Containment is verified when Parts 1, 2, 3, 4 and 5 all pass, with Part 3
weighted most heavily - a containment feature that leaks into ordinary world
behaviour is worse than the problem it solves.
