package me.jackcw.duels.match;

/**
 * One interested party in a finished match.
 *
 * <p>Consumers are handed a result exactly once per {@link MatchResult#resultId()}
 * and must treat it as the only notification they will get for that match. A
 * consumer is responsible for its own persistence, its own threading and its own
 * failure handling: {@link MatchResultDispatcher} isolates a thrown exception so
 * one consumer cannot stop another, but it does not retry, queue or otherwise
 * make a consumer's work durable on its behalf.
 */
public interface MatchResultConsumer
{
    /** Identifies this consumer in log messages when it fails. */
    String name();

    /**
     * Called on the main thread while the match is unwinding, so implementations
     * must not block. Anything slow belongs on JCore's task pool.
     */
    void accept(MatchResult result);
}
