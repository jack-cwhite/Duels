package me.jackcw.duels.challenge;

import me.jackcw.duels.arena.ArenaSelection;
import java.time.Instant;
import java.util.UUID;

public final class Challenge
{
    private final UUID challenger;
    private final UUID challenged;
    private final Instant expiry;
    private final ArenaSelection selection;
    private boolean claimed;

    public Challenge(UUID challenger, UUID challenged, Instant expiry)
    {
        this(challenger, challenged, expiry, ArenaSelection.any());
    }

    public Challenge(UUID challenger, UUID challenged, Instant expiry, ArenaSelection selection)
    {
        this.challenger = challenger;
        this.challenged = challenged;
        this.expiry = expiry;
        this.selection = selection != null ? selection : ArenaSelection.any();
    }

    public UUID getChallenger()
    {
        return challenger;
    }

    public UUID getChallenged()
    {
        return challenged;
    }

    public Instant getExpiry()
    {
        return expiry;
    }

    public ArenaSelection getSelection() { return selection; }
    public boolean isClaimed() { return claimed; }
    public void setClaimed(boolean claimed) { this.claimed = claimed; }
}
