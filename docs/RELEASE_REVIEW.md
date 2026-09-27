# Release review

## Status

The pre-Phase-5 codebase reached release-candidate status and its complete in-game suite passed. SQLite, MySQL, MariaDB and PostgreSQL were each exercised manually against the earlier statistics schema. Phase 5 now has automated SQLite/YAML coverage and still requires its fresh-storage in-game acceptance pass, including a repeat of the external SQL matrix before release. One wider release pass also remains:

1. **`docs/TESTING.md`** - the clean-install release matrix on a fresh server, especially inventory interaction, crash recovery and damage attribution. Database behaviour is already manually verified, but the clean-install path is still a distinct release gate.

## Release issues fixed during review

- Active matches are aborted safely and online players restored during plugin disable.
- Match termination is idempotent, preventing duplicate restoration or stat writes.
- Existing matches hold independent kit snapshots; live kit edits and deletion affect future matches only.
- Arena and kit IDs are allocated monotonically and no longer reused after deletion.
- First database access is synchronized, preventing competing pools and migrations.
- Migration tables are namespaced per consuming plugin.
- Duels' initial SQL migration creates the complete Phase 5 schema on fresh storage, including portable indexes. An old test schema is rejected with an actionable startup error rather than being partially upgraded.
- JCore drains queued async work before closing the database pool.
- JCore closes owned menus before shutdown so editable-menu restore callbacks run.
- JCore uses a bounded background executor instead of an unbounded cached pool.
- Pending chat-input requests are thread-safe and removed on disconnect.
- Invalid saved-player entries are skipped with a warning instead of stopping startup.
- Player-state cache entries are removed only after a successful apply and file update.
- Kit editor item movement no longer consumes admin items or permits copying kit items.
- Editable menus block drop actions and validate offhand swaps.
- Kit-selection navigation starts a fresh navigation flow and stale clicks are rejected.
- Kit previews use the match snapshot even if the live kit has been deleted.
- Third-party damage is blocked in both directions, including projectiles, tamed pets, primed TNT, lingering clouds, and evoker fangs.
- SQL win/loss and leaderboard queries use portable boolean parameters for PostgreSQL, MySQL, MariaDB, and SQLite.
- Players are cleaned again when selection ends, so empty kits and bare-fist arenas cannot retain countdown pickups or effects.
- Arena spawn buttons implement their documented left-click set and right-click teleport behavior.
- Players may hold requests from several opponents; duplicate requests for the same pair are rejected and unrelated requests are cleared when a match starts.
- Invalid countdown settings fall back to safe values with warnings.
- Newly added YAML defaults are reloaded immediately, and database defaults merge without an existing-file warning.
- Admin menu sections respect arena/kit permissions.
- Arena and kit list order is deterministic by ID.
- The machine-specific Maven copy step was removed.
- Duels now has integration tests for IDs, challenges, kit snapshots, command-created kit slot separation, and deep kit copies.
- `/duels diagnostics` reports every manager's live counters with per-admin baseline/compare, so an admin can see whether a flow leaked rather than inferring it from the absence of visible problems.

## Known boundaries

- Duels defines arena bounds and contains a duel to them: a duellist cannot break or place outside the box, explosions and fire/liquid spread are trimmed at it, and changes inside it are rolled back at match end. It still does not stop *outsiders* entering an arena or teleporting into one, so a protection or world-management system is still wanted for that.
- YAML stats rewrite a growing file and are intended for small installations. SQLite or an external SQL server is the better long-term choice.
- SQL write failures are logged and do not block match cleanup. There is no durable retry queue, so a result can be lost while the database is unavailable.
- Arena and kit definitions remain YAML-backed even when match statistics use SQL.
- Existing installations created before monotonic ID metadata cannot reconstruct IDs that were already deleted. From this release onward IDs are not reused.
- All four supported database engines passed manual repeated-match/stat checks against the earlier schema. Exact external engine versions were not recorded, and non-SQLite dialects remain outside automated coverage; the new Phase 5 schema must repeat that matrix on fresh storage.

## Automated verification

- JCore: 303 tests run with no failures or errors. One inventory-click test is skipped because MockBukkit does not implement the required slot conversion; its behavior remains in the Paper acceptance plan.
- Duels: 73 tests run with no failures or errors, covering plugin startup, monotonic IDs, multiple challenge requests, match kit snapshots, command-created kit slot separation, kit deep copies, strict arena fields, composite SQL/YAML statistics parity, match timing/end metadata and streak calculations, the full PREGAME/GRACE/IN_PROGRESS lifecycle including disconnect forfeits, spectator lifecycle, two-instance concurrency, block-change rollback, arena containment, bounds geometry and openings/visibility advisories, diagnostics cleanup, bystander protection throughout asynchronous arena reset, and bounded dynamic-slot capacity/reservation/retirement behaviour. Shutdown restoration and the Phase 5 menus remain real-server acceptance checks because MockBukkit does not implement every PlayerState/UI behaviour used by Paper.
- The shaded Duels jar builds successfully with JCore and database drivers. This checkout also has a machine-local package-phase copy into the test server; that final copy can fail while the running server holds the old jar open, without invalidating the built artifact.
- Not covered automatically, and therefore reliant on manual passes: `DynamicArenaProvisioner`/`DynamicArenaRecovery` end to end, menu click handling (the MockBukkit slot-conversion test is skipped), shutdown-time player restoration, and every SQL dialect other than SQLite. All have current manual evidence except the clean-install release matrix noted above. The confidence table in `docs/SESSION_CONTEXT.md` records how much each system is trusted and why.
