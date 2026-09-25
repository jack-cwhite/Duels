# Duels in-game test suite

_Target: Paper 1.21.11, Java 21. Last revised 2026-09-25._

**This is the single list of everything still to run in game.** It was
consolidated from Part D of `docs/RETEST_PLAN.md` and all six parts of
`docs/CONTAINMENT_TEST_PLAN.md` so that a run does not mean flipping between
documents. Those two files keep the rationale and history behind each item; this
file is the run order.

Tick boxes as you go. Stages are ordered so that earlier ones set up what later
ones need - Stage 1 wants a fresh database, Stage 3 leaves you with a dynamic
arena that Stage 8 reuses.

**Rough budget:** Stages 1-3 about 45 minutes. Stages 4-9 about 90 minutes.
Stage 4 is the longest single stage because it is 24 separate admin actions.

---

## What you need before starting

**Clients.** Two accounts for the main flow (**A** and **B**), a third (**C**)
for every bystander and regression check. Stage 8 needs two simultaneous
matches, so four accounts, or two plus patience.

**Build and install.**

```powershell
cd C:\Users\jncwh\Development\JCore
mvn clean install
cd C:\Users\jncwh\Development\Duels
mvn clean package
Copy-Item .\target\Duels-1.0-SNAPSHOT.jar 'C:\path\to\paper\plugins\Duels.jar' -Force
```

**Config.** Confirm the shipped defaults are in effect, because Stage 1 depends
on them:

- `config.yml` - `stats-storage: SQL`
- `database.yml` - `type: SQLITE`

**Delete `plugins/Duels/duels.db` before Stage 1** so the migration runs from
nothing. Back it up first if you care about the existing results.

**Arena setup.** You need three arenas by the end. Build them now or as each
stage calls for them:

| Arena | Type | Used by |
|---|---|---|
| A sealed room - floor, four walls, roof | STATIC, 2 instances | Stages 1-2, 4-7, 9 |
| A second sealed room with a **deliberate doorway** | STATIC, 1 instance | Stage 4 (openings advisory) |
| A dynamic source build | DYNAMIC | Stages 3, 8 |

**Bounds convention.** Set bounds around the arena's **interior**: click the
**floor block** in one corner and the **ceiling block** in the diagonally
opposite corner. Floor and ceiling are then tracked and repaired, and the four
walls sit one block outside the box and are immune to everything a duel does.
Being inside the bounds makes a block *tracked*, not indestructible.

**Marker blocks.** Before Stage 5, place a few distinctive blocks (wool, glass)
just **outside** each wall. Several checks are "the marker survived".

---

## Stage 1 - The SQL stats path, on SQLite

**Why this is first.** `SqlStatsRepository` and its migration have never
executed against a real database. Every one of Phase 5's stats queries builds on
this code, so a defect here is a defect under all of Phase 5. The earlier
`BLOCKED` note assumed an external MySQL server was needed - it is not, SQLite
needs no server.

- [ ] **1.1** Server starts clean. The console shows the migration applying, and
  `plugins/Duels/duels.db` appears.
- [ ] **1.2** Restart with the database already present. The migration is
  recognised as applied and does **not** run again. No duplicate-table or
  duplicate-index errors.
- [ ] **1.3** Play one duel to a normal death. `/duel top` shows the winner with
  one win **immediately** - not after a restart. (This is the read-after-write
  ordering added in `dc67306`.)
- [ ] **1.4** Play a duel ending by **disconnect**. The quitter takes the loss
  and the survivor takes the win.
- [ ] **1.5** Inspect the tables directly with any SQLite browser or `sqlite3`:

  ```sql
  SELECT * FROM duels_matches;
  SELECT * FROM duels_match_participants;
  ```

  Each match has exactly **one** `duels_matches` row and exactly **two**
  `duels_match_participants` rows. `won` is true for exactly one of the pair.
  `kit_id` is populated when a kit was used and `NULL` when the duel was
  bare-fisted.
- [ ] **1.6** Play a duel in a **dynamic** arena. Its `arena_id` is the
  **template's** arena id, not the per-copy instance id. Stats are per arena, not
  per generated copy, and this is the only place that distinction is visible.
- [ ] **1.7** Stop the server with `stop` mid-session. Hikari shuts the pool down
  cleanly - no "pool suspended" warning, no scheduler warnings, no exception
  during disable.

