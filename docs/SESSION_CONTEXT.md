# Duels Current Session Context

_Last verified: 2026-09-28_

This file is the compact handoff for a new development session. Read it before
starting work, then inspect the relevant source files before changing code.

## Current state

The Duels standalone foundation is complete, tested, and manually verified on a real
Paper server.
Phases 0-5, 5.5 and 11 are done. Phase 5.5's kit effects, clickable interactions,
rematches and hardening passed automated and live testing on Paper 1.21.11 on
2026-09-28, including diagnostics returning to baseline with no errors.
Phase 5's filterable statistics and match-history system passed its in-game acceptance
run. Arena containment/bounds and dynamic provisioning have passed the complete
target-Paper run in `docs/IN_GAME_SUITE.md`.

Terminology changed on 2026-09-27: this completed system is now the **standalone
foundation baseline**, not the intended public V1. Jack's public V1 also includes kit
effects/debuffs, clickable interactions, rematches, Vault rewards, local matchmaking,
ranked ratings, and working Velocity support across fixed Paper backends. The
authoritative remaining order is `docs/V1_COMPLETION_PLAN.md`.

The shared pooled void world remains the dynamic-arena baseline. It now warns
when a captured arena's real footprint can enter an adjacent slot's chunk-send
range. Per-instance worlds remain a future option only if a real feature needs
authoritative world-scoped isolation; if introduced, they should be pooled per
`ArenaInstance`, not created afresh for every duel.

Dynamic capacity is a hard global slot bound per backend (64 by default). Ready
copies are reused first; a full pool never creates another world. Occupied,
reserved, available and maximum counts are visible in admin arena menus and
`/duels diagnostics`. Capacity failure reaches players as a distinct message and
does not mutate either participant. Retiring a copy is the explicit way to return
its slot.

Three guards now keep a live duel isolated from everyone not in it.
`ArenaContainmentGuard` keeps duellists inside their own bounds, `ArenaAccessGuard` stops
anyone else walking into an arena that is currently hosting a match, and
`MatchInterferenceGuard` stops an outsider affecting a duel from outside it. Two
deliberate boundaries: the access guard does **not** block entry to an *idle* arena,
because that broke arena edit mode; and nothing outside arena bounds is touched, so a
bystander who dies outside an arena remains an ordinary untouched vanilla event and Duels
can never cause item loss on a survival server.

Player state restoration never trades an inventory for a teleport. If the world a player
was in before Duels moved them has gone - unloaded, renamed or deleted - the snapshot is
still applied and they are placed at the `fallback-world` spawn with a warning, instead of
the restore failing. Velocity and fall distance are only meaningful at the captured
position, so they are zeroed whenever a substitute destination is used. If applying a
snapshot throws, the snapshot is deliberately left on disk so a later attempt can retry it
rather than silently eating an inventory. `/duels diagnostics state <player>` reads a saved
snapshot back, including where it would put the player and whether the captured world is
still loaded, and `/duels diagnostics` now reports the saved-state count and the resolved
fallback spawn.

