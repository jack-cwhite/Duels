# Duels Current Session Context

_Last verified: 2026-09-25_

This file is the compact handoff for a new development session. Read it before
starting work, then inspect the relevant source files before changing code.

## Current state

Duels V1 is complete, tested, and manually verified on a real Paper server.
Phases 0-4 and 11 are done. Arena containment and bounds - the follow-up work
under Phases 2 and 3B - is complete and committed, awaiting one in-game pass
against `docs/IN_GAME_SUITE.md` (Stages 4-9).

Phase 4B is fully implemented and its manual acceptance suite has been run and
signed off (`docs/RETEST_PLAN.md`, section B10). It is held open only by three
verification residuals listed under "Remaining before 4B sign-off" in
`docs/ROADMAP.md` and consolidated as Stages 1-3 of `docs/IN_GAME_SUITE.md`, the
most important being that the SQL stats path has never
been exercised in game. Per-instance *worlds* for dynamic arenas are a separate
deferred idea, not a 4B blocker.

Verification at this handoff:

- `mvn -o test`: 63 tests passed, 0 failures, 0 errors, 0 skipped. JCore: 303
  passed, 1 skipped.
- `mvn -o -q package -DskipTests`: clean.
- Manual in-game testing passed for arena management, matches, kits, bounds,
  instancing, rollback, spectators, GUI/command parity, and advancements.

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

## Current intentional limitations and remaining verification

- Selected-arena challenges have both command and player GUI paths. Dynamic
  admin setup has GUI controls for source, capture corners/template, health,
  retry, and retirement, all exercised in the signed-off acceptance run.
- Dynamic capture/provisioning/recovery is implemented and signed off except for
  the three residuals now covered by Stages 1-3 of `docs/IN_GAME_SUITE.md`: the in-game SQLite
  stats pass, a measured post-flow cleanup check, and a structure-capture-size
  retest.
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
| Arena containment and bounds | **Medium-high** | Strong automated coverage - 6 containment tests, 6 bounds-geometry tests, 5 openings-advisory tests - but the physics paths (fluid spread, fire spread, trimmed explosions, falling blocks) can only really be judged on a live server, and that pass has not been run since `8028d94`. Stages 4-9. |
| Block-change rollback | **Medium-high** | Automated for basic, explosion, ceiling and double-reset cases. The tracked-change ceiling is a known, documented cliff rather than a bug. |
| Spectators | **Medium-high** | Automated for disconnect, eject, forfeit-while-spectated and detached-session finish. In-game pass done. Less real-world mileage than matches simply because fewer people use it. |
| Dynamic arena provisioning and recovery | **Medium** | Signed off in game, but `DynamicArenaProvisioner` and `DynamicArenaRecovery` have **no end-to-end automated coverage** - they touch world generation and structure APIs that MockBukkit does not implement. The layout maths is tested in isolation. This is the widest automated-coverage gap in the plugin. |
| Stats, YAML backend | **Medium** | Automated read-after-write ordering; in-game pass. |
| Stats, SQL backend | **Low** | The code is written and reviewed and the boolean-parameter handling is unit tested, but **it has never executed against a real database**. This is Stage 1 and it sits directly on Phase 5's critical path. |
| Non-SQLite dialects (MySQL, MariaDB, PostgreSQL) | **Unverified** | Dialect-specific SQL, no automated or manual pass. A release gate, not a phase blocker. |
| Menus (admin and user) | **Medium** | Automated for layout rules - bottom row reserved, static/dynamic screen separation, slot upgrade - but **not for click handling**, because the MockBukkit slot-conversion test is skipped. Every menu action has been driven by hand in game instead. |
| Commands and permissions | **Medium-high** | In-game GUI/command parity pass. Permission nodes are declared in `plugin.yml` and were checked by hand; nothing asserts they stay in sync with the code. |
| Diagnostics (`/duels diagnostics`) | **Medium-high** | Automated test asserts a clean match returns every counter to baseline, comparing whole `Snapshot` records so a field added later is covered automatically. Not yet used in anger on a live server - that is Stage 2, and Stage 2 is simultaneously the tool's first real use. |
| Post-flow cleanup (no leaks) | **Low until Stage 2** | Previously signed off by *inference* - nothing visibly broke, so nothing was assumed to leak. The diagnostics command exists precisely because that is not evidence. One automated test covers the clean-match case. |
| JCore infrastructure | **High** | 303 tests, and every system is consumed by Duels rather than existing speculatively. |

### What could usefully be tested more

In rough order of how much the coverage is worth:

1. **The SQL stats path** - Stage 1. Lowest confidence, highest downstream cost.
2. **Dynamic provisioning end to end.** MockBukkit blocks the obvious route, but
   an integration test could still cover the layout-allocation and recovery
   *bookkeeping* against a fake world, leaving only the paste itself manual.
3. **Menu click handling.** The skipped slot-conversion test is the blocker. Worth
   revisiting: a menu mis-wire is easy to introduce and currently only a human
   clicking every button would catch it.
4. **Permission-node drift.** A test that walks the command tree and asserts every
   declared permission exists in `plugin.yml` would be cheap and would stay
   correct forever.
5. **Shutdown-time player restoration.** Hard to automate; a scripted server
   start/stop harness is the realistic route if it ever bites.