---

## Stage 2 - Post-flow cleanup, measured rather than assumed

**Why this matters.** This was previously signed off by inference: nothing
visibly went wrong, so nothing was assumed to have leaked. `/duels diagnostics`
now measures it.

**How to use the command.** `/duels diagnostics baseline` before the flow,
`/duels diagnostics compare` after it. **Unchanged rows are omitted**, so a clean
flow prints "Nothing changed since your baseline" and *anything printed at all is
a finding*. `/duels diagnostics` on its own prints the full table plus which
matches and instances are involved. Do not hand-count anything.

Take a **fresh baseline before each item** - each leaves the server in a slightly
different state.

- [ ] **2.1** Baseline, then fight a duel using **bows and splash potions** so
  projectiles and effect clouds are in play, kill the loser, compare. Clean: no
  arrows stuck in blocks, no lingering clouds, no dropped items, no pending
  players.
- [ ] **2.2** Repeat with a **spectator** attached who leaves via `/duel leave`.
  Clean.
- [ ] **2.3** Repeat with a spectator who **disconnects instead of leaving**.
  Their saved row is kept deliberately so they are restored on next join -
  confirm they are. The live spectator-session count still returns to baseline;
  the surviving row is on disk, not a live session.
- [ ] **2.4** Quit during the **kit selection** countdown, and separately during
  the **grace** countdown. Both compares clean - in particular "Live countdowns"
  and "Scheduled plugin tasks" return to baseline, which is the part that was
  previously impossible to check. The arena is released and the quitter takes the
  loss.
- [ ] **2.5** Leave an **edit session** open, then disconnect. Edit sessions and
  capture drafts return to baseline, and the arena is not left locked as in-use.
- [ ] **2.6** After all of the above, `/duels diagnostics` reports zero live
  matches, zero pending players and zero tracked block changes, and `/duels`
  reports every arena free.

---

## Stage 3 - Structure capture size

Fixed in code, never re-verified on the target Paper build.

- [ ] **3.1** Capture a template whose structure corners are deliberately
  **larger than the gameplay bounds** - include the walls and some surrounding
  ground.
- [ ] **3.2** Provision a copy and compare it against the source build **block
  for block**. The full captured volume is pasted, not a volume trimmed to the
  gameplay bounds.
- [ ] **3.3** The reported capture size matches the corners you clicked, counting
  both corner blocks - a selection from X=10 to X=12 reads as **3**, not 2.
- [ ] **3.4** Provision several copies. No two overlap, and none sits partly
  inside another's slot.

---

## Stage 4 - Bounds corners and admin feedback

Covers `a901127` (block-aligned bounds, click-to-select corners) and `8028d94`
(the corrected openings advisory).

| # | Action | Expected | |
|---|---|---|---|
| 4.1 | Enter edit mode, hold the bounds corner 1 tool, **left-click a block** | Corner set to *that* block. Chat reports its coordinates. | [ ] |
| 4.2 | Set corner 2 by clicking the opposite block | Coordinates reported, plus a second line giving the box size in blocks. | [ ] |
| 4.3 | Check the reported size against the arena you actually built | Matches, counting both corner blocks - a box from X=10 to X=12 reads as **3** wide. | [ ] |
| 4.4 | **Right-click** the corner 1 tool | Teleports you standing in the **middle** of the corner block, not on its edge. | [ ] |
| 4.5 | Left-click **air** with a corner tool | Falls back to your own position - the old stand-here workflow still works. | [ ] |
| 4.6 | Stand on the arena floor, left-click **air** to set a corner | Selects the block you are standing *in* (the air above the floor). The floor is **not** included. | [ ] |
| 4.7 | Now left-click the **floor block itself** | Floor included. Reported Y is one lower than 4.6. | [ ] |
| 4.8 | Set a **spawn** with a spawn tool | Uses your exact position **and facing** - spawns deliberately ignore the clicked block. | [ ] |
| 4.9 | Right-click the spawn tool to teleport back | Returns you facing the direction you set it from. | [ ] |
| 4.10 | Set bounds corners via `/duels` and via the instance detail menu | Same coordinate + size feedback as the edit tool. | [ ] |
| 4.11 | Try to set a bounds corner on an arena **in use by a live match** | Refused as in-use. | [ ] |
| 4.12 | With both corners set, look at the **aqua particle frame** | It wraps the blocks being enforced: the bottom rail sits under the lowest included block and the top rail sits **above** the highest, not on top of it. | [ ] |
| 4.13 | Count the frame against the reported size | A frame around a 3x10x20 box spans 3 blocks in X, 10 in Y, 20 in Z. It previously drew 2x9x19 and looked a block low. | [ ] |
| 4.14 | Set bounds around the arena's **interior** (the recommended convention) | Corner + size lines, and **no** advisory. The boundary blocks are all air, but the real walls sit just outside them, so nothing can escape. **This is the case the first version of the check got wrong** - it reported a warning per boundary block, about 500 of them. | [ ] |
| 4.15 | Set bounds that swallow the floor, walls and roof themselves | Same corner + size lines, **no** advisory either. Both conventions are acceptable; interior is recommended because it leaves the walls immune. | [ ] |
| 4.16 | Set bounds on the arena with the deliberate **doorway** | Advisory fires, naming the number of openings. The corner is still set - this is advice, not a refusal. | [ ] |
| 4.17 | Set bounds on an arena with a solid floor and walls but an **open top** | **No** advisory. An open roof is a valid design and liquid cannot escape upward. | [ ] |
| 4.18 | Check the **orange structure frame** in a source arena | Same correction applies - it wraps the capture volume rather than sitting a block low. | [ ] |

