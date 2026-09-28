# Duels V1 Completion Plan

_Established: 2026-09-27_

This is the execution plan for the first public Duels release. `docs/ROADMAP.md`
remains the detailed architectural history; this document defines the remaining
product scope and dependency order.

## Definition of V1

V1 is not merely the completed standalone foundation. The first public release is a
player-friendly duelling product that includes:

- the completed arena, instancing, reset, spectator, statistics and dynamic-
  provisioning systems;
- potion effects and debuffs in kits;
- clickable, discoverable player interactions and rematches;
- configurable Vault rewards;
- local matchmaking queues;
- ranked ratings and rating-aware matchmaking; and
- a working Velocity deployment across fixed Duels backend servers.

Commands remain complete, permission-aware fallbacks. Normal player journeys should
not require remembering commands when a menu or clickable Adventure component can
present the action safely.

The network target is fixed backend servers behind Velocity. V1 does **not** require
Kubernetes, automatic process creation, elastic cloud infrastructure, multiple proxy
high availability, or arbitrary dynamic game-server provisioning. Those are later
operational stages.

## Product principles

1. **Discoverable first.** A player should be shown their useful next actions.
2. **Commands remain authoritative.** Clickable UI invokes the same validated command
   or application service as typed input; it never bypasses permissions or state checks.
3. **Standalone stays simple.** A single Paper server must not require a proxy, Redis,
   or an external database unless the administrator enables the corresponding feature.
4. **Network features are additive.** Local challenges, queues and matches continue to
   work without the network module.
5. **One match result, several consumers.** Statistics, rewards and ratings observe one
   authoritative completed result instead of independently reconstructing the outcome.
6. **Duels proves the design before JCore owns it.** Keep clean boundaries, but do not
   generalize domain concepts without a second real minigame use case.

## Dependency order

```text
Completed foundation (Phases 0-5 and 11)
    -> Phase 5.5: kit depth and player experience
    -> Phase 6: reliable outcomes and Vault rewards
    -> Phase 7: local matchmaking
    -> Phase 8: ratings and ranked matchmaking
    -> Phase 9A: network contracts and backend identity
    -> Phase 9B: Velocity companion and cross-server flows
    -> release hardening and clean-install matrix
    -> second-minigame architecture exercise
    -> evidence-based JCore extraction
```

Rematches are placed before rewards and matchmaking because they extend the already-
stable match/challenge lifecycle without depending on either. The authoritative result
boundary arrives with rewards before ratings, so both later consumers reuse it.

## Phase 5.5 - Kit Depth and Player Experience

### Problem / opportunity

The functional systems are strong, but common interactions still depend too heavily on
players knowing command syntax. Kits also cannot yet express persistent potion effects
or debuffs, which limits game-mode variety.

### Desired behaviour

- Kits can store validated holder-only potion effects, including level, ambient,
  particle and icon visibility. Kit effects are permanent match baselines rather than
  finite-duration effects; instant effects are rejected.
- A kit effect baseline is immutable while the kit is held: no potion, beacon, milk,
  `/effect` command or opponent debuff can weaken, strengthen or remove it. Live testing
  overturned the original "a stronger temporary effect replaces the baseline" rule,
  because it let a player exceed the balance their kit was given and let an opponent
  push a debuff further than the kit intended.
- Applied kit effects are part of the immutable match kit snapshot. Editing a live kit
  affects future matches only.
- Player restoration removes duel effects and restores the player's captured effects on
  every finish, disconnect, shutdown and recovery path.
- Challenge messages offer clickable Accept and Deny actions with hover explanations.
- Relevant messages offer clickable Spectate, View Stats, Join/Leave Queue and Rematch
  actions as those systems become available.
- Commands remain usable and are the same validated entry points used by click actions.
- After a match, either participant can request a rematch. The other participant must
  accept before a new match is prepared.

### Architecture

- Potion configuration belongs to `Kit` and its serializer; application/restoration
  belongs to the existing kit and player-state lifecycle.
- Build chat interactions with Paper's Adventure `Component`, `ClickEvent` and
  `HoverEvent` APIs. Do not use raw JSON, packets or NMS for supported interactions.
