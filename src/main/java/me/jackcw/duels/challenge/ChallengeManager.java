package me.jackcw.duels.challenge;

import me.jackcw.duels.DuelsSettings;
import me.jackcw.jcore.task.TaskManager;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

public final class ChallengeManager
{
    private static final long TICKS_PER_SECOND = 20L;

    private final TaskManager taskManager;
    private final DuelsSettings settings;
    private final Consumer<Challenge> onExpire;
    private final List<Challenge> challenges = new ArrayList<>();

    public ChallengeManager(TaskManager taskManager, DuelsSettings settings, Consumer<Challenge> onExpire)
    {
        this.taskManager = taskManager;
        this.settings = settings;
        this.onExpire = onExpire;
    }

    public boolean createChallenge(Player challenger, Player challenged)
    {
        if (challenger == null || !challenger.isOnline())
            return false;

        if (challenged == null || !challenged.isOnline())
            return false;

        if (getChallengeBetween(challenger.getUniqueId(), challenged.getUniqueId()) != null)
            return false;

        int expirySeconds = settings.challengeExpirySeconds();
        Instant expiry = expirySeconds > 0 ? Instant.now().plusSeconds(expirySeconds) : Instant.MAX;

        Challenge challenge = new Challenge(challenger.getUniqueId(), challenged.getUniqueId(), expiry);
        challenges.add(challenge);

        if (expirySeconds > 0)
            taskManager.runSyncLater(() -> expire(challenge), expirySeconds * TICKS_PER_SECOND);

        return true;
    }

    public Challenge acceptChallenge(UUID challenged)
    {
        Challenge challenge = getLatestIncoming(challenged);

        if (challenge == null)
            return null;

        challenges.remove(challenge);

        return challenge;
    }

    public Challenge acceptChallenge(UUID challenged, UUID challenger)
    {
        Challenge challenge = getChallengeBetween(challenged, challenger);

        if (challenge == null || !challenge.getChallenged().equals(challenged))
            return null;

        challenges.remove(challenge);

        return challenge;
    }

    public Challenge declineChallenge(UUID uuid)
    {
        Challenge challenge = getLatestInvolving(uuid);

        if (challenge == null)
            return null;

        challenges.remove(challenge);

        return challenge;
    }

    public Challenge declineChallenge(UUID uuid, UUID other)
    {
        Challenge challenge = getChallengeBetween(uuid, other);

        if (challenge == null)
            return null;

        challenges.remove(challenge);

        return challenge;
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
        challenges.remove(challenge);
    }

    public void removeAll(UUID... playerIds)
    {
        for (UUID playerId : playerIds)
            challenges.removeIf(challenge -> challenge.getChallenger().equals(playerId) || challenge.getChallenged().equals(playerId));
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
        if (!challenges.remove(challenge))
            return;

        if (onExpire != null)
            onExpire.accept(challenge);
    }
}
