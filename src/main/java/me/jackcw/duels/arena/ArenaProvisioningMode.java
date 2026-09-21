package me.jackcw.duels.arena;

/**
 * How additional physical copies of an arena template may become available.
 */
public enum ArenaProvisioningMode
{
    /** Only administrator-created, registered instances may be allocated. */
    STATIC,

    /** Existing instances are preferred, but another may be provisioned on demand. */
    DYNAMIC
}
