package me.jackcw.duels.challenge;

import me.jackcw.duels.DuelsSettings;
import me.jackcw.duels.arena.ArenaSelection;
import me.jackcw.jcore.task.TaskManager;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

public final class ChallengeManager
{
    private final TaskManager taskManager;
    private final DuelsSettings settings;
    private final Consumer<Challenge> onExpire;
    private final List<Challenge> challenges = new ArrayList<>();

    // The scheduled expiry for each pending challenge, kept so that a challenge
    // leaving the list early - accepted, declined, or dropped when a player
    // quits - takes its timer with it. Without the handle there was nothing to
    // cancel, so every challenge ever issued left a task sitting in Bukkit's
    // scheduler until its original expiry time came round.
    private final Map<Challenge, BukkitTask> expiryTasks = new HashMap<>();

    public ChallengeManager(TaskManager taskManager, DuelsSettings settings, Consumer<Challenge> onExpire)
    {
        this.taskManager = taskManager;
        this.settings = settings;
        this.onExpire = onExpire;
    }

    public boolean createChallenge(Player challenger, Player challenged)
    {
        return createChallenge(challenger, challenged, ArenaSelection.any());
    }

    public boolean createChallenge(Player challenger, Player challenged, ArenaSelection selection)
    {
        return createChallenge(challenger, challenged, selection, ChallengeKind.DIRECT,
                settings.challengeExpirySeconds());
    }

    /**
     * Creates a challenge with an explicit lifetime and origin.
     *
     * <p>A rematch supplies its own expiry - whatever is left of the post-match
     * window - rather than the configured challenge expiry, so a request can
     * never outlive the opportunity that justified it. Everything after that
     * point is identical to a direct challenge on purpose: pair uniqueness,
     * claiming during arena allocation and removal all stay in one place.
     */
    public boolean createChallenge(Player challenger, Player challenged, ArenaSelection selection,
                                   ChallengeKind kind, int expirySeconds)
    {
        Instant expiry = expirySeconds > 0 ? Instant.now().plusSeconds(expirySeconds) : Instant.MAX;
        return createChallenge(challenger, challenged, selection, kind, expiry);
    }

    /** Uses the rematch window's exact deadline, without rounding away its last second. */
    public boolean createChallenge(Player challenger, Player challenged, ArenaSelection selection,
                                   ChallengeKind kind, Instant expiry)
    {
        if (challenger == null || !challenger.isOnline())
            return false;

        if (challenged == null || !challenged.isOnline())
            return false;

        if (getChallengeBetween(challenger.getUniqueId(), challenged.getUniqueId()) != null)
            return false;

        if (expiry == null || !expiry.isAfter(Instant.now()))
            return false;

        Challenge challenge = new Challenge(challenger.getUniqueId(), challenged.getUniqueId(), expiry, selection, kind);
        challenges.add(challenge);

        if (!Instant.MAX.equals(expiry))
        {
            long millis = Duration.between(Instant.now(), expiry).toMillis();
            long ticks = Math.max(1L, (millis + 49L) / 50L);
            expiryTasks.put(challenge, taskManager.runSyncLater(() -> expire(challenge), ticks));
        }

        return true;
    }

    /**
     * The one way a challenge leaves the list. Centralised because its expiry
     * task has to be cancelled alongside it, and there are six paths that drop a
     * challenge - accepting, declining either way round, expiring, a stale
     * claim being released, and a player quitting. Cancelling at each of those
     * call sites instead would mean one missed site is a leak nobody notices.
     */
    private boolean discard(Challenge challenge)
    {
        BukkitTask expiryTask = expiryTasks.remove(challenge);

        if (expiryTask != null)
            expiryTask.cancel();

        return challenges.remove(challenge);
    }

    /**
     * Resolves the challenge an accept would consume <em>without</em> removing
     * it, so the caller can confirm a match will actually start before spending
     * it. Accepting is not a single atomic step here - arena allocation can
     * still fail after the challenge is resolved - so consumption is left to
     * the caller via {@link #remove(Challenge)}.
     */
    public Challenge findIncoming(UUID challenged)
    {
        return getLatestIncoming(challenged);
    }