- A Duels-owned `RematchManager` owns post-match eligibility/context, while the existing
  challenge boundary owns the pending pair invitation, expiry claim and acceptance.
- A rematch stores identities and the requested previous configuration, not live
  `Player`, `Match`, `ArenaInstance` or mutable `Kit` references.
- Acceptance revalidates both players, permissions, kit availability and arena policy,
  then enters the normal match-preparation/allocation flow. It never reserves the old
  physical arena while players decide.

### Failure handling

- Disconnect, queue entry, new challenge/match, expiry or plugin disable invalidates a
  pending rematch.
- If the previous arena is no longer valid, explain why and require a new challenge; do
  not silently change templates. Rematches always reopen kit selection against current
  valid kit snapshots.
- Invalid potion identifiers or values fail visibly during admin editing/loading rather
  than partially applying a kit.

### JCore vs Duels

Kit effects and rematches are Duels domain behaviour. Small Adventure construction
helpers may eventually belong in JCore, but only after a second plugin demonstrates the
same interaction vocabulary.

### Testing and definition of done

- Serializer, snapshot and validation tests cover effects and debuffs.
- Live tests cover death, disconnect, rematch expiry, simultaneous requests, kit edits,
  disabled/deleted resources and player-state restoration.
- Every generated click action is also tested through its underlying command/service.
- A new player can challenge, accept, rematch, spectate and inspect statistics without
  being required to type an undisclosed command.

The agreed implementation, ownership, configuration and test sequence is specified in
`docs/PHASE_5_5_DESIGN.md`.

## Phase 6 - Reliable Outcomes and Vault Rewards

### Problem / opportunity

Economy rewards turn match completion into a financially meaningful operation. A
logged-and-dropped failure that is tolerable for optional statistics is not sufficient
for currency.

### Desired behaviour

- Vault is an optional soft dependency; Duels starts and plays normally without it.
- Rewards are configurable by result and mode, with permissions/caps where product
  testing shows they are needed.
- Reward feedback clearly states what was granted and why.
- Duplicate match completion cannot intentionally grant a reward twice.
- Failed or ambiguous grants are visible to administrators and recoverable according to
  a documented policy.

### Architecture and important constraint

Match completion produces one stable result identity. Statistics, rewards and later
ratings consume that result independently. Persist a reward intent/ledger before or
around the Vault call and make local processing idempotent.

A database transaction cannot atomically include an arbitrary economy plugin's Vault
call. A server crash between “deposit succeeded” and “ledger marked complete” creates an
unavoidable ambiguity unless the economy provider itself supports idempotency. Phase 6
must explicitly choose and document a policy—normally avoiding automatic duplicate
currency and surfacing ambiguous entries for reconciliation—rather than claiming
impossible exactly-once delivery.

Vault integration and reward rules belong in Duels. A generic durable-consumer/outbox
facility is only a JCore candidate after the second-minigame exercise proves compatible
requirements.

### Testing and definition of done

- No-Vault, disabled-reward, successful, rejected and failure paths are tested.
- Duplicate completion and restart recovery do not silently duplicate grants.
- Database work remains asynchronous; Bukkit/Vault calls obey their provider's thread
  requirements.
- Administrators can diagnose pending/failed/ambiguous reward work.

## Phase 7 - Local Matchmaking

### Problem / opportunity

Direct challenges require players to find a specific opponent. Matchmaking should let
players choose a mode and wait for any compatible opponent.

### Desired behaviour

- Named queues define arena/kit/ruleset eligibility rather than hard-coding one global
  queue.
- Players can join, leave and inspect queues through GUI, clickable chat and commands.
- Pairing begins with understandable join-order/compatibility rules.
- Queue feedback explains waiting state, unavailable capacity and match creation.
- Direct challenges and rematches continue to coexist with queues.

### Architecture

A Duels-owned queue manager owns local waiting entries and pairing. Entries contain
UUID, chosen queue/mode and timing metadata—not retained `Player` objects. One atomic
claim on the main server coordination path prevents a player being consumed by two
matches. Match creation uses the same preparation and arena-allocation boundary as a
challenge.