6. **The other three SQL dialects** - a release gate, and genuinely only provable
   against real servers.

## Progress and time frame

**Scope being measured.** "Complete" here means the target in `CLAUDE.md`:
release-ready for a standalone server, with the full single-server feature set
finished and tested. Matchmaking, ELO and network support are counted below
because they are on the current roadmap, but they are explicitly *not* required
for Duels itself to be considered complete.

| Milestone | Status | Remaining effort |
|---|---|---|
| Phases 0-4, 11 (foundation, arenas, instancing, spectators, containment, QoL) | Done | - |
| Phase 4B (dynamic provisioning) | Implementation done, verification open | ~2-3 hours of in-game testing (`docs/IN_GAME_SUITE.md`) |
| Phase 5 (deeper statistics) | Designed in full, not started | ~3-4 sessions |
| Phase 6 (Vault + durable rewards) | Not designed | ~3-4 sessions |
| Phase 7 (local matchmaking queues) | Not designed | ~3-4 sessions |
| Phase 8 (ELO / MMR / SBMM) | Not designed | ~2-3 sessions |
| Phase 9 (network-readiness boundaries) | Not designed | ~2-3 sessions |
| Phase 10 (advanced/optional) | Deliberately open-ended | not estimated |

**Against the standalone-complete definition: roughly 90%.** What is left is one
in-game test run and the release-gate items in `docs/RELEASE_REVIEW.md` - the
other three SQL dialects and a clean-install pass of `docs/TESTING.md`. Realistic
time to that point is **one solid testing day plus one session of fixes**, on the
assumption the suite finds a handful of small issues rather than a design problem.

**Against the full current roadmap through Phase 9: roughly 65%.** Phases 5-9 are
each a small number of sessions of real work, and Phase 5 is the only one already
designed. A plausible calendar estimate is **three to four months of the current
cadence**, dominated by Phases 6 and 7 rather than by Phase 5.

Treat both numbers as effort estimates, not deadlines. The most likely thing to
move them is the Stage 1 SQL pass: if the stats path needs rework, Phase 5 starts
later and every phase behind it shifts.

## Next development target: close Phase 4B, then Phase 5

Phase 4B is built. What remains is verification, in this order, agreed with Jack:

1. **Run Part D of `docs/RETEST_PLAN.md`** to close 4B. The three residuals are
   the in-game SQL/SQLite stats pass, a measured post-flow cleanup check, and a
   structure-capture-size retest. Item D1 comes first because Phase 5 is entirely
   SQL query work and `SqlStatsRepository` has never executed against a real
   database - the earlier `BLOCKED` note assumed an external MySQL was needed,
   but the shipped default is `SQL` + `SQLITE`, which needs no server.
2. **Run `docs/CONTAINMENT_TEST_PLAN.md`** for the arena containment work.
3. **Then Phase 5, Deeper Statistics & Tracking.** Design agreed; see below.

A MySQL/MariaDB/PostgreSQL pass is deliberately release-readiness work rather
than a 4B blocker, recorded in `docs/RELEASE_REVIEW.md`.

### Phase 5 design decisions already made with Jack

These are settled - implement against them rather than reopening them.

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
- **Migration 2 is `ALTER TABLE ADD COLUMN` only** - `started_at`, `end_reason`,
  and indexes on `arena_id` and `(player_id, kit_id)`. All four dialects spell
  that identically. `winner_id` stays `NOT NULL`: no code path ends a match
  without a winner, and dropping a `NOT NULL` on SQLite means a table rebuild.
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

Implementation order: migration 2 and `MatchEndReason` threaded through
`endMatch`; then `StatsQuery`/`PlayerStats` and both repositories with tests
asserting SQL and YAML agree; then `StatsManager` wiring, which already orders
reads behind writes so filtered reads inherit that; then the profile menu; then
`/duel top` sorting; then docs.

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

1. Phase 4B: close out the three verification residuals (Stages 1-3 of
   `docs/IN_GAME_SUITE.md`). Implementation is done.
2. Phase 5: deeper statistics and tracking.
3. Phase 6: Vault integration and durable, idempotent rewards.
4. Phase 7: local matchmaking queues.
5. Phase 8: ELO/MMR/SBMM, after stats and matchmaking exist.
6. Phase 9: network readiness boundaries, then a separate Velocity-side
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
- Continue Phase 4B from its incremental implementation plan; do not rewrite V1
  or re-run already-completed phases.
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
  outstanding, consolidated in run order.
- Historical full manual V1 suite, kept as reference: `docs/V1_TEST_PLAN.md`
- Rationale behind each acceptance item, and the record of what Parts A-C passed:
  `docs/RETEST_PLAN.md`. The older `IN_GAME_TEST_PLAN.md` it superseded has
  been removed.
- Containment rationale, commit-by-commit, and the known-limitation sign-off:
  `docs/CONTAINMENT_TEST_PLAN.md`
- Earlier focused regression/retest checklist, all passed: `docs/RETEST.md`
- Release-readiness gaps and known boundaries: `docs/RELEASE_REVIEW.md`
- Learning notes: `docs/LEARNING.md` and `docs/JAVA_CONCEPTS_AND_JCORE.md`
