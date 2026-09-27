package me.jackcw.duels.menu.admin.arena;

import me.jackcw.duels.Duels;
import me.jackcw.duels.arena.Arena;
import me.jackcw.duels.arena.ArenaInstance;
import me.jackcw.duels.arena.ArenaInstanceManager;
import me.jackcw.duels.arena.ArenaManager;
import me.jackcw.duels.arena.ArenaMutationResult;
import me.jackcw.duels.arena.ArenaProvisioningMode;
import me.jackcw.duels.arena.ArenaTemplateDefinition;
import me.jackcw.duels.arena.ArenaTemplateManager;
import me.jackcw.duels.arena.BoundaryMode;
import me.jackcw.duels.arena.DynamicArenaProvisioner;
import me.jackcw.duels.arena.DynamicArenaSlotManager;
import me.jackcw.duels.message.Message;
import me.jackcw.jcore.menu.MenuContext;
import me.jackcw.jcore.menu.ConfiguredMenu;
import me.jackcw.jcore.menu.MenuManager;
import me.jackcw.jcore.message.MessageManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public final class ArenaDetailMenu
{
    private final MenuManager menus;
    private final ArenaManager arenaManager;
    private final ArenaInstanceManager arenaInstanceManager;
    private final ArenaTemplateManager templateManager;
    private final DynamicArenaProvisioner provisioner;
    private final DynamicArenaSlotManager dynamicArenaSlotManager;
    private final MessageManager messageManager;
    private final ArenaKitMenu arenaKitMenu;
    private final ArenaInstanceListMenu arenaInstanceListMenu;
    private final ArenaInstanceDetailMenu arenaInstanceDetailMenu;

    public ArenaDetailMenu(Duels plugin, ArenaKitMenu arenaKitMenu, ArenaInstanceListMenu arenaInstanceListMenu, ArenaInstanceDetailMenu arenaInstanceDetailMenu)
    {
        this.menus = plugin.core().menus();
        this.arenaManager = plugin.getArenaManager();
        this.arenaInstanceManager = plugin.getArenaInstanceManager();
        this.templateManager = plugin.getArenaTemplateManager();
        this.provisioner = plugin.getDynamicArenaProvisioner();
        this.dynamicArenaSlotManager = plugin.getDynamicArenaSlotManager();
        this.messageManager = plugin.core().messages();
        this.arenaKitMenu = arenaKitMenu;
        this.arenaInstanceListMenu = arenaInstanceListMenu;
        this.arenaInstanceDetailMenu = arenaInstanceDetailMenu;
    }

    public void open(Player player, Arena arena)
    {
        open(player, arena.getId());
    }

    public void open(Player player, int arenaId)
    {
        Arena arena = arenaManager.getArena(arenaId);

        if (arena == null)
        {
            messageManager.send(player, Message.ARENA_NOT_FOUND, "id", arenaId);
            player.closeInventory();
            return;
        }

        ConfiguredMenu menu = menus.menu(arena.getProvisioningMode() == ArenaProvisioningMode.STATIC
                        ? "arena-detail" : "arena-dynamic-detail")
                .placeholders(Map.of("arena-name", arena.getName()))
                .item("toggle-available", !arena.isEnabled(), context ->
                {
                    ArenaMutationResult result = arenaManager.toggleEnabled(arenaId);

                    switch (result.status())
                    {
                        case NOT_FOUND -> messageManager.send(player, Message.ARENA_NOT_FOUND, "id", arenaId);
                        case IN_USE -> messageManager.send(player, Message.ARENA_IN_USE, "id", arenaId);
                        case SUCCESS -> messageManager.send(player, result.arena().isEnabled() ? Message.ARENA_ENABLED : Message.ARENA_DISABLED, "id", arenaId);
                    }

                    context.reopen();
                })
                .item("rename", context ->
                {
                    context.requestInput(
                            prompt(Message.ARENA_RENAME_PROMPT),
                            name ->
                            {
                                ArenaMutationResult result = arenaManager.rename(arenaId, name);

                                switch (result.status())
                                {
                                    case NOT_FOUND -> messageManager.send(player, Message.ARENA_NOT_FOUND, "id", arenaId);
                                    case IN_USE -> messageManager.send(player, Message.ARENA_IN_USE, "id", arenaId);
                                    case SUCCESS -> messageManager.send(player, Message.ARENA_RENAMED, "id", arenaId, "name", name);
                                }

                                context.reopen();
                            },
                            () ->
                            {
                                messageManager.send(player, Message.MENU_INPUT_CANCELLED);
                                context.reopen();
                            });
                })
                .item("delete", context ->
                {
                    Arena current = requireArena(player, arenaId, context);

                    if (current == null)
                        return;

                    if (current.getProvisioningMode() == ArenaProvisioningMode.DYNAMIC)
                    {
                        openCascadeDeleteConfirm(player, arenaId, current, context);
                        return;
                    }

                    if (isInUse(player, arenaId, context))
                        return;

                    context.openChild(() -> menus.confirm()
                            .title("&8Delete Arena?")
                            .description(List.of(
                                    "&cDelete arena '" + current.getName() + "'?",
                                    "&cThis cannot be undone."
                            ))
                            .onConfirm(confirmContext ->
                            {
                                ArenaMutationResult result = arenaManager.deleteArena(arenaId);

                                switch (result.status())
                                {
                                    case NOT_FOUND ->
                                    {
                                        messageManager.send(player, Message.ARENA_NOT_FOUND, "id", arenaId);
                                        player.closeInventory();
                                    }
                                    case IN_USE ->
                                    {
                                        messageManager.send(player, Message.ARENA_IN_USE, "id", arenaId);
                                        confirmContext.back();
                                    }
                                    case SUCCESS ->
                                    {
                                        messageManager.send(player, Message.ARENA_DELETED, "id", arenaId);

                                        if (!confirmContext.back(2))
                                            player.closeInventory();
                                    }
                                }
                            })
                            .open(player));
                })
                .item("kits", context -> context.openChild(() -> arenaKitMenu.open(player, arenaId)))
                .item("boundary", Map.of(
                        "mode", arena.getBoundaryMode().name(),
                        "grace", arena.getGraceSeconds()
                ), context -> handleBoundaryClick(player, arenaId, context));

        if (arena.getProvisioningMode() == ArenaProvisioningMode.STATIC)
        {
            menu.item("instances", context -> context.openChild(() -> arenaInstanceListMenu.open(player, arenaId)))
                    .item("create-instance", context ->
                    {
                        ArenaInstance instance = arenaInstanceManager.createInstance(arenaId);
                        messageManager.send(player, Message.ARENA_INSTANCE_CREATED, "id", instance.getId(), "arenaId", arenaId);
                        context.openChild(() -> arenaInstanceDetailMenu.open(player, instance.getId()));
                    });

            List<ArenaInstance> copies = arenaInstanceManager.getInstancesForArena(arenaId);
            if (copies.size() == 1 && !copies.getFirst().isProvisioned())
                menu.item("convert", context -> convert(player, arenaId, context));
        }
        else
        {
            DynamicArenaSlotManager.Capacity capacity = dynamicArenaSlotManager.capacity();
            ArenaInstance source = arenaInstanceManager.getSource(arenaId);
            if (source == null)
                menu.item("source", context -> createSource(player, arenaId, context));
            else
                menu.item("source", context -> context.openChild(() -> arenaInstanceDetailMenu.open(player, source.getId())));
            menu.item("generated-copies", Map.of(
                            "occupied", capacity.occupied(),
                            "reserved", capacity.reserved(),
                            "available", capacity.available(),
                            "maximum", capacity.maximum()
                    ), context -> context.openChild(() -> arenaInstanceListMenu.open(player, arenaId)))
                    .item("template", Map.of(
                            "status", arena.getTemplateDefinition() == null ? "&cNot captured" : "&aRevision " + arena.getTemplateDefinition().revision(),
                            "size", templateSize(arena.getTemplateDefinition())
                    ), context -> showTemplate(player, arenaId, context));
        }
        menu.back().open(player);
    }

    private String templateSize(ArenaTemplateDefinition template)
    {
        return template == null ? "-" : template.size().x() + "x" + template.size().y() + "x" + template.size().z();
    }

    private void createSource(Player player, int arenaId, MenuContext context)
    {
        try
        {
            ArenaInstance source = arenaInstanceManager.createSource(arenaId);
            messageManager.send(player, Message.ARENA_INSTANCE_CREATED, "id", source.getId(), "arenaId", arenaId);
            context.openChild(() -> arenaInstanceDetailMenu.open(player, source.getId()));
        }
        catch (IllegalStateException e)
        {
            messageManager.send(player, Message.ARENA_OPERATION_FAILED, "reason", e.getMessage());
            context.reopen();
        }
    }

    private void convert(Player player, int arenaId, MenuContext context)
    {
        List<ArenaInstance> copies = arenaInstanceManager.getInstancesForArena(arenaId);
        if (copies.size() != 1 || copies.getFirst().isProvisioned())
        {
            messageManager.send(player, Message.ARENA_OPERATION_FAILED,
                    "reason", "conversion needs exactly one idle hand-built copy; other static arenas should be recreated as DYNAMIC");
            context.reopen();
            return;
        }
        ArenaInstance source = copies.getFirst();
        context.openChild(() -> menus.confirm()
                .title("&8Convert to Dynamic?")
                .description(List.of(
                        "&eCopy #" + source.getId() + " becomes the build source.",
                        "&eIt will stop hosting matches.",
                        "&7Spawns, bounds and blocks are preserved.",
                        "&7Capture it before dynamic matches can start."
                ))
                .onConfirm(confirmContext ->
                {
                    if (arenaInstanceManager.convertToDynamicSource(arenaId, source.getId()))
                    {
                        messageManager.send(player, Message.ARENA_PROVISIONING_SET, "id", arenaId, "mode", "DYNAMIC (source #" + source.getId() + ")");
                        confirmContext.back();
                    }
                    else
                    {
                        messageManager.send(player, Message.ARENA_OPERATION_FAILED,
                                "reason", "the copy changed or is in use; conversion did not occur");
                        confirmContext.back();
                    }
                }).open(player));
    }

    private void showTemplate(Player player, int arenaId, MenuContext context)
    {
        Arena arena = requireArena(player, arenaId, context);
        if (arena == null)
            return;

        ArenaTemplateDefinition template = arena.getTemplateDefinition();
        if (context.clickType().isRightClick())
        {
            if (template == null)
            {
                messageManager.send(player, Message.ARENA_OPERATION_FAILED, "reason", "no captured template exists");
                context.reopen();
                return;
            }
            context.openChild(() -> menus.confirm()
                    .title("&8Clear Template?")
                    .description(List.of("&cDelete arena #" + arenaId + "'s captured template?", "&7Retire generated copies first."))
                    .onConfirm(confirmContext ->
                    {
                        if (templateManager.clear(arenaId))
                            messageManager.send(player, Message.ARENA_TEMPLATE_CLEARED, "id", arenaId);
                        else
                            messageManager.send(player, Message.ARENA_OPERATION_FAILED, "reason", "template is in use; retire generated copies first");
                        confirmContext.back();
                    }).open(player));
            return;
        }

        messageManager.send(player, Message.ARENA_TEMPLATE_INFO,
                "id", arenaId, "status", arena.getTemplateStatus().name(),
                "revision", template == null ? "-" : template.revision(),
                "size", templateSize(template));
    }

    /**
     * Boundary mode and grace period are one concept to an admin but two
     * values, so they share a single item: left click steps through the modes,
     * right click asks for the grace period in chat. This mirrors the
     * left-to-change / right-for-the-other-action split the instance spawn and
     * bounds items already use.
     */
    private void handleBoundaryClick(Player player, int arenaId, MenuContext context)
    {
        Arena arena = requireArena(player, arenaId, context);

        if (arena == null)
            return;

        if (context.clickType().isRightClick())
        {
            context.requestInput(
                    prompt(Message.ARENA_BOUNDARY_GRACE_PROMPT),
                    input ->
                    {
                        int graceSeconds;

                        try
                        {
                            graceSeconds = Integer.parseInt(input.trim());
                        }
                        catch (NumberFormatException ignored)
                        {
                            messageManager.send(player, Message.MENU_INVALID_NUMBER);
                            context.reopen();
                            return;
                        }

                        applyBoundary(player, arenaId, null, graceSeconds, context);
                    },
                    () ->
                    {
                        messageManager.send(player, Message.MENU_INPUT_CANCELLED);
                        context.reopen();
                    });

            return;
        }

        if (!context.clickType().isLeftClick())
            return;

        BoundaryMode[] modes = BoundaryMode.values();
        applyBoundary(player, arenaId, modes[(arena.getBoundaryMode().ordinal() + 1) % modes.length], null, context);
    }

    /**
     * A null {@code mode} or {@code graceSeconds} means "leave that one alone".
     * The current value is re-read here rather than captured at click time
     * because the chat prompt lets another admin change the arena in between.
     */
    private void applyBoundary(Player player, int arenaId, BoundaryMode mode, Integer graceSeconds, MenuContext context)
    {
        Arena arena = requireArena(player, arenaId, context);

        if (arena == null)
            return;

        BoundaryMode newMode = mode != null ? mode : arena.getBoundaryMode();
        int newGrace = graceSeconds != null ? graceSeconds : arena.getGraceSeconds();

        ArenaMutationResult result = arenaManager.setBoundaryMode(arenaId, newMode, newGrace);

        switch (result.status())
        {
            case NOT_FOUND -> messageManager.send(player, Message.ARENA_NOT_FOUND, "id", arenaId);
            case IN_USE -> messageManager.send(player, Message.ARENA_IN_USE, "id", arenaId);
            case SUCCESS -> messageManager.send(
                    player,
                    Message.ARENA_BOUNDARY_SET,
                    "id", arenaId,
                    "mode", newMode.name(),
                    "grace", newGrace
            );
        }

        context.reopen();
    }

    private Arena requireArena(Player player, int arenaId, MenuContext context)
    {
        Arena arena = arenaManager.getArena(arenaId);

        if (arena == null)
        {
            messageManager.send(player, Message.ARENA_NOT_FOUND, "id", arenaId);
            context.back();
        }

        return arena;
    }

    private boolean isInUse(Player player, int arenaId, MenuContext context)
    {
        if (!arenaManager.hasInstances(arenaId))
            return false;

        messageManager.send(player, Message.ARENA_IN_USE, "id", arenaId);
        context.reopen();
        return true;
    }

    /**
     * DYNAMIC arenas differ from STATIC ones: their generated copies are
     * disposable slots, not hand-built structures, so it is safe to retire
     * every copy, delete the source and remove the arena in one confirmed
     * step instead of forcing an admin to clear each copy manually first.
     */
    private boolean anyInstanceActive(int arenaId)
    {
        for (ArenaInstance instance : arenaInstanceManager.getInstancesForArena(arenaId))
            if (arenaInstanceManager.isActive(instance.getId()))
                return true;

        return false;
    }

    private void openCascadeDeleteConfirm(Player player, int arenaId, Arena current, MenuContext context)
    {
        if (anyInstanceActive(arenaId))
        {
            messageManager.send(player, Message.ARENA_IN_USE, "id", arenaId);
            context.reopen();
            return;
        }

        long copyCount = arenaInstanceManager.getInstancesForArena(arenaId).stream()
                .filter(ArenaInstance::isProvisioned)
                .count();

        context.openChild(() -> menus.confirm()
                .title("&8Delete Dynamic Arena?")
                .description(List.of(
                        "&cDelete arena '" + current.getName() + "'?",
                        "&c" + copyCount + " generated " + (copyCount == 1 ? "copy" : "copies") + " will be retired",
                        "&cand cleared, then the build source and arena deleted.",
                        "&cThis cannot be undone."
                ))
                .onConfirm(confirmContext -> runCascadeDelete(player, arenaId, confirmContext))
                .open(player));
    }

    private void runCascadeDelete(Player player, int arenaId, MenuContext confirmContext)
    {
        if (anyInstanceActive(arenaId))
        {
            messageManager.send(player, Message.ARENA_IN_USE, "id", arenaId);
            confirmContext.back();
            return;
        }

        List<ArenaInstance> copies = arenaInstanceManager.getInstancesForArena(arenaId).stream()
                .filter(ArenaInstance::isProvisioned)
                .toList();

        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (ArenaInstance copy : copies)
            chain = chain.thenCompose(ignored -> provisioner.retire(copy));

        chain.whenComplete((ignored, failure) ->
        {
            if (failure != null)
            {
                messageManager.send(player, Message.ARENA_OPERATION_FAILED, "reason", "retiring generated copies failed; check the server log");
                return;
            }

            templateManager.clear(arenaId);

            ArenaInstance source = arenaInstanceManager.getSource(arenaId);
            if (source != null)
                arenaInstanceManager.deleteInstance(source.getId());

            ArenaMutationResult result = arenaManager.deleteArena(arenaId);

            switch (result.status())
            {
                case NOT_FOUND -> messageManager.send(player, Message.ARENA_NOT_FOUND, "id", arenaId);
                case IN_USE -> messageManager.send(player, Message.ARENA_OPERATION_FAILED,
                        "reason", "the arena still has registered instances after cascading delete; check the server log");
                case SUCCESS -> messageManager.send(player, Message.ARENA_DELETED, "id", arenaId);
            }
        });

        if (!confirmContext.back(2))
            player.closeInventory();
    }

    private Component prompt(Message key)
    {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(messageManager.format(key));
    }
}
