# Duels / JCore V2 Roadmap

This roadmap is dependency-ordered, not feature-list-ordered. Each phase exists because
something in an earlier phase makes it structurally cheaper or structurally necessary.
V1 is a working, previously-reviewed release candidate (see `RELEASE_REVIEW.md`) - this
roadmap is about evolving it, not replacing it.

Status legend: `[ ]` not started, `[~]` in progress, `[x]` done.

**Scope target:** "Duels complete" means release-ready for a standalone server - the
full single-server feature set finished, tested, and technically shippable, regardless
of whether it is actually published. Phases 4B, 6, 7, 8, and 9 (dynamic provisioning,
Vault, matchmaking, ELO, network readiness) are deliberately v2+: they extend Duels
toward Jack's longer-term goal of a custom minigame network built on JCore, but they are
not required for Duels itself to be considered complete. JCore stays Duels-driven -
JCore APIs are not generalized for a hypothetical second minigame until one actually
exists and needs it.

## V1 status: complete

Every phase below the "v1" cutoff is `[x]` done, covered by automated tests, and
manually verified in-game (see `docs/RETEST.md`). There is no known open bug or gap in
the standalone feature set.

| Phase | What it covers |
| --- | --- |
| 0 - Foundation Hardening | Reliability fixes: challenge ordering, state restoration, match end-state, death-screen handling, advancement suppression, durable stats-failure handling, configurable DB pool size |
| 1 - Arena Config vs Runtime Instance | `Arena` template vs `ArenaInstance` runtime split |
| 2 - Arena Bounds | Bounds editing, live particle visualization, boundary modes (warn/soft-return/forfeit) |
| 3 - Arena Instancing | Multiple physical copies per arena template, allocated per match |
| 3B - Arena Reset | Automatic block-change rollback between matches, including explosion debris |
| 4 - Spectator Mode | Watch a live duel without interfering, clean entry/exit on every path |
| 11 - Admin Command/GUI Parity | Every admin action reachable by both GUI and command, in both directions |

Everything from **Phase 4B onward is v2** - not missing from v1, deliberately out of
scope for it. See each phase for its individual status.

---

## Phase 0 - Foundation Hardening `[x]`

Small, low-risk fixes surfaced by the V2 audit. None of these block anything by
themselves, but several become more important once later phases build on top of the
systems they touch, so they're cheapest to fix now while the blast radius is small.

- [x] Hikari pool size is configurable per `database.yml` (`pool-size`, validated with a
      warning and a default of 5 on an invalid value) via `DatabaseConfigLoader` ->
      `DatabaseConfiguration` -> `AbstractDatabase`. Already done; this item was stale.
