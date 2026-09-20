# Duels / JCore V2 Roadmap

This roadmap is dependency-ordered, not feature-list-ordered. Each phase exists because
something in an earlier phase makes it structurally cheaper or structurally necessary.
V1 is a working, previously-reviewed release candidate (see `RELEASE_REVIEW.md`) - this
roadmap is about evolving it, not replacing it.

Status legend: `[ ]` not started, `[~]` in progress, `[x]` done.

---

## Phase 0 - Foundation Hardening

Small, low-risk fixes surfaced by the V2 audit. None of these block anything by
themselves, but several become more important once later phases build on top of the
systems they touch, so they're cheapest to fix now while the blast radius is small.

- [ ] Make the Hikari pool size configurable per `database.yml` instead of the hardcoded
      default in `AbstractDatabase`. Not a problem at V1's scale, but Phase 6 (rewards)
      and Phase 7 (matchmaking) both increase concurrent DB traffic around match-end.
- [ ] Add a durable "pending outcome" record (or at minimum a structured log) for the
      moment a match ends, before stats/rewards are written - see Phase 6's failure
      handling section for why this matters more once Vault is involved.
- [x] `DuelCommand` challenge-acceptance ordering. The *messaging* half of this was
      already correct - "accepted" was only sent after a non-null `startMatch`. The real
      remaining defect was that `ChallengeManager.acceptChallenge` **consumed** the
      challenge before the match was confirmed, so a failed arena allocation destroyed the
      challenge too: both players got "no arena available" and had to start over for
      something that was nobody's fault. Resolution and consumption are now separate -
      `findIncoming` resolves without removing, and `DuelCommand.finishAccept` calls
      `remove` only once a match genuinely exists. The two `acceptChallenge` overloads
      were deleted rather than left as dead API.
- [x] `PlayerStateManager` restoration hardening. The missing-world check was already
      present; what remained was that `PlayerState.apply` itself could throw for any other
      reason (a malformed `ItemStack`, a bad attribute) and leak out of a join event. It is
      now wrapped, logged, and fails forward - the snapshot is deliberately *kept* on
      failure so a later attempt can retry rather than silently discarding a player's
      inventory. Also guarded a null saved location, which would previously have NPE'd
      inside the world check.
- [x] `MatchManager.endMatch` never called `setState(MatchState.ENDED)` - only
      `abortMatch` did. The `getState() == ENDED` guard at the top of `endMatch` was
      therefore dead on its own path, which relied entirely on `forgetParticipant`
      removing the map entry, and `Match.getState()` was unreliable for a match that had
      already finished. `endMatch` now transitions before any restoration work, so
      anything observing a match while it unwinds sees `ENDED`. Both call sites guard
      against a repeat transition, which `StateMachine` would otherwise reject since
      `ENDED -> ENDED` is not an allowed edge. Fixed ahead of Phase 4 because spectator
      cleanup is exactly the kind of code that would reasonably trust `getState()`.
- [x] Fixed a death-screen glitch: `MatchManager.restoreParticipant` was teleporting and
      restoring the player who just died synchronously inside `PlayerDeathEvent`, before
      the client had actually respawned - moving/mutating a dead-but-not-yet-respawned
      player produces exactly the "Respawn button just closes the screen" bug found during
      manual testing. Restoration for the dying player is now deferred: `MatchListener`
      forwards `PlayerRespawnEvent` to `MatchManager.handleRespawn`, which sets the landing
      spot via `event.setRespawnLocation(...)` and applies the rest of the saved state one
      tick later once the player is actually alive again.
- [x] Suppressed vanilla advancement toasts/chat broadcasts triggered by kit items being
      placed directly into a player's inventory during a match (`kit.apply(...)` mutating
      inventory contents still runs vanilla's normal advancement-trigger checks).
      `MatchListener.onAdvancementDone` revokes any criteria awarded to a player currently
      in a match.
- [x] **JCore: consuming plugins never received new `messages.yml` keys on update.**
      `JCore.ensureMessages()` called `mergeDefaults(CORE_MESSAGE_DEFAULTS)`, which only
      merges JCore's own hardcoded `core:` block - the consuming plugin's *bundled*
      `messages.yml` was only ever written on first install, because `YamlFile`'s
      `copyResource` flag copies the resource only when the file does not already exist.
      Any key added to Duels' `messages.yml` in a later version was therefore silently
      missing on every existing server. `ensureMessages()` now also calls
      `updateDefaults()`, matching what `ensureConfig()` already did correctly for
      `config.yml`. Fixed at the root in JCore rather than worked around in Duels.
- [x] **JCore: `YamlDefaultsMerger` reformatted untouched lines.** Because the merger
      rewrites the whole file through SnakeYAML, the dumper's defaults hard-wrapped long
      message strings at 80 columns and reduced list-item indentation - producing a large
      cosmetic diff over a file a server owner has been hand-editing. `DumperOptions` now
      sets an unlimited width and explicit indicator indentation, so a merge is
      additions-only. Verified against a real server `messages.yml`: comment header
      preserved, hand-edited values untouched, only genuinely new keys added.
