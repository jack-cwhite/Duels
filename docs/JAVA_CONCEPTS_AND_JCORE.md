# Java concepts behind JCore (and where Duels uses them)

This is a companion to `ARCHITECTURE.md`. That doc explains how the *systems* fit
together. This one explains the *Java language features and design patterns*
that make those systems work — the stuff that's easy to skim past without
really understanding, especially if some of it is new to you.

Each section follows the same shape:
1. **What it is** — a tiny, isolated example, nothing to do with Minecraft.
2. **Why you'd reach for it** — the problem it solves that simpler code can't.
3. **Where it's actually used** — real file/line references from JCore and Duels.

Read it top to bottom once, then use it as a lookup table later when you hit
something in the code and think "wait, why is this written like *this*?"

---

## 1. Interfaces as contracts, not just "Java requires it"

**What it is.** An interface says "anything that claims to be one of these
must provide these methods" — it says nothing about *how*.

```java
interface Shape {
    double area();
}

class Circle implements Shape {
    double radius;
    public double area() { return Math.PI * radius * radius; }
}
```

Code that only knows about `Shape` can call `.area()` on a `Circle`, a
`Square`, or anything else that implements `Shape`, without ever being
rewritten when you add a new shape.

**Why you'd reach for it.** It lets you write code against "a thing that can
do X" instead of "specifically a Circle." That's what makes swapping
implementations possible later without touching the calling code.

**Where it's used.**

- `Database` (`JCore src/main/java/me/jackcw/jcore/database/Database.java`) is
  an interface. `SQLiteDatabase`, `MySQLDatabase`, and `PostgreSQLDatabase`
  each implement it differently, but `SqlStatsRepository` only ever calls
  `plugin.getJCore().database()` and uses `Database` methods — it has no idea
  which one it's actually talking to, and doesn't need to.
- `StatsRepository` (Duels `stats/StatsRepository.java`) has two
  implementations, `SqlStatsRepository` and `YamlStatsRepository`.
  `StatsManager` picks one based on `config.yml`'s `stats-storage` setting and
  hands it out through the interface — every other class in Duels that wants
  stats talks to `StatsRepository`, never to the concrete class.

This is the **Strategy pattern**: the "strategy" (how stats are stored) is
swappable because callers depend on the interface, not the implementation.

---

## 2. Generics (`<T>`) — writing one class that works for many types, safely

**What it is.** A generic class or method is parameterized by a type you fill
in later.

```java
class Box<T> {
    private T contents;
    void put(T item) { contents = item; }
    T get() { return contents; }
}

Box<String> box = new Box<>();
box.put("hello");
String s = box.get(); // no cast needed, and box.put(5) won't compile
```

Before generics existed, you'd write `Box` using `Object` for everything, and
every caller would need to cast (`(String) box.get()`) — which compiles fine
even when it's wrong, and blows up at runtime instead of at compile time.
Generics move that mistake earlier, to compile time, where it's cheap to fix.

**Why you'd reach for it.** You want one reusable class/method, but you don't
want to give up type safety to get the reuse.

**Where it's used — this is everywhere in JCore:**

- `YamlRepository<T>` (`storage/YamlRepository.java`) is one class that stores
  *any* type as long as you tell it how (a `Class<T>` and a
  `Function<T,Integer>` to extract an ID). Duels uses the exact same class for
  both `YamlRepository<Arena>` and `YamlRepository<Kit>`
  (`Duels.java:201-207`) — no copy-pasted "ArenaRepository"/"KitRepository"
  classes needed.
- `Serializer<T>` (`serialization/Serializer.java`) — one interface,
  implemented once per type (`ItemStackSerializer implements
  Serializer<ItemStack>`, `KitSerializer implements Serializer<Kit>`, etc).
- `ArgumentType<T>` (`command/ArgumentType.java`) — `ArgumentTypes.player()`
  returns an `ArgumentType<Player>`, `ArgumentTypes.integer()` returns
  `ArgumentType<Integer>`. `CommandContext.get("player")` then hands back a
  real, already-typed `Player` — you never cast a raw `String` yourself.
- `PaginatedMenu<T>` (`menu/PaginatedMenu.java`) — one paging/rendering engine
  reused for `PaginatedMenuBuilder<Kit>` in `KitSelectorMenu` and any future
  paginated list of any type, without JCore needing to know what a `Kit` is.

