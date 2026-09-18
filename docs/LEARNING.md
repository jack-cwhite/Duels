# Duels / JCore Learning Notes

This tracks the concepts worth understanding at each stage of `ROADMAP.md`, tied
directly to real code in this repo rather than treated as a standalone course. See
`JAVA_CONCEPTS_AND_JCORE.md` for the deeper V1 concept writeups this builds on.

## Already demonstrated (V1)

These are confirmed solid from the existing codebase and won't be re-taught from
scratch - referenced here only so new material can build on top of them:

- Interfaces as boundaries/Strategy (`Database`, `StatsRepository`), Template Method
  (`AbstractDatabase`)
- Generics (`YamlRepository<T>`, `Serializer<T>`, `StateMachine<S>`)
- Records, Builder pattern (`PlayerState`)
- `CompletableFuture`, bounded `ExecutorService`, `ThreadLocal` for nested transactions
- Closures/`Runnable` as first-class navigation state (`MenuNavigator`)
- Adventure basics already in production use: `Title`, `BossBar`, action bars, legacy
  `&`-code messages via `LegacyComponentSerializer`

## Phase 0/1 concepts

- **Composition vs. shared mutable reference.** `Match` currently holds a live `Arena`
  reference; introducing `ArenaInstance` is about *what* a match should be composed of,
  not a new language feature - the concept worth internalizing is that "owning a
  reference to a mutable object" is a design decision with consequences (whoever else
  holds that reference can observe your changes), and the existing kit-snapshot vs.
  arena-reference asymmetry (see `ARCHITECTURE.md`) is the concrete example.
- **Configuration state vs. runtime state.** Not a Java feature at all - a modelling
  distinction. Worth being explicit about it because it's easy to accidentally persist
  something that should be runtime-only (like which instance is currently claimed) or
  vice versa.

## Phase 2 concepts

- **`PlayerMoveEvent` performance characteristics.** This is one of the highest-frequency
  events Bukkit fires - historically a very easy way to accidentally tank server
  performance by doing expensive work in a naive handler. The correct pattern
  (compare block coordinates between `getFrom()`/`getTo()`, and scope the check to only
  players currently in a match) is worth understanding before writing the bounds check,
  not after profiling a slowdown.

## Phase 6 concepts

- **Idempotency and at-least-once delivery.** Once a reward can be granted for a match
  result, "what happens if this runs twice" and "what happens if this never completes"
  both become real questions, in a way they weren't for a leaderboard entry. This is the
  first place in the roadmap where "exactly once" needs to be actively designed for
  rather than assumed - the existing `AbstractDatabase.transaction(...)` gives atomicity
  within the database, but Vault's economy call is a separate system that can succeed or
  fail independently of the database transaction.

## Phase 7/8 concepts

- **Queues as domain objects vs. general collections.** A matchmaking `Queue` is a
  small state machine in its own right (waiting -> paired -> removed), similar in
  spirit to `ChallengeManager`'s existing lifecycle handling of challenges.

## Phase 9 concepts (network, taught from first principles when this phase starts)

- What a proxy (Velocity/BungeeCord) actually does vs. what a backend Paper server does
- Why Stage B doesn't need a shared queue or Redis, but does benefit from a shared
  statistics database
- Why Stage C (cross-server matchmaking) is the point where a message bus earns its
  complexity, not before

## Modern capabilities worth knowing about (not yet adopted, no pressure to adopt)

- **MiniMessage** - Adventure's string-based rich text format. Duels currently builds
  messages with legacy `&`-codes via `LegacyComponentSerializer.legacyAmpersand()`. This
  works fine and doesn't need to change for its own sake, but MiniMessage is the modern
  replacement if/when Duels' messages need features legacy codes can't express (hover
  text, click-to-run buttons, gradients). Relevance: useful soon, if message config ever
  gets revisited.
- **PersistentDataContainer (PDC)** - lets arbitrary typed data be attached to
  ItemStacks/entities/blocks without NBT hacking. Not currently used anywhere in Duels;
  worth knowing about for kit items that might need hidden metadata later (e.g. marking
  an item as "kit item, don't let players keep it after the match" more robustly than
  positional inventory tracking). Relevance: useful background now, relevant once kit
  items need to carry match-specific metadata.
- **Modern item components (1.20.5+ data components API)** - replaced a lot of the old
  NBT-based ItemMeta manipulation. Worth a proper look whenever kit item serialization
  is next touched, since `ItemStackSerializer` in JCore hasn't been reviewed in this
  audit yet.
