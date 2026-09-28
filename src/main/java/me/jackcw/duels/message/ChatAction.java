package me.jackcw.duels.message;

/**
 * A clickable element an administrator can place inside any Duels message by
 * writing its placeholder, for example {@code {accept}}.
 *
 * <p>The split of ownership here is deliberate. Everything a player <em>sees</em>
 * - the label, its colours, the hover text - is configuration, because that is
 * presentation and servers want their own wording. Everything a click
 * <em>does</em> - which command runs, which permission is required - is code,
 * because a message file that could dictate the command to run would let anyone
 * who can edit messages.yml make a player run an arbitrary command by clicking a
 * chat line.
 *
 * <p>Commands are the same ones a player can type. A click is never a private
 * back channel into the plugin, so a stale or malicious click can only reach a
 * handler that already validates its input.
 */
public enum ChatAction
{
    CHALLENGE("challenge", ClickKind.SUGGEST_COMMAND, "/duel challenge", Argument.NONE, null,
            "&a&l[CHALLENGE]", "&7Click, then type the name of the player you want to duel."),

    SELECT_ARENA("select", ClickKind.SUGGEST_COMMAND, "/duel select", Argument.NONE, null,
            "&b&l[PICK ARENA]", "&7Click, then type a player's name to choose an arena first."),

    ACCEPT("accept", ClickKind.RUN_COMMAND, "/duel accept", Argument.OPTIONAL_PLAYER, null,
            "&a&l[ACCEPT]", "&7Accept the duel challenge."),

    DENY("deny", ClickKind.RUN_COMMAND, "/duel deny", Argument.OPTIONAL_PLAYER, null,
            "&c&l[DENY]", "&7Decline the duel challenge."),

    REMATCH("rematch", ClickKind.RUN_COMMAND, "/duel rematch", Argument.OPTIONAL_PLAYER, null, Feature.REMATCH,
            "&a&l[REMATCH]", "&7Ask for another duel on the same arena."),

    KIT("kit", ClickKind.RUN_COMMAND, "/duel kit", Argument.NONE, null,
            "&e&l[KITS]", "&7Reopen the kit selection menu."),

    SPECTATE("spectate", ClickKind.RUN_COMMAND, "/duel spectate", Argument.NONE, "duels.spectate",
            "&d&l[SPECTATE]", "&7Watch a duel that is being played right now."),

    LEAVE("leave", ClickKind.RUN_COMMAND, "/duel leave", Argument.NONE, "duels.spectate",
            "&c&l[STOP WATCHING]", "&7Stop spectating and go back to where you were."),

    STATS("stats", ClickKind.RUN_COMMAND, "/duel stats", Argument.NONE, null,
            "&b&l[MY STATS]", "&7Open your own duel statistics."),

    OPPONENT_STATS("opponent-stats", ClickKind.RUN_COMMAND, "/duel stats", Argument.REQUIRED_PLAYER, null,
            "&b&l[THEIR STATS]", "&7Open your opponent's duel statistics."),

    LEADERBOARD("top", ClickKind.RUN_COMMAND, "/duel top", Argument.NONE, null,
            "&6&l[LEADERBOARD]", "&7Open the duel leaderboards.");

    /**
     * What a click does. Running is right for an action the player has already
     * decided on; suggesting is right when the command still needs an argument
     * typing, because running an incomplete command would only show a usage error.
     */
    public enum ClickKind
    {
        RUN_COMMAND,
        SUGGEST_COMMAND
    }

    /**
     * Whether the action's command takes the {@code {player}} the surrounding
     * message is about.
     *
     * <p>Every current action either takes no argument or takes a player name,
     * so this stays a small closed choice rather than a general argument model.
     * {@code OPTIONAL_PLAYER} exists because {@code {accept}} is meaningful both
     * in a challenge message, where it should answer that specific challenger,
     * and in the help list, where it should answer whoever is pending.
     */
    private enum Argument
    {
        NONE,
        OPTIONAL_PLAYER,
        REQUIRED_PLAYER
    }

    /**
     * A configurable feature an action depends on.
     *
     * <p>An action whose feature is switched off is hidden in exactly the same
     * way as one the viewer lacks permission for. A server that sets
     * {@code rematch-expiry-time: 0} should not be showing a rematch button
     * whose only possible answer is "rematches are disabled", and the help line
     * advertising it should disappear with it.
     */
    public enum Feature
    {
        ALWAYS,
        REMATCH
    }

    private static final String PLAYER_PLACEHOLDER = "player";
    private static final String ACTIONS_ROOT = "duel.actions";

    private final String key;
    private final ClickKind clickKind;
    private final String command;
    private final Argument argument;
    private final String permission;
    private final Feature feature;
    private final String defaultText;
    private final String defaultHover;

    ChatAction(String key, ClickKind clickKind, String command, Argument argument, String permission,
               String defaultText, String defaultHover)
    {
        this(key, clickKind, command, argument, permission, Feature.ALWAYS, defaultText, defaultHover);
    }

    ChatAction(String key, ClickKind clickKind, String command, Argument argument, String permission,
               Feature feature, String defaultText, String defaultHover)
    {
        this.key = key;
        this.clickKind = clickKind;
        this.command = command;
        this.argument = argument;
        this.permission = permission;
        this.feature = feature;
        this.defaultText = defaultText;
        this.defaultHover = defaultHover;
    }

    /**
     * The placeholder an administrator writes in a message, including braces.
     */
    public String placeholder()
    {
        return "{" + key + "}";
    }

    public String key()
    {
        return key;
    }

    public ClickKind clickKind()
    {
        return clickKind;
    }

    /**
     * The permission a player needs before this action is worth showing them,
     * or null if anyone may use it. Hiding an action they cannot use is better
     * than showing a button that answers with "no permission".
     */
    public String permission()
    {
        return permission;
    }

    /**
     * The configurable feature this action depends on, or {@link Feature#ALWAYS}
     * when it is always available.
     */
    public Feature feature()
    {
        return feature;
    }

    public String textPath()
    {
        return ACTIONS_ROOT + "." + key + ".text";
    }

    public String hoverPath()
    {
        return ACTIONS_ROOT + "." + key + ".hover";
    }

    public String defaultText()
    {
        return defaultText;
    }

    public String defaultHover()
    {
        return defaultHover;
    }

    /**
     * Builds the command this action's click should carry, or null when the
     * surrounding message did not supply a player name that the command needs.
     *
     * <p>Returning null rather than a command with an unresolved
     * {@code {player}} in it is the important part: a click that would run
     * {@code /duel stats {player}} is worse than no click at all, so the caller
     * renders the label as inert text instead.
     */
    public String resolveCommand(String playerName)
    {
        boolean hasPlayer = playerName != null && !playerName.isBlank();

        return switch (argument)
        {
            case NONE -> command;
            case OPTIONAL_PLAYER -> hasPlayer ? command + " " + playerName : command;
            case REQUIRED_PLAYER -> hasPlayer ? command + " " + playerName : null;
        };
    }

    /**
     * The placeholder name this action reads a player name from, so the renderer
     * can pull it out of the same replacers the surrounding message uses.
     */
    public static String playerPlaceholder()
    {
        return PLAYER_PLACEHOLDER;
    }

    public static ChatAction byKey(String key)
    {
        for (ChatAction action : values())
            if (action.key.equals(key))
                return action;

        return null;
    }
}
