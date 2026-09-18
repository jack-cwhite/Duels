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
- [ ] Reorder `DuelCommand` challenge-acceptance messaging so "accepted" is only sent
      once `MatchManager.startMatch` has actually produced a match, not before. Purely a
      QoL/ordering fix, no architectural change.
- [ ] Wrap `PlayerStateManager` restoration in defensive handling for the case where the
      snapshot references a world that no longer exists (renamed/removed world). Today a
      thrown exception during `PlayerState.apply` would surface as an unhandled exception
      in a join event; the snapshot is left in place either way (fail-forward), but the
      failure should be caught and logged instead of leaking into Bukkit's event handling.
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

## Phase 2 - Arena Bounds `[~]` (in progress)

**Status:** Bounds storage and an admin "edit mode" wand system are implemented and
compile clean; boundary *enforcement* during live matches (the `PlayerMoveEvent` check
described below) has not been started yet.

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

Not yet done for this phase:

- Boundary *enforcement* - nothing currently stops a player leaving `boundsCorner1`/
  `boundsCorner2` during a live match. This is the `PlayerMoveEvent` work described
  below and is still fully unstarted.
- The out-of-bounds response (soft return vs. forfeit vs. warning) is still an open
  product decision, deferred per the original plan below.
- Manual testing of the edit-mode wand itself (clicking tools before a value is set,
  quitting mid-session, deleting an arena while someone has it open in edit mode) has not
  been performed yet - only `mvn compile` has been verified clean.

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

1. Add bounds (two `Location` corners) to `Arena` and its serializer.
2. Add capture UI in the arena admin menu, mirroring the existing spawn-capture flow.
3. Add a bounded `PlayerMoveEvent` check in `MatchListener`, scoped to in-match players.
4. Decide and implement the out-of-bounds response (this is a product decision, not an
   architecture one - flag it for discussion when this phase starts).

### Testing Plan

Verify no performance regression from the move-event handler under normal (non-match)
play; verify in-match boundary detection triggers correctly at all four edges and both
Y extremes if applicable.

### Definition of Done

Arenas can store and edit bounds through the admin menu; a player leaving those bounds
during a live match is detected and handled per the agreed response.

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