- [x] Closed the two real problems found while auditing this: `SqlStatsRepository`'s
      async `recordMatch` was swallowing a persistence failure down to a bare "could not
      record match result" line with a stack trace, and there was no equivalent guard for
      `YamlStatsRepository`'s synchronous save throwing - which would have propagated out
      of `MatchManager.endMatch` entirely, skipping player restoration and arena release
      for both participants. Fixed by (1) wrapping the `recordMatch` call in `endMatch`
      in a try/catch so a stats failure can never block the rest of match cleanup, and
      (2) upgrading both backends' failure logging to a structured SEVERE line containing
      every `MatchResult` field, not just the exception, so a lost result can be manually
      recovered from the log. A true durable pending-outcome record (retried automatically
      rather than just logged) remains future work for when Phase 6 (Vault rewards)
      actually needs guaranteed delivery - see that phase's failure handling section.
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
      inventory contents still runs vanilla's normal advancement-trigger checks). The
      first implementation (`MatchListener.onAdvancementDone` revoking awarded criteria
      after the fact) turned out to be the cause of a bug found in the in-game pass:
      `PlayerAdvancementDoneEvent` is not cancellable and fires after the toast/broadcast,
      and because revoking left the underlying trigger still satisfied, the criterion was
      immediately re-awarded - a grant/revoke/grant loop that spammed the player.
      `MatchListener.onAdvancementCriterionGrant` now handles Paper's cancellable
      `PlayerAdvancementCriterionGrantEvent` instead, which fires *before* a criterion is
      granted, so nothing is ever awarded and there is nothing to re-award.
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
- [x] **A player named the same as a `/duel` subcommand (`spectate`, `accept`, `deny`,
      `kit`, `leave`, `top`) could never be challenged via `/duel <name>`.** JCore's
      `CommandManager.findChild` always matches `args[0]` against a registered child
      literal before falling back to the parent's own optional argument, so `/duel
      Spectate` opens the spectate menu instead of challenging a player literally named
      Spectate. This is inherent to matching a literal token against an argument with the
      same string - it isn't fixable by reordering resolution without making the command's
      behaviour depend on who happens to be online, which is worse. Fixed with an
      unambiguous escape hatch instead: `/duel challenge <player>` always means challenge,
      regardless of the name, added as an extra child rather than replacing `/duel
      <player>` - renaming the common path to remove a near-zero-probability collision
      would have made every normal use of the command clunkier to fix an edge case.

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

## Phase 3 - Arena Instancing `[x]`

**Status:** Implemented and tested. `ArenaInstance` (Duels, `arena/ArenaInstance.java`) is
now a real persisted entity - a stable int id, a back-reference to its `arenaId`, and its
own `spawn1`/`spawn2`/`boundsCorner1`/`boundsCorner2`/`hasBounds()`/`isReady()` - rather
than the throwaway per-allocation value object Phase 1 introduced. `Arena` itself shrank
to pure template/policy: name, `BoundaryMode`, `graceSeconds`, disallowed kits, enabled
flag. No behavioural surprises versus the design below; the implementation matches the
"Proposed Architecture" and "Implementation Plan" sections almost exactly, so they are
left as written and this status note only records what's worth knowing on top of them.

Implemented:

- `ArenaInstanceSerializer` + `ArenaInstanceManager` follow the same `YamlRepository`
  pattern as `Arena`/`Kit` (own `arena-instances.yml`, strict `FIELDS` allowlist on the
  serializer - the same defensive-deserialization convention Phase 2 established for
  `ArenaSerializer`).
- `ArenaInstanceMigrator.migrate(...)` runs once at startup, before `ArenaManager` is
  constructed, reading `arenas.yml` raw via `ConfigurationSection`. Any arena still
  carrying an inline `spawn1` (the pre-instancing shape) becomes that arena's first
  registered instance, and the four legacy keys are stripped from `arenas.yml`.
  Idempotent - a server that has already migrated sees no changes on subsequent boots,
  since there is nothing left to convert.
- **"In use" split into two independent concepts, not one, which is the real correction
  this phase made to Phase 1's design.** `ArenaManager`'s old `activeCheck`/`isActive`
  (borrowed from Phase 1, when template and instance were the same object) is gone
  entirely - template mutation (rename, boundary mode, kit permissions) is always safe
  now, because it can no longer collide with "a match is running here." What remains on
  `ArenaManager` is a narrower `hasInstancesCheck` (`IntPredicate`, default `id -> false`)
  used only to block **deleting** a template that still has registered instances -
  deleting the template out from under an instance would leave the instance pointing at
  nothing. Separately, `ArenaInstanceManager` gained its own `activeCheck`, wired to
  `arenaAllocator::isAllocated`, which blocks editing spawns/bounds or deleting a specific
  instance while a match is actually using it. `ArenaMutationResult.Status.IN_USE` is
  reused rather than renamed for the template's narrower meaning, since the type already
  existed and adding a parallel enum for one status would have been the premature
  abstraction CLAUDE.md warns against.
- `StaticArenaAllocator` now depends on both `ArenaManager` and `ArenaInstanceManager`
  and allocates by instance id, matching the "track allocation by instance identity"
  item in the implementation plan below.
- `BoundaryEnforcer` resolves an `Enforcement` record holding the match's `ArenaInstance`
  (for spawns/bounds) plus a separate lookup of its `Arena` (for `boundaryMode`/
  `graceSeconds`) - the mechanical rework flagged below as worth budgeting time for.
- **Admin menu split, following the decision already reached in Phase 1's "GUI over
  commands where reasonable" pattern.** `ArenaDetailMenu` (a template) gained "Instances"
  (opens a new paginated `ArenaInstanceListMenu`) and "Create Instance" buttons. Each
  entry in that list opens a new `ArenaInstanceDetailMenu` - the direct successor to what
  used to be `ArenaDetailMenu`'s spawn/bounds/edit-mode items, now scoped to one instance.
  One implementation constraint worth recording: JCore's `PaginatedMenuBuilder` has no
  mechanism for adding a static item (like "Create Instance") alongside its per-entry
  factory, so "list" and "create" stay split across the paginated list menu and its
  plain-`MenuBuilder` parent - the same split `ArenaMainMenu`/`ArenaListMenu` already use
  for templates themselves.
- **Commands moved to an instance-scoped subtree**, matching the menu split:
  `/duels arena instance <create|delete|list|setspawn|bounds|editmode>`, replacing the
  old template-level `/duels arena <setspawn|bounds|editmode>`. `create`/`list` take an
  `arenaId`; the rest take an `instanceId`.
- `ArenaEditListener`/`ArenaEditManager`/`ArenaEditSession`/`SpectatorManager` all now
  operate on `ArenaInstance` rather than `Arena` - the mechanical rework the design below
  called out as "worth budgeting time for."

**Deviation from the design below:** the "flagged for later" split of `ArenaEditManager`
into a generic session/inventory-safety half and an arena-specific tool-behaviour half
turned out to be unnecessary. `ArenaEditManager`'s tool logic already operated on whatever
object it was handed; retargeting it from `Arena` to `ArenaInstance` was a type change at
the call sites, not a redesign of the class. The split remains a reasonable idea if a
second, meaningfully different edit-mode consumer ever appears, but building it
speculatively for this phase would have been solving a problem instancing didn't actually
have.

Verified via `mvn clean package`: full build succeeds, all 17 tests pass, including
`DuelsIntegrationTest`'s scenarios reworked to create instances and set their spawns/
bounds through `ArenaInstanceManager` instead of constructing `Arena` with inline spawns.

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

**Settled decision: bounds move to the instance, not the template.** With several
physical copies of "Desert" at different coordinates, one pair of world corners on the
`Arena` template is meaningless - each copy needs its own box. The split that falls out
of this: coordinate-dependent data (spawn1, spawn2, boundsCorner1, boundsCorner2) belongs
to `ArenaInstance`; policy that's sensible to share across every copy of a template
(which kits are allowed, `boundaryMode`, `graceSeconds`) stays on `Arena`. An admin sets
bounds once per physical copy, the same "stand in the world and click a tool" motion
already used for the template today - not new math, just the same tool pointed at a
different target.

This means `ArenaInstance` stops being what it is today - a throwaway value object,
freshly constructed with a random UUID on every `allocate()` call - and becomes a real,
persisted, admin-registered entity with its own identity, much like `Arena` and `Kit`
already are. Concretely:

- `ArenaInstance` gains `boundsCorner1`/`boundsCorner2` (and a `hasBounds()` the way
  `Arena` has today), and needs its own `ArenaSerializer`-style persistence and an ID
  that survives a restart - today's random `UUID` minted per allocation call cannot be
  used as a stable identity, since nothing currently reads or persists it.
- `StaticArenaAllocator` changes from "is this `arenaId` in use" (`Set<Integer>`) to
  "is this specific registered `ArenaInstance` in use" (`Set<UUID>`, or equivalent) - the
  identity it tracks moves from the template to the instance, which is the real
  correction this phase makes, not just an addition.
- `BoundaryEnforcer` and `SpectatorManager` both currently read bounds off the `Arena`
  template (`BoundaryEnforcer.java:130/158/260`, `SpectatorManager.java:88`) - both move
  to reading bounds off `match.getArenaInstance()` instead. This is a real, mechanical
  rework of code written in the last two phases, not just new code - worth budgeting time
  for, and it's exactly why settling this now (rather than after Phase 4/2 had gone
  further) matters.
- **Migration:** existing installs already have one `Arena` with spawns and bounds set
  directly on it. On upgrade, that data should become that arena's first registered
  instance automatically, so nobody's working server loses its one configured arena.
  Given the public-release goal, this has to be handled by code, not by a line in a
  changelog telling admins to reconfigure by hand.

**Flag for later (not yet designed):** setting a physical instance's own spawn/bounds is
the same "stand somewhere in the world and click a tool" action as today's `Arena`
edit-mode wand (`arena/ArenaEditManager.java`, `arena/ArenaEditTool.java`), just scoped to
one `ArenaInstance` instead of the `Arena` template. `ArenaEditManager` currently mixes
two responsibilities: generic protected-inventory-session bookkeeping (full hotbar/
offhand save-clear-restore, blocking drop/click/drag/swap/death while editing,
config-driven tool slots via JCore's `SlotResolver`) and arena-specific tool behaviour
(what each of the five tools actually does on click). The session/inventory half is
already resource-agnostic and worth reusing as-is; the tool-behaviour half is not.
Splitting those two responsibilities so a second edit-mode consumer (per-instance
editing) can reuse the session machinery without inheriting `Arena`-specific click logic
is a real design pass to do when this phase starts, not before - the right interface
shape depends on what per-instance editing's tool set actually needs, which doesn't
exist until this phase begins.

### JCore vs Duels

Duels. `ArenaInstance` persistence follows the same `YamlRepository`/serializer pattern
`Arena` and `Kit` already use in JCore - reused, not reinvented.

### Java / Paper Concepts

Nothing conceptually new beyond Phase 1/2 - the main new skill exercised is migrating an
existing persisted shape (one arena's inline spawn/bounds) into a new persisted shape
(a registered instance) without losing data for servers already running Duels.

### Existing Duels Example

Directly extends the `ArenaInstance` concept introduced in Phase 1, and follows the same
persisted-entity-with-a-repository pattern `Arena` and `Kit` already use.

### Real Scenario

A popular arena causes queue backlog because only one copy exists. The admin builds a
second physical copy elsewhere on the map, registers it as a second instance of the same
template, and sets its own spawns and bounds; `MatchManager` now has two claimable
instances instead of needing an entirely separate configured arena with duplicated
kit-permission setup.

### Implementation Plan

1. `[x]` Move `boundsCorner1`/`boundsCorner2`/`hasBounds()` from `Arena` onto
   `ArenaInstance`; give `ArenaInstance` a stable persisted identity and its own
   serializer/repository.
2. `[x]` Write the upgrade path: an existing `Arena`'s inline spawn/bounds becomes its
   first registered instance automatically on load, so no working server loses its arena
   (`ArenaInstanceMigrator`).
3. `[x]` Update `StaticArenaAllocator` to track allocation by instance identity rather
   than by `arenaId`.
4. `[x]` Update `BoundaryEnforcer` and `SpectatorManager` to read bounds from the match's
   `ArenaInstance` instead of the `Arena` template.
5. `[x]` Extend arena admin menu to register/list/remove instances of a template
   (`ArenaInstanceListMenu`, `ArenaInstanceDetailMenu`).
6. `[x]` Resolved differently than planned: the design pass on splitting
   `ArenaEditManager` turned out unnecessary - see the "Deviation" note above. Its tool
   logic was already agnostic to what object it edited, so retargeting it to
   `ArenaInstance` was a type change at the call sites, not a class split.
7. `[x]` Confirmed no other code special-cases "one instance per arena" - `MatchManager`'s
   claim logic from Phase 1 already handled multiple instances per template unchanged.

### Testing Plan

Three simultaneous matches requested against a two-instance arena - two should succeed
immediately, the third should correctly report no free instance. Covered by
`DuelsIntegrationTest.twoInstancesOfTheSameArenaAllowTwoConcurrentMatchesButNotAThird`,
which also confirms the two concurrent matches claim distinct instance ids and that
ending one frees its instance for a subsequent match to reclaim.

### Definition of Done

An admin can register more than one physical instance of the same arena template, and
concurrent matches correctly claim distinct instances. `[x]`

### Rejected (for now): per-match world generation

A third way to get "a copy" was considered and rejected as the baseline: generating a
whole fresh `World` per match (via `WorldCreator`, copying a template world folder) and
deleting it afterward, rather than reusing a small number of physical/schematic copies.

It gives total isolation between matches, but it is meaningfully heavier than the other
two options - creating a `World` is a blocking, disk-I/O-bound operation on the calling
thread, and each loaded world holds real memory for as long as a match runs. It's used
by some large, well-resourced networks, but it is not the common baseline even among
established minigame plugins, and it is the option least likely to "just work" on the
small-server case this project is explicitly designed to support.

It's also worth being precise about what it does and doesn't buy: it does not make
"there's always an arena available" actually true. Any single server has a finite
CPU/memory ceiling on concurrent matches regardless of how a copy is produced; per-match
worlds raise that ceiling at a real resource cost, they don't remove it. Removing the
ceiling is a Phase 9 (network) property - more backend servers behind a proxy - not
something single-server instancing can solve by itself. Revisit this only if a specific
server genuinely needs per-match world isolation and has the resources for it; it should
be possible to add later as a third `ArenaAllocator` implementation without touching
`MatchManager`, exactly like the WorldEdit-based reset strategy below is designed to slot
in without touching match logic.

## Phase 3B - Arena Reset `[x]` (baseline rollback strategy)

**Status:** The mandatory, no-external-dependency baseline described below is implemented,
tested, and verified in-game against the full "## Arena reset (block rollback)" section of
`docs/RETEST.md` - `ArenaResetStrategy`, `BlockChangeRollbackStrategy`, and the
`MatchManager` wiring that runs it between match end and instance release. The optional
`SchematicPasteResetStrategy` and its per-arena strategy selection are **not** built and
remain future work; see the deviations below for why the config surface for choosing a
strategy was deferred rather than added now.

**Deviations from the design below:**
- **Keyed by instance, not by match.** `Match` has no stable id and deliberately does not
  override `equals`/`hashCode` (see Phase 3/4's use of reference identity in
  `getActiveMatches()`), so the change log is keyed by `ArenaInstance` id instead. This is
  actually a better fit than keying by match: only one match can hold a given instance at
  a time, so instance id is a simpler, already-unique key with no lifecycle mismatch.
- **The tracked-change ceiling and per-tick replay batch size are global config
  (`config.yml`), not per-arena.** Raised explicitly rather than silently decided: these
  are internal safety/performance valves, not admin-facing arena behaviour like boundary
  mode or allowed kits, so the project's per-resource-configurability preference was
  judged not to apply here. Revisit if a real case for per-arena tuning shows up (e.g. an
  arena built specifically for large TNT fights wanting a much higher ceiling).
- **`ArenaInstance.contains(Location)` was extracted as a public method**, and
  `BoundaryEnforcer`'s private `isWithinBounds` was refactored to call it instead of
  duplicating the same AABB check - the rollback tracker needed the identical "is this
  location inside the instance's bounds" logic, so a second private copy would have been
  duplication with a concrete reason to avoid it, not a speculative cleanup.
- **`SchematicPasteResetStrategy` and per-arena strategy selection are deferred**, not
  built now. Only one real strategy exists at the moment, so a selection field would have
  nothing meaningful to select between yet - flagged here explicitly rather than added
  silently, per the project's stance on forward-looking config surfaces.
- **Explosions also have their yield zeroed, not just their blocks recorded.** Found in
  the in-game pass: the rollback restored the exploded wall correctly, but the item
  entities the explosion had already spawned were left scattered over the repaired arena.
  Restoring a block says nothing about drops that have become independent entities, so
  `EntityExplodeEvent`/`BlockExplodeEvent#setYield(0f)` prevents them being created at
  all. Yield is per-explosion rather than per-block, so an explosion straddling the bounds
  edge suppresses drops for its outside-bounds blocks too - preferred over leaving debris
  inside the arena.