    public Challenge findIncoming(UUID challenged, UUID challenger)
    {
        Challenge challenge = getChallengeBetween(challenged, challenger);

        if (challenge == null || !challenge.getChallenged().equals(challenged))
            return null;

        return challenge;
    }

    public Challenge declineChallenge(UUID uuid)
    {
        Challenge challenge = getLatestInvolving(uuid);

        if (challenge == null)
            return null;

        discard(challenge);

        return challenge;
    }

    public Challenge declineChallenge(UUID uuid, UUID other)
    {
        Challenge challenge = getChallengeBetween(uuid, other);

        if (challenge == null)
            return null;

        discard(challenge);

        return challenge;
    }

    /**
     * Every challenge still pending, across all players. Reported by
     * {@code /duels diagnostics}, where a challenge outliving its expiry task or
     * the match it started is the leak worth catching.
     */
    public int getChallengeCount()
    {
        return challenges.size();
    }

    public List<Challenge> getChallenges(UUID uuid)
    {
        List<Challenge> involving = new ArrayList<>();

        for (Challenge challenge : challenges)
            if (challenge.getChallenger().equals(uuid) || challenge.getChallenged().equals(uuid))
                involving.add(challenge);

        return involving;
    }

    public void remove(Challenge challenge)
    {
        discard(challenge);
    }

    /** Claims a challenge while delayed arena preparation is in progress. */
    public boolean claim(Challenge challenge)
    {
        if (challenge == null || !challenges.contains(challenge) || challenge.isClaimed())
            return false;

        if (!challenge.getExpiry().isAfter(Instant.now()))
        {
            expire(challenge);
            return false;
        }

        challenge.setClaimed(true);
        return true;
    }

    /**
     * Returns whether the challenge remains pending. If allocation failed only
     * after its original expiry time, it expires normally instead of becoming
     * a zombie request.
     */
    public boolean releaseClaim(Challenge challenge)
    {
        if (challenge == null || !challenges.contains(challenge))
            return false;

        challenge.setClaimed(false);
        if (!Instant.MAX.equals(challenge.getExpiry()) && !challenge.getExpiry().isAfter(Instant.now()))
        {
            discard(challenge);
            if (onExpire != null)
                onExpire.accept(challenge);
            return false;
        }
        return true;
    }

    public void removeAll(UUID... playerIds)
    {
        for (UUID playerId : playerIds)
            for (Challenge challenge : getChallenges(playerId))
                discard(challenge);
    }

    /** Drops rematch invitations when their eligibility window is cancelled. */
    public void removeRematchesInvolving(UUID... playerIds)
    {
        for (UUID playerId : playerIds)
            if (playerId != null)
                for (Challenge challenge : getChallenges(playerId))
                    if (challenge.isRematch())
                        discard(challenge);
    }

    public void removeAllRematches()
    {
        for (Challenge challenge : new ArrayList<>(challenges))
            if (challenge.isRematch())
                discard(challenge);
    }

    public Challenge getChallengeBetween(UUID uuid1, UUID uuid2)
    {
        for (Challenge challenge : challenges)
        {
            boolean matchesPair = (challenge.getChallenger().equals(uuid1) && challenge.getChallenged().equals(uuid2)) || (challenge.getChallenger().equals(uuid2) && challenge.getChallenged().equals(uuid1));

            if (matchesPair)
                return challenge;
        }

        return null;
    }

    private Challenge getLatestIncoming(UUID challenged)
    {
        Challenge latest = null;

        for (Challenge challenge : getChallenges(challenged))
        {
            if (!challenge.getChallenged().equals(challenged))
                continue;

            if (latest == null || challenge.getExpiry().isAfter(latest.getExpiry()))
                latest = challenge;
        }

        return latest;
    }

    private Challenge getLatestInvolving(UUID uuid)
    {
        Challenge latest = null;

        for (Challenge challenge : getChallenges(uuid))
            if (latest == null || challenge.getExpiry().isAfter(latest.getExpiry()))
                latest = challenge;

        return latest;
    }

    private void expire(Challenge challenge)
    {
        if (challenge.isClaimed())
            return;

        if (!discard(challenge))
            return;

        if (onExpire != null)
            onExpire.accept(challenge);
    }
}