`VoidArenaChunkGenerator` names its own fixed spawn. A `ChunkGenerator` that returns null
there delegates to vanilla's spawn search, which loads chunks synchronously on the main
thread hunting a solid block; in an empty world that froze the server for about eleven
seconds and tripped Paper's watchdog. This affected both `/mv create <name> normal -g
Duels` and the first-ever dynamic arena world.

Clickable chat is rendered by `ActionMessenger`, which sits on top of JCore's
`MessageManager` rather than replacing it. An administrator turns a message into a button
by writing a placeholder such as `{accept}` in `messages.yml`; the label, colours and
hover text under `duel.actions` are configuration, while the command each button carries
and the permission it requires are fixed in the `ChatAction` enum. That split is the
security boundary - a message file that could name the command would let anyone who can
edit `messages.yml` make a player run an arbitrary command by clicking a chat line.
Actions are located in the configured template *before* placeholders are substituted, so
a value passed in by code can never smuggle in a button. `ActionMessenger.audit()` runs at
startup and on every reload and reports three distinct problems: a missing label, a
placeholder written into a message that is not rendered interactively, and an upgraded
install whose existing wording means it has no buttons at all.
`/duels diagnostics buttons` renders every interactive message with sample data so an
edit can be checked without arranging a real duel.

Verification at this handoff:

- `mvn test`: 149 tests passed, 0 failures, 0 errors, 0 skipped. JCore: 303
  passed, 1 skipped.
- The shaded jar builds successfully and was copied into the local test-server
  plugin folder.
- Manual in-game testing passed for arena management, matches, kits, bounds,
  instancing, dynamic provisioning/recovery, rollback, spectators,
  GUI/command parity, advancements, diagnostics and containment.
- Phase 5.5 Slices 1-3 passed live testing: effect definitions surviving restart, the
  admin effect GUI and command fallbacks, `/duels reload`, and the immutable in-match
  baseline resisting potions, milk and `/effect`.
- Phase 5.5 Slices 4-6 passed live testing: component hover/click behaviour,
  challenge/stale-click safety, configurable labels, rematch request/deny/accept,
  consecutive rematches with fresh kit selection, expiry/disable handling and clean
  diagnostics. No errors or unexpected warnings were observed.
- Duel isolation and the world-gone restore path passed live testing, including a
  bystander evicted from an idle arena landing at the configured fallback world spawn.
- Phase 5 passed fresh SQLite and fresh YAML live write/read/filter/history/
  leaderboard testing, including restart persistence. The earlier schema was
  exercised on MySQL, MariaDB and PostgreSQL; Phase 5's fresh schema still needs
  that external-backend matrix repeated before release.

## V1 features now implemented

- Player challenge flow with expiration, accept/deny, and match-start failure
  handling.
- `/duel <player>` plus explicit `/duel challenge <player>` for names that
  collide with subcommands such as `spectate`, `accept`, or `deny`.
- Kit creation, editing, selection, arena kit restrictions, and kit snapshots.
- Persisted arena templates and persisted physical `ArenaInstance` records.
- Multiple instances of one arena template, with safe allocation and reuse.
- Per-instance spawns and bounds, edit-mode tools, and particle visualization.
- Boundary modes: warning, soft return, and forfeit, with grace periods.
- Match lifecycle, countdowns, death handling, disconnect handling, and player
  state restoration, including restart/crash-recovery paths.
- Spectator command and GUI, safe confinement, restoration, and match-end
  cleanup.
- Dependency-free block rollback between matches, including explosion handling
  and suppression of rollback debris drops.
- Advancement suppression during duels without the old grant/revoke spam loop.
- Admin command/GUI parity for the completed V1 operations.
- YAML, SQLite, MySQL/MariaDB, and PostgreSQL statistics backends.
- Configurable database pool size and resilient stats-failure cleanup.
- `/duels diagnostics` - a live read of every manager's internal counters, with
  per-admin baseline/compare so a leak shows up as a difference rather than
  having to be inferred.
- Configurable clickable challenge, help, result, statistics, spectate and rematch
  actions, backed by the same validated commands players can type.
- Holder-only permanent kit effects/debuffs with immutable match snapshots and full
  GUI/command administration.
- Mutual-consent rematches on the previous arena template with fresh instance
  allocation and kit selection.

## Current intentional limitations and remaining release verification

- Selected-arena challenges have both command and player GUI paths. Dynamic
  admin setup has GUI controls for source, capture corners/template, health,
  retry, and retirement, all exercised in the signed-off acceptance run.
- Dynamic capture/provisioning/recovery and all nine stages of
  `docs/IN_GAME_SUITE.md` are implemented and signed off.
- A fresh-server clean-install pass of `docs/TESTING.md` remains a useful foundation
  checkpoint. The final public-release pass will repeat it after Phase 9B so rewards,
  queues, ratings and network integration are covered too.
- The baseline reset is block-change rollback. The optional WorldEdit/FAWE
  schematic reset path is not implemented.
- There is not yet a matchmaking queue, ranked/MMR system, Vault reward integration,
  cross-server matchmaking, or Velocity companion plugin. These are now required for
  the intended public V1 rather than optional post-release ideas.
- Filterable profiles, match history, head-to-head queries and leaderboards are
  complete. Ranked ratings and more advanced analytics remain later phases.
- A durable pending-outcome/retry record is deferred until rewards and other
  consumers require guaranteed delivery.

The matchmaking/rewards/rating/network items are later scope decisions, not
unfinished V1 defects.

## Confidence by system

How much each system is trusted, and on what evidence. "Automated" means covered
by `mvn -o test`; "in game" means a recorded manual pass. The two are not
interchangeable - MockBukkit will happily confirm behaviour that a real server
does differently, and a manual pass proves one run rather than an invariant.

| System | Confidence | Evidence and what is missing |
|---|---|---|
| Arena templates and instances | **High** | Automated (serializers, monotonic ids, conversion guards, slot repair) plus a full in-game pass. The most-exercised code in the plugin. |
| Match lifecycle (PREGAME / GRACE / IN_PROGRESS) | **High** | Automated state-transition, disconnect-forfeit and concurrency tests, plus repeated in-game passes. Every known edge has a test rather than only a checklist line. |
| Kits and kit snapshots | **High** | Automated snapshot/slot-separation tests; in-game editor pass. Snapshots mean a mid-match kit edit cannot corrupt a running match. |
| Challenge flow and expiry | **High** | Automated pair tests; in-game pass. Small surface, well bounded. |
| Player state capture and restoration | **Medium-high** | In-game passes across death, disconnect, reconnect and reload. Not automatable end to end: MockBukkit cannot reproduce shutdown-time restoration, which is the one path that has only ever been checked by hand. |
| Arena containment and bounds | **High** | Strong automated coverage - 6 containment tests, 6 bounds-geometry tests, 5 openings-advisory tests - plus the completed live physics pass for fluid/fire spread, explosions, falling blocks, simultaneous matches and bystander safety. |
| Block-change rollback | **Medium-high** | Automated for basic, explosion, ceiling and double-reset cases. The tracked-change ceiling is a known, documented cliff rather than a bug. |
| Spectators | **Medium-high** | Automated for disconnect, eject, forfeit-while-spectated and detached-session finish. In-game pass done. Less real-world mileage than matches simply because fewer people use it. |
| Dynamic arena provisioning and recovery | **Medium-high** | The complete live capture/provision/restart/capacity/recovery suite passed. Automated tests now cover the hard slot limit, reservations, capacity reporting, reuse after retirement, player-safe capacity failure, layout maths and visibility. `DynamicArenaProvisioner` and `DynamicArenaRecovery` still have no full structure/world end-to-end coverage because MockBukkit does not implement those APIs. |
| Stats, YAML backend | **Medium-high** | Automated SQL/YAML parity plus a fresh live write/read/filter/history/leaderboard pass and restart-persistence check. |
| Stats, SQL backend | **High** | SQLite has automated coverage and passed the fresh live write/read/filter/history/leaderboard flow, restart persistence, and paging beyond 45 records. The Phase 5 external-dialect matrix remains a release check. |
| Non-SQLite dialects (MySQL, MariaDB, PostgreSQL) | **Medium-high** | Each backend passed repeated live match/stat checks. They are not covered automatically and the exact tested engine versions were not recorded, so future migration work should repeat the matrix. |
| Menus (admin and user) | **Medium** | Automated for layout rules - bottom row reserved, static/dynamic screen separation, slot upgrade - but **not for click handling**: nothing drives an `InventoryClickEvent` through a bundled menu. Every menu action has been driven by hand in game instead. |
| Commands and permissions | **Medium-high** | In-game GUI/command parity pass. Permission nodes are declared in `plugin.yml` and were checked by hand; nothing asserts they stay in sync with the code. |
| Diagnostics (`/duels diagnostics`) | **High** | Automated coverage compares whole `Snapshot` records, and baseline/compare was used successfully during the live suite. |
| Post-flow cleanup (no leaks) | **Medium-high** | A clean-match automated test and the measured live baseline/compare pass both returned to baseline. Wider soak testing would add confidence but no leak is currently known. |
| Kit effects (Phase 5.5 Slices 1-3) | **Medium-high** | Automated serializer and lifecycle coverage, plus a live pass over definition persistence, the admin GUI, command fallbacks and baseline immutability. Newer than the rest of the plugin, so it has less real mileage. |
| Duel isolation (access and interference guards) | **Medium-high** | Automated eviction and fallback-spawn tests plus a live pass. The intentional gaps - idle arenas stay enterable, and nothing outside arena bounds is touched - are design decisions, not missing coverage. |
| Clickable messages (Phase 5.5 Slice 4) | **Medium-high** | Automated tests cover rendering, arguments, permission hiding, colour continuity, configuration auditing and the admin preview. The Paper client pass confirmed hover rendering, click dispatch, stale actions, help, result/statistics actions and administrator label/hover customization. |
| Rematches (Phase 5.5 Slice 5) | **Medium-high** | Automated manager and command-flow tests plus a live Paper pass over request, deny, accept, consecutive rematches, fresh kit selection, exact arena template, expiry, feature disable and diagnostics cleanup. Newer than the core match lifecycle, so it has less production mileage. |
| JCore infrastructure | **High** | 303 tests, and every system is consumed by Duels rather than existing speculatively. |

### What could usefully be tested more

In rough order of how much the coverage is worth:

1. **Dynamic provisioning end to end.** MockBukkit blocks the obvious route, but
   an integration test could still cover the layout-allocation and recovery
   *bookkeeping* against a fake world, leaving only the paste itself manual.
2. **Menu click handling.** Nothing drives an `InventoryClickEvent` through a bundled
   menu, so no test exercises a menu's click handlers. Worth revisiting: a menu mis-wire
   is easy to introduce and currently only a human clicking every button would catch it.
3. **Permission-node drift.** A test that walks the command tree and asserts every
   declared permission exists in `plugin.yml` would be cheap and would stay
   correct forever.
4. **Shutdown-time player restoration.** Hard to automate; a scripted server
   start/stop harness is the realistic route if it ever bites.
5. **Automated SQL dialect coverage.** All four backends passed manually, but a
   future Testcontainers matrix would catch dialect regressions earlier.

## Progress and time frame

**Scope being measured.** "Complete" now means the public V1 target in
`docs/V1_COMPLETION_PLAN.md`, including the player-experience, rewards, matchmaking,
ranked and fixed-backend Velocity phases while preserving simple standalone mode.

| Milestone | Status | Remaining effort |
|---|---|---|
| Phases 0-4, 11 (foundation, arenas, instancing, spectators, containment, QoL) | Done | - |
| Phase 4B (dynamic provisioning) | Done and verified | - |
| Phase 5 (deeper statistics) | Done and verified | - |
| Phase 5.5 (kit effects, clickable UX, rematches) | Done and verified | - |
| Phase 6 (Vault + durable rewards) | Designed; Slices 1-2 of 6 done (result identity + dispatcher, reward model + rewards.yml) | ~2 sessions |
| Phase 7 (local matchmaking queues) | Integration planned; detailed design pending | ~3-4 sessions |
| Phase 8 (ELO / MMR / SBMM) | Product decisions pending | ~3-4 sessions |
| Phase 9A (network contracts/backend readiness) | Planned | ~2-3 sessions |
| Phase 9B (Velocity companion/cross-server flows) | Planned | ~5-8+ sessions |
| Phase 10 (advanced/optional) | Deliberately open-ended | not estimated |

**Against the revised public V1 definition: roughly 65%.** The difficult standalone
foundation is complete, but the remaining product and network integrations are
substantial. The fresh external-SQL and clean-install matrices remain useful checkpoint
tests and will be repeated as part of the final post-Phase-9 release campaign.

Treat both numbers as effort estimates, not deadlines.

## Current development target: Phase 6 reliable outcomes and Vault rewards

Phases 4B, 5 and 5.5 are closed. Phase 5.5's six slices are implemented,
automated-tested and verified in game; the signed-off behaviour and evidence are in
`docs/PHASE_5_5_DESIGN.md` and `docs/PHASE_5_5_TEST_PLAN.md`.

Phase 6's design is complete and agreed in `docs/PHASE_6_DESIGN.md`. Slices 1 and 2 are
built and committed. Slice 1: `MatchResult` carries a `resultId`,
`MatchResultDispatcher` owns the fan-out from `endMatch`, stats are one consumer behind
`StatsResultConsumer`, and both the SQL and YAML stores reject a result they have
already recorded. Slice 2: `rewards.yml` plus the `reward` package
(`RewardTable`/`RewardTableLoader`/`RewardManager`/`RewardReport`) resolve what a result
would pay through the five-layer sparse merge, and `/duels rewards` and
`/duels rewards preview` show both what loaded and how a figure was reached. Nothing is
granted or persisted yet - no consumer is registered and Vault is not referenced.
Implementation continues at Slice 3 (Vault, the ledger and actually granting). Then follow Phases 7-9 in `docs/V1_COMPLETION_PLAN.md`. The
final public-release matrix happens after the network phase so later features are
included in the same clean-install evidence.

### Phase 6 agreed decisions

- **One result, several consumers.** `MatchResult` gains a `UUID resultId` minted in
  `MatchManager.endMatch`, and a small `MatchResultDispatcher` fans it out. Statistics
  becomes a consumer rather than an inline call, and its insert becomes idempotent on
  that id. The dispatcher deliberately does **not** own durability - statistics needs
  only a unique index, rewards need a ledger, and forcing both through one generic
  outbox would be an abstraction over two consumers that do not share a problem.
  Revisit if Phase 8's ratings consumer needs the same durable retry.
- **All four reward types, with unequal promises.** Currency, XP and items are
  verifiable and covered by the retry guarantee; console commands are necessary (giving
  a crate key from another plugin) but unverifiable and unreversible, so they are
  explicitly best-effort and are never auto-replayed during recovery.
- **Crash policy is FLAG.** Intent is written before granting, so a crash leaves a
  recorded ambiguity rather than an invisible one. `PENDING` rows are safely auto-
  regranted; `GRANTING` rows become `AMBIGUOUS` for admin resolution, because a missing
  reward is bounded and fixable while duplicated currency inflates an economy and may be
  reproducible. `RETRY` and `DISCARD` remain configurable.
- **Rewards ship disabled**, in their own `rewards.yml`, with active anti-farm defaults
  once enabled (minimum duration, repeat-opponent decay; daily cap opt-in). A plugin
  should not inject currency into an existing economy unasked, nor ship trivially
  farmable. IP-based anti-alt checks were considered and rejected as punishing real
  players.
- **Vault stays optional** behind `softdepend` and the `ServicesManager`, with a
  one-method internal economy seam so the ledger never knows whether Vault,
  VaultUnlocked or Treasury is installed. Only the Vault implementation is being built.
- Reward *tables* are file-edited rather than GUI-edited - a deliberate deviation from
  the GUI-first convention, because nested per-kit/per-arena tables with item and
  command lists are more legible and diffable in YAML.

### Phase 5.5 agreed decisions

- Kit effects apply only to their holder and form a permanent match baseline that is
  **immutable while the kit is held**. Nothing in a live match - potions, splash
  potions, beacons, milk, `/effect`, an opponent's debuff - can weaken, strengthen or
  remove it; only an identical-amplifier change is let through, which is the kit
  reapplying its own effect. This overturned the original "a stronger temporary effect
  wins" rule after live testing, because it let a player exceed their kit's intended
  balance and let an opponent push a debuff further than the kit intended. Instant
  effects and finite built-in durations are not part of this phase.
- Effect administration is GUI-first with complete command fallbacks. Persist canonical
  registry keys, user-facing levels and configurable ambient/particle/icon flags.
- Every clickable message's visible text and hover content is configurable. Duels owns
  the Adventure composition for now; clicks invoke fixed, validated command paths.
- A rematch preserves the prior arena template, allocates a fresh available instance,
  and reopens kit selection. It uses mutual consent and a dedicated configurable expiry;
  it never reserves an arena while pending or silently falls back to another template.
- No JCore production change is planned. Reconsider extraction only after a second
  minigame demonstrates the same requirement.

### Phase 5 implemented decisions

These are the implemented semantics; preserve them unless a tested product need changes.

- **One query object, not more repository methods.** The filters Jack wants are a
  product of arena x kit x opponent x time window, so `StatsQuery` carries the
  optional filters and `PlayerStats` carries the result. The SQL side builds a
  `WHERE` clause from the non-null fields; the YAML side filters the in-memory
  list with the same predicate, so the two backends cannot drift in what a filter
  *means*. Jack explicitly wants deep, combinable queries ("kds of players on a
  specific arena using specific kits") even where a combination is niche, on the
  grounds that the data is already there.
- **Streaks are computed from ordered history per query, not stored.** A stored
  counter is a second source of truth that desyncs the first time a write fails.
- **There is no V1-to-Phase-5 migration.** Jack confirmed every existing result
  is test data, so migration 1 now creates the final schema from scratch. Delete
  the test SQL schema/database or `stats.yml` before the first server run.
- **A disconnect is always a loss**, at any point in a match the player willingly
  entered, including during kit selection before any kit is applied. This is
  Jack's explicit decision and matches current behaviour, so no rule changes -
  `end_reason = DISCONNECT` exists so a profile can read "Losses: 42 (7 by
  disconnect)" for visibility, not to change the outcome. Nothing punitive is
  being built until there are real numbers to calibrate against.
- **Match records are kept forever.** No pruning, no retention config.
- **Filters are presented as menu buttons**, not command syntax: `/duel stats
  [player]` opens a profile showing matches, wins, losses, K/D, win rate, current
  and best streak and disconnect count, with kit and arena filter buttons that
  drill into any combination, and head-to-head shown when viewing another player.
  `/duel top` gains sort categories with a minimum-matches floor on win rate.
  Both keep the reserved bottom row per Jack's menu convention.

There is intentionally no migration from the old test schema. Delete `stats.yml`
or the test SQL schema/database before the first server run. The precise data,
query, GUI, reset and verification contract is in `docs/PHASE_5_STATISTICS.md`.

### How Phase 4B works, for context

Admins capture a Paper NBT template from separate edit-session structure corners;
template spawns and gameplay bounds are saved as offsets. A dynamic arena reuses
a free generated copy first, otherwise reserves a persisted grid slot in a lazy
Duels void world, prepares chunks, pastes the template, and publishes a normal
provisioned `ArenaInstance`. STATIC uses only hand-built playable copies; DYNAMIC
uses one non-playable source build plus generated copies.

Allocation and match start are asynchronous: `MatchManager` owns a pending-player
reservation and does not mutate either player until provisioning succeeds.
Challenges carry either Any or a specific arena selection (`/duel challenge
<player> <arenaId>`) and are claimed during preparation rather than consumed
early. Provisioned instances persist health states, startup rebuilds
interrupted/dirty copies, retirement is bounded, and failed copies can be
retried. See `docs/PHASE_4B_DESIGN.md` and `docs/ROADMAP.md`.

## Remaining V1 order

1. Phase 6: reliable match-result consumers and Vault rewards.
2. Phase 7: local matchmaking queues.
3. Phase 8: ratings and ranked matchmaking.
4. Phase 9A: backend identity, capacity and secure handoff contracts.
5. Phase 9B: Velocity companion and real cross-server flows on fixed backends.
6. Final V1 clean-install, database, performance and network release matrix.
7. Hypothetical SkyWars/BedWars architecture exercise, then evidence-based JCore
   extraction.

Do not add Redis, distributed locks, cross-server state, or generalized JCore
APIs before a concrete multi-server feature requires them. JCore is bundled into
Duels, not installed as a shared server plugin. Duels domain concepts remain in
Duels until a second real plugin creates a proven reusable need.

## Important architecture invariants

- `Arena` is a template/policy object; `ArenaInstance` is either a playable
  physical copy or the non-playable SOURCE for a DYNAMIC arena.
- `MatchManager` allocation is instance-based, never template-based.
- A spectator must never enter `MatchManager.matches`; combatant membership and
  spectator membership are separate questions.
- Bukkit/Paper world and player mutation stays on the main server thread.
- Reset must finish before an instance is released and made available again.
- Optional integrations must not be hard dependencies for the standalone path.
- Do not rewrite working V1 systems without a concrete reliability, lifecycle,
  performance, or future-feature reason.

## Working status and process

- The completed V1 worktree was committed as `9939dd8` (`Complete Duels V1
  baseline`), followed by the deployment-mode architecture note in `e84988f`.
- **Git habit:** keep commits small and frequent. After each logically complete
  and tested slice (for example, one design/documentation update or one
  implementation step), check the diff and make a focused commit before
  starting unrelated work. If work has accumulated without a checkpoint,
  remind Jack to commit rather than silently adding more changes. Do not bundle
  unrelated fixes or phases into one commit.
- End development updates with a compact, simplified roadmap/progress footer so
  Jack can always see the current position. Keep it to one short line or small
  checklist rather than repeating the detailed roadmap.
- Phases 4B and 5 are closed. Do not rewrite V1 or re-run already-completed phases.
- For substantial work: inspect current code and call sites, explain the
  problem and trade-offs, agree the design, implement incrementally, test, and
  update this file plus `docs/ROADMAP.md`.
- Claude is currently authorized to write implementation code directly, while
  explaining important decisions. A dedicated code-review/refinement pass and
  deeper learning/documentation pass will happen after the working system is
  built further.

## Source of truth

- Detailed phase status and dependencies: `docs/ROADMAP.md`
- Architectural reasoning and ownership rules: `docs/ARCHITECTURE.md`
- **The in-game suite to actually run: `docs/IN_GAME_SUITE.md`.** Everything
  passed on 2026-09-26; retained as the consolidated sign-off record.
- Historical full manual V1 suite, kept as reference: `docs/V1_TEST_PLAN.md`
- Containment rationale, commit-by-commit, and the known-limitation sign-off:
  `docs/CONTAINMENT_TEST_PLAN.md`
- Release-readiness gaps and known boundaries: `docs/RELEASE_REVIEW.md`