- Verified with `mvn clean package`: 22 tests pass, including
  `DuelsIntegrationTest.blockChangesDuringAMatchAreRolledBackAfterItEnds`, which breaks a
  block mid-match, ends the match, advances one tick, and confirms both that the block is
  restored and that the restore happens before/as part of `endMatch` returning the
  instance to the allocator, and
  `explosionsInsideTheBoundsAreRolledBackAndDropNothing`, which checks the yield is zeroed
  inside the bounds and left alone outside them.

**Note on numbering:** this phase was added after Phase 4 (Spectator Mode) had already
been implemented and numbered, while discussing what Phase 3 (Arena Instancing) actually
requires. It sits here because it depends on Phase 3 and is a prerequisite for reusing
an instance for destructive kits, not because the file is renumbered - later phase
numbers are unchanged.

### Problem / Opportunity

Kits are allowed to contain TNT, lava, and other block-destructive items. Once the same
arena instance is reused for many matches back to back (the entire point of Phase 3),
whatever damage the previous match left behind carries into the next one unless
something actively resets it between matches.

### Current Behaviour

Nothing resets arena terrain today. `MatchManager` releases an `ArenaInstance` back to
the allocator at match end (`endMatch`/`abortMatch`) with no cleanup step in between, so
a destructive kit's damage is permanent unless an admin manually repairs it in-world.

### Desired Behaviour

Between a match ending and its instance becoming claimable again, any block changes made
during that match are undone, without needing the server owner to install anything extra
to get a working baseline.

### Why This Point In The Roadmap

Directly follows Phase 3: reset only matters once an instance is reused for more than
one match, which is the entire premise of instancing. It is written up as its own item
rather than folded into Phase 3 because "how many copies exist" and "how a copy gets
cleaned between uses" are independent design questions with independent failure modes -
see the Proposed Architecture note below on why they're kept as separate strategy
interfaces rather than one system.

### Proposed Architecture

Two independent strategies behind one seam, mirroring how `ArenaAllocator` already
separates "how MatchManager gets an instance" from what `MatchManager` itself does:

- **`ArenaResetStrategy` interface** - one method, roughly
  `void reset(ArenaInstance instance, List<BlockChange> changes)` (or the tracker owns
  replay and this just receives the accumulated log - exact shape is an implementation
  decision, not an architectural one). `MatchManager` calls it once per ended match,
  after gameplay is fully over and before the instance is released back to the
  allocator, so a freshly-reset instance is what the next claimant actually gets.
- **`BlockChangeRollbackStrategy`** (always available, default). A `BlockPlaceEvent`/
  `BlockBreakEvent`/`EntityExplodeEvent` listener records `(location, previousBlockData)`
  for any change inside a match's active bounds while it is `IN_PROGRESS`, keyed by
  match. At reset time, changes are replayed in reverse order, restoring blocks to what
  they were. This needs no external plugin and works on every server, matching the
  project's public-release constraint - but it is not correctness-for-free:
  - Bukkit block mutation is main-thread-only, so replaying a large change list (a TNT
    kit detonating in the open, say) cannot happen in a single tick without a visible
    stutter. The replay has to be spread across several ticks - restore a bounded batch
    of blocks per tick via JCore's task/scheduler utilities, not the whole list at once.
  - The change log for one match needs a hard ceiling (config-driven) so an unusually
    chaotic match can't grow it unboundedly; past the ceiling, the arena degrades to
    "mostly restored" rather than tracking forever, which is an acceptable trade for a
    duel arena.
- **`SchematicPasteResetStrategy`** (optional, present only if WorldEdit/FAWE is on the
  classpath). Pastes a saved snapshot of the instance's original state back over its
  region. Much cheaper per match than rollback for large changes, since it's one paste
  operation instead of replaying thousands of individual block edits - but it is a real
  external dependency, so it can never be the only implementation Duels ships with.
  `plugin.yml` uses `softdepend: [WorldEdit]`, not `depend:` - Paper loads WorldEdit
  first if present so Duels can detect and use it, but Duels still enables normally
  without it. Which strategy an arena actually uses is a per-arena config choice (see
  the project's standing per-resource-configurability preference), defaulting to
  rollback, so an admin who happens to have FAWE installed can opt an arena into the
  faster strategy without it being required anywhere else.

### JCore vs Duels

Duels. `ArenaResetStrategy` and its implementations are domain-specific to what "reset"
means for a duel arena. JCore's task/scheduler utilities are the reusable part (spreading
work across ticks is not Duels-specific), and are consumed rather than duplicated.