### Stage 4b - Legacy arenas (no migration expected)

Bounds corners are now stored block-aligned and compared inclusively. Arenas
saved before that change must keep working untouched.

| # | Action | Expected | |
|---|---|---|---|
| 4.19 | Start the server with arenas configured **before** these changes | Load normally. No errors, no bounds warnings. | [ ] |
| 4.20 | Run a match in one, breaking blocks on the **lowest-X wall**, **lowest-Z wall** and **floor** | All allowed, all restored at match end. This is the face the old raw-coordinate comparison wrongly excluded. | [ ] |
| 4.21 | Stand hard against the lowest-X wall during a match | **No** spurious out-of-bounds warning, and building there is allowed. | [ ] |

### Stage 4c - Template capture regression

| # | Action | Expected | |
|---|---|---|---|
| 4.22 | Set both structure corners by **clicking blocks**, then capture the template | Succeeds. Reported dimensions match what you built. | [ ] |
| 4.23 | Right-click a structure corner tool | Teleports to the middle of that corner block. | [ ] |
| 4.24 | Provision a dynamic arena from that template and play a match in it | Pastes correctly, plays normally, resets correctly. | [ ] |

---

## Stage 5 - Direct player actions (expect a message)

A and B are duelling. Every action here should be **cancelled with a message**.

| # | Action | Expected | |
|---|---|---|---|
| 5.1 | A places a block well inside the arena | Succeeds. Removed again when the match ends. | [ ] |
| 5.2 | A stands inside, aims over the wall, places a block outside | Cancelled. `You can only build inside the arena bounds.` | [ ] |
| 5.3 | A spam-clicks that same outside spot for ~10s | At most one message every 2 seconds. | [ ] |
| 5.4 | A **breaks** one of the marker blocks outside the wall | Cancelled, same message. Marker survives. | [ ] |
| 5.5 | A empties a **lava bucket** outside the bounds | Cancelled, same message. No lava appears. | [ ] |
| 5.6 | A **fills** a bucket from a source outside the bounds | Cancelled, same message. Source survives. | [ ] |
| 5.7 | A **flint-and-steels** a block outside the bounds | Cancelled, same message. No fire. | [ ] |
| 5.8 | Repeat 5.2 as **B**, and again with B as an operator | Same result - no participant is exempt. There is deliberately no bypass permission. | [ ] |
| 5.9 | A disconnects mid-match and rejoins, then retries 5.2 | Message appears immediately - no stale throttle state. | [ ] |
| 5.10 | **During kit selection**, before either player has picked a kit, A tries 5.2 | Cancelled, same message. Both duellists are teleported into the arena the moment the match is created, so containment starts there rather than when combat does. | [ ] |
| 5.11 | **During kit selection**, A empties a lava bucket *inside* the arena, then the match runs and ends | Allowed at the time and **rolled back** at match end, like any other in-bounds change. Previously this was neither prevented nor restored. | [ ] |
| 5.12 | Repeat 5.11 during the **grace countdown** | Same result. | [ ] |

