package me.jackcw.duels.message;

import me.jackcw.duels.Duels;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.file.FileConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the clickable-message layer against the real messages.yml the plugin
 * ships rather than a hand-built configuration, because the failure most likely
 * to reach a server is a mismatch between the shipped defaults and the actions
 * the code knows about.
 */
class ActionMessengerTest
{
    private ServerMock server;
    private Duels plugin;
    private ActionMessenger messenger;
    private FileConfiguration messages;

    private final List<LogRecord> logged = new ArrayList<>();
    private Handler capture;

    @BeforeEach
    void setup()
    {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(Duels.class);
        messenger = plugin.getActionMessenger();
        messages = plugin.core().messages().getFile().getConfig();

        capture = new Handler()
        {
            @Override
            public void publish(LogRecord record)
            {
                logged.add(record);
            }

            @Override
            public void flush()
            {
            }

            @Override
            public void close()
            {
            }
        };

        plugin.getLogger().addHandler(capture);
    }

    @AfterEach
    void cleanup()
    {
        plugin.getLogger().removeHandler(capture);
        logged.clear();
        MockBukkit.unmock();
    }

    // ------------------------------------------------------------------
    // The shipped defaults
    // ------------------------------------------------------------------

    @Test
    void challengeButtonsRunExactlyTheCommandsAPlayerCouldType()
    {
        PlayerMock receiver = player("Defender");

        messenger.send(receiver, Message.CHALLENGE_RECEIVED, "player", "Challenger", "arena", "Castle");

        Component sent = receiver.nextComponentMessage();

        assertEquals("/duel accept Challenger", runCommandOn(sent, "[ACCEPT]"));
        assertEquals("/duel deny Challenger", runCommandOn(sent, "[DENY]"));
    }

    @Test
    void theBodyOfAnInteractiveMessageStillReadsNormally()
    {
        PlayerMock receiver = player("Defender");

        messenger.send(receiver, Message.CHALLENGE_RECEIVED, "player", "Challenger", "arena", "Castle");

        String plain = plain(receiver.nextComponentMessage());

        assertTrue(plain.contains("Challenger"), plain);
        assertTrue(plain.contains("Castle"), plain);
        assertTrue(plain.contains("[ACCEPT]"), plain);
        assertTrue(plain.contains("[DENY]"), plain);
    }

    @Test
    void matchResultButtonsPointAtBothProfiles()
    {
        PlayerMock receiver = player("Winner");

        messenger.send(receiver, Message.MATCH_WIN, "player", "Loser");

        Component sent = receiver.nextComponentMessage();

        assertEquals("/duel stats", runCommandOn(sent, "[MY STATS]"));
        assertEquals("/duel stats Loser", runCommandOn(sent, "[THEIR STATS]"));
    }

    @Test
    void everyActionTheCodeKnowsAboutIsDefinedInTheShippedMessageFile()
    {
        for (ChatAction action : ChatAction.values())
        {
            assertNotNull(messages.getString(action.textPath()), action.textPath());
            assertNotNull(messages.getString(action.hoverPath()), action.hoverPath());
        }
    }

    @Test
    void theShippedDefaultsPassTheirOwnAudit()
    {
        messenger.forgetWarnings();
        logged.clear();

        messenger.audit();

        assertTrue(warnings().isEmpty(), warnings().toString());
    }

    // ------------------------------------------------------------------
    // Presentation an administrator controls
    // ------------------------------------------------------------------

    @Test
    void configuredLabelColourFormatAndHoverAreUsed()
    {
        rewrite("duel.actions.accept.text", "&2&nTake it");
        rewrite("duel.actions.accept.hover", "&7Fight &e{player}&7.");

        PlayerMock receiver = player("Defender");
        messenger.send(receiver, Message.CHALLENGE_RECEIVED, "player", "Challenger", "arena", "Castle");

        Component button = findButton(receiver.nextComponentMessage(), "Take it");

        assertNotNull(button);
        assertEquals(NamedTextColor.DARK_GREEN, button.color());
        assertEquals(TextDecoration.State.TRUE, button.decoration(TextDecoration.UNDERLINED));

        HoverEvent<?> hover = button.hoverEvent();

        assertNotNull(hover);
        assertTrue(plain((Component) hover.value()).contains("Fight Challenger"),
                plain((Component) hover.value()));
    }

