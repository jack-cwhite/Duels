# Phase 5.5 live test plan

_Result: passed in full on Paper 1.21.11 on 2026-09-28. Clickable actions,
challenge and stale-click handling, kit-effect immutability and cleanup,
mutual-consent rematches, fresh kit selection, expiry/disable behaviour,
administrator message customization, and diagnostics cleanup all behaved as
expected. No errors or unexpected warnings were observed._

Automated tests cover effect rules, component rendering, rematch state and command
flows. These checks need Paper 1.21.11, two player clients (Alice and Bob), and
an admin account. The detailed clickable-message checklist is in
`PHASE_5_5_SLICE_4_TEST_PLAN.md`.

## Prepare

1. Back up the server's `plugins/Duels/config.yml` and `messages.yml`.
2. Install the newly built Duels jar and restart. Existing message values are
   preserved. To see the new default buttons, remove only the old `duel.help`
   list and the six changed keys named in the Slice 4 plan, then restart.
3. Set `rematch-expiry-time: 30` in `config.yml` and run `/duels reload`.
4. Have one enabled arena with a playable instance and at least one selectable
   kit. Set short kit-selection and grace times for these checks if desired.
5. Run `/duels diagnostics baseline` before starting.

## Kit effects (Slices 1-3)

1. Add a positive effect and a harmful effect to a kit through the admin GUI;
   verify the corresponding command fallback and persisted configuration.
2. Start a duel and select that kit. Both effects should apply to its holder
   during grace and combat, with no effect transferred to the opponent.
3. Try milk, a stronger same-type potion, and a weaker same-type potion. The
   kit baseline should remain at its configured amplifier. Other effect types
   should behave normally.
4. Finish and then abort separate matches. Original player effects and state
   should be restored; kit effects should not leak into the lobby.
5. Restart and confirm the kit's configured effects remain available.

## Clickable chat (Slice 4)

Run `PHASE_5_5_SLICE_4_TEST_PLAN.md`, including the diagnostics preview,
hover/click behaviour, permissions, stale buttons, and existing-message-file
migration. Record any missing button paths reported on startup.

## Rematches (Slice 5)

1. Alice wins a duel in a named arena. Both players should see `[REMATCH]`
   alongside their result actions. Click on Alice's button; Bob should receive
   the rematch invitation with `[ACCEPT]` and `[DENY]`.
2. Bob clicks `[ACCEPT]`. The new match should use the same arena template and a
   fresh available instance, reopen kit selection, and let both choose new kits.
   The previous kit selection must not carry over.
3. Finish that second match, then rematch again with Bob as requester. Confirm
   consecutive rematches work and no arena is reserved while an invitation
   waits for acceptance.
4. Repeat using `/duel rematch` from both players. The first invocation sends
   an invitation; the reverse invocation accepts it. Also use `/duel deny` and
   confirm neither player enters a match.
5. Let a rematch window expire, then click the old result button. It should
   report that no rematch is available and create no invitation. Repeat with
   an invitation left pending until expiry.
6. Delete or disable the previous arena before acceptance. The request must
   fail with rematch-specific feedback; it must not switch arenas. Restore the
   arena before later checks.
7. Make the arena temporarily full before acceptance. Both players should get
   capacity feedback, stay in place, and be able to retry while the original
   rematch window is open.
8. During an open window, have either player disconnect, start a direct duel
   with someone else, or start a new match. Old rematch invitations must not
   start an unexpected match.
9. Set `rematch-expiry-time: 0`, run `/duels reload`, and confirm the rematch
   button and help line disappear. Existing windows and invitations should
   close. Set it back to 30 and reload; new completed matches should offer
   rematches again.

## Finish

After the last duel, allow any rematch window to expire. Run
`/duels diagnostics compare`. Active matches, pending challenges, rematch
windows, arena reservations, and pending restores should be back at baseline.
Check the console for exceptions. Record server version, jar commit, dates,
pass/fail for each section, and any wording or layout changes needed.

Automated commands from the repository root:

```powershell
mvn test
mvn package -DskipTests
```

Run the JCore test suite separately from `../JCore` with `mvn test`.