### Stage 5b - WARNING boundary mode (the two-sided check)

Set the arena's boundary mode to `WARNING` so a duellist can physically leave the
bounds and stay outside.

| # | Action | Expected | |
|---|---|---|---|
| 5.13 | A walks outside the bounds, then tries to build back **into** the arena | Cancelled. Containment is two-sided: it is about the blocks, not about where the player is standing. | [ ] |

---

## Stage 6 - Propagation (expect silence)

Physics, not player clicks. **No containment messages should appear at all** -
these paths cancel events that no player directly triggered, so messaging them
would spam.

| # | Action | Expected | |
|---|---|---|---|
| 6.1 | TNT detonated in the middle of the arena | Normal crater. **No item drops.** Fully restored at match end. | [ ] |
| 6.2 | TNT against the inside of a wall, lit **with a redstone torch** | Inside face destroyed; outside markers untouched; wall rebuilt at match end. No message. | [ ] |
| 6.3 | TNT on the roof, lit by redstone | Roof damaged inside the bounds only; nothing above or outside changes. | [ ] |
| 6.4 | Lava bucket emptied next to a wall, interior bounds | Flow spreads normally inside and **stops at the boundary**. Nothing outside changes. Arena restored afterwards. | [ ] |
| 6.5 | Water bucket emptied on the bounds edge | Spreads inward normally. Interior bounds inside a sealed room have no openings, so there is nothing for the guard to veto and the flow behaves as a player expects. | [ ] |
| 6.6 | Flint and steel on a flammable block inside, near a wall | Fire spreads inside the bounds but **does not cross it**. Nothing outside catches or burns away. | [ ] |
| 6.7 | TNT detonated right **on** the boundary line | Blocks inside destroyed and restored; blocks outside untouched. The blast is trimmed, not cancelled. | [ ] |
| 6.8 | A long TNT-and-lava fight, ~1 minute | Chat stays clean of containment messages. Arena restored, allowing for the documented `arena-reset-max-tracked-block-changes` ceiling. | [ ] |
| 6.9 | **Known behaviour, not a failure:** knock a hole through a wall so the bounds have a real opening, then empty lava right beside it | The lava may sit completely still, even with open space inside. Minecraft picks the spread direction before the event fires, so vetoing the escape does not redirect it. Nothing escapes. Closing the opening - or moving the bounds so it falls outside them - resolves it, which is what the Stage 4 advisory tells the admin to do. | [ ] |
| 6.10 | **Sand or gravel floor:** blow a crater under it with TNT inside the bounds, let it settle, then end the match | The rearranged floor is restored to its original arrangement. A falling block is an entity in flight, so neither a break nor a place event fires - this was unrestored before `EntityChangeBlockEvent` was tracked. | [ ] |
| 6.11 | Hang **item frames with items in them**, a painting and an armour stand on the arena walls, then fight a TNT-and-lava match around them | All survive untouched. No frame breaks, no item pops out, the armour stand is not knocked over, nothing drops. Decoration is entities, so the rollback cannot record it - it is protected instead, exactly as the walls are. | [ ] |
| 6.12 | Punch an item frame inside a live arena as a duellist | Nothing happens - the item stays in the frame. | [ ] |
| 6.13 | Punch the same item frame with **no match running** | Normal vanilla behaviour: the item pops out. Protection is scoped to a live arena, not permanent. | [ ] |

---

## Stage 7 - Regressions (nothing unrelated may change)

**Weight this stage most heavily.** Containment that leaks into ordinary world
behaviour is worse than the problem it solves. If any item here fails, stop and
report it rather than continuing.

| # | Action | Expected | |
|---|---|---|---|
| 7.1 | While A and B duel, **C** builds freely right outside the arena wall | Completely unrestricted. No message. | [ ] |
| 7.2 | C detonates TNT outside the arena, away from any bounds | Normal vanilla explosion, **normal item drops**. | [ ] |
| 7.3 | C empties a lava bucket outside the arena and lets it flow | Flows normally. Not cancelled at any boundary. | [ ] |
| 7.4 | C builds / breaks / TNTs far away with **no match running at all** | Entirely normal. | [ ] |
| 7.5 | C's TNT outside the arena blasts **into** the live arena | Blocks inside the arena are still tracked and **restored** at match end. | [ ] |
| 7.6 | A duel in an arena with **no bounds configured** | Nothing restricted, nothing rolled back - unchanged from before bounds existed. | [ ] |
| 7.7 | Ordinary world fire and lava spread somewhere with no arena nearby | Behaves exactly as vanilla. | [ ] |

