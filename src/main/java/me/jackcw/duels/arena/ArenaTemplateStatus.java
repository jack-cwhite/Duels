package me.jackcw.duels.arena;

/**
 * The administrator-facing readiness of an arena's dynamic provisioning
 * policy. A static arena is valid without a captured structure.
 */
public enum ArenaTemplateStatus
{
    STATIC,
    MISSING_TEMPLATE,
    READY
}
