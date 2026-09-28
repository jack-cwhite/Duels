package me.jackcw.duels.message;

import me.jackcw.duels.DuelsSettings;
import me.jackcw.jcore.message.MessageKey;
import me.jackcw.jcore.message.MessageManager;
import me.jackcw.jcore.util.StringUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Sends Duels messages that may contain clickable {@link ChatAction} buttons.
 *
 * <p>This is deliberately a thin layer over JCore's {@code MessageManager}
 * rather than a replacement for it. The method shapes match
 * {@code MessageManager.send}/{@code sendList} so a call site can be moved onto
 * it without changing how it reads, and everything JCore already owns - loading,
 * the prefix, {@code &} colours, {@code {placeholder}} substitution - stays
 * where it is. All this class adds is the step where a configured
 * {@code {accept}}-style placeholder becomes a component carrying a hover and a
 * click.
 *
 * <p>It stays in Duels because the vocabulary of actions is Duels' own. JCore
 * has one consumer for this today, and generalising it now would be building a
 * framework feature for a single caller.
 *
 * <h2>Ordering of substitutions</h2>
 * Actions are located in the configured text <em>before</em> any placeholder is
 * substituted, and only the literal runs between them are then substituted. That
 * order is what makes a click safe to trust: if substitution ran first, a value
 * supplied by a caller - an arena name, or in principle a player name - could
 * contain the text {@code {accept}} and would be turned into a real button.
 * Scanning the template first means only what an administrator wrote in
 * messages.yml can ever become clickable.
 *
 * <p>The cost of that order is that if a message both carries an action and is
 * given a replacer of the same name, the button wins. No Duels message does
 * that today, and a button is the more surprising thing to lose silently.
 */
public final class ActionMessenger
{
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    /**
     * Characters that follow {@code &} in a legacy colour code. Colours and
     * {@code r} reset the accumulated formats; {@code k}-{@code o} add to them.
     */
    private static final String COLOUR_CODES = "0123456789abcdef";
    private static final String FORMAT_CODES = "klmno";

    /**
     * The messages Duels itself renders through this class. Used only by
     * {@link #audit()}: an action placeholder written into any other message
     * would be shown to players as literal text, and an administrator should be
     * told that rather than left wondering why their button is not clickable.
     */
    private static final List<Message> INTERACTIVE_SURFACES = List.of(
            Message.DUEL_HELP,
            Message.CHALLENGE_RECEIVED,
            Message.CHALLENGE_NO_PENDING,
            Message.MATCH_WIN,
            Message.MATCH_LOSE,
            Message.SPECTATE_STARTED,
            Message.SPECTATE_NO_MATCHES,
            Message.REMATCH_RECEIVED);

    private final MessageManager messages;
    private final DuelsSettings settings;
    private final Logger logger;
    private final Set<String> warned = ConcurrentHashMap.newKeySet();

    public ActionMessenger(MessageManager messages, DuelsSettings settings, Logger logger)
    {
        this.messages = messages;
        this.settings = settings;
        this.logger = logger;
    }

    /**
     * Sends a single configured message, with the prefix applied, rendering any
     * action placeholders it contains as clickable buttons.
     */
    public void send(CommandSender receiver, MessageKey key, Object... replacers)
    {
        String message = prefixed(key);

        if (message == null)
            return;

        receiver.sendMessage(render(receiver, message, replacers));
    }

    /**
     * Builds the prefixed but still un-substituted message.
     *
     * <p>{@code MessageManager.format} would do the prefixing, but it also
     * substitutes, and this class has to see the administrator's original text
     * before anything is substituted into it - see the ordering note on the
     * class.
     */
    private String prefixed(MessageKey key)
    {
        String message = messages.get(key);

        if (message == null)
            return null;

        String prefix = messages.getPrefix();

        return prefix != null && !prefix.isBlank() ? prefix + " " + message : message;
    }

    /**
     * Sends a configured list message - currently the help lists - one line at a
     * time.
     *
     * <p>A line is skipped entirely when it advertises an action the receiver
     * has no permission for. Dropping the line rather than only the button is
     * what makes this right for help: a help entry whose whole point is a
     * command you cannot run is noise, and a line reading "&nbsp;- watch a live
     * duel" with the button removed would be worse than not printing it.
     */
    public void sendList(CommandSender receiver, MessageKey key, Object... replacers)
    {
        List<String> lines = messages.getList(key);

        if (lines == null)
            return;

        for (String line : lines)
        {
            if (hidesAnyAction(receiver, line))
                continue;

            receiver.sendMessage(render(receiver, line, replacers));
        }
    }

