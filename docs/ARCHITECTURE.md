# How JCore and Duels fit together

## The shortest useful mental model

JCore supplies reusable infrastructure. Duels supplies the rules of the game.

For example, JCore knows how to read a YAML section and ask a serializer to turn it into an object. It does not know what an arena means. Duels defines `Arena`, `ArenaSerializer`, and `ArenaManager`; JCore supplies `YamlFile`, `SerializerManager`, and `YamlRepository<Arena>`.

The usual flow is:

```text
Paper event or command
        ↓
Duels command/listener/menu
        ↓
Duels manager (game rule or use case)
        ↓
JCore service (file, repository, task, database, message, menu)
        ↓
Paper / YAML / SQL
```

## Deployment modes are a deliberate product requirement

Duels is intended to support a range of server sizes without forcing every
server to install or operate the infrastructure needed by the largest one.
The deployment mode changes how arenas are supplied and how players are routed;
it does not change the core match lifecycle.

### Standalone Paper server with static arenas

This is the simplest supported mode. An administrator manually builds and
registers a finite set of `ArenaInstance` copies. `StaticArenaAllocator`
claims one free copy for each match. No dynamic world, structure-pasting
system, proxy, Redis instance, or external service is required.

### Paper server with opt-in dynamic arena instances

A server may create particular arenas as DYNAMIC. Each has one hand-built
SOURCE that never hosts a match. Duels captures it and generates playable
copies in its dedicated arena world, reusing free generated copies before
creating more. STATIC arenas have only hand-built playable copies and no
structure capture. Both types can coexist on one server, but one arena never
mixes the two kinds of playable copy.

The implementation architecture and recovery model are specified in
`docs/PHASE_4B_DESIGN.md`.

The chosen baseline is one bounded, persisted slot grid in one Duels-owned void
world. A loaded world per duel would multiply world/chunk/tick lifecycle overhead
without helping the current gameplay requirements. If a future feature needs only
different visual time or weather, use Paper's player-scoped presentation APIs. If it
needs authoritative independent weather, time, gamerules, spawning, dimension rules,
or whole-world discard, reconsider one reusable world per pooled `ArenaInstance` -
never a freshly created world for each match.

Slot visibility is a presentation concern rather than an allocation invariant. When a
template is provisioned or recovered, `DynamicArenaWorldManager` compares its real
captured footprint's edge-to-edge separation with the world's chunk send distance and
logs one advisory warning per arena if an adjacent slot may be visible. It does not
silently move persisted slots or refuse provisioning, because neighbouring arenas may
be an intentional admin choice.

This dynamic mode means creating arena copies inside one Paper server. It does
not mean creating new Minecraft servers. The match, spectator, reset, and
player-restoration systems continue to work against a playable `ArenaInstance`
regardless of whether it was manually built or provisioned. The allocator
filters out the SOURCE before the match lifecycle begins.

### Paper backends behind a Velocity network

Duels continues to run on the backend Paper servers, where Bukkit/Paper owns
players, worlds, arenas, and matches. A future Velocity companion handles
connection routing and player transfers; Velocity cannot call Bukkit APIs and
is not a place to run `MatchManager`.

The first network stage can use fixed backend servers, each with its own local
static or dynamic arena instances. Shared statistics and later cross-server
matchmaking are separate concerns and are introduced only when a real network
deployment needs them. A standalone server must not need those services.

The important invariant is therefore: deployment-specific allocation and
routing sit at the boundary, while the local match lifecycle remains reusable.
A future network allocator should choose a backend by identity and capacity, not by
knowing that backend's world name or slot coordinates. The selected Paper backend
continues to own its local shared-world (or any future per-instance-world) details.

## JCore's central object

`JCore` is a facade: one object that creates and exposes the shared services for a plugin.

Duels creates it in `Duels.onEnable()`:

```java
jCore = JCore.create(this, DatabaseConfigLoader.load(this));
jCore.initialize();
```

After that, Duels uses focused services:

- `jCore.files()` for YAML files.
- `jCore.serializers()` for object conversion.
- `jCore.messages()` for configured messages.
- `jCore.commands()` for command trees.
- `jCore.menus()` for inventory menus and navigation.
- `jCore.tasks()` for server-thread and background work.
- `jCore.database()` for SQL work.
- `jCore.migrations()` for schema versions.

The facade avoids constructing those systems in every plugin class. It is composition rather than inheritance: Duels uses JCore services; Duels does not extend a JCore plugin class.

## Files, serializers, and repositories

These three layers answer different questions.

### `YamlFile`: where is the data?

`YamlFile` owns one file and provides operations such as `get`, `set`, `save`, `reload`, and default merging. It deals in YAML-compatible values such as strings, numbers, lists, and maps.

### `Serializer<T>`: how does one object become data?

`ArenaSerializer` converts an `Arena` to a map:

```text
Arena
  name = "Castle"
  spawn1 = Location(...)
  enabled = true

becomes

name: Castle
spawn1: { ... }
enabled: true
```

Deserialization performs the reverse operation. A `RepositorySerializer<T>` also receives the repository key as the object's ID. That is why the ID does not need to be duplicated inside every arena entry.

### `YamlRepository<T>`: how are many objects stored by ID?

The arena repository is configured with:

```java
new YamlRepository<>(file, serializers, "arenas", Arena.class, Arena::getId)
```

That line gives the repository everything it needs:

- The file to use.
- The serializer registry.
- The root YAML path (`arenas`).
- The type to deserialize.
- A function that extracts an ID when saving.

`findAll()` reads each key below `arenas`, parses the key as an ID, and calls the registered `ArenaSerializer`. Its return type is `List<Arena>`, so `ArenaManager` receives real objects rather than raw maps.

`ArenaManager` then keeps those objects in memory for fast gameplay access. Saving through the manager updates the in-memory map and the repository. Directly mutating an arena changes the same object held by the map, but it does not persist the change; calling `arenaManager.save(arena)` is still required.

The repository now also reserves monotonically increasing IDs. Deleting kit `7` will not allow a different kit to become `7` later, which keeps historical statistics meaningful.

## Managers are the use-case layer

Managers coordinate domain objects and infrastructure:

- `ArenaManager` creates, finds, saves, sorts, and deletes arenas.
- `KitManager` does the same for kits.
- `ChallengeManager` owns pending challenges and their expiry tasks.
- `MatchManager` owns active matches and the transition from selection to combat to restoration.
- `StatsManager` selects a storage implementation and exposes queries without callers caring whether storage is YAML or SQL.

Code outside a manager should avoid reproducing its rules. For example, allocating an arena ID belongs in `ArenaManager`; a menu should ask the manager to create the arena instead of calculating an ID itself.

There is still room to tighten this boundary. Arena menus currently mutate an `Arena` and then call `save`. A future refactor could expose operations such as `renameArena(id, name)` and `setSpawn(id, slot, location)`. That would put validation, active-match checks, mutation, and persistence in one place. Do this because it creates one authoritative rule path for commands and menus, not merely to shorten menu classes.

## Commands

JCore commands form a tree. Each node can define:

- A name and aliases.
- A permission.
- Player-only execution.
- Typed required or optional arguments.
- Child commands.
- An executor.

For `/duels arena setspawn 3 1`, `CommandManager` walks through `duels`, `arena`, and `setspawn`, checks permissions at each node, parses both integer arguments, creates a `CommandContext`, and invokes Duels' method.

This removes repeated string parsing from plugins. The tradeoff is that parent permissions are also checked. A user granted only the leaf permission cannot reach it unless they also have the required parent permissions. Keep that rule documented or change it deliberately in JCore; do not let individual plugins assume different behavior.

## Menus and navigation

There are two main menu builders:

- `ConfiguredMenu` handles fixed named buttons from `menus.yml`.
- `PaginatedMenu<T>` handles a list of entries and reserves its last row for navigation.

The config controls appearance. Duels controls behavior. For example, `menus.yml` defines how an arena button looks, while `ArenaListMenu` supplies placeholders and the click callback.

