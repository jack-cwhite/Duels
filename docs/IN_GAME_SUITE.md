# Duels in-game test suite

_Target: Paper 1.21.11, Java 21. Last revised 2026-09-26._

**This is the single list of everything still to run in game.** It was
consolidated from Part D of `docs/RETEST_PLAN.md` and all six parts of
`docs/CONTAINMENT_TEST_PLAN.md` so that a run does not mean flipping between
documents. Those two files keep the rationale and history behind each item; this
file is the run order.

Tick boxes as you go. Stages are ordered so that earlier ones set up what later
ones need - Stage 1 wants a fresh database, Stage 3 leaves you with a dynamic
arena that Stage 8 reuses.

**Rough budget:** Stages 1-3 about 45 minutes, plus ~10 minutes once to build the
small sealed box described below (needed only for Stage 4b). Stages 4-9 about 90
minutes. Stage 4 remains the longest single stage.

---

## What you need before starting

**Clients.** Two accounts for the main flow (**A** and **B**), a third (**C**)
for every bystander and regression check. Stage 8 needs two simultaneous
matches, so four accounts, or two plus patience.

**Build and install.** JCore installs into your local Maven repository; Duels
shades it in. Duels' own `pom.xml` copies the finished jar into the test server's
`plugins/` folder as part of `package`, so **do not copy it yourself** - a second
copy under a different filename leaves Paper loading Duels twice, two instances
fighting over the same config, database, listeners and arenas.

```powershell
cd C:\Users\jncwh\Development\JCore
mvn -o clean install -DskipTests
cd C:\Users\jncwh\Development\Duels
mvn -o clean package -DskipTests
```

**Then restart the server.** A rebuild is not live until the Paper process
restarts - the running server is still holding the old jar's classes. This is the
single easiest way to waste an hour: chasing a bug in a build the server never
loaded, or re-confirming a fix that isn't actually running. After any rebuild,
`stop`, start, and check `plugins/` contains exactly one Duels jar.


**Config.** Confirm the shipped defaults are in effect, because Stage 1 depends
on them:

- `config.yml` - `stats-storage: SQL`
- `database.yml` - `type: SQLITE`

**Delete `plugins/Duels/database.db` before Stage 1** so the migration runs from
nothing. Back it up first if you care about the existing results.

### Your existing arenas - keep them, one small addition

You already have what this suite needs. Nothing about their design is a problem:

- **Static arena, 2 instances.** An open platform with a small building on it, no
  walls or ceiling. This is fine as-is. Block containment is decided purely by the
  bounds box's coordinates, not by any physical wall standing in the way - and
  player containment is handled by the arena's **boundary mode** (warning / soft
  return / forfeit), which exists specifically to substitute for walls. Used by
  Stages 1, 2, 4a, 5, 6, 7, 9.
- **Dynamic arena, one generated copy.** Same reasoning, same open-platform
  design. Used by Stages 3, 4a, 4d, 8.
- **The temporary stone platform - remove it.** Nothing in this suite uses it;
  delete the arena or retire the instance so it stops appearing in `/duels`.
- **One new build - a small sealed box.** Needed only for Stage 4b, which checks
  the openings advisory. Your two real arenas have no walls at all, so they can
  only ever produce the "this is open" case; the box gives you the "this is
  sealed" case to compare against. Exact spec is under Stage 4b below - build it
  whenever is convenient, it doesn't block Stages 1-3.

### Setting bounds on your existing platforms (no walls - exact steps)

Do this once per instance you're testing - both static instances, and the dynamic
arena's live copy once one exists - before Stages 1, 2, 5, 6, 7, 9.

1. Enter edit mode on the instance (`/duels edit <arena>`, or the edit button on
   the instance in the admin menu) and equip the **bounds corner 1** tool.
2. Decide the footprint you want playable - typically the platform's own edge, or
   a deliberate margin inside it if the platform runs wider than you want combat
   to reach. Walk to one corner of that footprint, stand on the platform surface,
   and **left-click the ground block under your feet**. Chat reports the
   coordinates it just recorded.