Queue definitions are persisted configuration; waiting membership is runtime state.
Disconnect, challenge acceptance, rematch acceptance, manual leave, plugin disable and
successful pairing remove membership deterministically.

### Testing and definition of done

- Concurrency tests prove one player cannot enter two matches.
- Live tests cover join/leave, disconnect, full arena capacity, incompatible choices,
  stale GUI clicks and recovery after match-start failure.
- No distributed queue, Redis or proxy dependency exists in local mode.

## Phase 8 - Ratings and Ranked Play

### Problem / opportunity

Raw wins do not measure opponent strength. Ranked play needs a stable rating model,
clear player expectations and persistence tied to authoritative match results.

### Required product decisions before implementation

- Elo, Glicko-2 or another understandable model;
- global, queue-specific or kit/ruleset-specific ratings;
- placement matches and provisional display;
- seasons, resets and inactivity behaviour;
- disconnect and administrative cancellation treatment;
- ranked eligibility and minimum match requirements; and
- whether exact rating or broader divisions are player-visible.

Do not pick a formula before these product semantics are agreed.

### Architecture

Rating updates consume the same result identity introduced for rewards and are
idempotent. Rating calculation is pure Java domain logic; persistence and menu/chat
presentation surround it. Local matchmaking can first widen an acceptable rating range
as waiting time increases, with transparent caps and fallback behaviour.

Ratings and matching policy remain Duels domain concepts. Generic mathematical helpers
do not justify a JCore subsystem.

### Testing and definition of done

- Deterministic tests cover expected rating changes, symmetry, placements, disconnects,
  duplicate processing and season/reset policy.
- Ranked queues never mix incompatible rating scopes.
- History explains rating change and players can discover their rating without commands.

## Phase 9A - Network Contracts and Backend Readiness

### Problem / opportunity

Local `Player`, arena and match objects cannot cross JVM boundaries. Before a proxy can
coordinate games, Duels needs explicit identities and a safe handoff contract without
making standalone installations distributed systems.

### Desired behaviour and architecture

- Every backend has a configured stable server identity.
- Shared SQL stores cross-server player statistics and ratings; local worlds, arena
  instances and active matches remain owned by one Paper backend.
- A backend exposes capacity/mode availability through a narrow transport boundary.
- Backends advertise a versioned capability catalogue so the coordinator assigns only
  modes/kits their deployed configuration can actually run. Configuration remains
  file/deployment managed in V1 rather than turning arena and kit definitions into
  distributed mutable database state.
- A signed or otherwise authenticated match ticket identifies players, backend, mode,
  expiry and a unique nonce.
- The Paper side consumes a ticket only once and starts a match only after both expected
  players are online on that backend.
- All network services are optional; their absence leaves standalone behaviour intact.

Choose transport only after requirements are fixed. Standard Minecraft plugin messages
normally need a connected player as their carrier, which can make them insufficient for
idle-backend capacity heartbeats. Redis, a direct authenticated service channel or a
different transport becomes justified only after that concrete communication model is
designed; do not adopt distributed infrastructure for prestige.

### Testing and definition of done

- Contract serialization/versioning, expiry, authentication and duplicate consumption
  are automated.
- A backend restart or failed transfer cannot create a ghost match or strand local
  capacity indefinitely.
- Standalone startup and all local flows pass with every network feature disabled.

## Phase 9B - Velocity Companion and Cross-Server Flows

### Problem / opportunity

A proxy routes connections but cannot call Bukkit APIs. A working network therefore
needs a separate Velocity plugin to coordinate players and capacity, then hand a match
ticket to the chosen Paper backend.

### V1 network scope

- One Velocity proxy and several fixed Duels Paper backends.
- Network player presence and backend capacity discovery.
- Cross-server queue pairing and transfer to an allocated backend.
- Cross-server direct challenges as well as queue pairing.
- Timeout, cancellation, failed-transfer and backend-disconnect recovery.
- Shared player statistics and ratings.
- Configurable post-match routing, such as returning to a lobby or remaining on the
  Duels backend for a rematch/next queue action.
