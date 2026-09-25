# Arena Containment - In-Game Test Plan

Covers three changes, in the order they should be tested:

| Commit | Change |
|---|---|
| `a901127` | Bounds defined in whole blocks; corners selectable by clicking a block; coordinate and size feedback. |
| `20466ae` | `ArenaContainmentGuard` - a duel can only affect blocks inside its own arena. |
| `5348961` | Bystanders protected from a duel's lava, fire and hot floor, not just explosions. |
| _pending_ | Particle frame corrected to wrap the block volume; `BlockBox` extracted; `ArenaBoundsValidator` shell advisory. |

The integration tests (59 passing) cover the logic. This plan covers what
MockBukkit cannot simulate - real fire spread, real liquid flow, real explosion
radii, real template capture - and whether the feedback actually feels right in
chat.

**Run the parts in document order.** Part 1 comes first deliberately: a mis-set
bounds box makes Parts 2 and 3 fail in ways that look like containment bugs.

## Setup

1. One **hand-built** arena with an enclosed floor, four walls and a roof, using
   the block-change rollback reset strategy. Not a provisioned/template arena -
   template pasting would repair anything the rollback missed and hide failures.
2. **Set the bounds to include the floor, walls and roof - not just the interior
   air.** This is the documented convention, for two reasons: the rollback only
   restores blocks inside the bounds, so a floor left outside the box stays
   damaged after a TNT fight; and a gap in the shell makes liquid appear frozen
   (see row 3.9). The plugin now advises you when it spots gaps.
3. A kit containing **TNT, flint and steel, a lava bucket, a water bucket, and
   redstone torches**. Redstone-lit TNT specifically matters: it is the case
   where `TNTPrimed.getSource()` is null, which is why containment is decided by
   geometry rather than by who lit it.
4. Three accounts: two duellists (**A**, **B**) and one **bystander** (**C**)
   who is never in a match.
5. Marker blocks (coloured wool) placed **just outside** each wall and one on
   the roof, so outward damage is visible at a glance.
6. A provisioned/dynamic arena setup available for Parts 5 and 6.

---

## Part 1 - Bounds corners and admin feedback

Edit-mode convention is unchanged - **left-click sets, right-click teleports
to** the position the held tool marks.

| # | Action | Expected |
|---|---|---|
| 1.1 | Enter edit mode, hold the bounds corner 1 tool, **left-click a block** | Corner set to *that* block. Chat reports its coordinates. |
| 1.2 | Set corner 2 by clicking the opposite block | Coordinates reported, plus a second line giving the box size in blocks. |
| 1.3 | Check the reported size against the arena you actually built | Matches, counting both corner blocks - a box from X=10 to X=12 reads as **3** wide, not 2. |
| 1.4 | **Right-click** the corner 1 tool | Teleports you standing in the **middle** of the corner block, not on its edge. |
| 1.5 | Left-click **air** with a corner tool | Falls back to your own position - the old stand-here workflow still works. |
| 1.6 | Stand on the arena floor, left-click **air** to set a corner | Selects the block you are standing *in* (the air above the floor). The floor is **not** included. |
| 1.7 | Now left-click the **floor block itself** | Floor included. Reported Y is one lower than 1.6. |
| 1.8 | Set a **spawn** with a spawn tool | Uses your exact position **and facing** - spawns deliberately ignore the clicked block. |
| 1.9 | Right-click the spawn tool to teleport back | Returns you facing the direction you set it from. |
| 1.10 | Set bounds corners via `/duels` and via the instance detail menu | Same coordinate + size feedback as the edit tool. |
| 1.11 | Try to set a bounds corner on an arena **in use by a live match** | Refused as in-use, as before. |
| 1.12 | With both corners set, look at the **aqua particle frame** | It wraps the blocks being enforced: the bottom rail sits under the lowest included block and the top rail sits **above** the highest, not on top of it. |
| 1.13 | Count the frame against the reported size | A frame around a 3x10x20 box spans 3 blocks in X, 10 in Y, 20 in Z. Previously it drew 2x9x19 and looked a block low. |
| 1.14 | Set bounds that exclude the floor (click the interior air) | Corner + size lines, then an advisory: non-solid blocks in the floor or walls. |
| 1.15 | Set bounds that include floor, walls and roof | Same corner + size lines, **no** advisory. |
| 1.16 | Set bounds on a sealed arena that has one deliberate doorway | Advisory fires, naming the gap count. The corner is still set - this is advice, not a refusal. |
| 1.17 | Set bounds on an arena with a solid floor/walls but an **open top** | **No** advisory. An open roof is a valid design and liquid cannot escape upward. |
| 1.18 | Check the **orange structure frame** in a source arena | Same correction applies - it wraps the capture volume rather than sitting a block low. |