3. Equip the **bounds corner 2** tool. Walk to the diagonally opposite corner of
   the footprint. This time aim for height rather than the ground: fly or jump up
   to a point a few blocks above the tallest part of the small building (so
   players can stand on its roof without leaving bounds), then **left-click while
   looking straight down at a block**, or left-click a block of the building at
   that height if one is in reach. Chat reports the finished box size.
4. **If the openings advisory prints here, ignore it.** With no walls at all,
   most or all of the perimeter genuinely is open, so the check is correctly
   reporting that. It is not a finding on this arena style - the pass/fail
   contrast for the advisory itself happens on the sealed box in Stage 4b, not on
   your real arenas.

### Marker blocks

Stage 5 checks that things just outside the bounds survive a duel untouched.
Without walls, "outside" simply means outside the box you set above. Place two or
three distinctive blocks (wool, glass) directly on the platform, a block or two
past the footprint edge you clicked in step 2 above.

---

## Stage 1 - The SQL stats path, on SQLite

**Why this is first.** `SqlStatsRepository` and its migration have never
executed against a real database. Every one of Phase 5's stats queries builds on
this code, so a defect here is a defect under all of Phase 5. The earlier
`BLOCKED` note assumed an external MySQL server was needed - it is not, SQLite
needs no server.

- [X] **1.1** Server starts clean. The console logs `Applied database
  migration 1.` during `Enabling Duels`, and `plugins/Duels/database.db`
  appears.
- [X] **1.2** Restart with the database already present. The console instead
  logs `Database schema is already up to date`. The migration is
  recognised as applied and does **not** run again. No duplicate-table or
  duplicate-index errors.
- [X] **1.3** Play one duel to a normal death. `/duel top` shows the winner with
  one win **immediately** - not after a restart. (This is the read-after-write
  ordering added in `dc67306`.)
- [X] **1.4** Play a duel ending by **disconnect**. The quitter takes the loss
  and the survivor takes the win.
- [X] **1.5** Inspect the tables directly with any SQLite browser or `sqlite3`:

  ```sql
  SELECT * FROM duels_matches;
  SELECT * FROM duels_match_participants;
  ```

  Each match has exactly **one** `duels_matches` row and exactly **two**
  `duels_match_participants` rows. `won` is true for exactly one of the pair.
  `kit_id` is populated when a kit was used and `NULL` when the duel was
  bare-fisted.
- [X] **1.6** Play a duel in a **dynamic** arena. Its `arena_id` is the
  **template's** arena id, not the per-copy instance id. Stats are per arena, not
  per generated copy, and this is the only place that distinction is visible.
- [X] **1.7** Stop the server with `stop` mid-session. Hikari shuts the pool down
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
worth investigating* - though see step 6 below before calling it a finding.
`/duels diagnostics` on its own prints the full table plus which
matches and instances are involved. Do not hand-count anything.

**Run every item in this exact order.** The rows are cheap to read but easy to
misread, and almost every confusing result in Stage 2 has come from taking a
reading at the wrong moment rather than from a real leak.

1. **Settle first.** Get both players standing still, out of any match, with no
   pending challenges. Then wait **35 seconds** before baselining. Challenges
   schedule an expiry task that lives for `duel-request-expiry-time` (default
   **30s**), so a baseline taken seconds after a `/duel` command captures a
   countdown that is still ticking away underneath you.
2. **Baseline.** `/duels diagnostics baseline`.
3. **Run the flow** for the single item you are on. One item per baseline - do
   not chain two.
4. **Settle again, the same 35 seconds**, before comparing. Everything Duels
   schedules is either immediate or bounded by a config value; the longest is the
   30s challenge expiry. Waiting past it means anything still pending is a real
   leak rather than a timer you caught mid-flight.
5. **Compare.** `/duels diagnostics compare`. "Nothing changed since your
   baseline" is a pass.
6. **If a row prints, do not report it yet.** Wait another 35 seconds and compare
   again. A row that clears on the second compare was a timer in flight and is
   not a finding - note the timing and move on. A row that persists across two
   compares 35 seconds apart **is** a finding: report it with the row name, the
   numbers, and what you did.

**Reading a "Scheduled plugin tasks" delta.** This row is a single total of every
pending Bukkit task the plugin owns, with no breakdown, so it cannot tell you
*what* leaked on its own. Narrow it before reporting:

- Did **"Live countdowns"** change too? If so it is a countdown. If countdowns
  read 0 while tasks read non-zero, a countdown's task outlived its state - a
  real bug.
- Did **"Boundary check task active"** flip to `true`? That is the boundary
  enforcer's periodic check, which should retire itself once everybody is back in
  bounds.
- Neither, and it persists past 35 seconds? Report it as unattributed, with the
  exact flow.

Take a **fresh baseline before each item** - each leaves the server in a slightly
different state.

- [X] **2.1** Baseline, then fight a duel using **bows and splash potions** so
  projectiles and effect clouds are in play, kill the loser, compare. Clean: no
  arrows stuck in blocks, no lingering clouds, no dropped items, no pending
  players.
- [X] **2.2** Repeat with a **spectator** attached who leaves via `/duel leave`.
  Clean.
- [X] **2.3** Repeat with a spectator who **disconnects instead of leaving**.
  Their saved row is kept deliberately so they are restored on next join -
  confirm they are. The live spectator-session count still returns to baseline;
  the surviving row is on disk, not a live session.
- [X] **2.4** Quit during the **kit selection** countdown, and separately during
  the **grace** countdown. Both compares clean - in particular "Live countdowns"
  and "Scheduled plugin tasks" return to baseline, which is the part that was
  previously impossible to check. The arena is released and the quitter takes the
  loss.
- [X] **2.5** Leave an **edit session** open, then disconnect. Edit sessions and
  capture drafts return to baseline, and the arena is not left locked as in-use.
- [X] **2.6** After all of the above, `/duels diagnostics` reports zero live
  matches, zero pending players and zero tracked block changes, and `/duels`
  reports every arena free.

---

## Stage 3 - Structure capture size

Fixed in code, never re-verified on the target Paper build. The defect this stage
exists to catch is a capture or paste that gets **trimmed to the gameplay bounds**
instead of covering the full selection. That is invisible from inside the arena -
everything you would walk on is present, and only the captured margin outside the
bounds is missing - so do not try to check it by eye.

**Use the command.** `/duels diagnostics template <arenaId>` reads the stored
structure file and compares every generated copy against it block for block,
spread over ticks so it does not stall the server. It reports the recorded size,
the size actually written to the file, how many blocks the file holds, every
copy's slot and origin, whether any copy overlaps another, and any block that
differs from the template. Run it instead of comparing builds manually.

One line in its output is expected and is **not** a finding: "N blocks are the
right material with different properties". Fences, walls and stairs reconnect to
their new neighbours when pasted, so their block state legitimately changes.
Only a *material* mismatch means a block was never placed.

- [X] **3.1** On the dynamic arena's source instance, set the two **structure
  capture corners deliberately wider than the gameplay bounds** - include the
  small building and a few blocks of surrounding platform on every side. Capture
  the template. Chat reports the captured size.
- [X] **3.2** The reported capture size **counts both corner blocks** - a
  selection from X=10 to X=12 reads as **3**, not 2. Check this against the
  coordinates chat echoed when you clicked each corner.
- [X] **3.3** Provision one copy, then run `/duels diagnostics template <arenaId>`.
  Clean result: "Blocks stored in file" equals the recorded volume, no "file size
  disagrees" line, and the copy "matches the template across all N blocks". This
  is the item that proves the **full captured volume** is pasted rather than a
  volume trimmed to the gameplay bounds.
- [X] **3.4** Provision several more copies, then run the command again. Every
  copy reports its own slot index and origin, the output ends with "No copy
  overlaps another, and each fits inside its own slot", and each copy matches the
  template. Then fly out and confirm visually that the copies are separated by
  the configured padding rather than touching.
- [X] **3.5** Walk the outermost copy's **captured margin** - the ground you
  included outside the gameplay bounds in 3.1. It is present. This is a sanity
  check on the command itself: if 3.3 passed but the margin is missing here, the
  command is comparing the wrong region and that is the finding to report.

Leave the copies in place. Stage 8 reuses them.

---

## Stage 4 - Bounds corners and admin feedback