### Java / Paper Concepts

- `BlockPlaceEvent`, `BlockBreakEvent`, `EntityExplodeEvent`, `BlockData` (capturing
  enough state to restore a block exactly, not just its `Material`).
- Main-thread-only world mutation, and spreading bounded work across ticks instead of
  doing it all at once - the same constraint `BoundaryEnforcer` and `MatchManager`
  already respect elsewhere.
- `softdepend` vs `depend` in `plugin.yml`, and detecting an optional plugin's classes
  safely at runtime (e.g. checking `getServer().getPluginManager().getPlugin("WorldEdit")`
  before ever referencing a WorldEdit type, so the class loader never even attempts to
  resolve WorldEdit types when it isn't installed).

### Existing Duels Example

Same shape as `ArenaAllocator`: an interface `MatchManager` depends on, with more than
one implementation and a config-driven choice of which one an arena uses - not a new
pattern, a second application of one already proven in this codebase.

### Real Scenario

An arena's kit includes TNT. A match plays out, a wall gets blown open. The match ends,
`MatchManager` calls the arena's configured reset strategy before releasing the instance,
and by the time the next match claims that instance, the wall is back.

### Implementation Plan

1. `[x]` Define `ArenaResetStrategy` and wire `MatchManager` to call it between match end
   and instance release.
2. `[x]` Implement `BlockChangeRollbackStrategy`: track changes during `IN_PROGRESS`, cap
   the tracked count per instance, replay in reverse spread across ticks.
3. `[ ]` Implement `SchematicPasteResetStrategy` behind a WorldEdit/FAWE presence check;
   `softdepend` in `plugin.yml`. Deferred - no second strategy exists yet to justify it.
4. `[ ]` Per-arena config option choosing a strategy, defaulting to rollback. Deferred
   alongside item 3; currently there is only one strategy, applied globally.
5. `[ ]` Admin tooling/menu entry to save an arena's "clean" snapshot for the schematic
   strategy to paste back. Deferred alongside item 3.

### Testing Plan

A match that places and breaks blocks inside arena bounds should have every one of those
changes reverted after the match ends and before the instance is claimable again -
covered by `DuelsIntegrationTest.blockChangesDuringAMatchAreRolledBackAfterItEnds`. A
change made *outside* arena bounds during the match should not be touched - guaranteed by
`ArenaInstance.contains`, the same bounds check `BoundaryEnforcer` uses to decide who is
inside the arena. A change count past the configured ceiling should still restore up to
the ceiling rather than throwing - covered by
`blockChangeTrackingStopsAtTheConfiguredCeilingPerInstance`, which tracks one more change
than the configured maximum and confirms every change up to the ceiling is restored while
the one past it is correctly left untouched. Reset must not run twice for the same
instance - covered by `resettingAnInstanceTwiceDoesNothingTheSecondTime`, which resets an
instance, then resets it again and confirms the second call's completion callback fires
immediately with nothing left to replay.

### Definition of Done

A destructive kit's damage is gone from an instance before it can be claimed for another
match, using only built-in mechanics by default. `[x]` for the baseline. WorldEdit/FAWE as
an opt-in faster path remains future work, not required for this phase to be considered
functionally complete for a standalone server.

## Phase 4 - Spectator Mode `[x]`

**Status:** `[x]` Complete. Covered by integration tests and verified in-game against the
full "## Spectator mode" section of `docs/RETEST.md`.

**Deviations from the design as written below:**

- **Disconnect and shutdown detach rather than restore.** The plan said a quitting
  spectator is restored immediately. In the code, `SpectatorManager.detach` drops the
  live session but deliberately keeps the persisted row, so the join handler puts them
  back next time. Teleporting a player who is already leaving is unreliable, and this
  reuses the crash-recovery path that has to exist and be tested anyway. It is the same
  trade `MatchManager.shutdown(preservePlayerStates)` already makes.
- **`BoundaryEnforcer` did get a small context type after all.** The design said no
  `BoundaryContext` abstraction would be extracted. The spectator mode-override has to
  apply in both `handleMove` and `tickPlayer`, so a private nested `Enforcement` record
  plus one `resolve()` method beat branching identically in two places. It is private to
  `BoundaryEnforcer`, not a public abstraction.
- **`NO_BOUNDS` refuses instead of falling back.** A spectator free to fly anywhere could
  drift into a neighbouring arena while the plugin still believed they were watching this
  one, so an arena without bounds simply cannot be spectated.

**Product decisions (settled):**

- **Both entry points.** A `/duel spectate <player>` command *and* a GUI listing live
  matches. The command is what experienced players use; the GUI is what makes the feature
  discoverable for everyone else.
- **No privacy or refusal model.** Anybody may spectate any match. There is no per-player
  "no spectators" toggle and no staff bypass permission, because there is nothing to
  bypass. See "Rejected: a privacy model" below for why, and for the condition under which
  we would revisit it.

### Problem / Opportunity

No spectator concept exists anywhere in the codebase today - not in `Match`, not in
`MatchManager`, not in `MatchListener`'s player-scoping logic. A player who wants to watch
a friend's duel has no supported way to do it. The unsupported way - walking to the arena
in survival mode - is actively bad: they are a third party inside a live fight, and the
interference guards in `MatchListener` exist precisely because that situation is a
problem.

The opportunity is larger than "watching". A spectator concept is the same primitive that
later supports a post-match "watch the rest of the round" flow, tournament/staff viewing,
and (much later, Phase 9+) cross-server viewing of a match happening elsewhere.

### Current Behaviour

`MatchManager` owns `Map<UUID, Match> matches`, keyed by **participant** UUID, with both
combatants pointing at the same `Match` object. `MatchManager.getMatch(uuid)` is the
single question every listener asks, and the answer means "this player is a combatant in
this match".

That one map drives all of the following in `MatchListener`:

- `onEntityDamage` - damage to a player in a match is cancelled unless it came from their
  opponent, and lethal damage ends the match.
- `onPlayerDeath` - clears drops and ends the match.
- `onPlayerQuitWhileInMatch` - **quitting forfeits the match**.
- `onAdvancementDone` - advancements are revoked.
- `shouldBlockEffect` - potion/area-effect-cloud effects are blocked for anyone who is not
  the source or their opponent.
- `BoundaryEnforcer.handleMove` - bounds are enforced against players whose match is
  `IN_PROGRESS`.

`PlayerStateManager` snapshots a combatant's full state (inventory, armour, health, food,
location, gamemode) to `playerstates.yml` at match start and restores it at match end,
surviving a crash because it is persisted rather than held in memory.

There is no way to enumerate live matches. `matches.values()` contains every match twice
(once per participant); the only place that is dealt with is `MatchManager.shutdown`,
which wraps it in `new HashSet<>(...)`. `Match` also has no start timestamp, so "how long
has this been running" is currently unanswerable.

### Desired Behaviour

A player runs `/duel spectate Alice`, or opens the live-match GUI and clicks a match. They
are put into `GameMode.SPECTATOR`, teleported inside the arena, and constrained to the
arena's bounds. They cannot affect the fight in any way. `/duel leave` (or the match
ending) puts them back exactly where they were, in the gamemode they were in.

This is how spectating works on essentially every practice/duels server: spectator
gamemode, constrained to the arena, invisible and inert to the combatants, and ejected
automatically when the match ends. Deviating from that would surprise players for no gain.

Two behaviours are worth being explicit about because they are easy to get wrong:

- A spectator disconnecting must have **no effect on the match**. Today, disconnecting
  while `getMatch()` returns non-null is a forfeit.