    @Test
    void colourCarriesAcrossAButtonInsteadOfResettingToWhite()
    {
        Component rendered = messenger.render(player("Viewer"), "&7Challenged! {accept} or ignore it");

        Component tail = find(rendered, node -> plain(node).startsWith(" or ignore it"));

        assertNotNull(tail, plain(rendered));
        assertEquals(NamedTextColor.GRAY, tail.color());
    }

    @Test
    void formattingCarriesAcrossAButtonAndAResetClearsIt()
    {
        assertEquals("&7", ActionMessenger.trailingStyle("&7grey text", ""));
        assertEquals("&7&l", ActionMessenger.trailingStyle("&7&lbold grey", ""));
        assertEquals("&c", ActionMessenger.trailingStyle("&7grey &cred", ""));
        assertEquals("", ActionMessenger.trailingStyle("&7grey &rplain", ""));
        assertEquals("&7", ActionMessenger.trailingStyle(" and more", "&7"));
        assertEquals("&a", ActionMessenger.trailingStyle("&agreen", "&7&l"));
    }

    @Test
    void aSeparatorBetweenTwoButtonsKeepsItsOwnColour()
    {
        Component rendered = messenger.render(player("Viewer"), "&7Result. {stats} &8| {opponent-stats}",
                "player", "Rival");

        Component separator = find(rendered, node -> plain(node).contains("|") && node.color() != null);

        assertNotNull(separator, plain(rendered));
        assertEquals(NamedTextColor.DARK_GRAY, separator.color());
    }

    // ------------------------------------------------------------------
    // Arguments
    // ------------------------------------------------------------------

    @Test
    void anActionThatNeedsAPlayerIsInertRatherThanClickingAnUnresolvedCommand()
    {
        Component rendered = messenger.render(player("Viewer"), "&7Result. {opponent-stats}");

        Component label = find(rendered, node -> plain(node).contains("[THEIR STATS]") && node.hoverEvent() != null);

        assertNotNull(label, plain(rendered));
        assertNull(label.clickEvent());
        assertNull(findButton(rendered, "[THEIR STATS]"));
        assertTrue(warnings().stream().anyMatch(line -> line.contains("opponent-stats")), warnings().toString());
    }

    @Test
    void anActionThatDoesNotTakeAPlayerIgnoresOneThatIsSupplied()
    {
        Component rendered = messenger.render(player("Viewer"), "{stats} {opponent-stats}", "player", "Rival");

        assertEquals("/duel stats", runCommandOn(rendered, "[MY STATS]"));
        assertEquals("/duel stats Rival", runCommandOn(rendered, "[THEIR STATS]"));
    }

    @Test
    void anOptionalPlayerActionFallsBackToTheBareCommandInTheHelpList()
    {
        Component rendered = messenger.render(player("Viewer"), "{accept}");

        assertEquals("/duel accept", runCommandOn(rendered, "[ACCEPT]"));
    }

    @Test
    void anActionStillNeedingAnArgumentIsSuggestedRatherThanRun()
    {
        Component rendered = messenger.render(player("Viewer"), "{challenge}");
        ClickEvent click = findButton(rendered, "[CHALLENGE]").clickEvent();

        assertEquals(ClickEvent.Action.SUGGEST_COMMAND, click.action());
        assertEquals("/duel challenge ", click.value());
    }

    @Test
    void anUnresolvedPlaceholderInALabelIsRemovedRatherThanShownAsBraces()
    {
        rewrite("duel.actions.stats.text", "&b[STATS {player}]");

        Component rendered = messenger.render(player("Viewer"), "{stats}");

        assertFalse(plain(rendered).contains("{"), plain(rendered));
        assertFalse(plain(rendered).contains("}"), plain(rendered));
    }