### Part 1b - Legacy arenas (no migration expected)

The block-aligned comparison is what makes existing configs correct on load, so
this must pass without re-setting any corners.

| # | Action | Expected |
|---|---|---|
| 1.19 | Start the server with arenas configured **before** today's changes | Load normally. No errors or bounds warnings. |
| 1.20 | Run a match in one, breaking blocks on the **lowest-X wall**, **lowest-Z wall** and **floor** | All allowed, all restored at match end. This is the face the old raw-coordinate comparison wrongly excluded. |
| 1.21 | Stand hard against the lowest-X wall during a match | **No** spurious out-of-bounds warning, and building there is allowed. |

### Part 1c - Template capture regression

Structure corners are block-aligned now too. Capture already reduced them to
block coordinates internally, so output should be identical - this confirms it.

| # | Action | Expected |
|---|---|---|
| 1.22 | Set both structure corners by **clicking blocks**, then capture the template | Succeeds. Reported dimensions match what you built. |
| 1.23 | Right-click a structure corner tool | Teleports to the middle of that corner block. |
| 1.24 | Provision a dynamic arena from that template and play a match in it | Pastes correctly, plays normally, resets correctly. |

---

## Part 2 - Direct player actions (expect a message)

The denial message is throttled to one per two seconds, so spam-clicking should
produce a trickle, not a wall of text.

| # | Action | Expected |
|---|---|---|
| 2.1 | A places a block well inside the arena | Succeeds. Removed again when the match ends. |
| 2.2 | A stands inside, aims over the wall, places a block outside | Cancelled. `You can only build inside the arena bounds.` |
| 2.3 | A spam-clicks that same outside spot for ~10s | At most one message every 2 seconds. |
| 2.4 | A **breaks** one of the marker blocks outside the wall | Cancelled, same message. Marker survives. |
| 2.5 | A empties a **lava bucket** outside the bounds | Cancelled, same message. No lava appears. |
| 2.6 | A **fills** a bucket from a source outside the bounds | Cancelled, same message. Source survives. |
| 2.7 | A **flint-and-steels** a block outside the bounds | Cancelled, same message. No fire. |
| 2.8 | Repeat 2.2 as **B**, and again with B as an operator | Same result - no participant is exempt. There is no bypass permission. |
| 2.9 | A disconnects mid-match and rejoins, then retries 2.2 | Message appears immediately - no stale throttle state. |

### 2.10 - WARNING boundary mode (the two-sided check)

Set the arena's boundary mode to `WARNING`, so leaving the bounds only warns
instead of teleporting the player back.

1. A walks physically outside the bounds.
2. A tries to place a block **inside** the bounds, reaching back over the
   boundary.

Expected: **cancelled** with the message, even though the target block is
inside. This is the half of the check that tests the player's own location, and
it is the only scenario that exercises it.

---

## Part 3 - Propagation (expect silence)

Nothing here should produce a chat message. Watch chat throughout - a message in
this part is a bug, because none of these changes are attributable to a player.

| # | Action | Expected |
|---|---|---|
| 3.1 | TNT detonated in the middle of the arena | Normal crater. **No item drops.** Fully restored at match end. |
| 3.2 | TNT against the inside of a wall, lit **with a redstone torch** | Inside face destroyed; outside markers untouched; wall rebuilt at match end. No message. |
| 3.3 | TNT on the roof, lit by redstone | Roof damaged inside the bounds only; nothing above or outside changes. |
| 3.4 | Lava bucket emptied next to a wall, bounds **sealed** (setup item 2) | Flow spreads normally inside and **stops at the boundary**. Nothing outside changes. Arena restored afterwards. |
| 3.5 | Water bucket emptied on the bounds edge, bounds **sealed** | Spreads inward normally. This is the case that looked frozen before the shell convention was documented. |
| 3.6 | Flint and steel on a flammable block inside, near a wall | Fire spreads inside the bounds but **does not cross it**. Nothing outside catches or burns away. |
| 3.7 | TNT detonated right **on** the boundary line | Blocks inside destroyed and restored; blocks outside untouched. The blast is trimmed, not cancelled. |
| 3.8 | A long TNT-and-lava fight, ~1 minute | Chat stays clean of containment messages. Arena restored, allowing for the documented `arena-reset-max-tracked-block-changes` ceiling. |
| 3.9 | **Known behaviour, not a failure:** open a gap in the bounds floor or a wall, then empty lava right beside it | The lava may sit completely still, even with open space inside. Minecraft picks the spread direction before the event fires, so vetoing the escape does not redirect it. Nothing escapes. Sealing the shell resolves it. |