**A generic method vs a generic class** — you'll also see this shape:

```java
public <T> CompletableFuture<T> submitAsync(Callable<T> task) { ... }
```

(`task/TaskManager.java`). Here `<T>` is scoped to just this one method, not
the whole `TaskManager` class — `TaskManager` itself isn't "for" any
particular type, but this one method needs to say "whatever type your
`Callable` returns, that's what future I'll give you back."

**The honest limitation worth knowing:** `CommandContext.get(String name)`
(`command/CommandContext.java`) looks like this:

```java
@SuppressWarnings("unchecked")
public <T> T get(String name) {
    return (T) arguments.get(name);
}
```

That cast is *unchecked* — nothing actually verifies at runtime that the `T`
you ask for matches what was stored. If you declared an argument as
`ArgumentTypes.player()` but call `context.<String>get("player")` by mistake,
this won't throw a nice error — it'll throw a confusing `ClassCastException`
somewhere later, or silently misbehave. Generics catch type mistakes at
compile time *most* of the time, but a manual cast like this is a place where
you're trusting yourself instead of the compiler. Good to know when you're
debugging a command that's misbehaving.

---

## 3. Functional interfaces and lambdas — passing behavior as a value

**What it is.** A **functional interface** is any interface with exactly one
abstract method. Because it only has one method, Java lets you skip writing a
whole named class to implement it — you can write the method body inline as a
**lambda**.

```java
interface Greeter {
    String greet(String name);
}

Greeter g = name -> "Hello, " + name; // a lambda implementing Greeter
System.out.println(g.greet("Jack"));  // "Hello, Jack"
```

Without lambdas, you'd write this as a whole separate class (or an anonymous
`new Greeter() { public String greet(...) {...} }` block) just to pass one
small piece of behavior around. Lambdas let you write that behavior exactly
where you use it.

**Why you'd reach for it.** Any time you want to say "run this later" or "call
me back when X happens" without building a whole class for a one-off piece of
logic.

**Where it's used — constantly:**

- `ArgumentType<T>` has one abstract method (`parse`), so
  `ArgumentTypes.string()` can just be:
  ```java
  public static ArgumentType<String> string() {
      return input -> input;
  }
  ```
  (`command/ArgumentTypes.java:21-24`) — that lambda *is* the whole
  implementation.
- `MenuClickHandler` — every button in every menu is a slot number mapped to a
  lambda: `menu.item("save", context -> save(player, kitId, context))`
  (`KitEditMenu.java:84`). The menu system doesn't need to know *what* saving
  a kit means — it just calls whatever lambda you gave it when that slot gets
  clicked.
- `Predicate<ItemStack>` (a built-in Java functional interface — one abstract
  method, `boolean test(T t)`) is used for editable-slot validation:
  `KitEditMenu::isBoots` (`KitEditMenu.java:76`) is a **method reference** — a
  shorthand for `item -> KitEditMenu.isBoots(item)` — passed directly as the
  `Predicate<ItemStack>`.
- `IntConsumer` / `IntFunction<String>` in `Countdown.Builder`
  (`countdown/Countdown.java`) are primitive-specialized versions of
  `Consumer<Integer>` / `Function<Integer,String>` — they exist purely to
  avoid boxing every tick's `int` into an `Integer` object. Small detail, but
  it's why you'll see `IntConsumer` instead of `Consumer<Integer>` in
  performance-sensitive, called-every-tick code.

**The catch: default methods break the "just use a lambda" trick.**
`ArgumentType<T>` has a second method with a body already provided:

```java
public interface ArgumentType<T> {
    T parse(String input) throws IllegalArgumentException;
    default List<String> suggest(CommandSender sender, String partial) { return List.of(); }
}
```

A lambda can only ever provide the *one abstract* method. So
`ArgumentTypes.player()`, which needs a real `suggest()` for tab-completion,
can't be written as a lambda anymore — it falls back to an anonymous class:

```java
public static ArgumentType<Player> player() {
    return new ArgumentType<>() {
        @Override
        public Player parse(String input) { ... }
        @Override
        public List<String> suggest(CommandSender sender, String partial) { ... }
    };
}
```

