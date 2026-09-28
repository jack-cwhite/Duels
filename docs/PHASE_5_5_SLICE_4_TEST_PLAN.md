# Phase 5.5 Slice 4 - Clickable messages: manual test plan

_Result: passed on Paper 1.21.11 on 2026-09-28. Client hover and click
behaviour, help, challenge actions, stale clicks, result/statistics actions,
spectating, administrator customization and reload auditing all behaved as
expected, with no errors or unexpected warnings._

This covers the in-game checks for interactive (clickable) chat messages. The
automated suite already covers rendering, argument resolution, permission
hiding, configuration auditing and colour continuity - 29 tests in
`src/test/java/me/jackcw/duels/message/ActionMessengerTest.java`. What it cannot
cover is what a real Minecraft client does with a component: whether the hover
actually appears, whether a click actually dispatches, and whether the colours
read well in chat. That is what this plan is for.

Expect it to take about 15 minutes with two clients.

---

## 0. Before you start - IMPORTANT

Your server already has a `plugins/Duels/messages.yml`, and Duels never
overwrites a key you already have. That is the right behaviour, but it means
**your existing file has none of the new buttons in it**. New keys (the whole
`duel.actions` block) are merged in automatically; changed keys
(`duel.challenge-received`, `duel.no-pending`, `duel.match-win`,
`duel.match-lose`, `duel.spectate-started`, `duel.spectate-no-matches`) and the
`duel.help` list are left exactly as you had them.

So do one of these before testing:

* **Simplest:** delete `plugins/Duels/messages.yml` entirely and restart. You
  lose any wording you customised.
* **Targeted:** delete just the `duel.help:` list and the six keys above, then
  restart. Everything else you customised survives.

On startup you should see an `INFO` line naming any message that *could* carry
buttons but does not. If you take the targeted route and miss one, that line
tells you which.

**Expected startup log (with a fresh messages.yml):** no Duels warnings about
`duel.actions` at all. If you see a `WARNING` naming a `duel.actions.*.text`
path, a label is missing from your file.

---

## 1. The fast path - `/duels diagnostics buttons`

This is the single command that replaces most of the manual setup. It renders
every message that can carry buttons using sample data, then lists each button
on its own with the exact command it carries.

```
/duels diagnostics buttons
```

**Expected:**

| Check | What you should see |
|---|---|
| Header | `Duels clickable message preview (as <yourname>)` |
| Seven message paths listed | `duel.help`, `duel.challenge-received`, `duel.no-pending`, `duel.match-win`, `duel.match-lose`, `duel.spectate-started`, `duel.spectate-no-matches` |
| No red `no buttons configured` | If any path is flagged red, that key in your `messages.yml` still has your old wording - go back to step 0 |
| Ten button lines at the bottom | Each shows the placeholder, the rendered button, and `-> /duel ...` |
| `{opponent-stats}` line | shows `-> /duel stats <yourname>` |
| `{challenge}` line | shows `-> /duel challenge` |

**Now actually interact with the preview:**

1. Hover every button. Each should show its hover text. A button with no hover
   is a missing `duel.actions.<key>.hover`.
2. Click `[MY STATS]` - your stats menu opens.
3. Click `[ACCEPT]` - because the sample name is your own, this runs
   `/duel accept <yourself>` and should answer **"You have no pending challenge
   to respond to!"** followed by a `[CHALLENGE]` button. **This is the stale-click
   test**: a button that points at something that is not there must fail with a
   normal message, never an error or a stack trace.
4. Click `[CHALLENGE]` - it should *not* run anything. It should put
   `/duel challenge ` into your chat box with the cursor after it, ready for a
   name. Same for `[PICK ARENA]`.
5. Click `[LEADERBOARD]` - the leaderboard menu opens.

If all of that passes, the rendering layer is working and the rest of this plan
is confirming it in the real flows.

---

## 2. Help

```
/duel
```

**Expected:**

* A `&8&m` separator line, a `Duels - click a button, or type the command`
  header, one line per command, and a closing separator.
* Every line still shows the typed command in yellow, with a button appended.
  The point is that clicking is an addition, not a replacement - someone reading
  over a shoulder still learns the command.
* The `/duel challenge <player>` line has **no** button (it needs a name that a
  suggest-click cannot guess any better than `/duel <player>` already does).

**Permission check.** On an account without `duels.spectate`:

```
/duel
```

**Expected:** the `/duel spectate` and `/duel leave` lines are **absent
entirely**, not present-with-the-button-removed. Two fewer lines than the
admin sees.

---

## 3. Challenge receive / accept / deny

Two clients: **A** challenges **B**.

```
A: /duel B
```

**Expected on B:** `<A> has challenged you to a duel in <arena>! [ACCEPT] | [DENY]`

| Check | Expected |
|---|---|
| Hover `[ACCEPT]` | "Accept the duel challenge." |
| Colour after the buttons | the `|` separator is dark grey, and the sentence before the buttons is still grey - no stray white text |
| B clicks `[DENY]` | the challenge is declined exactly as `/duel deny` would; A is told |
| B clicks `[DENY]` **again** | "You have no pending challenge to respond to! `[CHALLENGE]`" - the second click must not throw or double-decline |

Repeat and have B click `[ACCEPT]` instead. The duel should start normally.

**Expiry test.** A challenges B, then wait for the challenge to expire
(`challenge-expiry-seconds` in `config.yml`, default 30). B then clicks the now
stale `[ACCEPT]`.

**Expected:** "You have no pending challenge to respond to!" - nothing else.