Covers `a901127` (block-aligned bounds, click-to-select corners) and `8028d94`
(the corrected openings advisory). Split into four parts: 4a checks the
corner-setting mechanics on your real arenas (you already did the clicking for
this back in the setup section - these items are about confirming what you saw),
4b is the one part that needs the sealed box, 4c is optional, and 4d covers the
dynamic arena's source structure.

### Stage 4a - Corner mechanics (on your real arenas)

You already set bounds on your static and dynamic arenas in the setup section
above. Confirm the following there - no new building needed:

| # | Check | Expected | |
|---|---|---|---|
| 4.1 | Coordinates chat reported when you clicked corner 1 | Match the block you actually stood on. | [X] |
| 4.2 | Size chat reported when you clicked corner 2 | Counts both corners inclusively - a span of 5 blocks along one axis reads as **5**, not 4. | [X] |
| 4.3 | **Right-click** the corner 1 tool | Teleports you to the middle of that corner block, not its edge. | [X] |
| 4.4 | Left-click **air** with a corner tool, standing anywhere with nothing in reach | Falls back to your own current position - the old stand-here workflow still works. | [X] |
| 4.5 | Set a **spawn** with the spawn tool | Uses your exact position **and facing** - spawns deliberately ignore whatever block you clicked. | [X] |
| 4.6 | Right-click the spawn tool | Teleports back, facing the direction you originally set it from. | [X] |
| 4.7 | Try setting a bounds corner on an arena **currently in use by a live match** | Refused as in-use. | [X] |
| 4.8 | Look at the **aqua particle frame** around your platform's bounds | Wraps the tracked volume: the bottom rail sits under the lowest included block, the top rail sits **above** the highest, not sitting on top of it. | [X] |
| 4.9 | The advisory that fired when you set bounds on the open platform | Correctly reports openings - that's expected here. The no-advisory contrast case is Stage 4b, next. | [X] |

### Stage 4b - The openings advisory itself (needs the sealed box)

**Build this once,** anywhere on flat ground, out of any block you have to hand -
it never hosts a real duel, so looks don't matter:

- **Footprint:** 5x5 blocks.
- **Walls:** 1 block thick, running around all four edges, 3 blocks tall.
- **Floor:** one solid 5x5 layer under the walls.
- **Roof:** one solid 5x5 layer on top of the walls.
- **Result:** a fully sealed box with a **3x3x3 interior** - a floor, four walls,
  and a roof, with no gaps anywhere.

**Register it as an arena:** create a new static arena from it (for example
`/duels create boundstest`), with one instance. Spawns can point anywhere inside
the box; they are never used.

**Set its bounds** - this exact pair of clicks is what makes the rest of this
stage work:

1. Enter edit mode, equip the bounds corner 1 tool.
2. Stand **inside** the box, in the corner where two walls meet the floor. Look
   straight down and **left-click the floor block you're standing on**.
3. Equip the bounds corner 2 tool. Move to the **diagonally opposite interior
   corner, at the top** - stand so your head is against the roof, look straight
   up, and **left-click the roof block above you**.
4. Chat should report a **3x5x3** box - `{sizeX}x{sizeY}x{sizeZ}`. Not 3x3x3: the
   interior is 3x3x3, but you clicked the **floor block** and the **roof block**,
   so the Y span is floor + 3 interior + roof = **5**. X and Z stay at 3 because
   both clicks were on interior columns. If either horizontal figure reads 4 or 5,
   a click landed on a roof or floor block sitting over a *wall* rather than over
   the interior - right-click each corner tool to teleport to the block it
   actually recorded and confirm both are interior columns.
5. This floor-block-to-roof-block convention leaves the **four walls** one block
   outside the tracked box, which is what makes them immune later. The floor and
   roof are deliberately inside it, so blocks broken out of them during a duel
   are recorded and restored.

Now the checks:

| # | Action | Expected | |
|---|---|---|---|
| 4.10 | With the box fully sealed (just built, bounds just set per above) | **No** advisory printed - only the corner + size lines. **This is the case your two real open-platform arenas cannot produce**, which is the whole reason this box exists. | [X] |
| 4.11 | Punch a single 1-block hole through the middle of one wall, at mid-height | Nothing prints yet - the advisory is computed when a bounds corner is set, not continuously as the world changes. | [X] |
| 4.12 | Re-click the **same** corner 2 block (the roof block from step 3 above) to force a recheck | Advisory now fires, naming **1** opening. | [X] |
| 4.13 | Patch the hole with a block, re-click corner 2 again | Advisory clears - back to no advisory, matching 4.10. | [X] |
| 4.14 | Punch two separate 1-block holes (different walls this time), re-click corner 2 | Advisory names **2** openings. | [X] |
| 4.15 | Remove the entire roof, re-click corner 2 | **No** advisory. An open top is a valid design - liquid cannot escape upward, so only side openings are flagged. | [X] |

### Stage 4c - Legacy arenas *(skip if this doesn't apply to you)*

Only relevant if you have an arena whose bounds were set **before** the
block-aligned bounds work (`a901127` onward) and have never been re-clicked
since. If every arena you're using was configured recently, there's nothing
legacy to check - skip straight to 4d.

| # | Action | Expected | |
|---|---|---|---|
| 4.16 | Start the server with that arena's old bounds left untouched | Loads normally - no errors, no bounds warnings. | [-] |
| 4.17 | Run a match in it, breaking blocks on its lowest-X face, lowest-Z face and floor | All allowed, all restored - this is the face the old raw-coordinate comparison used to wrongly exclude. | [-] |

### Stage 4d - Template capture regression (dynamic arena)

Stage 3 already validated that the captured template pastes completely. This
re-validates the capture *tooling* itself - the corner clicks and the frame.

| # | Action | Expected | |
|---|---|---|---|
| 4.18 | On the dynamic arena's source, set both structure corners by **clicking blocks**, then recapture the template | Succeeds. Reported dimensions match what's actually built. | [X] |
| 4.19 | Right-click a structure corner tool | Teleports to the middle of that corner block. | [X] |
| 4.20 | Look at the **orange structure frame** on the source | Wraps the capture volume with the same correction as the aqua bounds frame - not sitting a block low. | [X] |
| 4.21 | Provision a fresh copy from the recaptured template and play a match in it | Pastes, plays, and resets normally. | [X] |

---

## Stage 5 - Direct player actions (expect a message)

A and B are duelling. Every action here should be **cancelled with a message**.

| # | Action | Expected |     |
|---|---|---|-----|
| 5.1 | A places a block well inside the arena | Succeeds. Removed again when the match ends. | [X] |
| 5.2 | A stands inside, aims past the bounds edge, places a block outside | Cancelled. `You can only build inside the arena bounds.` | [X] |
| 5.3 | A spam-clicks that same outside spot for ~10s | At most one message every 2 seconds. | [X] |
| 5.4 | A **breaks** one of the marker blocks outside the bounds edge | Cancelled, same message. Marker survives. | [X] |
| 5.5 | A empties a **lava bucket** outside the bounds | Cancelled, same message. No lava appears. | [X] |
| 5.6 | A **fills** a bucket from a source outside the bounds | Cancelled, same message. Source survives. | [X] |
| 5.7 | A **flint-and-steels** a block outside the bounds | Cancelled, same message. No fire. | [X] |
| 5.8 | Repeat 5.2 as **B**, and again with B as an operator | Same result - no participant is exempt. There is deliberately no bypass permission. | [X] |
| 5.9 | A disconnects mid-match and rejoins, then retries 5.2 | Message appears immediately - no stale throttle state. | [X] |
| 5.10 | **During kit selection**, before either player has picked a kit, A tries 5.2 | Cancelled, same message. Both duellists are teleported into the arena the moment the match is created, so containment starts there rather than when combat does. | [X] |
| 5.11 | **During kit selection**, A empties a lava bucket *inside* the arena, then the match runs and ends | Allowed at the time and **rolled back** at match end, like any other in-bounds change. Previously this was neither prevented nor restored. | [X] |
| 5.12 | Repeat 5.11 during the **grace countdown** | Same result. | [X] |

### Stage 5b - WARNING boundary mode (the two-sided check)

Set the arena's boundary mode to `WARNING` so a duellist can physically leave the
bounds and stay outside.

| # | Action | Expected |     |
|---|---|---|-----|
| 5.13 | A walks outside the bounds, then tries to build back **into** the arena | Cancelled. Containment is two-sided: it is about the blocks, not about where the player is standing. | [X] |

