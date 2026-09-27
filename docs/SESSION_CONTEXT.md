# Duels Current Session Context

_Last verified: 2026-09-27_

This file is the compact handoff for a new development session. Read it before
starting work, then inspect the relevant source files before changing code.

## Current state

Duels V1 is complete, tested, and manually verified on a real Paper server.
Phases 0-4B and 11 are done. Phase 5 is implemented and awaiting its in-game
statistics acceptance pass. Arena containment/bounds and dynamic provisioning
have passed the complete target-Paper run in `docs/IN_GAME_SUITE.md`.

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

Verification at this handoff:

- `mvn clean package`: 73 tests passed, 0 failures, 0 errors, 0 skipped. JCore: 303
  passed, 1 skipped.
- The shaded jar builds successfully and was copied into the local test-server
  plugin folder.
- Manual in-game testing passed for arena management, matches, kits, bounds,
  instancing, dynamic provisioning/recovery, rollback, spectators,
  GUI/command parity, advancements, diagnostics and containment.
- The V1 schema was exercised on SQLite, MySQL, MariaDB and PostgreSQL. Phase 5's
  fresh schema is automatically verified on SQLite and still needs the ordered
  external-backend acceptance pass before release.

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

## Current intentional limitations and remaining release verification

- Selected-arena challenges have both command and player GUI paths. Dynamic
  admin setup has GUI controls for source, capture corners/template, health,
  retry, and retirement, all exercised in the signed-off acceptance run.
- Dynamic capture/provisioning/recovery and all nine stages of
  `docs/IN_GAME_SUITE.md` are implemented and signed off.
- A fresh-server, clean-install pass of `docs/TESTING.md` remains the public-release
  gate. It is separate from Phase 4B and does not block Phase 5 development.
- The baseline reset is block-change rollback. The optional WorldEdit/FAWE
  schematic reset path is not implemented.
- There is no matchmaking queue, ranked/MMR system, Vault reward integration,
  cross-server matchmaking, or Velocity companion plugin.
- Statistics are useful baseline tracking, not the future deep stats/product
  analytics system.
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
| Stats, YAML backend | **Medium** | Automated read-after-write ordering; in-game pass. |
| Stats, SQL backend | **High** | SQLite has automated coverage and passed the live migration/write/read/leaderboard flow. All supported external backends also tracked repeated matches correctly in manual testing. |
| Non-SQLite dialects (MySQL, MariaDB, PostgreSQL) | **Medium-high** | Each backend passed repeated live match/stat checks. They are not covered automatically and the exact tested engine versions were not recorded, so future migration work should repeat the matrix. |
| Menus (admin and user) | **Medium** | Automated for layout rules - bottom row reserved, static/dynamic screen separation, slot upgrade - but **not for click handling**, because the MockBukkit slot-conversion test is skipped. Every menu action has been driven by hand in game instead. |
| Commands and permissions | **Medium-high** | In-game GUI/command parity pass. Permission nodes are declared in `plugin.yml` and were checked by hand; nothing asserts they stay in sync with the code. |
| Diagnostics (`/duels diagnostics`) | **High** | Automated coverage compares whole `Snapshot` records, and baseline/compare was used successfully during the live suite. |
| Post-flow cleanup (no leaks) | **Medium-high** | A clean-match automated test and the measured live baseline/compare pass both returned to baseline. Wider soak testing would add confidence but no leak is currently known. |
| JCore infrastructure | **High** | 303 tests, and every system is consumed by Duels rather than existing speculatively. |

### What could usefully be tested more

In rough order of how much the coverage is worth:

1. **Dynamic provisioning end to end.** MockBukkit blocks the obvious route, but
   an integration test could still cover the layout-allocation and recovery
   *bookkeeping* against a fake world, leaving only the paste itself manual.
2. **Menu click handling.** The skipped slot-conversion test is the blocker. Worth
   revisiting: a menu mis-wire is easy to introduce and currently only a human
   clicking every button would catch it.
3. **Permission-node drift.** A test that walks the command tree and asserts every
   declared permission exists in `plugin.yml` would be cheap and would stay
   correct forever.
4. **Shutdown-time player restoration.** Hard to automate; a scripted server
   start/stop harness is the realistic route if it ever bites.
5. **Automated SQL dialect coverage.** All four backends passed manually, but a
   future Testcontainers matrix would catch dialect regressions earlier.

## Progress and time frame

**Scope being measured.** "Complete" here means the target in `CLAUDE.md`:
release-ready for a standalone server, with the full single-server feature set
finished and tested. Matchmaking, ELO and network support are counted below
because they are on the current roadmap, but they are explicitly *not* required
for Duels itself to be considered complete.

| Milestone | Status | Remaining effort |
|---|---|---|
| Phases 0-4, 11 (foundation, arenas, instancing, spectators, containment, QoL) | Done | - |
| Phase 4B (dynamic provisioning) | Done and verified | - |
| Phase 5 (deeper statistics) | Implemented; in-game acceptance pending | one acceptance pass |
| Phase 6 (Vault + durable rewards) | Not designed | ~3-4 sessions |
| Phase 7 (local matchmaking queues) | Not designed | ~3-4 sessions |
| Phase 8 (ELO / MMR / SBMM) | Not designed | ~2-3 sessions |
| Phase 9 (network-readiness boundaries) | Not designed | ~2-3 sessions |
| Phase 10 (advanced/optional) | Deliberately open-ended | not estimated |

**Against the standalone-complete definition: roughly 95%.** The in-game suite and
all four database backends have passed. What remains for a public release is the
fresh-server clean-install matrix in `docs/TESTING.md` and any issues it reveals.

**Against the full current roadmap through Phase 9: roughly 75%.** Phase 5 is now
implemented; the remaining schedule is dominated by Phases 6 and 7.

Treat both numbers as effort estimates, not deadlines.

## Current development target: verify Phase 5

Phase 4B is closed. Phase 5's filterable profiles, match history, composite SQL/YAML
queries, offline-player lookup, timing/end metadata and category leaderboards are
implemented. Automated tests pass; the remaining boundary is the ordered in-game
menu and fresh-storage acceptance pass in `docs/PHASE_5_STATISTICS.md`.

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

## Recommended V2 order

1. Phase 5: deeper statistics and tracking.
2. Phase 6: Vault integration and durable, idempotent rewards.
3. Phase 7: local matchmaking queues.
4. Phase 8: ELO/MMR/SBMM, after stats and matchmaking exist.
5. Phase 9: network readiness boundaries, then a separate Velocity-side
   integration when a real multi-server test environment exists.

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
- Phase 4B is closed; Phase 5 awaits its in-game acceptance pass. Do not rewrite
  V1 or re-run already-completed phases.
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