    // ------------------------------------------------------------------
    // Text that is not an action
    // ------------------------------------------------------------------

    @Test
    void aSuppliedValueCannotSmuggleInAButton()
    {
        Component rendered = messenger.render(player("Viewer"), "&7{player} challenged you.",
                "player", "{accept}");

        assertTrue(plain(rendered).contains("{accept}"), plain(rendered));
        assertNull(findButton(rendered, "[ACCEPT]"));
    }

    @Test
    void anUnknownPlaceholderIsLeftAsWrittenAndDoesNotSwallowALaterButton()
    {
        Component rendered = messenger.render(player("Viewer"), "&7{mystery} {stats}");

        assertTrue(plain(rendered).contains("{mystery}"), plain(rendered));
        assertEquals("/duel stats", runCommandOn(rendered, "[MY STATS]"));
    }

    @Test
    void anUnclosedBraceDoesNotLoseTheRestOfTheMessage()
    {
        Component rendered = messenger.render(player("Viewer"), "&7Broken {accept and the rest");

        assertTrue(plain(rendered).contains("and the rest"), plain(rendered));
    }

    // ------------------------------------------------------------------
    // Permissions
    // ------------------------------------------------------------------

    @Test
    void aButtonIsHiddenFromSomeoneWhoCannotUseIt()
    {
        PlayerMock viewer = player("Viewer");
        viewer.addAttachment(plugin, "duels.spectate", false);

        Component rendered = messenger.render(viewer, "&7Nothing on. {challenge} {spectate}");

        assertNull(findButton(rendered, "[SPECTATE]"), plain(rendered));
        assertNotNull(findButton(rendered, "[CHALLENGE]"), plain(rendered));
        assertTrue(plain(rendered).contains("Nothing on."), plain(rendered));
    }

    @Test
    void aHelpLineAdvertisingAnUnusableButtonIsNotPrintedAtAll()
    {
        PlayerMock allowed = player("Allowed");
        PlayerMock denied = player("Denied");
        denied.addAttachment(plugin, "duels.spectate", false);

        messenger.sendList(allowed, Message.DUEL_HELP);
        messenger.sendList(denied, Message.DUEL_HELP);

        List<String> allowedLines = drain(allowed);
        List<String> deniedLines = drain(denied);

        assertTrue(allowedLines.stream().anyMatch(line -> line.contains("/duel spectate")), allowedLines.toString());
        assertTrue(deniedLines.stream().noneMatch(line -> line.contains("/duel spectate")), deniedLines.toString());
        assertTrue(deniedLines.stream().noneMatch(line -> line.contains("/duel leave")), deniedLines.toString());
        assertTrue(deniedLines.stream().anyMatch(line -> line.contains("/duel accept")), deniedLines.toString());
        assertEquals(allowedLines.size() - 2, deniedLines.size());
    }

    @Test
    void theRematchButtonIsPresentWhileRematchesAreEnabled()
    {
        PlayerMock winner = player("Winner");

        messenger.send(winner, Message.MATCH_WIN, "player", "Loser");

        Component sent = winner.nextComponentMessage();

        assertEquals("/duel rematch Loser", runCommandOn(sent, "[REMATCH]"));
    }

    @Test
    void theRematchButtonDisappearsWhenRematchesAreTurnedOff()
    {
        disableRematches();

        PlayerMock winner = player("Winner");

        messenger.send(winner, Message.MATCH_WIN, "player", "Loser");

        Component sent = winner.nextComponentMessage();

        assertNull(findButton(sent, "[REMATCH]"), plain(sent));
        assertNotNull(findButton(sent, "[MY STATS]"), plain(sent));
        assertTrue(plain(sent).contains("You won the duel"), plain(sent));
    }

    @Test
    void theRematchHelpLineDisappearsWhenRematchesAreTurnedOff()
    {
        PlayerMock before = player("Before");
        messenger.sendList(before, Message.DUEL_HELP);
        List<String> withRematches = drain(before);

        disableRematches();

        PlayerMock after = player("After");
        messenger.sendList(after, Message.DUEL_HELP);
        List<String> withoutRematches = drain(after);

        assertTrue(withRematches.stream().anyMatch(line -> line.contains("/duel rematch")), withRematches.toString());
        assertTrue(withoutRematches.stream().noneMatch(line -> line.contains("/duel rematch")), withoutRematches.toString());
        assertEquals(withRematches.size() - 1, withoutRematches.size());
    }

