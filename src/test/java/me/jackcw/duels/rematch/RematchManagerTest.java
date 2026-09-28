package me.jackcw.duels.rematch;

import me.jackcw.duels.Duels;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the rematch window on its own, away from matches and commands.
 *
 * <p>The interesting behaviour here is all about a window ending: by running
 * out, by being replaced, by a player leaving, or by the plugin shutting down.
 * Each of those has to take both players' entries and the scheduled task with
 * it, because a window that outlives its task - or a task that outlives its
 * window - is a leak nobody can see from in game.
 */
class RematchManagerTest
{
    private ServerMock server;
    private Duels plugin;
    private RematchManager rematches;

    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();
    private final UUID carol = UUID.randomUUID();

    @BeforeEach
    void setup()
    {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(Duels.class);
        rematches = plugin.getRematchManager();
    }

    @AfterEach
    void cleanup()
    {
        MockBukkit.unmock();
    }

    @Test
    void aFinishedDuelLeavesBothPlayersLookingAtTheSameWindow()
    {
        RematchContext context = rematches.register(alice, "Alice", bob, "Bob", 7);

        assertNotNull(context);
        assertSame(context, rematches.get(alice));
        assertSame(context, rematches.get(bob));
        assertEquals(1, rematches.getContextCount());
        assertEquals(7, context.arenaId());
        assertEquals("Bob", context.opponentNameOf(alice));
        assertEquals(alice, context.opponentOf(bob));
    }

    @Test
    void aPlayerWhoHasNotJustDuelledHasNoWindow()
    {
        rematches.register(alice, "Alice", bob, "Bob", 7);

        assertNull(rematches.get(carol));
    }

    @Test
    void theWindowClosesWhenItsTimeIsUp()
    {
        setExpirySeconds(2);

        rematches.register(alice, "Alice", bob, "Bob", 7);
        server.getScheduler().performTicks(3L * 20L);

        assertNull(rematches.get(alice));
        assertNull(rematches.get(bob));
        assertEquals(0, rematches.getContextCount());
    }

    /**
     * The expiry task runs a tick at a time, so a click can land in the gap
     * between the window ending and the task sweeping it. Reading the window has
     * to give the same answer either side of that gap.
     */
    @Test
    void aWindowPastItsTimeIsGoneEvenBeforeItsTaskHasRun()
    {
        rematches.register(alice, "Alice", bob, "Bob", 7);

        RematchContext expired = new RematchContext(
                alice, "Alice", bob, "Bob", 7, Instant.now().minusSeconds(1));

        assertTrue(expired.isExpired());
        assertEquals(0, expired.remainingSeconds());
    }

    @Test
    void aNewerDuelReplacesTheWindowOfBothItsPlayers()
    {
        rematches.register(alice, "Alice", bob, "Bob", 7);
        RematchContext newer = rematches.register(alice, "Alice", carol, "Carol", 9);

        assertSame(newer, rematches.get(alice));
        assertSame(newer, rematches.get(carol));
        assertNull(rematches.get(bob), "Bob's window should have gone with Alice's older one");
        assertEquals(1, rematches.getContextCount());
    }

    @Test
    void closingOnePlayersWindowClosesItForTheirOpponentToo()
    {
        rematches.register(alice, "Alice", bob, "Bob", 7);
        rematches.invalidate(alice);

        assertNull(rematches.get(alice));
        assertNull(rematches.get(bob));
        assertEquals(0, rematches.getContextCount());
    }

    @Test
    void invalidatingSomeoneWithNoWindowDoesNothing()
    {
        rematches.register(alice, "Alice", bob, "Bob", 7);
        rematches.invalidateAll(carol, null);

        assertEquals(1, rematches.getContextCount());
    }

    @Test
    void nothingIsOfferedWhileRematchesAreTurnedOff()
    {
        setExpirySeconds(0);

        assertFalse(rematches.isEnabled());
        assertNull(rematches.register(alice, "Alice", bob, "Bob", 7));
        assertNull(rematches.get(alice));
        assertEquals(0, rematches.getContextCount());
    }

    @Test
    void turningRematchesBackOnDoesNotNeedARestart()
    {
        setExpirySeconds(0);
        assertFalse(rematches.isEnabled());

        setExpirySeconds(30);

        assertTrue(rematches.isEnabled());
        assertNotNull(rematches.register(alice, "Alice", bob, "Bob", 7));
    }

    @Test
    void shuttingDownClosesEveryWindow()
    {
        rematches.register(alice, "Alice", bob, "Bob", 7);
        rematches.register(carol, "Carol", UUID.randomUUID(), "Dave", 9);

        assertEquals(2, rematches.getContextCount());

        rematches.clear();

        assertEquals(0, rematches.getContextCount());
        assertNull(rematches.get(alice));
        assertNull(rematches.get(carol));
    }

    /**
     * Written and reloaded the way an administrator would, so the test proves
     * the setting is read live rather than cached at construction.
     */
    private void setExpirySeconds(int seconds)
    {
        plugin.core().config().getConfig().set("rematch-expiry-time", seconds);
        plugin.core().config().save();
        plugin.reloadConfiguration();
    }
}