**Disconnect test.** A challenges B, then A quits. B clicks `[ACCEPT]`.

**Expected:** the same no-pending message. (Duels cancels the challenge on
disconnect, so this is a second line of defence.)

**Already-in-match test.** A challenges B, B accepts and the duel starts. Now,
mid-duel, have B click the `[ACCEPT]` from the original message again.

**Expected:** "You have no pending challenge to respond to!" - the challenge was
consumed when it was accepted, so the second click finds nothing. The important
part is that it cannot start a second match and does not error.

---

## 4. Match result

Finish a duel.

**Expected on the winner:** `You won the duel against <loser>! [MY STATS] | [THEIR STATS]`
**Expected on the loser:** `You lost the duel against <winner>. [MY STATS] | [THEIR STATS]`

| Check | Expected |
|---|---|
| `[MY STATS]` | opens your own stats profile |
| `[THEIR STATS]` | opens the *opponent's* profile, with their name in the title |
| Colour | the win line is green either side of the buttons; the lose line red |
| `[THEIR STATS]` after the opponent logs out | still opens their profile - stats come from storage, not the live player |

---

## 5. Spectating

With a duel running, on a third account:

```
/duel spectate
```

**Expected:** the spectate menu. Pick a match.

**Expected message:** `You are now spectating <player>'s duel. [STOP WATCHING]`
Click `[STOP WATCHING]` - you are returned exactly as `/duel leave` would.

With **no** duel running:

```
/duel spectate
```

**Expected:** `There are no duels being played right now. [CHALLENGE]` - and
clicking `[CHALLENGE]` suggests `/duel challenge ` in your chat box.

---

## 6. Admin configurability

This is the part that matters for release: everything visible has to be yours to
change, and nothing you can write in `messages.yml` may change what a click
*does*.

### 6a. Change a label and hover

In `plugins/Duels/messages.yml`:

```yaml
duel:
  actions:
    accept:
      text: '&2&l[FIGHT ME]'
      hover: '&aLet''s go.'
```

```
/duels reload
/duels diagnostics buttons
```

**Expected:** the accept button now reads `[FIGHT ME]` in dark green bold, with
the new hover, and still carries `/duel accept <name>`.

### 6b. Move a button to a different message

```yaml
duel:
  no-pending: '&cNothing pending. {spectate} &8| {top}'
```

```
/duels reload
/duel accept
```

**Expected:** the new buttons appear and work. A button is not tied to one
message.

### 6c. Put a button somewhere it cannot work

```yaml
duel:
  challenge-sent: '&7Challenge sent. {accept}'
```

```
/duels reload
```

**Expected:** a `WARNING` in console naming `duel.challenge-sent` and listing the
seven messages that do support buttons. Then send a challenge - the message shows
the literal text `{accept}`, which is exactly what the warning predicted.

Put it back afterwards.

### 6d. Delete a label

Remove `duel.actions.deny.text` entirely, then `/duels reload`.

**Expected:** a `WARNING` naming `duel.actions.deny.text`, and the button still
works using the built-in `[DENY]` wording. Losing your customisation must not
lose the button.

### 6e. Remove all the buttons

```yaml
duel:
  challenge-received: '&e{player} &7has challenged you! Use &e/duel accept'
```

```
/duels reload
```

**Expected:** an `INFO` line noting that `duel.challenge-received` could carry
buttons but has none - and the message renders as plain text with no leftover
braces. Servers that do not want clickable chat must be able to turn it off by
just deleting the placeholders.

### 6f. The safety property

Try to make a click run something Duels did not intend:

```yaml
duel:
  no-pending: '&cNothing pending. {op} {accept}'
```

```
/duels reload
/duel accept
```

**Expected:** `{op}` is printed literally - unrecognised placeholders are left
alone - and `[ACCEPT]` still works. There is deliberately no way to write a
command into `messages.yml`; the label and hover are configuration, the command
and its permission are code.

---

## 7. Reload behaviour

```
/duels reload
```

and then the reload button in `/duels` (the admin menu).

**Expected:** both do exactly the same thing, including re-running the message
audit. Break something (delete a label), reload, see the warning; fix it, reload,
the warning stops. A warning that only appeared once per server lifetime would
make "did my fix work?" impossible to answer without a restart.

---

## What is already covered automatically

Do not spend manual time on these; `ActionMessengerTest` asserts them:

* every action the code knows about exists in the shipped `messages.yml`;
* the shipped defaults produce no audit warnings;
* `{accept}`/`{deny}` carry exactly `/duel accept <name>` / `/duel deny <name>`;
* `{stats}` carries `/duel stats` and `{opponent-stats}` carries `/duel stats <name>`;
* `{challenge}` and `{select}` suggest rather than run, with a trailing space;
* an action needing a player name it was not given renders inert instead of
  clicking a command with an unresolved placeholder in it;
* colour and formatting carry across a button instead of resetting to white;
* a value passed in by code cannot smuggle in a button, because actions are found
  in the configured template before anything is substituted into it;
* unknown placeholders and unclosed braces do not eat the rest of the message;
* a help line advertising a button you lack permission for is dropped entirely;
* a missing label falls back to built-in wording and warns exactly once;
* the audit names misplaced placeholders, missing labels, and upgraded installs
  that kept button-less wording;
* `/duels diagnostics buttons` lists every action and reports hidden ones.

---

## Sign-off

Slice 4 is done when sections 1-7 pass on Paper with two clients and an admin
account, and no unexpected Duels warnings appear in console.