`MenuContext` carries the player, clicked slot, click type, event, and page. This is why Duels can make left click select a kit and right click preview it without putting game-specific behavior into JCore.

`MenuNavigator` stores render functions rather than inventory objects. Opening a child pushes the current render function onto a stack. Back pops and reruns it, rebuilding the screen from current data. That prevents stale menus after an object changes.

Editable slots are different from buttons. JCore allows normal item movement only in marked slots and validates equipment slots. Duels' kit editor snapshots the admin's real inventory and restores it on close, so its contents act as a template rather than a container that consumes or duplicates items.

## Tasks and countdowns

Bukkit objects generally belong on the server thread. Blocking work such as SQL belongs on a background executor.

`TaskManager` provides both paths. `SqlStatsRepository` submits SQL work asynchronously. `LeaderboardMenu` waits for its futures and then uses `runSync` before creating player heads and opening an inventory.

`Countdown` is a small lifecycle object built on repeating server tasks. `MatchManager` supplies callbacks: display the remaining time, then apply kits and switch the match to `IN_PROGRESS`.

JCore shuts its executor down before closing the database pool, allowing queued writes to finish. Database initialization is synchronized so simultaneous leaderboard queries cannot create several pools or race migrations.

## Database and migrations

`Database` hides connection-pool and JDBC boilerplate. Its operations are synchronous; the caller chooses whether to invoke them through `TaskManager`.

Migrations are ordered schema changes. Duels registers migration `1`, which creates match and participant tables plus indexes. JCore records the applied version in a table named for the consuming plugin, preventing two JCore-based plugins from sharing one migration version sequence accidentally.

The database is lazy: configuring SQL does not connect during JCore construction. The first database operation connects and runs every registered migration once. Duels keeps match writes and stat reads off the server thread.

## Player state and match lifecycle

`PlayerState` is a snapshot of one player's inventory, location, health, XP, game mode, flight, effects, attributes, and related flags. `PlayerStateManager` persists that snapshot before a duel changes the player.

The normal lifecycle is:

```text
challenge accepted
  → choose free arena
  → close menus and persist both player states
  → snapshot the arena's currently allowed kits
  → clear/normalize players and teleport them
  → select kits while combat is disabled
  → apply selected/default snapshots
  → enable combat
  → determine winner
  → record stats asynchronously
  → restore both saved states
```

Kit snapshots are held by the match. Editing or deleting a live kit therefore changes later matches only. Arenas are not snapshotted, so Duels blocks arena mutation while one is active.

Snapshots also provide restart and crash recovery. During a full server stop, Duels leaves the durable snapshots in `playerstates.yml`; the join listener restores each player and tells them what happened on the next startup. During a plugin-only disable or reload, active players are restored immediately before JCore shuts down.

## "Am I fighting?" and "am I watching?" are two questions

Membership of `MatchManager`'s participant-keyed map *is* the definition of a combatant. It is not a cache of who happens to be in an arena - every listener answers "is this player in a duel?" by calling `getMatch(uuid)`, and acts on the match it gets back. The most damaging consequence of blurring that is `MatchListener.onPlayerQuitWhileInMatch`: if a spectator were reachable through that map, a spectator closing their client would award the duel to one of the fighters.

Spectators therefore live entirely in `SpectatorManager`, in a separate UUID-keyed map of `SpectatorSession`, and never appear in `MatchManager`'s map. A `SpectatorSession` records what to put back (game mode, flight flags, return location) plus which match is being watched. Code that needs to know "is this player watching a duel?" asks `SpectatorManager.isSpectating(uuid)`; code that needs "is this player fighting?" still asks `MatchManager.getMatch(uuid)`. Keeping the two questions separate is what makes it safe for a spectator to stand inside a live arena.