    /**
     * Renders legacy {@code &}-coded text, in which action placeholders have
     * become clickable components, for one viewer.
     *
     * <p>Exposed so callers that already hold text - and tests - can render
     * without sending.
     */
    public Component render(CommandSender viewer, String text, Object... replacers)
    {
        String playerName = valueOf(replacers, ChatAction.playerPlaceholder());

        TextComponent.Builder builder = Component.text();

        int literalStart = 0;
        int cursor = 0;
        String carried = "";

        while (true)
        {
            int open = text.indexOf('{', cursor);

            if (open < 0)
                break;

            int close = text.indexOf('}', open + 1);

            if (close < 0)
                break;

            ChatAction action = ChatAction.byKey(text.substring(open + 1, close));

            if (action == null)
            {
                // Not one of ours. Leave it in the text exactly as written, which
                // is what an unrecognised placeholder has always done.
                cursor = open + 1;
                continue;
            }

            String literal = StringUtil.substitute(text.substring(literalStart, open), replacers);
            builder.append(LEGACY.deserialize(carried + literal));
            carried = trailingStyle(literal, carried);

            Component rendered = renderAction(viewer, action, playerName, replacers);

            if (rendered != null)
                builder.append(rendered);

            literalStart = close + 1;
            cursor = close + 1;
        }

        builder.append(LEGACY.deserialize(carried + StringUtil.substitute(text.substring(literalStart), replacers)));

        return builder.build();
    }

    /**
     * Prints every message that can carry buttons, rendered with sample data,
     * followed by each button on its own with the command it would run.
     *
     * <p>This exists because the alternative way to check a messages.yml edit is
     * to arrange the situation that produces the message - be challenged, win a
     * duel, find a live match to spectate - which is slow enough that in
     * practice the edit gets shipped unchecked. {@code sampleName} is normally
     * the viewer's own name, which also makes the buttons safe to actually
     * click: {@code /duel accept <yourself>} just reports no pending challenge.
     */
    public void preview(CommandSender receiver, String sampleName)
    {
        Object[] sample = {"player", sampleName, "arena", "(sample arena)", "seconds", "30"};

        receiver.sendMessage(LEGACY.deserialize("&8&m                                        "));
        receiver.sendMessage(LEGACY.deserialize("&c&lDuels &7clickable message preview &8(&7as &e"
                + sampleName + "&8)"));
        receiver.sendMessage(Component.empty());

        for (Message surface : INTERACTIVE_SURFACES)
        {
            receiver.sendMessage(LEGACY.deserialize("&8" + surface.getPath()
                    + (hasAction(surface) ? "" : " &8- &cno buttons configured")));

            if (surface == Message.DUEL_HELP)
                sendList(receiver, surface, sample);
            else
                send(receiver, surface, sample);
        }

        receiver.sendMessage(Component.empty());
        receiver.sendMessage(LEGACY.deserialize("&7Each button, and the command it carries:"));

        for (ChatAction action : ChatAction.values())
            receiver.sendMessage(describe(receiver, action, sampleName, sample));

        receiver.sendMessage(LEGACY.deserialize("&8&m                                        "));
    }

    private Component describe(CommandSender receiver, ChatAction action, String sampleName, Object... sample)
    {
        String command = action.resolveCommand(sampleName);
        String suffix = command == null
                ? " &8-> &cno click &7(this message does not know a player name)"
                : " &8-> &f" + command;

        if (!isEnabled(action))
            suffix = " &8-> &7hidden (&frematch-expiry-time&7 is 0 in config.yml)";
        else if (action.permission() != null && !isPermitted(receiver, action))
            suffix = " &8-> &7hidden from you (&f" + action.permission() + "&7)";

        return Component.text()
                .append(LEGACY.deserialize("&8" + action.placeholder() + " "))
                .append(LEGACY.deserialize(clean(configured(action.textPath(), action.defaultText()), sample)))
                .append(LEGACY.deserialize(suffix))
                .build();
    }

    private boolean hasAction(Message surface)
    {
        Object value = messages.getFile().getConfig().get(surface.getPath());

        return value != null && containsAnyAction(textOf(value));
    }