- [x] **JCore: `YamlDefaultsMerger` does not merge into existing YAML *lists* - resolved
      as intended behaviour.** `mergeMappings` recurses into `MappingNode`s but treats a
      `SequenceNode` as an already-set value, so list-valued keys never gain new entries.
      In practice `admin.help`/`duel.help` on an existing server keep their old contents
      and newly added help lines never appear. **Decision: help lists are treated as
      user-owned and this is documented rather than worked around.** A server owner who
      has reworded their help list should not have our entries injected back into it, and
      there is no way to distinguish "they edited this" from "this is stale" without
      version metadata. The header comment in `messages.yml` now tells owners to delete
      the `help:` block to regenerate it in full.

      The rejected alternative was a config-version key letting JCore selectively replace
      flagged lists on upgrade. That is the mechanism that genuinely solves "ship new
      content into an existing config", and is worth building **when a second case for it
      appears** - designing it for help text alone would be inventing a versioning system
      to solve one cosmetic problem.

## Phase 1 - Arena Configuration vs Arena Runtime Instance `[x]` (core done)

**Status:** The core of this phase is implemented and manually tested. `ArenaInstance`
(Duels, `arena/ArenaInstance.java`) is a runtime snapshot (id + cloned spawn locations)
built at allocation time, decoupled from the live, mutable `Arena`. `Match` now holds an
`ArenaInstance` instead of an `Arena` reference. Rather than the pre-built "instance
registry" originally sketched below, occupancy is managed through a new `ArenaAllocator`
interface (`allocate()` / `release()` / `isAllocated()`) with a `StaticArenaAllocator`
implementation that reproduces the old scan-for-a-free-arena behaviour but owns its own
`Set<Integer>` of claimed arena IDs instead of deriving "in use" from scanning the live
match table. `ArenaManager`'s `activeCheck` now asks `arenaAllocator::isAllocated` instead
of `matchManager::isArenaInUse`, preserving the existing edit-while-in-use protection.
This satisfies the phase's Definition of Done (`Match`/`MatchManager` no longer reference
`Arena` directly for occupancy) and gives Phase 3 (instancing) a clean seam: a future
allocator implementation that manages multiple physical copies per template is a new
`ArenaAllocator` implementation, not a rework of `Match` or `MatchManager`.

**This is the single most important structural change in the roadmap.** Phases 2
(bounds), 3 (instancing) and 4 (spectators) all depend on it, and matchmaking (Phase 7)
becomes much simpler once it exists.

### Problem / Opportunity

`Arena` currently conflates two different concepts: the *configured template* (name,
spawn points, allowed/disallowed kits) and *the one physical place a match happens*.
`MatchManager.isArenaInUse(arenaId)` treats an arena as singly-occupied, and `Match`
holds a direct reference to the live `Arena` object. This is why running N concurrent
duels today requires configuring N separate arenas, even if several of them are
identical layouts.

### Current Behaviour

`ArenaManager` owns a `Map<Integer, Arena>` loaded from YAML via `YamlRepository`.
`Arena` holds spawn points and kit permissions. `MatchManager` looks up a free `Arena`
by scanning for one whose ID isn't in the current matches' arena references, and the
`Match` object keeps that same `Arena` object for its lifetime. Editing/deleting an
arena while `isArenaInUse` is true is blocked via `ArenaMutationResult` - this works
today because "arena" and "the one active use of it" are the same object.

### Desired Behaviour

Most Paper minigame plugins that support concurrent play on the same map separate
"the map" from "a running copy of the map." A common pattern is: configuration defines
a template once; at match-start time the system either (a) claims one of several
pre-built physical copies of that template, or (b) copies/regenerates a world region
per match. Either way, code that manages match lifecycle should depend on a
"runtime instance" concept, not directly on the configuration object.

### Why This Point In The Roadmap

Everything else physical (bounds, instancing, spectators) needs to know "which physical
copy of which arena is this match using" as a first-class concept. Doing this now, while
there's only ever one instance per configured arena, means the change is a pure
refactor with no behavioural difference yet - much cheaper than retrofitting it once
spectators or instancing already assume today's 1:1 model.

### Proposed Architecture

- `Arena` (Duels) stays configuration: id, name, spawns, allowed kits, (later) bounds.
  Owned by `ArenaManager`, mutable only through admin menus/commands, persisted to YAML.
- A new `ArenaInstance` (Duels) represents one physical, currently-claimable copy of an
  arena: which `Arena` it's based on, its actual spawn locations (may differ from the
  template once instancing exists), and whether it's currently claimed. Runtime-only,
  not persisted - rebuilt at startup from configuration (one instance per arena, to
  start).
- `Match` holds an `ArenaInstance` reference instead of an `Arena` reference directly.
- `MatchManager` becomes the owner of instance claim/release (it already owns match
  lifecycle, so this doesn't introduce a new class solely for this - it's a method split
  on the manager that already exists).
- Arena *configuration* mutation (rename, kit changes) continues to check
  "is any instance of this arena claimed" through the same `activeCheck` wiring that
  exists today, just checked against instances instead of a raw ID-in-use set.

### JCore vs Duels

Entirely Duels. `ArenaInstance` is a domain concept specific to Duels' gameplay model,
not reusable infrastructure - it doesn't belong in JCore.

### Java / Paper Concepts