- A spectator must be ejected on **every** way a match can end, including server shutdown
  and a crash. Being left stranded in `SPECTATOR` in an empty arena after a restart is the
  worst possible failure here, because the player cannot fix it themselves.

### Why This Point In The Roadmap

- It **depends on Phase 2 (bounds)**, which is now complete. Without bounds there is no
  answer to "where is a spectator allowed to be", and the fallback - letting them fly
  anywhere - means they can fly into the next arena over and watch a different match while
  the plugin still believes they are watching this one.
- It **depends on the Phase 0 `endMatch` fix**, also complete. `endMatch` now transitions
  the match to `ENDED` before doing any restoration work, so spectator ejection has a
  single reliable place to hook and `getState()` is trustworthy for a match that has
  finished.
- It is **not blocked by Phase 3 (instancing)**. The roadmap's earlier note suggested
  instancing should come first so "this match" is unambiguous. That turned out to be
  backwards: `ArenaInstance` already exists from Phase 1 and a `Match` already holds
  exactly one, so identifying a match is unambiguous today. Phase 3 changes how many
  instances exist per template, not how a spectator names one.
- It makes Phase 8 (ranked) considerably more attractive - ranked matches people can watch
  are worth far more than ranked matches they cannot.

### Proposed Architecture

#### State ownership

`SpectatorManager` (Duels, `spectator/SpectatorManager.java`) owns
`Map<UUID, SpectatorSession>` as the **single source of truth** for who is spectating
what. This is the same self-tracking-map pattern as `StaticArenaAllocator` (Phase 1) and
`ArenaEditManager` (Phase 2).

`SpectatorSession` is a small holder:

```text
spectatorId      UUID
match            Match      (runtime only - never persisted)
arenaId          int        (persisted, so a restored session is still diagnosable)
returnLocation   Location   (persisted)
previousGameMode GameMode   (persisted)
previousFlying   boolean    (persisted)
```

**The critical constraint: spectators must not go into `MatchManager.matches`.** That map
means "combatant". Putting a spectator in it would make every listener above treat them as
a fighter - most seriously, `onPlayerQuitWhileInMatch` would award the match to a
combatant because a *spectator* disconnected. `MatchManager.getMatch(uuid)` keeps its
current meaning, and `SpectatorManager.getSession(uuid)` is a separate question.

Why not put `Set<UUID> spectators` on `Match` itself? Two reasons. First, `Match` is
currently a pure state/data object - it has no manager behaviour and is constructed
directly in tests - and lifecycle work (teleporting, gamemode changes, persistence) does
not belong on it. Second, a set on `Match` still does not answer "which match is this
player spectating" without scanning every match, so we would need the `UUID -> session`
map anyway, and then two structures would need keeping in sync. One map, plus a filtered
stream for the rarer "who is watching this match" query, is simpler. At realistic scale
(single-digit concurrent matches, single-digit spectators each) the stream costs nothing,
and the hot path - `isSpectating(uuid)` inside event handlers - is a single map lookup.

#### Who is allowed to change it

Only `SpectatorManager`. `start(Player, Match)`, `stop(UUID)` and `stopAll(Match)` are the
whole API. `MatchManager` calls `stopAll` and never touches the map directly, the same way
it calls into `PlayerStateManager` rather than manipulating saved state itself.

#### Lifecycle

```text
/duel spectate <player>  OR  GUI click
  -> validate: target online, target is in a non-ENDED match,
     requester is not themselves in a match, requester is not already
     spectating that same match, arena has bounds
  -> capture and PERSIST session (gamemode, flying, return location)
  -> setGameMode(SPECTATOR)
  -> teleport into the arena
  -> BoundaryEnforcer now constrains them
  ...
  -> ejection, via ANY of:
       match ends normally          (MatchManager.endMatch)
       match aborted/server stop    (MatchManager.abortMatch)
       spectator runs /duel leave
       spectator disconnects
       plugin disable               (Duels.onDisable)
  -> restore gamemode + flying + location, delete persisted session
```

#### Failure handling and persistence

The reason the session is **persisted** rather than held in memory is the crash case. On a
crash neither `PlayerQuitEvent` nor `onDisable` runs, so nothing restores the player -
they log back in flying through blocks in `SPECTATOR` in an arena, with no command to fix
it. Persisting the session and restoring it on `PlayerJoinEvent` makes that recoverable,
and it is exactly why `PlayerStateManager` is a YAML file rather than a `HashMap`.

It should **not** reuse `PlayerStateManager`, for the same reason `ArenaEditSession`
deliberately did not (Phase 2): that system captures and restores a combatant's entire
inventory/armour/health/effects snapshot, which is far more than a spectator needs, and it
holds one slot per player - so sharing it creates a class of bug where one subsystem's
snapshot clobbers another's. A separate `spectators.yml` with the five fields above is
smaller, independent, and cannot collide.

Failure modes and their handling:

- **Return location's world was unloaded.** The same problem `PlayerStateManager.restore`
  already solves: check `Bukkit.getWorlds().contains(...)`, and if it is gone, discard the
  location but still restore the gamemode and send the player to the main world spawn.
  Restoring gamemode matters more than restoring position - a wrong position is an
  inconvenience, a stuck gamemode is a support ticket.
- **Teleport out fails.** `Player#teleport` returns a `boolean`. Set the gamemode first,
  and only delete the persisted session once the teleport has succeeded, so a failed
  ejection is retried on next join instead of being silently forgotten. This is the same
  clear-only-after-confirm ordering the `BoundaryEnforcer` soft-return fix needed.
- **Server stop.** Do not attempt to restore during shutdown - teleports late in shutdown
  are unreliable. Leave the persisted session in place and let the join handler restore it
  next boot. This mirrors `MatchManager.shutdown(preservePlayerStates)`, which already
  chooses "preserve and restore later" over "restore now" when the server is stopping.
- **The match ends while the GUI is open.** The click handler must re-resolve and
  re-validate the match rather than trusting the clicked item, since the item was rendered
  from a snapshot taken when the menu opened. Stale menu state is a general hazard with
  this menu system.

#### Synchronous vs asynchronous

All of it is synchronous main-thread work. Gamemode changes, teleports and menu work are
main-thread-only in Paper, and nothing here needs the database. The only async component
would be if the GUI wanted per-player stats in its lore, which it does not need.

#### Boundary enforcement for spectators

`BoundaryEnforcer` becomes its second caller - and this is the payoff for having extracted
it in Phase 2 rather than leaving the logic inline in `MatchListener`.

Its current shape resolves everything from `matchManager.getMatch(uuid)` and requires
`IN_PROGRESS`. Spectators differ in two ways:

1. They should be constrained during `PREGAME` and `GRACE` too, not just `IN_PROGRESS`.
2. The arena's configured `BoundaryMode` must **not** apply to them. `FORFEIT` is
   meaningless for someone who is not fighting, and `WARNING` would let them wander off.
   Spectators are always soft-returned with zero grace.

The minimal change: where `handleMove`/`tickPlayer` currently give up because
`getMatch(uuid)` is null, ask `SpectatorManager` instead, and treat a spectator as
`SOFT_RETURN` with `graceSeconds == 0` regardless of arena config. Their fallback safe
location is the arena's spawn point rather than the combatant `lastInBoundsLocation` path.

I am deliberately **not** extracting a `BoundaryContext` abstraction (arena + mode + grace
+ fallback, resolved per player) yet, even though that is clearly where this goes if a
third category ever appears. Two callers with one `if` is not yet a pattern worth naming -
flagging it as the obvious future refactor, not doing it now.

**Capability worth knowing about:** `PlayerStartSpectatingEntityEvent` (Paper, in
`com.destroystokyo.paper.event.player` - not Bukkit). In spectator gamemode,
left-clicking an entity locks the camera to it and the player's position follows that
entity. A combatant who gets knocked out of the arena would drag a camera-locked
spectator with them, bypassing move-event enforcement. Handling this cleanly means
cancelling the event unless the target is one of the two combatants - which is also a
nice feature, since "click a fighter to follow them" is genuinely good spectating UX.

