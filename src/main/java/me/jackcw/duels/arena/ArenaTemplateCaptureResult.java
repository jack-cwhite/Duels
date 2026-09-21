package me.jackcw.duels.arena;

/**
 * A command/UI-safe outcome for template capture. The low-level exception is
 * logged by {@link ArenaTemplateManager}; callers only need the actionable
 * reason and never receive a half-published template.
 */
public record ArenaTemplateCaptureResult(Status status, ArenaTemplateDefinition template)
{
    public enum Status
    {
        SUCCESS,
        ARENA_NOT_FOUND,
        INSTANCE_NOT_FOUND,
        INSTANCE_IN_USE,
        DYNAMIC_MODE_ACTIVE,
        MISSING_CAPTURE_CORNERS,
        INSTANCE_NOT_READY,
        BOUNDS_NOT_SET,
        WORLD_MISMATCH,
        OUTSIDE_CAPTURE_REGION,
        TOO_LARGE,
        CAPTURE_FAILED
    }

    public static ArenaTemplateCaptureResult success(ArenaTemplateDefinition template)
    {
        return new ArenaTemplateCaptureResult(Status.SUCCESS, template);
    }

    public static ArenaTemplateCaptureResult failure(Status status)
    {
        return new ArenaTemplateCaptureResult(status, null);
    }
}