    // ------------------------------------------------------------------
    // Missing, misplaced and upgraded configuration
    // ------------------------------------------------------------------

    @Test
    void aDeletedLabelFallsBackToTheBuiltInWordingAndWarnsOnlyOnce()
    {
        rewrite("duel.actions.accept.text", null);
        rewrite("duel.actions.accept.hover", null);

        Component rendered = messenger.render(player("Viewer"), "{accept}");
        messenger.render(player("Second"), "{accept}");

        assertNotNull(findButton(rendered, "[ACCEPT]"), plain(rendered));
        assertEquals(1, warnings().stream().filter(line -> line.contains("duel.actions.accept.text")).count(),
                warnings().toString());
    }

    @Test
    void aMessageWithNoButtonsRendersExactlyAsItReads()
    {
        PlayerMock receiver = player("Defender");

        rewrite(Message.CHALLENGE_RECEIVED.getPath(),
                "&e{player} &7has challenged you in &e{arena}&7! Use &e/duel accept");
        messenger.send(receiver, Message.CHALLENGE_RECEIVED, "player", "Challenger", "arena", "Castle");

        String plain = plain(receiver.nextComponentMessage());

        assertTrue(plain.contains("Challenger has challenged you in Castle! Use /duel accept"), plain);
    }

    @Test
    void auditNamesAnActionPlaceholderWrittenIntoANonInteractiveMessage()
    {
        rewrite(Message.CHALLENGE_SENT.getPath(), "&7Sent. {accept}");

        messenger.forgetWarnings();
        logged.clear();
        messenger.audit();

        assertTrue(warnings().stream().anyMatch(line -> line.contains(Message.CHALLENGE_SENT.getPath())),
                warnings().toString());
    }

    @Test
    void auditNamesAMissingActionLabel()
    {
        rewrite("duel.actions.deny.text", null);

        messenger.forgetWarnings();
        logged.clear();
        messenger.audit();

        assertTrue(warnings().stream().anyMatch(line -> line.contains("duel.actions.deny.text")),
                warnings().toString());
    }

    @Test
    void auditPointsOutAnUpgradedInstallThatKeptItsOldButtonlessWording()
    {
        rewrite(Message.CHALLENGE_RECEIVED.getPath(), "&7You were challenged. Use /duel accept");

        messenger.forgetWarnings();
        logged.clear();
        messenger.audit();

        assertTrue(info().stream().anyMatch(line -> line.contains(Message.CHALLENGE_RECEIVED.getPath())),
                info().toString());
    }

    @Test
    void auditDoesNotThrowWhenEveryActionDefinitionIsGone()
    {
        rewrite("duel.actions", null);

        messenger.forgetWarnings();
        logged.clear();

        assertDoesNotThrow(() -> messenger.audit());
        assertFalse(warnings().isEmpty());

        Component rendered = messenger.render(player("Viewer"), "{accept}");

        assertNotNull(findButton(rendered, "[ACCEPT]"), plain(rendered));
    }

    @Test
    void aReloadReportsAProblemItAlreadyWarnedAboutBefore()
    {
        rewrite("duel.actions.kit.text", null);

        messenger.forgetWarnings();
        messenger.audit();

        assertTrue(warnings().stream().anyMatch(line -> line.contains("duel.actions.kit.text")),
                warnings().toString());

        logged.clear();

        // A reload has to report the same problem again. Without
        // forgetWarnings() the one-time warning would stay suppressed and an
        // administrator who reloaded to check their fix would see nothing.
        plugin.reloadConfiguration();

        assertTrue(warnings().stream().anyMatch(line -> line.contains("duel.actions.kit.text")),
                warnings().toString());
    }