---

## Stage 8 - Dynamic arenas sharing one world

Needs two simultaneous provisioned matches in **adjacent grid slots** of
`duels_dynamic_arenas`.

| # | Action | Expected | |
|---|---|---|---|
| 8.1 | Match 1 detonates TNT hard against the wall facing Match 2's slot | Match 2's arena is **physically unchanged**. | [ ] |
| 8.2 | Match 1 floods lava toward Match 2's slot | Flow stops at Match 1's bounds. | [ ] |
| 8.3 | Both matches end | Each arena restored independently; neither reset damages the other. | [ ] |

This only tests that one match cannot *alter* a neighbour. Neighbouring slots
being **visible** to each other is a separate, deferred item.

---

## Stage 9 - Bystander hazard damage

Re-verifies `5348961`, which extended bystander protection beyond explosions to
lava, fire and hot floor.

| # | Action | Expected | |
|---|---|---|---|
| 9.1 | C stands inside the arena bounds while A empties **lava** on them | C takes **no** damage. | [ ] |
| 9.2 | Same with **fire** / burning | C takes no damage. | [ ] |
| 9.3 | Same with a **magma block** / hot floor | C takes no damage. | [ ] |
| 9.4 | A and B damage **each other** with lava and fire | Damage applies **normally** - combatants are not protected from each other. | [ ] |
| 9.5 | A and B damage each other with TNT | Normal damage. | [ ] |
| 9.6 | C takes lava damage somewhere unrelated, **outside all bounds** | Normal damage. The protection is geometric, not global. | [ ] |
| 9.7 | C spectates properly (via the spectator system) during a TNT fight | Takes no damage, is not knocked around. | [ ] |

---

## Sign-off

- [ ] Stages 1-3 pass. Phase 4B's three verification residuals are closed.
- [ ] Stages 4-9 pass, with Stage 7 weighted most heavily.
- [ ] `docs/ROADMAP.md` Phase 4B flipped from `[~]` to `[x]`.
- [ ] Any new issues found are recorded here or raised, not left in chat history.
- [ ] `docs/SESSION_CONTEXT.md` updated with the pass date.

---

## Known limitations - expected, not failures

Do not report these as bugs.

- **TNT that detonates entirely outside all arena bounds is left alone.** This is
  duel containment, not grief prevention.
- **Liquid beside a genuine opening in the bounds can appear frozen** rather than
  flowing inward. Minecraft chooses a fluid's spread direction before any event
  fires, so cancelling an escaping flow does not redirect it. Containment still
  holds - nothing leaves the arena - and closing the opening, or moving the bounds
  so it falls outside them, removes the effect. Deliberately not fixed in code:
  redirecting the flow ourselves means maintaining our own copy of Minecraft's
  fluid physics.
- **A falling sand or gravel block that leaves the bounds downward**, through a
  hole in an in-bounds floor, lands out of bounds and is not restored. It needs a
  destructible floor with open space beneath it, which a sensible arena does not
  have.
- **The `arena-reset-max-tracked-block-changes` ceiling still applies.** A long
  enough demolition match can exhaust it, and blocks past the ceiling are not
  restored.
- **Outsiders can still walk or teleport into an arena.** Containment governs what
  a duel does to the world, not who may stand in it. A protection or
  world-management plugin is still wanted for that.
- **TNT lit by a redstone torch resolves to no attacker**, so bystander isolation
  cannot act on it. Arena bounds already keep bystanders out of blast range, so
  this is recorded as a decision to revisit rather than a bug.

---

## Not in this suite - release gates

These are real requirements before a public release, but they are not part of
closing the current phase and are not run here.

- **MySQL, MariaDB and PostgreSQL passes.** The dialect-specific SQL is where the
  backends differ, and SQLite cannot substitute. Procedure is in
  `docs/TESTING.md` section 10. Tracked in `docs/RELEASE_REVIEW.md`.
- **The full clean-install release plan**, `docs/TESTING.md`, end to end on a
  fresh server.
- **The historical V1 suite**, `docs/V1_TEST_PLAN.md`, kept as a reference for
  behaviour that predates V2 rather than as a list to re-run.