Most of the interference problem is handled by `GameMode.SPECTATOR` itself, which already prevents dealing and taking damage, block and inventory interaction, and collision, and hides the player from non-spectators. What it does *not* do is constrain position, so `BoundaryEnforcer` resolves spectators as well as combatants - always walling them in immediately, regardless of the arena's configured boundary mode, since forfeiting is meaningless for somebody who is not fighting. Nor does it prevent camera-locking onto an arbitrary entity, which would let a knocked-back fighter drag a spectator's position out of the arena; `SpectatorListener` cancels `PlayerStartSpectatingEntityEvent` for anything other than the two combatants.

Sessions are persisted to `spectators.yml` for the same reason `PlayerStateManager` writes to disk rather than holding a `HashMap`: on a crash neither `PlayerQuitEvent` nor `onDisable` runs, and a spectator would come back stuck in spectator mode inside an arena with no way to fix it themselves. Disconnect and server shutdown deliberately reuse that path - the live session is dropped but its row is kept, and the join listener finishes the restoration - because teleporting a player who is already on their way out is not dependable.

## Storage strategy

`StatsRepository` is an interface with YAML and SQL implementations. `StatsManager` chooses one from configuration and the rest of Duels talks only to the interface.

This pattern is useful when implementations genuinely differ but callers need the same operations. It is less useful for classes that have only one implementation and no testing seam; adding interfaces everywhere would create ceremony without flexibility.

## Refactors already done

1. **Arena mutations go through `ArenaManager`.** `rename`, `toggleEnabled`, `setSpawn`, `toggleKitAllowed`, and `deleteArena` each return an `ArenaMutationResult` (`SUCCESS` / `NOT_FOUND` / `IN_USE`). Commands and menus branch on that result instead of each re-checking `arenaManager.getArena(id) == null` and match activity around a raw setter. `ArenaManager` doesn't depend on `MatchManager` directly - `Duels.initializeManagers()` wires `arenaManager.setActiveCheck(matchManager::isArenaInUse)` after both exist, avoiding a constructor cycle. `ArenaManager.isActive(id)` exposes the same check read-only, for menus that want to gate a flow (e.g. not even opening a rename prompt) before attempting a mutation that would just be rejected.
2. **Selected/applied kits live on `Match`.** `Match.recordSelectedKit`/`getSelectedKit` and `recordAppliedKit`/`getAppliedKit` replaced `MatchManager`'s two parallel `Map<UUID, ...>` fields. `MatchManager.forgetParticipant` no longer needs to remember to clean up three maps in lockstep - there's only the one `matches` map left, and the per-match kit data is freed automatically once nothing references the `Match` anymore.
3. **`JCore` gained `InventoryEditSession`** (`me.jackcw.jcore.menu`), lifted from `KitEditMenu`'s private snapshot/restore class. Any future editable-slot menu (not just kit editing) can capture a player's real inventory before opening a template menu and restore it in `onClose`, without re-implementing the same capture/clone/restore logic.
4. **`CommandManager` supports a permission policy.** `PermissionPolicy.PARENT_AND_LEAF` (default, unchanged behavior) requires every ancestor's permission to reach a subcommand; `PermissionPolicy.LEAF_ONLY` checks only the node that actually executes, treating intermediate nodes as pure routing. Set via `commandManager.setPermissionPolicy(...)` before registering commands. Duels doesn't use `LEAF_ONLY` - it's there for a future plugin that needs it, without touching Duels' existing command trees.

## Refactors intentionally not done

These two items from the original roadmap were skipped because their own stated trigger condition hasn't happened - building them now would be speculative:

- **A `MatchFactory`/allocation service** - only worth it if arena modes, teams, or remote servers are added. Matches remain simple enough that direct `new Match(...)` construction in `MatchManager.startMatch` is still the right amount of code.
- **Splitting the `PlayerState` serializer into value components** - only worth it if a second plugin needs partial state capture. Its current size is understandable as-is because it preserves one complete state atomically; there's no second consumer yet to design the split around.

Also skipped, but only for lack of tooling in this environment, not by design: **Testcontainers integration tests for MySQL, MariaDB, and PostgreSQL** need Docker, which isn't available here. MockBukkit and SQLite tests still can't prove vendor-specific DDL and generated-key behavior - revisit this once a machine with Docker is available.