(`command/ArgumentTypes.java:76-104`). This is a useful rule of thumb: the
moment you need to override more than one method, or you need to keep state
between calls, you're back to a full anonymous (or named) class.

---

## 4. Records — a class that's just data, with the boilerplate written for you

**What it is.** A `record` declares a class whose entire job is holding a
fixed set of fields immutably. Java auto-generates the constructor, getters,
`equals`, `hashCode`, and `toString` for you.

```java
record Point(int x, int y) {}

Point p = new Point(1, 2);
p.x(); // 1, not p.getX() -- record accessors drop the "get" prefix
```

**Why you'd reach for it.** Any time you'd otherwise write a small class with
only a constructor and getters (no real behavior), a record says the same
thing in one line and can't accidentally be made mutable.

**Where it's used.**

- `TextInputManager` has `private record Request(Consumer<String> onSubmit,
  Runnable onCancel) {}` (`menu/TextInputManager.java:87-89`) — a tiny,
  private, internal-only bundle of "what to do when the player replies" and
  "what to do if they cancel." No getters to write by hand, no risk of
  someone mutating it later.
- `LeaderboardEntry` in Duels (`stats/LeaderboardEntry.java`) is the same
  idea — a `UUID` and a win count, nothing more, used purely to move data from
  a SQL query result to the leaderboard menu.

If you ever catch yourself writing a class that's *only* a constructor plus a
pile of getters and nothing else, that's the signal to reach for a `record`
instead.

---

## 5. The Builder pattern — constructing complex, immutable objects safely

**What it is.** Instead of one constructor with a long list of positional
parameters, you get an object whose setters each return itself, so you can
chain calls, followed by a final `.build()`.

```java
Pizza pizza = Pizza.builder()
        .size(12)
        .topping("pepperoni")
        .topping("mushroom")
        .build();
```

**Why you'd reach for it.** Two problems it solves at once:

1. **Readability** — `new Pizza(12, true, false, true, ...)` tells you
   nothing about which boolean means what. `.size(12).extraCheese(true)`
   does.
2. **Optional parameters without overload explosion** — you'd otherwise need
   a constructor for every combination of "with/without topping X."

**Where it's used.**

- `CommandBuilder` (`command/CommandBuilder.java`) — every `DuelCommand`,
  `DuelsCommand` you write is built this way:
  ```java
  CommandBuilder.command("duel")
      .description("...")
      .permission("duels.duel")
      .playerOnly()
      .optionalArgument("player", ArgumentTypes.player())
      .executes(this::handleDuel)
      .child(CommandBuilder.command("accept")...)
      .build();
  ```
  Each call returns `this` (see `command(...)`, `permission(...)`, etc. in
  `CommandBuilder.java` — every method ends `return this;`), which is what
  makes the chaining possible. `.build()` is the one method that *doesn't*
  return `this` — it produces a separate, immutable `CommandNode` (its lists
  are wrapped in `List.copyOf(...)`, so the finished command tree can't be
  mutated afterward).
- `PlayerState.Builder` (`serialization/PlayerState.java`) is the extreme
  version — `PlayerState` has around 28 fields (health, food, potion effects,
  attributes, flight state...). A 28-argument constructor would be both
  unreadable and a minefield (which `boolean` was `flying` again, and which
  was `allowFlight`?). The builder gives you `.health(20.0).allowFlight(true)`
  instead, and `PlayerState` itself stays fully immutable (every field
  `private final`) once built.
- `MenuBuilder`, `PaginatedMenuBuilder<T>`, `CommandBuilder` — same shape
  every time: mutable builder, immutable result.

---

## 6. The Composite pattern — trees made of the same building block

**What it is.** A **composite** is a structure where a "leaf" and a "branch
holding more leaves/branches" are the *same type*, so code that processes one
node doesn't need to know or care whether it has children.

**Where it's used.** `CommandNode` (`command/CommandNode.java`) is both a
command *and* a container of child commands (`List<CommandNode> children`).
Dispatching `/duel accept` walks the tree recursively:

```java
private boolean executeNode(CommandNode node, CommandSender sender, String[] args) {
    ...
    if (args.length > 0) {
        CommandNode child = findChild(node, args[0]);
        if (child != null) {
            String[] remaining = ...; // drop the consumed argument
            return executeNode(child, sender, remaining); // recurse
        }
    }
    if (node.getExecutor() != null)
        return execute(node, sender, args);
    ...
}
```

