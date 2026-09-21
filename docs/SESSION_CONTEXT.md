# Duels Current Session Context

_Last verified: 2026-09-21_

This file is the compact handoff for a new development session. Read it before
starting work, then inspect the relevant source files before changing code.

## Current state

Duels V1 is complete, tested, and manually verified on a real Paper server.
Phase 4B is implemented but needs a fresh real-Paper pass after the exclusive
STATIC/DYNAMIC setup redesign before sign-off.

Verification at the last handoff:

- `mvn -o test`: 43 tests passed, 0 failures, 0 errors, 0 skipped.
- `mvn -o package`: passed and copied the shaded jar to the configured test
  server plugins folder. Target-Paper acceptance remains to be checked.
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

## Current intentional limitations and remaining verification

- Selected-arena challenges have both command and player GUI paths. Dynamic
  admin setup has GUI controls for source, capture corners/template, health,
  retry, and retirement; real-Paper validation is still pending.
- Dynamic capture/provisioning/recovery is implemented but still requires the
  complete real-Paper run in `docs/IN_GAME_TEST_PLAN.md` before sign-off.
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

## Next development target: V2 Phase 4B

The next implementation phase is **Dynamic Arena Provisioning**, including
player/admin arena selection. The desired flow is:

1. A player or queue chooses an arena template/theme such as Desert or Castle.
2. STATIC uses a free hand-built playable copy. DYNAMIC uses a free generated
   copy or provisions a new one in a dedicated void-world slot.
3. A DYNAMIC source is only the build reference; it never hosts a duel.
4. The resulting copy becomes a normal `ArenaInstance`, so existing match,
   bounds, spectator, and reset systems continue to work.

The design must support both modes:

- **Static mode:** small/friends servers manually build a finite number of
  copies; no dynamic infrastructure is required.
- **Dynamic mode:** larger servers build one source, capture it and provision
  playable copies on demand.

The baseline should use Paper/vanilla Structure APIs rather than requiring
WorldEdit/FAWE. WorldEdit/FAWE may be an optional integration later. Whole
world-per-match generation is not the baseline because of blocking world I/O,
memory cost, and the fact that it does not remove the capacity limit of one
server.

Phase 4B core implementation is now present. Admins capture a Paper NBT template from
separate edit-session structure corners; template spawns and gameplay bounds are saved
as offsets. A dynamic arena reuses a free generated copy first, otherwise
reserves a persisted grid slot in a lazy Duels void world, prepares chunks, pastes the
template, and publishes a normal provisioned `ArenaInstance`.

Allocation and match start are asynchronous: `MatchManager` owns a pending-player
reservation and does not mutate either player until provisioning succeeds. Challenges
carry either Any or a specific arena selection (`/duel challenge <player> <arenaId>`) and
are claimed during preparation rather than consumed early. Provisioned instances persist
health states and startup rebuilds interrupted/dirty copies; retirement is bounded and
failed copies can be retried.

The remaining Phase 4B work is target-Paper manual verification (capture,
paste, capacity, shutdown/restart, spectator and reset paths), followed by a focused
hardening review. See `docs/PHASE_4B_DESIGN.md` and `docs/ROADMAP.md`.

The latest GUI/setup change from Jack's in-game review:

- The creation GUI chooses STATIC/DYNAMIC before naming. STATIC screens show
  hand-built playable copies and no structure controls. DYNAMIC screens show
  one source, template capture, and generated copies. Gameplay bounds support
  confinement/rollback; structure corners belong only to the source.
- Capture corners are temporary per-admin/per-instance drafts shared between the
  GUI and edit tools, kept on leaving edit mode but cleared at disconnect or when
  another instance is selected. They are never persisted as arena configuration.
- Orange structure preview is independent of gameplay bounds. The GUI now captures
  templates and guards generated-copy retirement. `/duel select <player>` exposes
  the selected-arena challenge flow to players without requiring IDs.
- The acceptance suite is ordered by in-game flow. All checks are open for a
  fresh pass on this build.

Current follow-up from Jack's next real-Paper test:

- Capturing arena #2 from instance #3 exposed Paper's two-corner `Structure.fill`
  saving one fewer block on every axis than our inclusive metadata. Capture now
  calls the explicit origin-and-size overload; the regression test checks a
  reversed inclusive 3x4x5 selection. Recheck on the target Paper server.
- Bundled arena detail reserves a third Back row; instance detail spreads
  controls across three content rows and a fourth Back row; the kit editor
  moves Save above its Back row. Untouched legacy layouts are migrated on
  startup; custom slot layouts remain intact.
- The hybrid allocator and GUI were replaced by exclusive per-arena flows.
  Existing one-copy STATIC arenas have guarded explicit conversion (preserving
  setup), and old one-manual-copy DYNAMIC arenas migrate their source at
  startup. Multi-copy legacy DYNAMIC arenas require explicit source adoption;
  no manual copy is accidentally matched.

## Recommended V2 order

1. Phase 4B: arena selection and dynamic provisioning, while preserving static
   mode.
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
- Full manual V1 test suite: `docs/V1_TEST_PLAN.md`
- Current complete V1 + Phase 4B in-game acceptance suite:
  `docs/IN_GAME_TEST_PLAN.md`
- Focused regression/retest checklist: `docs/RETEST.md`
- Learning notes: `docs/LEARNING.md` and `docs/JAVA_CONCEPTS_AND_JCORE.md`
