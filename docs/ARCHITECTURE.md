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
