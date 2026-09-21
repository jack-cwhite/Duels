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
        PROVISIONED_SOURCE,
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

    public static String describeFailure(Status status)
    {
        return switch (status)
        {
            case ARENA_NOT_FOUND -> "the arena no longer exists";
            case INSTANCE_NOT_FOUND -> "the arena instance no longer exists";
            case INSTANCE_IN_USE -> "that instance is hosting a match";
            case PROVISIONED_SOURCE -> "capture from a hand-built source instance, not a generated copy";
            case DYNAMIC_MODE_ACTIVE -> "switch the arena to STATIC before replacing its template";
            case MISSING_CAPTURE_CORNERS -> "set both structure capture corners first";
            case INSTANCE_NOT_READY -> "set both player spawns first";
            case BOUNDS_NOT_SET -> "set both gameplay bounds corners first";
            case WORLD_MISMATCH -> "the capture box, spawns, and bounds must be in one world";
            case OUTSIDE_CAPTURE_REGION -> "the capture box must contain both spawns and both gameplay bounds corners";
            case TOO_LARGE -> "the capture volume exceeds dynamic-arenas.max-template-volume";
            case CAPTURE_FAILED -> "Paper could not save or verify the structure file; check the server log";
            case SUCCESS -> "unknown";
        };
    }
}