---

## Part 4 - Regressions (nothing unrelated may change)

**The most important part.** Containment must never become server-wide grief
protection.

| # | Action | Expected |
|---|---|---|
| 4.1 | While A and B duel, **C** builds freely right outside the arena wall | Completely unrestricted. No message. |
| 4.2 | C detonates TNT outside the arena, away from any bounds | Normal vanilla explosion, **normal item drops**. |
| 4.3 | C empties a lava bucket outside the arena and lets it flow | Flows normally. Not cancelled at any boundary. |
| 4.4 | C builds / breaks / TNTs far away with **no match running at all** | Entirely normal. |
| 4.5 | C's TNT outside the arena blasts **into** the live arena | Blocks inside the arena are still tracked and **restored** at match end (existing behaviour, unchanged). |
| 4.6 | A duel in an arena with **no bounds configured** | Nothing restricted, nothing rolled back - unchanged from before. |
| 4.7 | Ordinary world fire and lava spread somewhere with no arena nearby | Behaves exactly as vanilla. |

---

## Part 5 - Dynamic arenas (shared world)

Requires two simultaneous provisioned matches in **adjacent grid slots** of
`duels_dynamic_arenas`.

| # | Action | Expected |
|---|---|---|
| 5.1 | Match 1 detonates TNT hard against the wall facing Match 2's slot | Match 2's arena is **physically unchanged**. |
| 5.2 | Match 1 floods lava toward Match 2's slot | Flow stops at Match 1's bounds. |
| 5.3 | Both matches end | Each arena restored independently; neither reset damages the other. |

Note: this only tests that one match cannot *alter* a neighbour. Neighbouring
slots being **visible** to each other is a separate, deferred item.

---

## Part 6 - Bystander hazard damage

Re-verifies `5348961`, which extended bystander protection beyond explosions to
lava, fire and hot floor.

| # | Action | Expected |
|---|---|---|
| 6.1 | C stands inside the arena bounds while A empties **lava** on them | C takes **no** damage. |
| 6.2 | Same with **fire** / burning | C takes no damage. |
| 6.3 | Same with a **magma block** / hot floor | C takes no damage. |
| 6.4 | A and B damage **each other** with lava and fire | Damage applies **normally** - combatants are not protected from each other. |
| 6.5 | A and B damage each other with TNT | Normal damage. |
| 6.6 | C takes lava damage somewhere unrelated, **outside all bounds** | Normal damage. The protection is geometric, not global. |
| 6.7 | C spectates properly (via the spectator system) during a TNT fight | Takes no damage, is not knocked around. |

---

## Sign-off

Verified when Parts 1 through 6 all pass, with **Part 4 weighted most heavily** -
a containment feature that leaks into ordinary world behaviour is worse than the
problem it solves.

Known limitations, expected and not failures:

- TNT that detonates entirely **outside** all arena bounds is left alone. This
  is duel containment, not grief prevention.
- Liquid beside a gap in the bounds shell can appear frozen rather than flowing
  inward. Minecraft chooses a fluid's spread direction before any event fires,
  so cancelling an escaping flow does not redirect it. Containment still holds -
  nothing leaves the arena - and sealing the bounds shell removes the effect.
  Deliberately not fixed in code: redirecting the flow ourselves means
  maintaining our own copy of Minecraft's fluid physics.
- Containment applies only while a match is `IN_PROGRESS`. Blocks placed during
  the pre-game countdown are neither restricted nor rolled back - a pre-existing
  gap, not introduced by these changes.
- Falling sand/gravel settling outside the bounds, and hanging entities, are not
  covered. No current kit places them.
