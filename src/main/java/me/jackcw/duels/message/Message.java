package me.jackcw.duels.message;

import me.jackcw.jcore.message.MessageKey;

public enum Message implements MessageKey
{
    ADMIN_HELP("admin.help"),
    NO_ARENAS("admin.no-arenas"),
    DUEL_HELP("duel.help"),
    CHALLENGE_CANNOT_SELF("duel.cannot-challenge-self"),
    CHALLENGE_ALREADY_PENDING("duel.already-pending"),
    CHALLENGE_SENT("duel.challenge-sent"),
    CHALLENGE_RECEIVED("duel.challenge-received"),
    CHALLENGE_NO_PENDING("duel.no-pending"),
    CHALLENGE_ACCEPTED("duel.accepted"),
    CHALLENGE_ACCEPTED_OPPONENT("duel.accepted-opponent"),
    CHALLENGE_DECLINED("duel.declined"),
    CHALLENGE_DECLINED_OPPONENT("duel.declined-opponent"),
    CHALLENGE_CANCELLED_DISCONNECT("duel.cancelled-disconnect"),
    CHALLENGE_EXPIRED("duel.challenge-expired"),
    NO_ARENA_AVAILABLE("duel.no-arena-available"),
    ALREADY_IN_MATCH("duel.already-in-match"),
    TARGET_IN_MATCH("duel.target-in-match"),
    MATCH_START("duel.match-start"),
    MATCH_WIN("duel.match-win"),
    MATCH_LOSE("duel.match-lose"),
    ARENA_CREATED("admin.arena-created"),
    ARENA_DELETED("admin.arena-deleted"),
    ARENA_NOT_FOUND("admin.arena-not-found"),
    ARENA_SPAWN_SET("admin.arena-spawn-set"),
    ARENA_SPAWN_NOT_SET("admin.arena-spawn-not-set"),
    ARENA_INVALID_SPAWN("admin.arena-invalid-spawn"),
    ARENA_RENAMED("admin.arena-renamed"),
    ARENA_LIST_ENTRY("admin.arena-list-entry"),
    ARENA_ENABLED("admin.arena-enabled"),
    ARENA_DISABLED("admin.arena-disabled"),
    ARENA_IN_USE("admin.arena-in-use"),
    KIT_CREATED("admin.kit-created"),
    KIT_DELETED("admin.kit-deleted"),
    KIT_NOT_FOUND("admin.kit-not-found"),
    KIT_RENAMED("admin.kit-renamed"),
    KIT_SAVED("admin.kit-saved"),
    KIT_ICON_SET("admin.kit-icon-set"),
    KIT_ICON_EMPTY_HAND("admin.kit-icon-empty-hand"),
    NO_KITS("admin.no-kits"),
    KIT_SELECTED("duel.kit-selected"),
    NOT_IN_DUEL("duel.not-in-duel"),
    KIT_SELECTION_CLOSED("duel.kit-selection-closed"),
    STATS_LOAD_FAILED("duel.stats-load-failed"),
    PLAYER_RECORD("duel.player-record"),
    NO_KITS_ALLOWED("duel.no-kits-allowed"),
    KIT_LIST_ENTRY("admin.kit-list-entry"),
    MENU_INVALID_NAME("menu.invalid-name"),
    ARENA_RENAME_PROMPT("menu.arena-rename-prompt"),
    ARENA_CREATE_PROMPT("menu.arena-create-prompt"),
    KIT_RENAME_PROMPT("menu.kit-rename-prompt"),
    KIT_CREATE_PROMPT("menu.kit-create-prompt"),
    MENU_INPUT_CANCELLED("menu.input-cancelled"),
    STATE_RESTORED("player.state-restored");

    private final String path;

    Message(String path)
    {
        this.path = path;
    }

    @Override
    public String getPath()
    {
        return path;
    }
}