The safest simplification rule is: remove duplicated decisions, not explicit boundaries. Managers, serializers, repositories, and storage interfaces each currently answer a separate question and should remain separate.

## Concurrency model: the main thread as the mutex

Duels doesn't use `synchronized`, locks, or concurrent collections anywhere in its arena,
kit, challenge, or match state - and this is correct, not an oversight. `ArenaManager`,
`KitManager`, `ChallengeManager`, and `MatchManager` are only ever mutated from Bukkit's
main thread: command execution, menu clicks, and event handlers all run there. Because
Bukkit guarantees only one thread ever runs game logic at a time, the main thread itself
acts as the mutex - there is never a moment where two admin actions or two players'
commands are executing this code concurrently.

The one place genuine concurrency exists is around I/O: `TaskManager`'s bounded async
executor (used for database work) and JCore's `ThreadLocal<Connection>`-based transaction
handling. That boundary is deliberately narrow - async code does the slow work (DB
reads/writes) and hands results back to the main thread via `runSync` before touching any
manager state. `YamlRepository.reserveId()` is `synchronized` for the same reason it's
the exception, not the rule: it's occasionally called from contexts where two overlapping
calls aren't ruled out by the main-thread guarantee alone.

The practical implication for future work: new manager state does not need locking as
long as it's only ever touched from the main thread. The moment something needs to be
read or written from an async callback directly (rather than via `runSync`), that's the
signal a real concurrency boundary has been crossed and needs explicit handling - not a
default assumption to apply everywhere.

## Arena is not snapshotted; Kit is - a deliberate asymmetry

`Match` deep-copies the kits it will offer (`availableKits = kits.stream().map(Kit::copy).toList()`)
at construction time, but holds its `Arena` by direct reference. This looks inconsistent
until you look at *why* each choice was made:

- **Kits are snapshotted** because a kit is just data (items, potion effects) with no
  physical presence. Copying it is cheap, and once copied, the match is fully isolated
  from any future edit or deletion of the live kit - `KitManager.deleteKit` has no
  "in use" check anywhere, because it doesn't need one.
- **Arenas are not snapshotted** because an arena *is* a physical place - it can't be
  copied by value the way a kit can. Instead, `ArenaManager` blocks destructive
  mutation (rename, delete) while `MatchManager.isArenaInUse` reports the arena as
  occupied, via the `activeCheck: IntPredicate` wired in after construction to avoid a
  constructor cycle between the two managers.

The general rule this establishes for future configurable objects: if a resource is pure
data, prefer snapshotting it into the match at creation time - it's simpler than locking
and avoids ever having to reason about "what if the source gets deleted mid-match." If a
resource has a real-world/physical identity that can't be meaningfully copied, protect it
with an in-use check instead. Phase 1 of `ROADMAP.md` (arena configuration vs. arena
runtime instance) extends this same reasoning rather than replacing it.

## Arena bounds are a box of blocks, and should enclose the arena

Bounds are stored as two opposite corners, and every question asked of them
("is this block inside?", "how big is it?", "where do I draw the frame?") goes
through `BlockBox`. Two rules follow from that, and both have already caused
bugs when a piece of code worked them out for itself instead:

**Both corner blocks are inside the box.** Corners are snapped to whole blocks
on the way in (`ArenaInstanceManager.setBoundsCorner`) *and* on comparison
(`BlockBox.of`). Snapping on comparison is what lets an arena configured before
that rule existed pick up the correct box on load with no migration. A box whose
corners are X=10 and X=12 is therefore three blocks wide, not two.

**A box measured in blocks has two different maximum coordinates.** The maximum
*block* is `maxX()`; the far face of that block, in continuous world
coordinates, is `maxCornerX()` - one greater. Anything drawing or measuring the
box in world space needs the latter. The edit-mode particle frame originally
used the former, which outlined a box one block short on every maximum face and
read in game as the whole frame sitting a block below the area actually being
enforced - which in turn made a correctly-set corner look as though it had
snapped inwards.