---

## Stage 6 - Propagation (expect silence)

Physics, not player clicks. **No containment messages should appear at all** -
these paths cancel events that no player directly triggered, so messaging them
would spam.

**On an open platform, expect item 6.9 below to happen essentially every time**
you point lava or water toward the bounds edge, not just as an edge case - since
there's no wall to visibly stop it against, a cancelled flow can look like it
simply freezes in mid-air. That's the correct, documented behaviour; don't
mistake it for a bug.

| # | Action | Expected |     |
|---|---|---|-----|
| 6.1 | TNT detonated in the middle of the arena | Normal crater. **No item drops.** Fully restored at match end. | [X] |
| 6.2 | TNT at ground level near the bounds edge, lit **with a redstone torch** | Ground destroyed up to the boundary line; markers just outside untouched; the crater is restored at match end. No message. | [X] |
| 6.3 | TNT placed against the small building, within the bounds height you set | Damage confined within the bounds box; nothing above the height you set for corner 2 changes. | [X] |
| 6.4 | Lava bucket emptied near the bounds edge | Flow spreads normally inside and **stops at the boundary**, even with no wall there to stop it visibly. Nothing outside changes. Arena restored afterwards. | [X] |
| 6.5 | Water bucket emptied near the bounds edge | Spreads inward normally and stops at the same line. | [X] |
| 6.6 | Flint and steel on a flammable block inside, near the bounds edge | Fire spreads inside the bounds but **does not cross it**. Nothing outside catches or burns away. | [X] |
| 6.7 | TNT detonated right **on** the boundary line | Blocks inside destroyed and restored; blocks outside untouched. The blast is trimmed, not cancelled. | [X] |
| 6.8 | A long TNT-and-lava fight, ~1 minute | Chat stays clean of containment messages. Arena restored, allowing for the documented `arena-reset-max-tracked-block-changes` ceiling. | [X] |
| 6.9 | **Known behaviour, not a failure, and likely by default on an open platform:** empty lava right beside the bounds edge | The lava may sit completely still even though there's open space just past it. Minecraft picks a fluid's spread direction before the event fires, so vetoing the escape does not redirect it. Nothing escapes regardless - if this looks wrong, it isn't. | [X] |
| 6.10 | **Sand or gravel floor:** blow a crater under it with TNT inside the bounds, let it settle, then end the match | The rearranged floor is restored to its original arrangement. A falling block is an entity in flight, so neither a break nor a place event fires - this was unrestored before `EntityChangeBlockEvent` was tracked. | [X] |
| 6.11 | Hang **item frames with items in them**, a painting and an armour stand somewhere inside the bounds - the small building's walls work well - then fight a TNT-and-lava match around them | All survive untouched. No frame breaks, no item pops out, nothing drops, and the armour stand is back where you put it once the arena resets. Decoration is entities, so the rollback cannot replay it the way it replays blocks - it is protected from damage instead, exactly as walls are, and its position is recorded and reapplied for the one thing protection cannot prevent: gravity. | [X] |
| 6.12 | Punch an item frame inside a live arena as a duellist | Nothing happens - the item stays in the frame. | [X] |
| 6.13 | Punch the same item frame with **no match running** | Normal vanilla behaviour: the item pops out. Protection is scoped to a live arena, not permanent. | [X] |
| 6.14 | Stand an armour stand on a block you can blow up, spill **lava** onto it, then detonate TNT under it | The stand never catches fire, and after the reset it is standing exactly where you left it rather than sunk into the restored floor. Combustion is cancelled alongside damage, and the stand's position is recorded when the match first changes a block and reapplied *after* the block replay - putting it back before the crater is filled would only drop it again. | [X] |

---

## Stage 7 - Regressions (nothing unrelated may change)

**Weight this stage most heavily.** Containment that leaks into ordinary world
behaviour is worse than the problem it solves. If any item here fails, stop and
report it rather than continuing.