    /**
     * Checks every message file entry and reports anything an administrator
     * would otherwise only discover by watching chat.
     *
     * <p>Run once at startup and again on reload. Three separate problems are
     * worth telling someone about, and they have different fixes, so they are
     * reported separately rather than as one vague warning.
     */
    public void audit()
    {
        FileConfiguration config = messages.getFile().getConfig();

        auditActionDefinitions(config);
        auditPlaceholderPlacement(config);
        auditSurfacesWithoutActions(config);
    }

    /**
     * An action whose label has been deleted still works - the built-in default
     * is used - but the administrator has silently lost control of how it looks,
     * so say so.
     */
    private void auditActionDefinitions(FileConfiguration config)
    {
        List<String> missing = new ArrayList<>();

        for (ChatAction action : ChatAction.values())
            if (config.getString(action.textPath()) == null)
                missing.add(action.textPath());

        if (!missing.isEmpty())
            logger.warning("These clickable action labels are missing from messages.yml, so Duels is using its"
                    + " built-in wording for them: " + String.join(", ", missing)
                    + ". Delete the 'duel.actions' block and restart to regenerate them.");
    }

    /**
     * An action placeholder in a message Duels does not render interactively
     * would reach the player as the literal text "{accept}". That looks like a
     * plugin bug from the outside, so name the exact path.
     */
    private void auditPlaceholderPlacement(FileConfiguration config)
    {
        Set<String> interactive = new LinkedHashSet<>();

        for (Message surface : INTERACTIVE_SURFACES)
            interactive.add(surface.getPath());

        List<String> offenders = new ArrayList<>();

        for (String path : config.getKeys(true))
        {
            Object value = config.get(path);

            if (value instanceof ConfigurationSection || interactive.contains(path) || isActionDefinition(path))
                continue;

            if (containsAnyAction(textOf(value)))
                offenders.add(path);
        }

        if (!offenders.isEmpty())
            logger.warning("These messages contain a clickable action placeholder but are not sent as"
                    + " interactive messages, so it will be shown to players as literal text: "
                    + String.join(", ", offenders)
                    + ". Move the placeholder to one of: " + String.join(", ", interactive));
    }

    /**
     * Existing installations keep their own wording for keys they already have,
     * which is the right default but means an upgrade does not switch clickable
     * buttons on by itself. One informational line is enough to close that gap
     * without nagging an administrator who removed the buttons on purpose.
     */
    private void auditSurfacesWithoutActions(FileConfiguration config)
    {
        List<String> plain = new ArrayList<>();

        for (Message surface : INTERACTIVE_SURFACES)
        {
            Object value = config.get(surface.getPath());

            if (value != null && !containsAnyAction(textOf(value)))
                plain.add(surface.getPath());
        }

        if (!plain.isEmpty())
            logger.info("These messages can carry clickable buttons but yours have none configured: "
                    + String.join(", ", plain)
                    + ". Add a placeholder such as {accept} or {stats} to use them.");
    }

    private Component renderAction(CommandSender viewer, ChatAction action, String playerName, Object... replacers)
    {
        if (!isAvailable(viewer, action))
            return null;

        String label = configured(action.textPath(), action.defaultText());
        String hover = configured(action.hoverPath(), action.defaultHover());
        String command = action.resolveCommand(playerName);

        Component rendered = LEGACY.deserialize(clean(label, replacers));

        if (hover != null && !hover.isBlank())
            rendered = rendered.hoverEvent(HoverEvent.showText(LEGACY.deserialize(clean(hover, replacers))));

        if (command == null)
        {
            // The surrounding message did not name the player this action's
            // command needs. Showing the label without a click is honest; wiring
            // a click to a command with "{player}" still in it is not.
            warnOnce(action.key() + ".no-player", "The message using {" + action.key() + "} did not supply a"
                    + " player name, so that button was shown without a click action.");

            return rendered;
        }

        return rendered.clickEvent(action.clickKind() == ChatAction.ClickKind.RUN_COMMAND
                ? ClickEvent.runCommand(command)
                : ClickEvent.suggestCommand(command + " "));
    }

    private String configured(String path, String fallback)
    {
        String value = messages.getFile().getConfig().getString(path);

        if (value != null)
            return value;

        warnOnce(path, "Missing '" + path + "' in messages.yml, using the built-in wording instead.");

        return fallback;
    }