Composition over holding a mutable shared reference; the difference between
configuration state and runtime state (why `ArenaInstance` isn't persisted); the
existing `activeCheck: IntPredicate` pattern in `ArenaManager` is the template to extend.

### Existing Duels Example

`Match` already deep-copies `Kit` objects into `availableKits` at construction time to
isolate itself from live kit edits, while deliberately *not* copying `Arena` and instead
blocking arena mutation via `ArenaMutationResult` + `activeCheck`. `ArenaInstance` is the
next step of that same reasoning applied to "which physical copy," not "should we copy
the data."

### Real Scenario

An admin configures one arena called "Desert." Two separate `/duel` challenges are
accepted around the same time. Today, the second challenge fails to find a free arena
if only one "Desert" is configured, even though the admin has plenty of empty space to
build a second copy. With `ArenaInstance`, the admin can register a second instance of
the same "Desert" template (Phase 3 formalizes this), and `MatchManager` claims whichever
instance is free without either match needing to know the other exists.

### Implementation Plan

1. Introduce `ArenaInstance` and an in-memory registry (starts as exactly one instance
   per `Arena`, built at plugin enable / on arena creation).
2. Change `MatchManager` to claim/release `ArenaInstance` instead of raw arena IDs.
3. Change `Match` to hold `ArenaInstance` instead of `Arena` (expose the underlying
   `Arena` through the instance for menu/kit-permission lookups).
4. Update `ArenaManager`'s in-use check to ask the instance registry instead of scanning
   matches directly, preserving the existing `activeCheck` seam.
5. No behavioural change expected yet - this phase should be verifiable purely by
   re-running the existing manual test matrix in `TESTING.md`.

### Testing Plan

Full regression of the existing arena-locking and concurrent-match sections of
`TESTING.md` (they should pass unchanged). Add one deliberate test: two duels
requested back-to-back with only one arena configured must still correctly report
"no arena available" for the second (this behaviour must be unchanged by the refactor).

### Definition of Done

`Match`/`MatchManager` no longer reference `Arena` directly for occupancy purposes;
all existing arena-editing/deletion protections still function against the same manual
test matrix; no persisted format changes.

## Phase 2 - Arena Bounds `[x]`

**Status:** Complete and verified in game. Bounds storage, the admin "edit mode" wand
system, live boundary enforcement, and a particle visualisation of the bounds are all
implemented and manually tested.

Implemented so far:

- `Arena` (Duels, `arena/Arena.java`) now has `boundsCorner1`/`boundsCorner2` (`Location`)
  fields, plus `hasBounds()`. `ArenaSerializer` reads/writes both, and the `FIELDS`
  allowlist used by its defensive deserialization check was updated to include them
  (this allowlist rejects any unrecognized YAML key, so it must be kept in sync with
  whatever `serialize()`/`deserialize()` actually touch).
- `ArenaManager.setBoundsCorner(id, corner, location)` mirrors the existing `setSpawn`
  method - same `NOT_FOUND`/`IN_USE`/`SUCCESS` result pattern, so bounds get the same
  "can't edit while in use" protection spawns already had.
- A new admin "edit mode" QoL feature was added alongside bounds, motivated by the same
  problem spawns already had: repeatedly reopening the admin GUI just to walk somewhere
  and click "set spawn" again is annoying. This is a hotbar-wand pattern (similar to
  WorldEdit), scoped deliberately to spatial actions only (spawns and bounds) - actions
  like rename or the kit-allowed list stay GUI-only since they aren't spatial and the
  existing menu already serves them fine.
  - `ArenaEditTool` (enum): `SPAWN_1`, `SPAWN_2`, `BOUNDS_CORNER_1`, `BOUNDS_CORNER_2`,
    `EXIT` - each carries a `Material` and display name used to build its hotbar item.
  - `ArenaEditSession`: a small per-player record (player UUID, arena ID, saved 9-slot
    hotbar) - deliberately *not* built on `PlayerStateManager`, since that system's full
    match-lifecycle inventory/health capture is a much heavier concern than "remember 9
    hotbar slots for the duration of an edit session."
  - `ArenaEditManager`: owns `Map<UUID, ArenaEditSession>` as the source of truth for who
    is currently editing what (same self-tracking-map pattern as `StaticArenaAllocator`
    from Phase 1). `start()` snapshots the player's hotbar and replaces it with tagged
    tool items; `end()` restores the snapshot and drops the session. Tool items are
    identified via a `PersistentDataContainer` tag under a plugin-scoped `NamespacedKey`
    (`arena-edit-tool`) - this is the first use of PDC tagging anywhere in Duels.
  - `ArenaEditListener` (Duels, `listener/`): handles `PlayerInteractEvent` (left-click a
    tool sets the corresponding spawn/bounds corner to the player's current location,
    right-click teleports to it if set, matching `ArenaDetailMenu.handleSpawnClick`'s
    existing left/right convention; `EXIT` ends the session either click) and
    `PlayerQuitEvent` (ends the session on disconnect so it can't leak forever - without
    this, a disconnecting admin would permanently occupy a phantom edit session).
  - `ArenaDetailMenu` gained an "Enter Edit Mode" button that calls
    `arenaEditManager.start(player, arena)` and closes the menu.
- New messages: `ARENA_BOUNDS_SET`, `ARENA_BOUNDS_NOT_SET`, `ARENA_EDIT_MODE_EXITED`.
- **Out-of-bounds response resolved (product decision).** Rather than picking one global
  behaviour, it is configured *per arena* via a `BoundaryMode` enum (`WARNING`,
  `SOFT_RETURN`, `FORFEIT`) plus an `Arena.graceSeconds` value. A parkour-style arena and
  a no-escape PvP arena want different answers, so this belongs on the arena, not in
  `config.yml`.
- **Bounds particle visualisation.** While an admin is in an edit session,
  `ArenaEditManager` runs a per-session repeating task that draws the twelve edges of the
  bounds cube using `Particle.DUST` via `player.spawnParticle(...)`. This is rendered
  client-side for that admin only, so there is no real world state to clean up (the
  alternative - placing literal barrier blocks - would need reverting and would be visible
  to everyone). The bounds are re-read every tick so moving a corner updates the box live.
  The task handle is stored on `ArenaEditSession` and cancelled in `ArenaEditManager.end`,
  which `Duels.onDisable` already calls for every open session.
- **`BoundaryEnforcer`** (`arena/BoundaryEnforcer.java`) owns all enforcement state and
  logic; `MatchListener.onPlayerMove` is a single delegating call. See the architecture
  note below for why enforcement is not purely event-driven.

### Problem / Opportunity

Duels has no concept of arena physical boundaries. `RELEASE_REVIEW.md` already lists
this as a known limitation - server owners currently rely on an external region
protection plugin to stop players wandering off, and there is no way to programmatically
answer "is this location part of arena X."

### Current Behaviour

`Arena` stores spawn points only. Nothing in Duels knows the extent of the playable
space.

### Desired Behaviour

An arena should be able to define a bounding region (simple min/max corner pair is
sufficient - full polygonal regions are unnecessary complexity for a duels-style
1v1/small-scale plugin). This unlocks: boundary warnings/soft walls, automatic
cause-of-death handling for players who leave the region, and - critically - it's the
prerequisite spatial concept spectators (Phase 4) need to know where they're allowed to
be.

### Why This Point In The Roadmap

Spectator mode and (to a lesser extent) instancing both need to reason about arena
physical space. Bounds is the cheapest, most self-contained way to introduce that
concept, so it comes before either.

### Proposed Architecture

Bounds live on `Arena` (configuration), set via admin menu/command (two corner
locations, likely captured the same way spawns are captured today). Runtime bounds
*checking* (is player still inside) is a `MatchListener` concern using a `PlayerMoveEvent`
check gated to only fire for players currently in a match - avoid the classic mistake of
checking bounds on every player's every move event server-wide.

### JCore vs Duels

Duels. Bounds are a domain concept of "arena," not reusable infrastructure. If a future
plugin needs generic AABB-contains-point math, that math (not the arena concept) could
move to a JCore utility - but only once a second real consumer exists.

### Java / Paper Concepts

`PlayerMoveEvent` has fired on every single block-fraction movement historically -
worth confirming the correct pattern (comparing block coordinates between `getFrom()`/
`getTo()`) to avoid a performance regression, since this event is one of the hottest in
Bukkit.

### Existing Duels Example

Same pattern as the existing third-party-damage-blocking check in `MatchListener` -
already correctly scoped to only act on players who are actually in a match, rather
than a blanket listener.

### Real Scenario

A player being chased around the "Desert" arena runs past the edge of the built arena
into unrelated build space. Today nothing stops them or reacts. With bounds, this can be
detected and handled (soft return, forfeit-by-leaving, or simply a warning) - the design
choice is deliberately deferred to when this is implemented, since it interacts with
future ranked-match rules.

### Implementation Plan

1. Add bounds (two `Location` corners) to `Arena` and its serializer. `[x]`
2. Add capture UI in the arena admin menu, mirroring the existing spawn-capture flow. `[x]`
3. Add a bounded `PlayerMoveEvent` check scoped to in-match players. `[x]`
4. Decide and implement the out-of-bounds response. `[x]` - resolved as per-arena
   `BoundaryMode` + `graceSeconds` rather than a single global behaviour.

### Architecture Note - Why Enforcement Is Not Purely Event-Driven

The obvious implementation is to do everything inside `PlayerMoveEvent`: detect the
crossing, start a grace timer, and check the deadline on subsequent moves. That was the
first implementation and it had a hole worth recording.

`PlayerMoveEvent` reports a **transition**. A grace period is a condition on the
**passage of time**, and no event fires for "three seconds have elapsed". A player who
walked outside the bounds and then stood perfectly still generated no further move events,
so their deadline was never re-evaluated and they were never punished - the code actively
rewarded camping outside the arena, which is the exact behaviour bounds exist to prevent.

`BoundaryEnforcer` therefore splits the two responsibilities:

- **Detection** stays on the move event, which is the only thing that knows a crossing
  happened.
- **Deadline evaluation** lives in a repeating sync task (10 ticks) that reads
  `player.getLocation()` directly. The task starts on demand when somebody first leaves
  the bounds and cancels itself the tick it finds nobody out of bounds, so there is no
  idle polling on a server where nobody is currently misbehaving.

Two related Paper details this phase surfaced:

- **Cancelling a `PlayerMoveEvent` and calling `player.teleport(...)` in the same handler
  fight each other.** Cancelling means "reject this move, put the player back at
  `getFrom()`" - which, for a player already outside the bounds, is itself outside the
  bounds. The supported way to redirect a movement is `event.setTo(safeLocation)`, which
  replaces the destination within the same movement packet instead of competing with it.
- **Clearing the out-of-bounds timestamp before acting on it is unsafe.** If the return
  fails, the next move event sees the player as having only just left and hands them a
  whole new grace period - producing an endless warn/reset cycle rather than enforcement.
  The timestamp is now cleared only after the correction is confirmed.

Because a zero-second grace period is the default, that case is still handled inline on
the move event via `setTo` so it feels like a solid wall rather than a yank-back up to
half a second later. Every other configuration defers to the scheduled check.

### Testing Plan

Verify no performance regression from the move-event handler under normal (non-match)
play; verify in-match boundary detection triggers correctly at all four edges and both
Y extremes. Per mode: `SOFT_RETURN` grace 0 behaves as a wall; `SOFT_RETURN` with grace
warns once then returns once at the deadline; `FORFEIT` fires for a player standing
**still** outside past the deadline; re-entering before the deadline grants a full fresh
grace period; `WARNING` warns once per exit without repeating.

### Definition of Done

Arenas can store and edit bounds through the admin menu; a player leaving those bounds
during a live match is detected and handled per the arena's configured response. `[x]`

## Phase 3 - Arena Instancing

### Problem / Opportunity

Once `ArenaInstance` exists (Phase 1) and bounds exist (Phase 2), formalize supporting
*multiple simultaneous instances of the same arena template* - either multiple
pre-built physical copies, or (more ambitious, likely later) per-match world/region
copies.

### Current Behaviour

One `Arena` = one physical location, enforced by the same object being both the
template and the "the one place it happens."

### Desired Behaviour

An admin registers a template once and either builds several physical copies (simplest,
matches how many Paper minigame plugins operate today by just having admins build N
copies and register them) or lets Duels regenerate/paste a region per match (much bigger
undertaking, requires WorldEdit/schematic tooling and is not warranted until real demand
exists).

### Why This Point In The Roadmap

Needs `ArenaInstance` (Phase 1) to exist as a concept, and benefits from bounds (Phase 2)
existing so multiple instances of "Desert" don't overlap in a way that leaks players/
visibility between them without a defined boundary.

### Proposed Architecture

Start with the simple form: an `Arena` can have N registered instances (each with its
own spawn locations/offset, sharing the template's kit permissions). `MatchManager`
already claims/releases instances after Phase 1 - this phase is mostly about the admin
tooling to register multiple instances per template rather than a runtime rearchitecture.

**Flag for later (not yet designed):** setting a physical instance's own spawn
locations is the same "stand somewhere in the world and click a tool" action as today's
`Arena` edit-mode wand (`arena/ArenaEditManager.java`, `arena/ArenaEditTool.java`), just
scoped to one `ArenaInstance` instead of the `Arena` template. `ArenaEditManager`
currently mixes two responsibilities: generic protected-inventory-session bookkeeping
(full hotbar/offhand save-clear-restore, blocking drop/click/drag/swap/death while
editing, config-driven tool slots via JCore's `SlotResolver`) and arena-specific tool
behaviour (what each of the five tools actually does on click). The session/inventory
half is already resource-agnostic and worth reusing as-is; the tool-behaviour half is
not. Splitting those two responsibilities so a second edit-mode consumer (per-instance
spawn editing) can reuse the session machinery without inheriting `Arena`-specific
click logic is a real design pass to do when this phase starts, not before - the right
interface shape depends on what per-instance editing's tool set actually needs, which
doesn't exist yet.

### JCore vs Duels

Duels.

### Java / Paper Concepts

Nothing new beyond Phase 1/2 - this is additive admin tooling over an already-built
runtime seam.

### Existing Duels Example

Directly extends the `ArenaInstance` registry introduced in Phase 1.

### Real Scenario

A popular arena causes queue backlog because only one copy exists. The admin builds a
second physical copy elsewhere on the map and registers it as a second instance of the
same template; `MatchManager` now has two claimable instances instead of needing an
entirely separate configured arena with duplicated kit-permission setup.

### Implementation Plan

1. Extend arena admin menu to register/list/remove instances of a template.
2. `MatchManager`'s existing claim logic (Phase 1) already handles multiple instances -
   confirm no assumptions elsewhere in code special-case "one instance per arena."
3. Design pass on splitting `ArenaEditManager`'s generic session/inventory-safety
   behaviour from its `Arena`-specific tool actions (see flag above), then build
   per-instance spawn editing on top of the shared half.

### Testing Plan

Three simultaneous matches requested against a two-instance arena - two should succeed
immediately, the third should correctly report no free instance.

### Definition of Done

An admin can register more than one physical instance of the same arena template, and
concurrent matches correctly claim distinct instances.

## Phase 4 - Spectator Mode

### Problem / Opportunity

No spectator concept exists anywhere in the codebase today - not in `Match`, not in
`MatchListener`'s player-scoping logic, nowhere.

### Why This Point In The Roadmap

Needs arena bounds (Phase 2) to know where spectators may exist/teleport, and benefits
from instancing (Phase 3) existing so spectating "this match" is unambiguous even when
multiple copies of the same arena template are running.

### Proposed Architecture

`Match` gains a spectator list (separate from the two combatants). `MatchListener`'s
existing third-party-interference checks need a third category added: "spectator of
this match" (exempt from damage-blocking collateral, but still not a combatant).
Spectators need `GameMode.SPECTATOR` handling distinct from `PlayerStateManager`'s
existing combatant prepare/restore flow - likely a lighter-weight state capture since
spectators don't need inventory/health snapshot/restore, just gamemode and location.

### JCore vs Duels

Duels - spectating is a domain concept of a match, not general infrastructure.

### Real Scenario

A player wants to watch their friend's duel in the "Desert" arena. They should be able
to teleport in as a spectator without triggering the existing "third party interference"
damage-block logic meant for uninvolved players wandering near a fight, and without
`PlayerStateManager` needing to snapshot/restore their full inventory as if they were a
combatant.

### Implementation Plan / Testing / DoD

Deferred to detailed design when this phase starts - flagging the dependency chain now
is the goal of this roadmap entry, not a full spec.

## Phase 5 - Deeper Statistics & Tracking

### Problem / Opportunity

Current stats (`StatsManager`/`MatchRecord`) capture win/loss and basic match records.
"Deeper statistics" from the future-feature list means things like per-kit win rates,
streaks, and head-to-head history (a `HeadToHead` class already exists but hasn't been
read in depth yet during this audit - worth confirming its current scope before
expanding it).

### Why This Point In The Roadmap

Mostly additive to the existing `stats` package - doesn't require Phases 1-4. Placed
here because Phase 6 (rewards) and Phase 8 (ranking) both want a trustworthy stats
pipeline to build on, and it's cheaper to solidify stats once than twice.

### Proposed Architecture

Extend the existing `StatsRepository` interface (already has SQL and YAML
implementations, per the Strategy-style boundary already in place) rather than
introducing a parallel tracking system.

### Implementation Plan

To be scoped in detail closer to the phase - depends on which specific stats Jack
actually wants surfaced (leaderboard categories, per-kit breakdowns, etc.).

## Phase 6 - Vault Integration & Rewards

### Problem / Opportunity

Awarding in-game currency for match results introduces a "the reward must not be lost
or duplicated" requirement that today's stats-writing pipeline doesn't fully guarantee.
`RELEASE_REVIEW.md` already documents that SQL write failures are logged but not
retried/durable - acceptable for a leaderboard entry, not acceptable for a player's
economy balance.

### Why This Point In The Roadmap

Depends on Phase 0's durability groundwork and benefits from Phase 5's stats pipeline
being settled, since reward-granting will hook into the same match-end flow.

### Proposed Architecture

Match-end should produce one authoritative "match result" event/record first, then
stats, rewards, and (later) rating updates should each consume it independently and
each be individually retryable/idempotent - rather than each caller performing its own
ad hoc side effect inline in `MatchManager.endMatch`. This is the first roadmap item
that touches "what happens if one of several post-match updates fails," which the
existing `AbstractDatabase.transaction(...)` machinery is well positioned to help with
for the DB-backed parts, but Vault's own economy call is a separate external system
that can fail independently of the database.

### JCore vs Duels

Vault integration itself is Duels-specific (a soft-dependency plugin hook), but if a
second JCore-based plugin later also needs "reliably grant a reward exactly once,"
that retry/idempotency mechanism could generalize into JCore. Not yet - one consumer
isn't enough to design the generalization around, per the existing project rule on the
`PlayerState` serializer split.

## Phase 7 - Matchmaking

### Problem / Opportunity

Currently, matches only start via direct challenge-and-accept between two specific
players. A queue-based matchmaking model (join queue, get paired automatically) is a
materially different flow.

### Why This Point In The Roadmap

Benefits significantly from arena instancing (Phase 3) - a matchmaking queue that can
only ever produce one match at a time because only one arena instance exists per
template isn't very useful. Doesn't strictly require ranking (Phase 8) - a queue can
pair players in join-order first, and ELO-aware pairing can be layered on afterward.

### Proposed Architecture

A `Queue` concept (Duels domain) - one or more named queues, each tracking waiting
players and pairing them when enough are present. Deliberately not a distributed/shared
queue at this stage (Stage A, per the standalone-first architecture principle) - a
single-server in-memory queue mirrors how `ChallengeManager` already works today, just
matching by "who's waiting" instead of "who's being challenged."

## Phase 8 - ELO/MMR/SBMM

Depends on Phase 5 (a trustworthy stats pipeline to compute from) and Phase 7
(matchmaking needs to exist before rating-aware pairing is meaningful). Rating
calculation itself (e.g. Elo update formula) is pure domain logic with no architectural
prerequisites beyond having match results to feed it - the dependency here is about
having somewhere for ratings to matter, not the math itself.

## Phase 9 - Network Readiness (Boundary Work Only)

### Problem / Opportunity

Nothing in Duels today assumes a single server in a way that would be expensive to
change later, but nothing has been deliberately designed to make a future Stage B
(proxy + several backend Duels servers) cheap either.

### Why This Point In The Roadmap

This phase is explicitly about identifying natural seams (not building network
infrastructure) so that when a genuine multi-server need appears, the rework is
localized rather than a rewrite. Per the project's core instruction: do not build
Stage D while still solving Stage A.

### What Actually Needs To Change For Stage B (First Principles)

- A **proxy** (Velocity/BungeeCord) exists purely to route player connections between
  backend servers and transfer players - it does not run Bukkit plugins or game logic.
- In Stage B (one proxy, several fixed Duels backend servers), each backend server keeps
  running its own independent `ArenaManager`/`MatchManager` exactly as today - arenas are
  physically tied to one server, so there's no reason to share arena state across
  servers.
- What *does* need to become shared at Stage B: player statistics (so a player's record
  is consistent no matter which backend they play on) and possibly kit definitions (so
  kits look the same everywhere) - both are already SQL-capable today via `StatsRepository`
  and could point at one shared database instance rather than per-server SQLite, which
  requires no new code, just a shared `database.yml` pointing multiple servers at one
  MySQL/PostgreSQL instance.
- Challenge/queue state stays per-server at Stage B - a player can only challenge
  someone who's on the same backend server, which is a reasonable and simple constraint
  for a first network version.
- Stage C (shared cross-server matchmaking) is the point where a genuinely distributed
  queue becomes necessary, because now a queue needs to pair two players who might be
  connected to different backend servers - this is where a message bus (Redis pub/sub,
  or the proxy's own plugin-messaging channels) actually earns its complexity, not
  before.
- Stage D (dynamic server provisioning) is a purely operational/infrastructure problem
  layered on top of Stage C's shared matchmaking - not something Duels' own code needs
  to anticipate architecturally beyond "a match's assigned server can be looked up by
  ID," which falls out naturally from Stage C's design.

### Recommended Preparatory Change

The only concrete "do this now" item: keep player stat storage decoupled from
per-server identity (it already is - `StatsRepository`/`SqlStatsRepository` key by
player UUID, not by server). No code change needed at Stage A - this is a "confirm and
preserve" item, not a "build" item.

## Phase 10 - Advanced/Optional Systems

Placeholder for ideas that come up during development that don't have an immediate
architectural dependency and aren't urgent - to be triaged as they arise rather than
speculatively designed now.

## Phase 11 - Admin Command/GUI Parity `[x]`

**Status:** Complete and verified in game. Nine new subcommands (arena `rename`, `toggle`,
`bounds`, `boundary`, `editmode`, `allowkit`; kit `rename`, `icon`, `edit`), nine matching
permission nodes in `plugin.yml`, and nine new `admin.help` lines.

This phase has no architectural dependency on anything above it and could be worked on
at any point - it's numbered last only because the roadmap's ordering reflects
architectural dependency, not urgency (per the note at the top of this document). Jack
asked for this directly, so it's fine to pull forward and interleave with other phases
whenever convenient.

### Problem / Opportunity

Every admin action in Duels today is reachable through the GUI (`/duels`, `/duels arena`,
`/duels kit`), but only a subset also has a direct command equivalent. A server owner who
prefers commands (for speed, for use in command blocks/console-driven setup scripts, or
because they're more comfortable with them) is currently forced into the GUI for some
actions and not others, with no consistent rule for which is which. Jack's stated goal:
"everything should be doable via GUI or command, player's choice," including deep-links
straight into a specific admin screen (his example: `/duels kit 1 edit` should jump
straight into that kit's item-editor GUI rather than requiring `/duels kit 1` then a
menu click).

### Current Behaviour

`DuelsCommand` (Duels, `commands/DuelsCommand.java`) already has some deep-linking: `/duels
arena <id>` and `/duels kit <id>` open straight into `ArenaDetailMenu`/`KitDetailMenu`
via `menus.openPath(...)`, skipping the main menu and list menu. Pure command equivalents
exist for arena/kit `create`, `delete`, `list`, and arena `setspawn`.

Actions that are currently GUI-only, with no command equivalent at all:

- Arena: rename, toggle enabled/disabled, set bounds corner 1/2 (`ArenaManager` already
  has `setBoundsCorner`, only `ArenaDetailMenu`'s edit-mode wand calls it), set
  `boundaryMode`/`graceSeconds` (added this session, no admin surface at all yet -
  editing these currently requires hand-editing the arena's YAML), toggle a kit
  allowed/disallowed for that arena (`ArenaKitMenu`), enter the edit-mode wand session.
- Kit: rename, set icon (from the item currently held), open the item-editor GUI
  (`KitEditMenu`) directly by ID rather than navigating through `/duels kit <id>` first.

### Desired Behaviour

Two genuinely different things are being asked for here, and they need different
solutions:

1. **Pure data mutations** (rename, toggle enabled, set bounds corner to a given
   coordinate or the sender's current location, set boundary mode/grace seconds, toggle a
   kit's allowed status, set a kit's icon from the held item) have no inherent reason to
   require a GUI at all. These should get a real command that performs the mutation
   directly, exactly like `setspawn` already does, with the same `ArenaMutationResult`/
   `NOT_FOUND`/`IN_USE` response pattern already established.
2. **Inherently visual/spatial actions** - `KitEditMenu`'s 36-slot inventory item editor,
   and anything that requires physically standing somewhere (the edit-mode wand's spawn/
   bounds capture) - can't be meaningfully replaced by command arguments; nobody wants to
   type coordinates for 40 inventory slots. For these, "command parity" means a command
   that jumps straight to the right screen/session (`/duels kit <id> edit` opens
   `KitEditMenu` directly; `/duels arena <id> editmode` calls
   `arenaEditManager.start(player, arena)` directly), not a command that avoids the GUI
   entirely.

### Why This Point In The Roadmap

No dependency on any other phase - it's pure additive admin UX over commands/menus that
already exist. Placed last because every other phase in this document is here because an
earlier phase makes it structurally cheaper or necessary; this phase has no such
relationship to anything else, so its position is arbitrary rather than load-bearing.

### Proposed Architecture

No new abstraction is needed. `DuelsCommand` already has the right shape - a
`CommandBuilder` tree with `optionalArgument("id")` for the deep-link case and dedicated
`child(...)` subcommands for named actions - so this phase extends the existing tree
rather than introducing a new command framework. Each new subcommand calls straight into
the same manager methods the corresponding menu button already calls
(`ArenaManager.rename`, `ArenaManager.toggleEnabled`, `ArenaManager.setBoundsCorner`, a
new `ArenaManager` method for boundary mode/grace seconds, `ArenaKitMenu`'s underlying
toggle, `KitManager.save` for rename/icon), so the command and the menu button for the
same action are two callers of one method, not two implementations of the same behaviour.
For the deep-link commands (`edit`, `editmode`), the command handler is a one-line call
into the same menu-opening/session-starting method the GUI's own button already calls
(`kitEditMenu.open(player, id)`, `arenaEditManager.start(player, arena)`).

Console/command-block usage needs explicit handling per action: bounds-corner-from-
current-location and the edit-mode wand are inherently player-only (there's no location
concept for the console), and should reject non-player senders with `CoreMessage.PLAYER_ONLY`
exactly as `setspawn` already does. Rename, toggle-enabled, boundary mode/grace seconds,
and kit-allowed-toggle have no location dependency and should work from console too, since
that's part of the stated motivation (command-block/console-driven setup).

### JCore vs Duels

Entirely Duels - this is Duels' own command surface calling Duels' own managers. If a
second JCore-based plugin later wants a generic "every menu action needs a matching
command" convention or helper, that could become a JCore pattern, but one consumer isn't
enough to design a generalization around (same reasoning already applied to the
`PlayerState` serializer split and the Vault reward mechanism above) - not something to
build now.

### Java / Paper Concepts

Nothing new - this reuses `CommandBuilder`/`CommandContext`/`ArgumentTypes` (JCore command
system) exactly as `DuelsCommand` already does today.

### Existing Duels Example

`DuelsCommand.setSpawn` is the template for every new pure-mutation command in this
phase: parse arguments, call the manager method, switch on `ArenaMutationResult.status()`
to send the right message. `openArenaMenuOrDetail`/`openKitMenuOrDetail` are the template
for the new deep-link commands.

### Real Scenario

An admin is setting up a new arena via a setup script/command block rather than walking
through menus by hand (e.g. provisioning several arenas identically at server start).
Today they can create the arena and set its spawns via commands, but must open the GUI to
rename it, enable it, or set its boundary mode - breaking the "fully scriptable setup"
goal for no structural reason, since none of those actions need a GUI's interactivity.

### Implementation Plan

All five steps below are done. `[x]`

1. Add `ArenaManager` methods needed for the new mutations that don't already have one
   (boundary mode/grace seconds setter, following the existing `NOT_FOUND`/`IN_USE`/
   `SUCCESS` result pattern).
2. Add arena subcommands: `rename`, `toggle`, `bounds <id> <1|2>` (mirrors `setspawn`),
   `boundary <id> <mode> [graceSeconds]`, `editmode <id>` (player-only), and a kit-allow
   toggle command under the existing arena tree.
3. Add kit subcommands: `rename`, `icon` (uses held item, player-only), `edit <id>`
   (deep-link, player-only).
4. Add matching permission nodes to `plugin.yml` for every new subcommand, following the
   existing `duels.admin.arena.<action>`/`duels.admin.kit.<action>` convention, and wire
   each into the relevant parent permission's `children` map.
5. Update the `admin.help`/`duel.help` message lists in `messages.yml` to document the
   new commands.

### Testing Plan

For each new command: verify it produces the same end state as clicking the equivalent
GUI button (same persisted YAML, same in-use/not-found protection). Verify player-only
commands reject console senders with the correct message. Verify permission nodes
actually gate access (a player without the specific child permission, but with the parent,
should still be blocked - matching how existing arena/kit permissions behave today).

### Definition of Done

Every GUI action identified above either has a direct command equivalent (pure
mutations) or a command that deep-links straight to it (visual/spatial actions);
`plugin.yml` and help messages are updated to match.

---

## Deferred (Not Rejected)

These items from the existing V1 review remain intentionally deferred, restated here so
the roadmap has one place tracking all deferred work:

- `MatchFactory`/allocation service abstraction - trigger condition (arena modes, teams,
  or remote servers) hasn't happened; Phase 1-3 above will re-evaluate this naturally.
- Splitting `PlayerState` serializer - trigger condition (second consumer) hasn't
  happened.
- Testcontainers MySQL/MariaDB/PostgreSQL integration tests - blocked on Docker
  availability in this environment, not a design decision.