#### What the interference work actually is

The earlier stub said `MatchListener` needs "a third category" in its interference checks.
Having traced it, that is mostly **not** true, and the truth is closer to the reverse.

`GameMode.SPECTATOR` already makes a player unable to deal or take damage, unable to
interact with blocks or inventories, non-collidable, and invisible to non-spectators. The
server does the work. So the interference guards need almost nothing added - what they
need is to **keep ignoring spectators**, which they do for free as long as spectators stay
out of `MatchManager.matches`.

The genuinely needed changes are narrow:

- `shouldBlockEffect` - add an explicit spectator exclusion as defence in depth.
  Spectators should not be candidates for potion or cloud effects at all.
- `onPlayerQuitWhileInMatch` - add a `spectatorManager.stop(uuid)` call alongside the
  existing `boundaryEnforcer.forget(uuid)`. That handler already runs for every quit and
  early-returns when there is no match, so it is the right place.
- Nothing needs to change in `onEntityDamage`, `onPlayerDeath` or `onAdvancementDone`.

This is a good example of why tracing the flow beats designing from the description: the
stub's plan would have added a category the gamemode already provides.

#### Enumerating live matches

The GUI needs something that does not exist: a list of running matches.

`MatchManager` gains `Collection<Match> getActiveMatches()` returning deduplicated,
non-`ENDED` matches - `new HashSet<>(matches.values())` filtered on state, which is
already the idiom `shutdown()` uses. `Match` has no `equals` override, so `HashSet` uses
identity, which is exactly what is wanted here.

`Match` also gains a `startedAt` timestamp (set in the constructor) so the GUI can show a
duration. `MatchResult` already captures an end timestamp, so this is a small consistent
addition rather than a new concept.

### JCore vs Duels

**Duels, entirely.** Spectating is a domain concept of a match - who may watch, when they
are ejected, and where they are allowed to stand are all Duels rules.

The one thing worth watching for: if a later plugin needs "put this player in spectator
mode, remember what they were, put them back safely, survive a restart", *that* is a
reusable primitive and would belong in JCore. But one caller is not evidence of
reusability, and generalising it now would mean designing a JCore API against a guess.
Build it in Duels; extract only if a second plugin genuinely needs it.

### Java / Paper Concepts

- **`GameMode.SPECTATOR` semantics** - what the server enforces for free (no damage dealt
  or taken, no interaction, non-collidable, invisible to non-spectators, can fly through
  blocks) versus what it does not (no position constraint, camera-locking to entities).
- **Map keys as meaning.** `MatchManager.matches` is not just a cache; membership *is* the
  definition of "combatant". Recognising that a lookup carries semantics is what makes the
  "do not put spectators in that map" constraint obvious rather than arbitrary.
- **Persistence as crash recovery**, not as storage. Nothing needs the spectator session
  after the process ends *except* the case where the process ended unexpectedly.
- **`Player#teleport` returns `boolean`** and can fail (cancelled by another plugin,
  unloadable chunk) - hence ordering cleanup after confirmed success rather than before.
- **Identity vs equality in collections** - `HashSet<Match>` deduplicating by reference
  because `Match` does not override `equals`/`hashCode`.
- **Stale GUI state** - a menu is a snapshot rendered at open time, so click handlers must
  re-validate.
- **Event cancellation as a constraint mechanism** - `PlayerStartSpectatingEntityEvent`.

### Existing Duels Example

Almost every piece has a direct precedent in the codebase:

- **Session manager owning a map:** `ArenaEditManager` + `ArenaEditSession` - per-player
  session, started explicitly, ended on quit and in `onDisable`, deliberately lighter than
  `PlayerStateManager`. `SpectatorManager` is the same shape plus persistence.
- **Persisted capture/restore with fail-forward:** `PlayerStateManager.save`/`restore`,
  including the unloaded-world guard and the "leave the snapshot in place if restoration
  failed" behaviour added in Phase 0.
- **Preserve instead of restore on shutdown:**
  `MatchManager.shutdown(preservePlayerStates)` and `abortForServerStop`.
- **Clear-only-after-confirm ordering:** the `BoundaryEnforcer` soft-return fix, where
  clearing state before the teleport succeeded handed the player a fresh grace period on
  every failed attempt.
- **Paginated list menu over a domain object:** `LeaderboardMenu` - `menus.paginatedMenu`
  with a key in `menus.yml`, an `itemFactory` building player heads, and an `onClick`.
  `SpectateMenu` is structurally the same but synchronous, since live matches are in
  memory and need no database round trip.
- **Subcommand registration:** `DuelCommand.build()`'s `CommandBuilder` children, exactly
  as `/duel top` is wired to `LeaderboardMenu`.

### Real Scenario

Alice and Bob are duelling in the "Colosseum" arena, which has `BoundaryMode.FORFEIT` with
a 3-second grace period. Charlie runs `/duel spectate Alice`.

`SpectatorManager.start` captures that Charlie was in `SURVIVAL` at the lobby spawn, writes
that to `spectators.yml`, sets him to `SPECTATOR`, and teleports him to the Colosseum
instance's spawn 1. `BoundaryEnforcer` now sees Charlie as a spectator of that match and
constrains him to the Colosseum bounds - soft-returned instantly if he drifts out, *not*
forfeited, because the arena's `FORFEIT` mode applies to combatants only.

Charlie left-clicks Alice to follow her camera. `PlayerStartSpectatingEntityEvent` allows
it, because Alice is a combatant in the match Charlie is watching. He then tries to click a
chicken outside the arena; that is cancelled.

Alice wins. `MatchManager.endMatch` transitions the match to `ENDED` and calls
`spectatorManager.stopAll(match)`. Charlie is set back to `SURVIVAL` at the lobby spawn and
his row in `spectators.yml` is deleted. Meanwhile Bob, who died, goes through the existing
respawn/restore flow untouched.

Alternative ending: the server crashes mid-duel. Nothing ran. Charlie logs in still in
`SPECTATOR` inside the Colosseum - and `PlayerJoinEvent` finds his persisted session,
restores `SURVIVAL` and the lobby spawn, and deletes the row.

### Implementation Plan

Each step should compile and be independently testable.

1. **`Match` gains `startedAt`** (`System.currentTimeMillis()` in the constructor, plus a
   getter). Trivial, and unblocks the GUI's duration display.
2. **`MatchManager.getActiveMatches()`** - deduplicated, non-`ENDED`. Add the integration
   test that two concurrent matches yield exactly two entries, not four.
3. **`SpectatorSession`** - the data holder, plus its serialization and `spectators.yml`
   read/write. No behaviour yet.
4. **`SpectatorManager`** - `start`, `stop`, `stopAll(Match)`, `getSession`,
   `isSpectating`, `getSpectators(Match)`, `restoreOnJoin(Player)`, `shutdown()`. All
   validation lives here, not in the command, so the command and the GUI cannot disagree
   about the rules.
5. **Wire lifecycle hooks:**
   - `Duels.initializeManagers()` - construct after `MatchManager` (it reads it), the same
     ordering constraint `BoundaryEnforcer` has.
   - `MatchManager.endMatch` and `abortMatch` - `stopAll(match)` after the `ENDED`
     transition. `abortMatch` must pass through its `preservePlayerStates` flag so a
     server stop preserves rather than restores.
   - `MatchListener.onPlayerQuitWhileInMatch` - `stop(uuid)` next to the existing
     `boundaryEnforcer.forget(uuid)`.
   - The join listener - `restoreOnJoin`.
   - `Duels.onDisable` - `shutdown()`.
6. **`BoundaryEnforcer` spectator support** - fall through to `SpectatorManager` when
   `getMatch` is null; force soft-return with zero grace; arena spawn as fallback.
7. **`PlayerStartSpectatingEntityEvent` handler** - allow only the two combatants of the
   match being spectated.