    /**
     * Whether this viewer should be shown this action at all.
     *
     * <p>Two separate reasons to hide a button collapse to the same answer on
     * purpose: a permission the viewer does not hold, and a feature the server
     * owner has switched off. Both mean "clicking this could only disappoint
     * you", and both should also take the whole help line advertising it with
     * them rather than leaving a sentence that describes a button that is not
     * there.
     */
    private boolean isAvailable(CommandSender viewer, ChatAction action)
    {
        return isEnabled(action) && isPermitted(viewer, action);
    }

    private boolean isPermitted(CommandSender viewer, ChatAction action)
    {
        return action.permission() == null || viewer == null || viewer.hasPermission(action.permission());
    }

    /**
     * Read live from settings rather than cached, so switching rematches off and
     * running {@code /duels reload} removes the button without a restart.
     */
    private boolean isEnabled(ChatAction action)
    {
        return action.feature() != ChatAction.Feature.REMATCH
                || settings == null
                || settings.rematchExpirySeconds() > 0;
    }

    private boolean hidesAnyAction(CommandSender viewer, String line)
    {
        for (ChatAction action : ChatAction.values())
            if (line.contains(action.placeholder()) && !isAvailable(viewer, action))
                return true;

        return false;
    }

    private static boolean containsAnyAction(String text)
    {
        if (text == null)
            return false;

        for (ChatAction action : ChatAction.values())
            if (text.contains(action.placeholder()))
                return true;

        return false;
    }

    private static boolean isActionDefinition(String path)
    {
        for (ChatAction action : ChatAction.values())
            if (path.equals(action.textPath()) || path.equals(action.hoverPath()))
                return true;

        return false;
    }

    private static String textOf(Object value)
    {
        if (value instanceof String string)
            return string;

        if (value instanceof List<?> list)
        {
            StringBuilder joined = new StringBuilder();

            for (Object item : list)
                joined.append(item).append('\n');

            return joined.toString();
        }

        return null;
    }

    /**
     * Substitutes the caller's placeholders into a label or hover, then removes
     * any that are left over.
     *
     * <p>The same action appears on more than one surface - {@code {accept}} is
     * used both in a challenge message, which knows the challenger's name, and
     * in the help list, which does not. Rather than forbid administrators from
     * writing {@code {player}} in a label, an unresolved placeholder renders as
     * nothing, so the worst case is slightly clipped wording instead of raw
     * braces in chat.
     */
    private static String clean(String text, Object... replacers)
    {
        return StringUtil.substitute(text, replacers).replaceAll("\\{[A-Za-z0-9_-]+}", "");
    }

    private static String valueOf(Object[] replacers, String key)
    {
        if (replacers == null)
            return null;

        for (int i = 0; i + 1 < replacers.length; i += 2)
            if (key.equals(String.valueOf(replacers[i])))
                return String.valueOf(replacers[i + 1]);

        return null;
    }

    /**
     * Works out which legacy colour and formats are still in force at the end of
     * a piece of text.
     *
     * <p>Each literal run either side of an action has to be deserialized on its
     * own, and a legacy deserializer starts every string from no colour at all.
     * Without carrying the trailing style forward, an administrator writing
     * {@code &7Challenged! {accept} or ignore it} would get grey text, a green
     * button, and then unexpectedly white text after it - a confusing result
     * from a message file that looks correct.
     */
    static String trailingStyle(String text, String inherited)
    {
        String combined = inherited + text;
        String colour = "";
        Set<Character> formats = new LinkedHashSet<>();

        for (int i = 0; i + 1 < combined.length(); i++)
        {
            if (combined.charAt(i) != '&')
                continue;

            char code = Character.toLowerCase(combined.charAt(i + 1));

            if (COLOUR_CODES.indexOf(code) >= 0)
            {
                colour = "&" + code;
                formats.clear();
            }
            else if (code == 'r')
            {
                colour = "";
                formats.clear();
            }
            else if (FORMAT_CODES.indexOf(code) >= 0)
                formats.add(code);
        }

        StringBuilder style = new StringBuilder(colour);

        for (char format : formats)
            style.append('&').append(format);

        return style.toString();
    }

    private void warnOnce(String key, String message)
    {
        if (warned.add(key))
            logger.warning(message);
    }

    /**
     * Lets a reload report configuration problems again, since the file may have
     * been fixed - or newly broken - since the last warning was suppressed.
     */
    public void forgetWarnings()
    {
        warned.clear();
    }
}