(`command/CommandManager.java`). `duel` is a `CommandNode`. Its child
`accept` is *also just a `CommandNode`* — same class, same fields, same
dispatch logic applies to both. That's why `DuelCommand.build()`
(`commands/DuelCommand.java`) can nest `.child(CommandBuilder.command("accept")...)`
arbitrarily deep without JCore needing any special-case code for "a command
with subcommands" versus "a plain command" — there is no such distinction in
the data structure.

---

## 7. The Template Method pattern — sharing the skeleton, varying one step

**What it is.** An abstract class implements most of an algorithm, but leaves
one or more specific steps as abstract methods for subclasses to fill in.

**Where it's used.** `AbstractDatabase` (`database/AbstractDatabase.java`)
implements the *entire* JDBC dance once — `execute`, `query`, `transaction`,
their async equivalents, error wrapping, connection pooling setup — and only
requires each subclass to implement one method:

```java
protected abstract void configure(HikariConfig config);
```

`SQLiteDatabase.configure` sets a SQLite JDBC URL and caps the pool at 1
connection. `MySQLDatabase.configure` sets a MySQL URL and a bigger pool.
Everything else — how a query actually runs, how errors get wrapped into
`DatabaseException`, how a transaction commits/rolls back — is written
**once**, in `AbstractDatabase`, and every database type gets it for free.
This is the difference between Template Method and plain Strategy (§1): here,
most of the behavior is *shared* in the parent, and only a small piece varies;
with `Database`/`StatsRepository` above, the *entire* behavior varies per
implementation.

---

## 8. `ThreadLocal` — per-thread "ambient" state without passing it everywhere

**What it is.** A `ThreadLocal<T>` looks like a single variable, but each
thread that reads or writes it actually gets its own independent copy.

```java
ThreadLocal<Integer> counter = new ThreadLocal<>();
counter.set(5);      // only visible to the current thread
counter.get();        // 5, on this thread
// a different thread calling counter.get() would see null (or whatever it set itself)
```

