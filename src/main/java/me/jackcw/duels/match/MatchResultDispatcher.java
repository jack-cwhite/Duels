package me.jackcw.duels.match;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The single point at which a finished match becomes everyone else's problem.
 *
 * <p>Before this existed, {@link MatchManager#endMatch} called the stats manager
 * directly. Every further consumer of a result - rewards, and later ratings -
 * would have meant another call, another bespoke try/catch, and another chance
 * for one subsystem's failure to abort the ones after it. Routing results through
 * one object gives all of them the same delivery guarantee and the same failure
 * isolation, and means {@code endMatch} does not grow a branch per feature.
 *
 * <p><strong>What this deliberately does not do:</strong> it holds no durable
 * queue. A result dispatched here is handed to each consumer in memory and then
 * forgotten. If the server dies between {@code endMatch} and a consumer's own
 * write, that consumer loses the result, and it is that consumer's job to decide
 * whether it cares (stats accept the loss and log it; Phase 6's rewards write
 * their own ledger before doing anything a player can see). This is the right
 * split while every consumer lives in the same process as the match. It stops
 * being the right split the moment a result has to reach another server, and at
 * that point the dispatcher is the place to put the outbox.
 */
public final class MatchResultDispatcher
{
    private static final Logger LOGGER = Logger.getLogger(MatchResultDispatcher.class.getName());

    /**
     * How many recent result ids to remember for duplicate suppression. A busy
     * server finishing a match every few seconds will not revisit an id from
     * this far back, and the memory cost is a few kilobytes.
     */
    private static final int REMEMBERED_RESULTS = 512;

    private final List<MatchResultConsumer> consumers = new ArrayList<>();
    private final Set<UUID> dispatched = new LinkedHashSet<>();

    public void register(MatchResultConsumer consumer)
    {
        consumers.add(consumer);
    }

    /**
     * Fans one result out to every registered consumer, in registration order.
     *
     * <p>Called on the main thread. The duplicate guard below is belt and braces:
     * {@code endMatch} already refuses to run twice for a match via its
     * {@link MatchState#ENDED} check. Keeping the check here as well makes
     * "at most once per result id" a property of this boundary that consumers can
     * rely on, rather than something that happens to be true because of how the
     * one current caller is written.
     */
    public void dispatch(MatchResult result)
    {
        if (result == null)
            return;

        if (!dispatched.add(result.resultId()))
        {
            LOGGER.warning("Ignoring a repeat dispatch of match result " + result.resultId());
            return;
        }

        if (dispatched.size() > REMEMBERED_RESULTS)
        {
            Iterator<UUID> oldest = dispatched.iterator();
            oldest.next();
            oldest.remove();
        }

        for (MatchResultConsumer consumer : consumers)
        {
            // A consumer that throws must not stop the consumers after it, and
            // must not stop the match cleanup that called us - players still need
            // restoring and the arena still needs releasing.
            try
            {
                consumer.accept(result);
            }
            catch (Exception exception)
            {
                LOGGER.log(Level.SEVERE, "Match result consumer '" + consumer.name()
                        + "' failed; continuing with the remaining consumers. Result was: " + result, exception);
            }
        }
    }
}