| # | Action | Expected | |
|---|---|---|---|
| 7.1 | While A and B duel, **C** builds freely right outside the bounds edge | Completely unrestricted. No message. | [X] |
| 7.2 | C detonates TNT outside the arena, away from any bounds | Normal vanilla explosion, **normal item drops**. | [X] |
| 7.3 | C empties a lava bucket outside the arena and lets it flow | Flows normally. Not cancelled at any boundary. | [X] |
| 7.4 | C builds / breaks / TNTs far away with **no match running at all** | Entirely normal. | [X] |
| 7.5 | C's TNT outside the arena blasts **into** the live arena | Blocks inside the arena are still tracked and **restored** at match end. | [X] |
| 7.6 | An arena copy with **no bounds configured** | **Not applicable - the state no longer exists.** Bounds are mandatory: `isReady()` requires `hasBounds()`, there is no way to clear bounds once set, and `setBounds` refuses while a copy is in use. A copy without bounds is not ready, is never offered to a match, and is named in a startup warning telling you to set them. The optional-bounds escape hatch that used to make this testable has been removed. | [-] |
| 7.7 | Ordinary world fire and lava spread somewhere with no arena nearby | Behaves exactly as vanilla. | [X] |

---

## Stage 8 - Dynamic arenas sharing one world

8.1 and 8.2 are **unreachable at the shipped geometry, not passing.** A slot is
`slot-width: 256` wide with `slot-padding: 16` either side, so neighbouring
slots' origins sit 288 blocks apart, and a real arena occupies far less than its
slot - leaving a couple of hundred blocks of air between one arena's wall and the
next one's. TNT reaches about 8 blocks and lava flows 7, so nothing a match can
do gets anywhere near its neighbour. `ArenaContainmentGuard` is still the thing
that would stop it; these items only become testable if an admin narrows
`slot-padding` or captures a template that nearly fills its slot, which is worth
remembering before treating the defaults as the only configuration.

8.3 is testable today and passes: two concurrent resets do not interfere, which
matters because rollback keys its tracked blocks *and* its decoration snapshot by
instance id.

| # | Action | Expected | |
|---|---|---|---|
| 8.1 | Match 1 detonates TNT hard against the wall facing Match 2's slot | Match 2's arena is **physically unchanged**. | [-] |
| 8.2 | Match 1 floods lava toward Match 2's slot | Flow stops at Match 1's bounds. | [-] |
| 8.3 | Two provisioned matches run at once and both end | Each arena restored independently; neither reset damages the other, and decoration in each returns to its own arena's recorded positions. | [X] |

Neighbouring slots being **visible** to each other is a separate, deferred item.
Padding is currently a fixed block count, while whether a neighbour is visible
depends on the server's `view-distance` - so the two are not related by anything
except the defaults happening to be generous. See the note in `docs/ROADMAP.md`.

---

## Stage 9 - Bystander hazard damage

Re-verifies `5348961`, which extended bystander protection beyond explosions to
lava, fire and hot floor.

| # | Action | Expected | |
|---|---|---|---|
| 9.1 | C stands inside the arena bounds while A empties **lava** on them | C takes **no** damage. | [X] |
| 9.2 | Same with **fire** / burning | C takes no damage. | [X] |
| 9.3 | Same with a **magma block** / hot floor | C takes no damage. | [X] |
| 9.4 | A and B damage **each other** with lava and fire | Damage applies **normally** - combatants are not protected from each other. | [X] |
| 9.5 | A and B damage each other with TNT | Normal damage. | [X] |
| 9.6 | C takes lava damage somewhere unrelated, **outside all bounds** | Normal damage. The protection is geometric, not global. | [X] |
| 9.7 | C spectates properly (via the spectator system) during a TNT fight | Takes no damage, is not knocked around. | [X] |
| 9.8 | C stands in duel-placed lava or fire when the duel ends | C remains protected until the multi-tick arena reset has removed the hazard, and is not left burning afterwards. Fixed in code, covered by automated reset-window and combustion tests, and verified on the live server. | [X] |

---

## Sign-off

- [X] Stages 1-3 pass. Phase 4B's three verification residuals are closed.
- [X] Stages 4-9 pass, with Stage 7 weighted most heavily.
- [ ] `docs/ROADMAP.md` Phase 4B flipped from `[~]` to `[x]`.
- [X] Any new issues found are recorded here or raised, not left in chat history.
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