**Why you'd reach for it.** When some piece of state is "the current X for
whatever's running right now," and threading it through every function call
as a parameter would be painful or impossible (because the calling code
wasn't written to accept it).

**Where it's used.** `AbstractDatabase` has:

```java
private final ThreadLocal<Connection> transactionConnection = new ThreadLocal<>();
```

When you call `database.transaction(op)`, it grabs one real JDBC `Connection`
from the pool, turns off autocommit, and stores that connection in the
`ThreadLocal` for the duration of `op`. If `op` itself calls
`withConnection(...)` again — which is exactly what happens in
`MigrationManager.applyMigration`, which runs the migration's SQL *and*
records the applied version, both inside one `transaction(...)` call —
`withConnection` checks the `ThreadLocal` first and reuses that *same*
connection instead of asking the pool for a second one.

This matters for two concrete reasons:
1. **Correctness** — a second connection would be a *different* transaction,
   so the two writes could commit/rollback independently instead of
   atomically together.
2. **Deadlock avoidance** — `SQLiteDatabase` caps its pool at exactly 1
   connection. If nested code asked the pool for a second connection while
   the first was still checked out on the same thread, it would hang until
   Hikari's connection timeout, then throw.

`ThreadLocal` is the standard Java tool for "context available to whatever
code happens to run on this thread" without changing every method signature
in between to accept a `Connection` parameter.

---

## 9. `CompletableFuture` and async work — not blocking the game thread

**What it is.** A `CompletableFuture<T>` represents "a `T` that will exist
later." You can attach callbacks (`.thenAccept(...)`) that run once it's
ready, without the calling code having to sit and wait (block).

**Why you'd reach for it.** Minecraft servers run on a "tick loop" — the main
thread has roughly 50ms to process everything for one tick before the next
one starts. Anything slow (a SQL query, a network call) done directly on that
thread makes the whole server stutter or freeze while it waits. Async work
runs on a *different* thread, so the main thread keeps ticking normally.

**Where it's used.** `TaskManager.submitAsync` (`task/TaskManager.java`):

```java
public <T> CompletableFuture<T> submitAsync(Callable<T> task) {
    return CompletableFuture.supplyAsync(() -> {
        try { return task.call(); }
        catch (Exception e) { throw new CompletionException(e); }
    }, asyncExecutor);
}
```

Notice the `try/catch` wrapping into `CompletionException`. This is a real
Java gotcha worth understanding: `Callable<T>.call()` is allowed to declare
`throws Exception` (a **checked exception** — the compiler forces you to
handle or declare it). But `CompletableFuture.supplyAsync` expects a
`Supplier<T>`, and `Supplier.get()` is *not* allowed to throw a checked
exception. So the checked `Exception` has to be caught and re-thrown wrapped
in something unchecked (`CompletionException`) before it can cross that
boundary. This is why `SqlStatsRepository.getWins(...)` can return a plain
`CompletableFuture<Integer>` — any `SQLException` buried deep inside surfaces
later, wrapped, whenever code calls `.join()` or `.exceptionally()` on the
result, instead of forcing a `try/catch` at every call site.

`LeaderboardMenu` (per `ARCHITECTURE.md`) shows the other half of this: it
`.join()`s or chains on the future to get data back, then explicitly hops
back onto the main thread with `taskManager.runSync(...)` before touching any
Bukkit API (like opening an inventory) — because *that* part is not safe to
do from the async thread the future resolved on.

---

## 10. Lambdas as closures — how "Back" works in menus without a screen-state class

**What it is.** A **closure** is a lambda that "remembers" the variables from
the scope it was created in, even after that scope has technically finished
running.

```java
Runnable printer;
{
    String name = "Jack";
    printer = () -> System.out.println("Hi " + name);
}
printer.run(); // prints "Hi Jack" -- it still has access to `name`
```

**Why this is genuinely clever here.** `MenuNavigator`
(`menu/MenuNavigator.java`) keeps, per player, a `Deque<Runnable>` — a stack
of "how to redraw a previous screen" closures:

```java
public void openChild(Player player, Runnable render) {
    Runnable previous = current.get(player.getUniqueId());
    if (previous != null)
        history.computeIfAbsent(player.getUniqueId(), k -> new ArrayDeque<>()).push(previous);
    current.put(player.getUniqueId(), render);
    render.run();
}
```

When `KitDetailMenu` opens `KitEditMenu` as a child, it does something like
`context.openChild(() -> kitEditMenu.open(player, kitId))`. That lambda
captures `player` and `kitId` *at the moment it's created*. When the player
later clicks "Back," `MenuNavigator.back()` just pops that exact lambda off
the stack and calls `.run()` on it again — re-executing `kitEditMenu.open(player,
kitId)` from scratch, rebuilding the screen with fresh, current data.

The alternative, without closures, would be building a "screen state" object
for every menu — recording which kit id, which page number, which arena you
were looking at — and writing code to reconstruct the right screen from that
state. Closures let the *call itself* carry that state for you, for free.

---

## 11. Static nested classes vs regular ("inner") classes

**What it is.** A class declared inside another class can be:
- **`static`** — it does *not* hold a hidden reference back to an instance of
  the outer class. It's really just namespaced inside the outer class for
  organization.
- **non-static (inner)** — it *does* hold a hidden reference to the specific
  outer instance that created it, and can't exist without one.

```java
class Outer {
    static class Nested { }       // no link back to an Outer
    class Inner { }                // needs `outer.new Inner()`, holds a link to that Outer
}
```

**Where it's used.** `PlayerState.Builder` and `PlayerState.AttributeState`
are declared `public static final class` (`serialization/PlayerState.java`).
That's correct here because a `Builder` is used *before* any `PlayerState`
exists yet — there's no outer instance for it to link back to. If they were
non-static inner classes, you'd be forced to have a `PlayerState` already in
hand before you could even start building one, which defeats the purpose.

Rule of thumb: if the nested class doesn't need to read/modify the outer
instance's fields, make it `static`. It's a small detail, but it avoids an
invisible reference being kept alive longer than intended, and it makes the
nested class usable on its own.

---

## 12. `switch` expressions and enums with behavior

**What it is.** Modern Java `switch` can be an *expression* (it produces a
value) rather than just a statement, using `->` instead of `case ... :` and
`break`.

```java
String size = switch (count) {
    case 0 -> "none";
    case 1 -> "one";
    default -> "many";
};
```

**Where it's used.** `DatabaseFactory.create` (`database/DatabaseFactory.java`):

```java
return switch (configuration.getType()) {
    case SQLITE -> new SQLiteDatabase(...);
    case MYSQL, MARIADB -> new MySQLDatabase(...);
    case POSTGRESQL -> new PostgreSQLDatabase(...);
};
```

Because `DatabaseType` is an `enum`, the compiler knows exactly how many
cases exist and will warn you (or refuse to compile, depending on
configuration) if you add a new enum constant and forget to handle it here —
that's a real safety net you don't get from an `if/else` chain over a
`String`.

Enums themselves aren't just named constants here, either —
`CoreMessage` (`message/CoreMessage.java`) is an `enum implements MessageKey`,
where each constant carries its own data (a YAML path):

```java
public enum CoreMessage implements MessageKey {
    NO_PERMISSION("core.no-permission"),
    ...
    private final String path;
    CoreMessage(String path) { this.path = path; }
    public String getPath() { return path; }
}
```

This is what lets `MessageManager.send(sender, CoreMessage.NO_PERMISSION,
...)` be fully type-checked — you can't typo a message key as a raw string,
because you're passing an actual enum constant, and Duels' own `Message` enum
implements the same `MessageKey` interface for its own messages, so both work
through the exact same `MessageManager` methods.

---

## 13. Pattern matching for `instanceof`

**What it is.** Older Java required a cast after an `instanceof` check:

```java
if (sender instanceof Player) {
    Player player = (Player) sender;
    ...
}
```

Modern Java lets you bind the variable right in the check:

```java
if (sender instanceof Player player) {
    // `player` is already a Player here, no separate cast line
}
```

**Where it's used.** All over — `CommandContext.getPlayer()`
(`command/CommandContext.java`):

```java
public Player getPlayer() {
    if (!(sender instanceof Player player))
        throw new IllegalStateException("Command sender is not a player");
    return player;
}
```

and `MatchListener.resolveAttacker` (`listener/MatchListener.java`) chains
several of these to unwrap "who's actually responsible for this damage" from
a projectile, a tamed wolf, primed TNT, etc. — each `instanceof ... shooter`
both checks the type *and* gives you a correctly-typed variable in one line.

---

## 14. Text blocks — multi-line strings without escape-character soup

**What it is.** `"""..."""` lets you write a multi-line string literally,
without `\n` and string concatenation.

**Where it's used.** `JCore.java`:

```java
private static final String CORE_MESSAGE_DEFAULTS = """
        core:
          prefix: ""
          no-permission: "&cYou do not have permission to use this command."
          ...
        """;
```

This is a whole embedded YAML snippet living as a Java constant, merged into
whatever plugin is using JCore via `mergeDefaults(...)`. Worth reading the
comment directly above it in `JCore.java` — it's *there* (as a Java string,
not a bundled `messages.yml` resource file) specifically because of how
Maven Shade merges multiple plugins' jars together; see `ARCHITECTURE.md`
and the shading explanation for the full reasoning.

---

## 15. try-with-resources — guaranteed cleanup

**What it is.** Anything that implements `AutoCloseable` (like a JDBC
`Connection`, `PreparedStatement`, or `ResultSet`) can be opened in a
`try (...)` header, and Java guarantees `.close()` gets called when the block
exits — even if an exception is thrown partway through.

```java
try (Connection connection = dataSource.getConnection()) {
    // use connection
} // connection.close() happens automatically here, success or failure
```

**Where it's used.** Every JDBC method in `AbstractDatabase.java` — `execute`,
`query`, `transaction`, `queryAsync` — nests these: a `Connection`, inside it
a `PreparedStatement`, inside that a `ResultSet`. If a `SQLException` happens
mid-query, all three still get closed in the right order automatically,
rather than needing a manual `finally` block for each one (which is exactly
how this used to be written before try-with-resources existed, and it was
easy to get subtly wrong).

---

## How to use this doc going forward

When you're reading (or writing) new Duels code and something looks
unfamiliar — a lambda where you expected a class, a generic method, a static
nested class — come back here first. If it's not covered, that's a good
signal to ask about it directly rather than copy the shape without
understanding why it's there; a pattern used without understanding its
tradeoff is exactly how a codebase quietly rots.