8. **`shouldBlockEffect`** - explicit spectator exclusion.
9. **Command surface** - `/duel spectate [player]` (no argument opens the GUI) and
   `/duel leave`, permission `duels.spectate`, plus the `duel.help` entries. Note that
   adding help lines means the documented `help:`-block deletion caveat in `messages.yml`
   applies - existing installs will not see the new lines until they delete the block.
10. **`SpectateMenu`** - `PaginatedMenu<Match>` over `getActiveMatches()`, keyed
    `spectate-matches` in `menus.yml`, entries showing both combatants, arena name, state
    and duration; the click handler re-validates, then delegates to
    `SpectatorManager.start`.
11. **New messages** - now spectating, stopped spectating, not spectating, target not in a
    match, cannot spectate while in a match, arena has no bounds configured, no live
    matches.
12. **Docs** - `ARCHITECTURE.md` gets the ownership rule ("`MatchManager.matches` means
    combatant; spectators live in `SpectatorManager`"), since that is the invariant most
    likely to be violated by future code.

### Testing Plan

Integration tests (MockBukkit, in `DuelsIntegrationTest`):

- **A spectator disconnecting does not end the match.** The single most important test -
  it is exactly the bug that occurs if spectators leak into `MatchManager.matches`. Assert
  the match is still live and no stats were recorded.
- **`endMatch` ejects every spectator**, restoring gamemode and location, and leaves
  `spectators.yml` empty.
- **A combatant disconnecting still forfeits** while a spectator is attached - the existing
  behaviour must be unchanged.
- **`getActiveMatches()` deduplicates** - two matches, two entries.
- **A persisted session survives a reload** - write a session, re-load the plugin, join,
  and assert the gamemode and location were restored and the file row deleted.
- **Unloaded return world** - the gamemode is still restored, the location falls back, the
  row is deleted.
- **Cannot spectate while in a match**, and **cannot spectate an `ENDED` match**.

Manual in-game tests (the things MockBukkit cannot meaningfully cover):

- A spectator drifting out of bounds is soft-returned instantly and is *not* forfeited, in
  an arena configured `FORFEIT`.
- Combatants cannot see, hit or be blocked by the spectator; splash potions do not affect
  them.
- Camera-lock onto a combatant works; camera-lock onto anything else is refused.
- `/stop` mid-spectate, then reboot: the spectator is restored on join.
- Kill the server process mid-spectate (the crash path), then reboot: the same.
- Open the GUI, have the match end while it is open, then click the entry - a clean
  refusal message, not a broken state.

### Definition Of Done

- A player can start spectating via both the command and the GUI, and stop via
  `/duel leave`.
- Spectators are constrained to arena bounds, always soft-returned, never forfeited.
- Spectators cannot influence a match in any way, and their disconnecting cannot affect it.
- Ejection is verified on all six exit paths: normal end, abort, combatant quit, spectator
  quit, plugin disable, and crash-then-rejoin.
- No spectator ever appears in `MatchManager.matches`, and there is a test that fails if
  one does.
- `spectators.yml` is empty whenever nobody is spectating - no leaked rows.
- Messages and `menus.yml` entries exist for everything, and `ARCHITECTURE.md` records the
  ownership invariant.

### Rejected: a privacy model

Considered and deliberately rejected: letting combatants refuse spectators, via a
per-player toggle plus a staff bypass permission.

Rejected because it is far more surface than it appears - a persisted per-player
preference, a toggle command, a permission node, a decision about whose preference wins
when the two combatants disagree, and a refusal path in both the command and the GUI - in
exchange for solving a problem that does not exist on a server where duels happen in the
open anyway. Anyone can already walk to the arena and look.

Revisit if Phase 8 (ranked/MMR) lands and stream-sniping or scouting becomes a real
competitive complaint. At that point the requirement would be better informed, and would
likely be "hide spectators' identities" or "delay the view" rather than a simple refusal
flag.

## Phase 4B - Dynamic Arena Provisioning `[~]` (v2)

**Status:** `[~]` Everything is implemented, and the ordered manual acceptance
suite has been run and signed off (`docs/RETEST_PLAN.md`, section B10). The phase
is held open only by the three residuals below, all of which are verification
rather than build work. Note that true per-instance *worlds* for dynamic arenas
are **not** part of this phase - that is a separate deferred idea, and 4B is not
waiting on it.

The complete implementation architecture, lifecycle, persistence model, failure
handling, UX, incremental build order, and test plan are defined in
`docs/PHASE_4B_DESIGN.md`. That document is authoritative for Phase 4B details; this
section remains the compact roadmap summary.

**Note on numbering:** like 3B, this sits here because of when it was scoped (after
Phase 4/Spectator Mode had already been implemented and numbered), not because the file
is renumbered. It depends on Phase 3 (Arena Instancing) and Phase 3B (Arena Reset), and
is v2 scope per this document's "Scope target" note above - it is not required for Duels
to be considered complete, but it is the next thing to build once the standalone feature
set is finished.

### Problem / Opportunity

Every `ArenaInstance` today is a physical space an admin manually built in the world and
registered with absolute coordinates (`ArenaInstanceManager`, `StaticArenaAllocator`).
Supporting more concurrent matches of a given arena means an admin building more physical
copies by hand. That doesn't fit the model of a real minigame network, where a player
picks an arena theme and the server produces an instance on demand rather than requiring
every copy to already exist.

### Desired Behaviour

An arena template stores *what to build*, not *where it already is*: a saved structure
plus spawn points/bounds expressed as offsets from the structure's origin. When a match
needs an instance and none is free, the system finds an empty slot, pastes the structure
there, computes real spawn/bounds from the offsets, and registers a normal
`ArenaInstance` against that pasted copy - reusing everything Phase 3/3B already built
(allocation, bounds tracking, reset) rather than replacing it.

### Proposed Architecture

- **Per-arena choice, not a global switch.** `StaticArenaAllocator` (hand-built,
  pre-registered instances) and a new dynamic allocator sit side by side behind the same
  allocation interface `MatchManager` already calls through. Which one an arena template
  uses is a per-arena setting. A standalone/friends server that only uses static arenas
  never touches the dynamic code path at all - no void world is created, no structures
  are loaded.
- **Dedicated void world(s), not carved space in existing worlds.** A purpose-built empty
  world divided into a grid of fixed-size slots far enough apart not to overlap. Chosen
  over reserving regions in normal worlds because it is trivial to bulk-reset a slot and
  guarantees nothing unrelated is nearby.
- **Baseline uses Paper/vanilla's built-in Structure API** (`Bukkit.getStructureManager()`,
  `org.bukkit.structure.Structure`) to save and paste the template, not WorldEdit/FAWE.
  This keeps the feature dependency-free for the baseline, consistent with the standing
  rule that Duels must not hard-depend on external plugins; WorldEdit/FAWE remains an
  optional, later, faster/richer path for admins who have it, including import of
  existing `.schem` arena files, mirroring how `SchematicPasteResetStrategy` was already
  scoped as optional.
- On match end, a slot is cleared (or simply overwritten by the next paste) and returned
  to the free pool.

### Implementation progress

1. `[x]` Template metadata, strict serializers, and backwards-compatible static
   defaults. Templates use relative spawns/bounds and versioned Duels-owned files.
2. `[x]` Admin edit-mode capture, Paper NBT save/reopen verification, template
   inspection/clearing, and guarded provisioning-mode commands.
3. `[x]` Lazy persistent grid layout plus a UUID-verified Duels void world. Static-only
   installations do not create either.
4. `[x]` Persisted manual/provisioned instance origin, slot/revision/size metadata,
   and non-allocatable recovery states.
5. `[x]` Asynchronous existing-instance-first allocation and chunk-backed provisioning.
   `MatchManager` owns pending players and commits no player changes before success.
6. `[x]` Challenge selection terms and `/duel challenge <player> <arenaId>`; acceptance
   claims rather than prematurely consumes a delayed challenge.
7. `[x]` Dirty/provisioning startup rebuild, bounded retirement cleanup, and failed
   instance retry command.
8. `[x]` Add player arena-selection GUI and admin menu controls for mode, capture
   corners/template, dynamic health/retry, and safe retirement. Structure preview
   no longer depends on gameplay bounds; mode activation checks the captured
   template rather than requiring the arena to already be dynamic.
