package me.jackcw.duels.arena;

/**
 * Persisted health/lifecycle state for a provisioned instance. Only READY is
 * eligible for normal allocation; every other state is deliberately retained
 * for recovery or administrator diagnosis.
 */
public enum DynamicArenaState
{
    PROVISIONING,
    READY,
    DIRTY,
    RETIRING,
    FAILED
}