    // ------------------------------------------------------------------
    // The admin preview
    // ------------------------------------------------------------------

    @Test
    void thePreviewShowsEveryButtonAndTheCommandItCarries()
    {
        PlayerMock admin = player("Admin");

        messenger.preview(admin, admin.getName());

        String printed = String.join("\n", drain(admin));

        for (ChatAction action : ChatAction.values())
            assertTrue(printed.contains(action.placeholder()), action.placeholder() + " missing from:\n" + printed);

        assertTrue(printed.contains("/duel accept Admin"), printed);
        assertTrue(printed.contains("/duel stats Admin"), printed);
        assertTrue(printed.contains(Message.CHALLENGE_RECEIVED.getPath()), printed);
        assertTrue(printed.contains(Message.DUEL_HELP.getPath()), printed);
    }

    @Test
    void thePreviewSaysWhichSurfacesHaveNoButtonsLeft()
    {
        rewrite(Message.SPECTATE_NO_MATCHES.getPath(), "&cThere are no duels being played right now.");

        PlayerMock admin = player("Admin");
        messenger.preview(admin, admin.getName());

        String printed = String.join("\n", drain(admin));
        int line = printed.indexOf(Message.SPECTATE_NO_MATCHES.getPath());

        assertTrue(line >= 0, printed);
        assertTrue(printed.substring(line, printed.indexOf('\n', line)).contains("no buttons configured"), printed);
    }

    @Test
    void thePreviewSaysWhenAButtonIsHiddenFromTheViewer()
    {
        PlayerMock admin = player("Admin");
        admin.addAttachment(plugin, "duels.spectate", false);

        messenger.preview(admin, admin.getName());

        assertTrue(String.join("\n", drain(admin)).contains("duels.spectate"));
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Changes messages.yml the way an administrator would - on disk, followed by
     * a reload - rather than poking the in-memory configuration. MessageManager
     * caches plain messages at load time while action labels are read live, so a
     * test that only set the live configuration would silently exercise one path
     * and not the other.
     */
    private void rewrite(String path, Object value)
    {
        plugin.core().messages().getFile().getConfig().set(path, value);
        plugin.core().messages().getFile().save();
        plugin.core().messages().reload();

        messages = plugin.core().messages().getFile().getConfig();
    }

    /**
     * Turns rematches off the way an administrator would, through config.yml and
     * a reload, so the test exercises the same live read the renderer does.
     */
    private void disableRematches()
    {
        plugin.core().config().getConfig().set("rematch-expiry-time", 0);
        plugin.core().config().save();
        plugin.reloadConfiguration();
    }

    private PlayerMock player(String name)
    {
        PlayerMock player = server.addPlayer(name);

        drain(player);

        return player;
    }

    private List<String> warnings()
    {
        return logged.stream()
                .filter(record -> record.getLevel().intValue() >= Level.WARNING.intValue())
                .map(LogRecord::getMessage)
                .toList();
    }

    private List<String> info()
    {
        return logged.stream()
                .filter(record -> record.getLevel() == Level.INFO)
                .map(LogRecord::getMessage)
                .toList();
    }

    private static List<String> drain(PlayerMock player)
    {
        List<String> lines = new ArrayList<>();

        for (Component message = player.nextComponentMessage(); message != null;
             message = player.nextComponentMessage())
            lines.add(plain(message));

        return lines;
    }

    private static String runCommandOn(Component root, String label)
    {
        Component button = findButton(root, label);

        assertNotNull(button, "no clickable button labelled " + label + " in: " + plain(root));

        ClickEvent click = button.clickEvent();

        assertEquals(ClickEvent.Action.RUN_COMMAND, click.action());

        return click.value();
    }

    private static Component findButton(Component root, String label)
    {
        return find(root, node -> node.clickEvent() != null && plain(node).contains(label));
    }

    private static Component find(Component root, Predicate<Component> match)
    {
        if (match.test(root))
            return root;

        for (Component child : root.children())
        {
            Component found = find(child, match);

            if (found != null)
                return found;
        }

        return null;
    }

    private static String plain(Component component)
    {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }
}