9. `[~]` The ordered target-Paper in-game, capacity and restart suite has been run
   and signed off in `docs/RETEST_PLAN.md` (B10), which superseded the removed
   `IN_GAME_TEST_PLAN.md`. Three residuals remain before sign-off, listed under
   "Remaining before 4B sign-off" below.
10. `[x]` Exclusive per-arena type: STATIC uses only hand-built playable copies;
    DYNAMIC uses one non-playable source build and generated playable copies
    only. Existing one-copy static arenas have explicit guarded conversion;
    legacy dynamic sources migrate safely. Type-specific GUI and suite updated.

### Remaining before 4B sign-off

Run as Part D of `docs/RETEST_PLAN.md`.

1. **The SQL stats path has never been exercised in game.** B10 records this as
   `BLOCKED` because the test server was switched to YAML storage and no external
   MySQL instance was available. That reason does not actually apply: the shipped
   default is `stats-storage: SQL` with `type: SQLITE`, which needs no external
   server at all, so the whole `SqlStatsRepository` path including its migrations
   can be verified as-is. This is also on Phase 5's critical path, since Phase 5 is
   entirely SQL query work.
2. **Post-flow cleanup has never been positively checked.** No projectiles, dropped
   items, temporary effects, spectators, pending players, countdowns or edit
   sessions may survive their owning flow. The existing note is explicit that this
   was inferred from the absence of reported problems rather than measured - no
   entity or task count was captured before and after a match.
3. **Structure capture size awaits a target-Paper retest.** Fixed in code, never
   re-verified live.

A MySQL/MariaDB pass is deliberately *not* a 4B blocker. SQLite covers the
repository logic and is what a small server runs; the dialect-specific SQL needs
its own pass before release, tracked as release-readiness work rather than here.

### Resolved implementation decisions

- Use a fixed, bounded, persisted slot grid rather than variable-size packing.
- Store Duels-owned Paper structure files under the plugin data folder.
- Capture through the existing in-game instance edit workflow with separate structure
  corners; gameplay bounds are not assumed to be the whole structure.
- Pool and reuse provisioned instances after normal block rollback. Clear a slot only
  when its instance is retired, deleted, or recovered from an incomplete operation.
- Make allocation/match preparation asynchronous, with pending-player reservations and
  main-thread publication only after preparation succeeds.
- Persist dynamic instance health so interrupted provisioning, matches, resets, and
  cleanup cannot make a dirty or partial slot allocatable after restart.

## Phase 5 - Deeper Statistics & Tracking `[ ]` (v2)

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

## Phase 6 - Vault Integration & Rewards `[ ]` (v2)

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

## Phase 7 - Matchmaking `[ ]` (v2)

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

## Phase 8 - ELO/MMR/SBMM `[ ]` (v2)

Depends on Phase 5 (a trustworthy stats pipeline to compute from) and Phase 7
(matchmaking needs to exist before rating-aware pairing is meaningful). Rating
calculation itself (e.g. Elo update formula) is pure domain logic with no architectural
prerequisites beyond having match results to feed it - the dependency here is about
having somewhere for ratings to matter, not the math itself.

## Phase 9 - Network Readiness (Boundary Work Only) `[ ]` (v2)

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

### How a network match actually gets triggered

Worth recording now, since it clarifies what does and doesn't need building later:
whatever decides two players should duel across a network - a `/duel <player>` where the
target is on a different backend, or a queue a player joins that auto-pairs them with
whoever else is waiting - is a *separate concern* from creating the match itself, and
lives in a separate piece of software, not inside `Duels.jar`.

The reason is a hard constraint, not a design choice: a Bukkit `Player` object is only
valid on the server that player is physically connected to. Velocity/BungeeCord cannot
call Bukkit API at all - it only knows about connections and can transfer a player from
one backend to another. So there is no way to "reach across" and start a match with a
player who hasn't arrived on the backend server yet.

The actual sequence, for either trigger (a direct challenge or a queue pairing), is
necessarily: (1) something network-aware works out that two specific players should
duel and which backend has room, (2) it transfers both to that backend server, (3) once
both are physically present as Bukkit `Player` objects on that one JVM, something calls
the exact same `MatchManager.startMatch(Player, Player)` that `/duel accept` calls
today. Step 3 doesn't care how the pairing happened - the method signature already only
needs two online players, which is why this needs no rework of match creation itself,
only an additional trigger for it.

That means the network layer is architecturally two things, not a config toggle on
Duels: a Velocity-side plugin (queue/presence/transfer logic, written against Velocity's
API, which has no relationship to Bukkit's - it must be a separate JAR, running in the
proxy's own process) and a thin listener on the Paper side (waiting for "these two
players are about to arrive to duel each other," then calling `startMatch` once they
have). Whether that Paper-side trigger lives inside `Duels.jar` as an optional module or
as its own tiny companion plugin is a call to make when this is actually built - either
way, it does not replace or duplicate `/duel`, it just gives `startMatch` a second way to
be invoked. This is exactly why Stage A's `/duel` command doesn't need to anticipate
Stage B/C now: it already produces the one primitive (two online players, one call) that
any future network trigger can reuse unchanged.

## Phase 10 - Advanced/Optional Systems `[ ]` (v2)

Placeholder for ideas that come up during development that don't have an immediate
architectural dependency and aren't urgent - to be triaged as they arise rather than
speculatively designed now.

## Phase 11 - Admin Command/GUI Parity `[x]`

**Status:** Complete and verified in game. Nine new subcommands (arena `rename`, `toggle`,
`bounds`, `boundary`, `editmode`, `allowkit`; kit `rename`, `icon`, `edit`), nine matching
permission nodes in `plugin.yml`, and nine new `admin.help` lines.

A gap in the other direction was found and closed during the Phase 3B in-game pass: the
arena instance Bounds Corner 1/2 items and the arena Out Of Bounds (mode/grace) item had
commands but no GUI equivalent at all. `ArenaInstanceDetailMenu` and `ArenaDetailMenu` now
have matching items (left/right click, mirroring the existing spawn-point item pattern),
so parity holds in both directions.

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
- Durable retry/buffering for `SqlStatsRepository.recordMatch` - currently a DB outage
  during the async result write loses that one match's stats permanently (logged as a
  single SEVERE entry with the full recoverable fields, but never retried). Verified
  during B6 retesting (2026-09-22): players are still restored and the arena still
  releases normally regardless, so this never cascades into gameplay-affecting failure -
  it only affects that one match's persisted record. Closing this gap would require a
  persistent local retry queue that survives a full plugin/server restart, dedup logic so
  a later-recovered DB doesn't double-count, and a bound on retry duration - real,
  ongoing complexity for a failure mode that's both rare on a standalone server (the DB
  going down in the exact seconds around match-end) and more likely to matter once
  network/multi-server work (Phase 9) makes DB availability more load-bearing. Revisit
  then rather than building it ahead of need.
- JCore: a permanent, required bottom row on every menu. Raised by Jack on 2026-09-24.
  Currently the bottom row is a per-menu convention rather than a framework guarantee -
  most menus reserve it for pagination, and navigation controls are added ad hoc where a
  given menu happens to need them. The request is to make the bottom row a structural
  part of the menu framework: always present, always reserved, carrying pagination *plus*
  standard navigation (a main-menu button and an explicit exit/close button). Escape
  already closes a menu, but an on-screen exit control is expected UI and shouldn't
  require players to know the keybind. Belongs in JCore rather than Duels because it is a
  property of the menu framework itself, not of any Duels screen - but per the standing
  "JCore stays Duels-driven" rule, Duels' own menus are the design driver. Design
  questions to settle when picked up: how a menu declares its "main menu" target (JCore
  can't know Duels' menu graph), whether the reserved row shrinks usable slot count for
  existing menus (it does - every current menu's layout needs re-checking), and whether
  any menu is legitimately allowed to opt out. Explicitly flagged as not pressing.