### What being inside the bounds actually means

Bounds do not make a block indestructible - they make it *tracked*. The two
sides of the boundary get opposite treatment, and which one an admin wants
differs per surface:

- **Inside the bounds**, a duellist may freely break and place. Every change is
  recorded by `BlockChangeRollbackStrategy` and replayed in reverse at match
  end, with drops suppressed so the repaired arena is not littered with items.
- **Outside the bounds**, a duel simply cannot reach. `ArenaContainmentGuard`
  cancels a duellist's break or placement, trims out-of-bounds blocks out of an
  explosion's `blockList()`, and refuses fire and liquid spread across the
  boundary. Nothing needs restoring because nothing changed.

So the recommended setup for a hand-built arena is to draw the box around the
**interior**: click the floor block in one corner and the ceiling block in the
diagonally opposite one. The floor and ceiling are then inside the box and get
repaired after a TNT fight, while the four walls sit one block outside it and
are immune for the duration of the match. An admin who instead wants breakable
walls can include them, and they will be restored rather than protected.

This is also why the walls being outside the box is not a gap to warn about -
it is the normal case.

### Decoration is protected, not restored

Item frames, paintings and armour stands are entities, not blocks, so no block
event fires for them and the rollback strategy can neither record nor restore
them. They are therefore protected outright while a match holds the arena:
`ArenaContainmentGuard` cancels `HangingBreakEvent` and any `EntityDamageEvent`
aimed at them inside a live arena's bounds.

Protecting rather than restoring is the deliberate choice, because it matches
what already happens to the walls - anything the admin built and the duellists
are not meant to touch survives the match untouched. Restoring would mean
reimplementing entity persistence for a case where the simpler rule is also the
one admins expect.

### Containment starts when the arena is occupied, not when combat does

Both duellists are teleported to their spawns in `MatchManager` as soon as the
match is created, while it is still `PREGAME` and they are choosing kits.
"Standing in the arena" therefore begins well before `IN_PROGRESS`, and rules
about what a duel may do to the *world* key on `Match.isLive()` instead - true
for `PREGAME`, `GRACE` and `IN_PROGRESS`. A bucket of lava emptied at a spawn
during kit selection is in the arena just as much as one emptied mid-fight, and
previously was neither prevented nor restored.

Rules about how a duellist may be *treated* still key on `IN_PROGRESS`, because
those depend on the fight actually being live. The two are genuinely different
questions and the split is intentional.

### Bounds with an opening make liquid look stuck

`ArenaBoundsValidator` advises on this when a corner is set. Minecraft picks a
fluid's spread direction *before* any event fires, and it does not try every
open neighbour - if the fluid can fall it commits to falling, and otherwise it
flows only towards the nearest place it could fall.
`ArenaContainmentGuard.onBlockFromTo` then vetoes that direction when it leaves
the arena, but **a veto is not a redirect**: if the direction Minecraft chose was
out through an opening, the fluid spreads nowhere at all and appears frozen,
even with open space inside the arena.

The failure is cosmetic rather than a containment leak - nothing escapes either
way - which is why this is advice and never a refusal. The fix is not to
re-implement fluid spread so we can redirect it; that means shipping our own
copy of Minecraft's fluid physics and keeping it in sync forever.

The test the validator applies is worth stating precisely, because the obvious
version of it is wrong. Asking "is the box's own outermost layer solid?"
condemns the recommended interior setup, where every boundary block is air and
nothing can escape regardless, because the real walls are one block further
out. An opening needs two things at once:

1. the boundary block is passable, so a liquid could actually be there;
2. the block immediately **outside** the box in that direction is also passable,
   so Minecraft may choose to spread that way.

Together those flag a genuine doorway, window or hole in the floor, while
staying quiet for a sealed room whichever way the box was drawn.

The roof is deliberately exempt: fluid never flows upward, so an open-topped
arena is a design choice rather than a mistake. Warning about it would only
teach admins to ignore the warning.