- Clear player feedback throughout transfer and preparation.

The Velocity companion is a separate build/JAR because Velocity cannot load Bukkit
plugins. On the destination backend, the existing `MatchManager` still starts the real
match after both Bukkit `Player` objects arrive. Network rewards also require the server
owner's chosen Vault economy to expose a network-consistent balance; Duels cannot make a
backend-local economy provider shared merely by running behind Velocity.

### Explicitly deferred

- Creating/destroying Paper server processes dynamically;
- Kubernetes/container orchestration;
- multi-proxy high availability;
- global tournaments spanning independent networks; and
- distributed arena/world state.

### Testing and definition of done

- A real proxy plus at least two Paper backends completes queue, challenge (if kept),
  match, result and return/next-action flows.
- Tests cover one player failing transfer, backend becoming full/unavailable, duplicate
  messages, stale tickets, proxy restart expectations and reconnect behaviour.
- A backend never trusts a player-supplied command as proof of a network assignment.
- Local standalone operation remains no harder to configure than it is today.

## Cross-cutting usability review

Each phase includes a player-journey review rather than postponing all UX until the end:

- What can the player do now?
- How do they discover it?
- What happens on click?
- What feedback appears while waiting?
- Can they cancel or go back safely?
- Does failure explain the next useful action?
- Is command syntax still documented for power users and administrators?

Menus and chat should use consistent names, colours, Back behaviour and confirmation
rules. Sounds, titles, boss bars and action bars are used where they communicate state,
not merely because the APIs exist.

## Final V1 Release Gate

After Phase 9B—not before—run the final release campaign:

1. `mvn clean package` for every JCore, Duels Paper and Velocity module.
2. Fresh standalone clean-install coverage from `docs/TESTING.md`.
3. Fresh Phase 5+ schema testing on SQLite, YAML, MySQL, MariaDB and PostgreSQL.
4. Vault-present and Vault-absent tests with a supported economy provider.
5. Local queue/ranked/rematch/effect tests across restart and failure paths.
6. A real Velocity plus multi-backend network matrix.
7. Spark profiling during simultaneous matches and queue/stat activity.
8. Final permission, default configuration, diagnostics, log and documentation review.
9. Replace snapshot versions, build reproducible release artifacts and tag the release.

The current clean-install and external-database checks remain useful checkpoints, but
they are no longer the final release gate because later phases change the product.

## After Duels: Second-Minigame Architecture Exercise

Once Duels V1 is complete, design a hypothetical SkyWars or BedWars plugin before
extracting large new JCore systems.

### Method

1. Write the second game's player journeys, lifecycle, state ownership, persistence and
   failure cases without copying Duels classes.
2. Sketch package structure, major interfaces and pseudocode for match creation,
   countdown, teams/players, world allocation, spectators, results and shutdown.
3. Compare it with Duels by behaviour and invariants—not by similar class names.
4. Classify overlap as:
   - already provided by JCore;
   - genuinely reusable infrastructure;
   - similar-looking but semantically different domain behaviour; or
   - accidental duplication not worth abstracting.
5. Extract the smallest API that both designs can use without adapters that merely hide
   incompatible semantics.
6. Recheck threading, ownership, lifecycle and failure behaviour in both consumers.

BedWars is likely the stronger abstraction test because teams, respawns, generators and
objective destruction differ materially from a two-player elimination duel. SkyWars is
closer to Duels and may be the easier first design. At that checkpoint, sketch both at a
high level and choose the one that tests the most uncertain JCore boundaries.

### Likely outcomes, not pre-decided abstractions

Reusable candidates may include lifecycle/task ownership, component/menu helpers,
world-copy orchestration, safe player-state sessions, durable result-consumer mechanics
or generic server-capacity messaging. `Duel`, `Rematch`, `DuelQueue`, rating policy,
`ArenaInstance` and game-specific win conditions remain domain concepts unless the
second design proves otherwise.

The success criterion is not moving the most code into JCore. It is making both plugins
simpler and harder to misuse without forcing either game's rules through the other's
model.
