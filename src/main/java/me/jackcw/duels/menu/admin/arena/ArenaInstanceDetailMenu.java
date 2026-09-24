package me.jackcw.duels.menu.admin.arena;

import me.jackcw.duels.Duels;
import me.jackcw.duels.arena.ArenaBoundsFeedback;
import me.jackcw.duels.arena.ArenaEditManager;
import me.jackcw.duels.arena.ArenaInstance;
import me.jackcw.duels.arena.ArenaInstanceManager;
import me.jackcw.duels.arena.ArenaInstanceMutationResult;
import me.jackcw.duels.arena.ArenaTemplateCaptureResult;
import me.jackcw.duels.arena.ArenaTemplateDefinition;
import me.jackcw.duels.arena.ArenaTemplateManager;
import me.jackcw.duels.arena.DynamicArenaProvisioner;
import me.jackcw.duels.arena.DynamicArenaState;
import me.jackcw.duels.message.Message;
import me.jackcw.jcore.menu.MenuContext;
import me.jackcw.jcore.menu.ConfiguredMenu;
import me.jackcw.jcore.menu.MenuManager;
import me.jackcw.jcore.message.MessageManager;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;

public final class ArenaInstanceDetailMenu
{
    private final MenuManager menus;
    private final ArenaInstanceManager arenaInstanceManager;
    private final ArenaEditManager arenaEditManager;
    private final ArenaTemplateManager templateManager;
    private final DynamicArenaProvisioner provisioner;
    private final MessageManager messageManager;

    public ArenaInstanceDetailMenu(Duels plugin)
    {
        this.menus = plugin.core().menus();
        this.arenaInstanceManager = plugin.getArenaInstanceManager();
        this.arenaEditManager = plugin.getArenaEditManager();
        this.templateManager = plugin.getArenaTemplateManager();
        this.provisioner = plugin.getDynamicArenaProvisioner();
        this.messageManager = plugin.core().messages();
    }

    public void open(Player player, int instanceId)
    {
        ArenaInstance instance = arenaInstanceManager.getInstance(instanceId);

        if (instance == null)
        {
            messageManager.send(player, Message.ARENA_INSTANCE_NOT_FOUND, "id", instanceId);
            player.closeInventory();
            return;
        }

        String menuKey = instance.isProvisioned() ? "arena-generated-detail"
                : instance.isSource() ? "arena-instance-detail" : "arena-static-instance-detail";
        ConfiguredMenu menu = menus.menu(menuKey)
                .placeholders(Map.ofEntries(
                        Map.entry("id", instanceId),
                        Map.entry("arenaId", instance.getArenaId()),
                        Map.entry("spawn1", describe(instance.getSpawn1())),
                        Map.entry("spawn2", describe(instance.getSpawn2())),
                        Map.entry("bounds1", describe(instance.getBoundsCorner1())),
                        Map.entry("bounds2", describe(instance.getBoundsCorner2())),
                        Map.entry("corner1", describe(arenaEditManager.getStructureCorner(player, instanceId, 1))),
                        Map.entry("corner2", describe(arenaEditManager.getStructureCorner(player, instanceId, 2))),
                        Map.entry("origin", instance.getOrigin().name()),
                        Map.entry("health", instance.isProvisioned() ? instance.getDynamicState().name() : instance.isSource() ? "Build source" : "Manual"),
                        Map.entry("spawnsStatus", instance.getSpawn1() != null && instance.getSpawn2() != null ? "&aboth set" : "&cmissing"),
                        Map.entry("boundsStatus", instance.hasBounds() ? "&aset" : "&cnot set")));

        if (instance.isProvisioned() && instance.getDynamicState() == DynamicArenaState.FAILED)
            menu.item("retry", context -> retry(player, instanceId, context));
        if (!instance.isProvisioned())
        {
            menu.item("spawn1", context -> handleSpawnClick(player, instanceId, 1, context))
                    .item("spawn2", context -> handleSpawnClick(player, instanceId, 2, context))
                    .item("bounds1", context -> handleBoundsClick(player, instanceId, 1, context))
                    .item("bounds2", context -> handleBoundsClick(player, instanceId, 2, context));
            if (instance.isSource())
                menu.item("structure1", context -> handleStructureClick(player, instanceId, 1, context))
                        .item("structure2", context -> handleStructureClick(player, instanceId, 2, context))
                        .item("capture", context -> capture(player, instanceId, context));
            menu.item("edit-mode", context ->
                {
                    ArenaInstance current = requireInstance(player, instanceId, context);

                    if (current == null)
                        return;

                    if (current.isProvisioned())
                    {
                        messageManager.send(player, Message.ARENA_OPERATION_FAILED, "reason", "provisioned copies are generated from the captured template; edit a manual source instead");
                        context.reopen();
                        return;
                    }

                    if (!arenaEditManager.start(player, current))
                    {
                        messageManager.send(player, Message.ARENA_EDIT_WHILE_IN_MATCH);
                        context.reopen();
                        return;
                    }

                    player.closeInventory();
                });
        }
        if (instance.isProvisioned())
            menu.item("setup-status", instance.getDynamicState() == DynamicArenaState.READY, context -> {});
        else
            menu.item("setup-status", context -> {});

        menu.item("delete", context ->
                {
                    ArenaInstance current = requireInstance(player, instanceId, context);

                    if (current == null)
                        return;

                    context.openChild(() -> menus.confirm()
                            .title("&8Delete Instance?")
                            .description(List.of(
                                    "&cDelete instance #" + current.getId() + "?",
                                    "&cThis cannot be undone."
                            ))
                            .onConfirm(confirmContext ->
                            {
                                ArenaInstance currentInstance = arenaInstanceManager.getInstance(instanceId);
                                if (currentInstance != null && currentInstance.isProvisioned())
                                {
                                    if (arenaInstanceManager.isActive(instanceId) || currentInstance.getDynamicState() == DynamicArenaState.RETIRING)
                                    {
                                        messageManager.send(player, Message.ARENA_INSTANCE_IN_USE, "id", instanceId, "arenaId", currentInstance.getArenaId());
                                        confirmContext.back();
                                        return;
                                    }
                                    provisioner.retire(currentInstance).whenComplete((ignored, failure) ->
                                    {
                                        if (failure == null)
                                            messageManager.send(player, Message.ARENA_INSTANCE_DELETED, "id", instanceId, "arenaId", currentInstance.getArenaId());
                                        else
                                            messageManager.send(player, Message.ARENA_OPERATION_FAILED, "reason", "retirement cleanup failed; check the server log");
                                    });
                                    if (!confirmContext.back(2))
                                        player.closeInventory();
                                    return;
                                }

                                ArenaInstanceMutationResult result = arenaInstanceManager.deleteInstance(instanceId);

                                switch (result.status())
                                {
                                    case NOT_FOUND ->
                                    {
                                        messageManager.send(player, Message.ARENA_INSTANCE_NOT_FOUND, "id", instanceId);
                                        player.closeInventory();
                                    }
                                    case IN_USE ->
                                    {
                                        if (currentInstance != null && currentInstance.isSource())
                                            messageManager.send(player, Message.ARENA_OPERATION_FAILED,
                                                    "reason", "this source is still linked to a captured template or generated copies; clear/retire them first");
                                        else
                                            messageManager.send(player, Message.ARENA_INSTANCE_IN_USE, "id", instanceId,
                                                    "arenaId", currentInstance != null ? currentInstance.getArenaId() : -1);
                                        confirmContext.back();
                                    }
                                    case SUCCESS ->
                                    {
                                        messageManager.send(player, Message.ARENA_INSTANCE_DELETED, "id", instanceId,
                                                "arenaId", currentInstance != null ? currentInstance.getArenaId() : -1);

                                        if (!confirmContext.back(2))
                                            player.closeInventory();
                                    }
                                }
                            })
                            .open(player));
                })
                .back()
                .open(player);
    }

    private boolean editableManual(Player player, int instanceId, MenuContext context)
    {
        ArenaInstance instance = requireInstance(player, instanceId, context);
        if (instance == null)
            return false;
        if (!instance.isProvisioned())
            return true;
        messageManager.send(player, Message.ARENA_OPERATION_FAILED, "reason", "provisioned copies are generated; change the manual source and recapture instead");
        context.reopen();
        return false;
    }

    private void handleStructureClick(Player player, int instanceId, int corner, MenuContext context)
    {
        if (!editableSource(player, instanceId, context))
            return;
        if (context.clickType().isRightClick())
        {
            Location location = arenaEditManager.getStructureCorner(player, instanceId, corner);
            if (location == null)
                messageManager.send(player, Message.ARENA_STRUCTURE_CORNER_NOT_SET, "corner", corner);
            else
                player.teleport(location);
            return;
        }
        if (!context.clickType().isLeftClick())
            return;
        arenaEditManager.setStructureCorner(player, instanceId, corner, player.getLocation());
        messageManager.send(player, Message.ARENA_STRUCTURE_CORNER_SET, "corner", corner);
        context.reopen();
    }

    private void capture(Player player, int instanceId, MenuContext context)
    {
        if (!editableSource(player, instanceId, context))
            return;
        ArenaTemplateCaptureResult result = templateManager.capture(instanceId,
                arenaEditManager.getStructureCorner(player, instanceId, 1),
                arenaEditManager.getStructureCorner(player, instanceId, 2));
        if (result.status() == ArenaTemplateCaptureResult.Status.SUCCESS)
        {
            ArenaTemplateDefinition template = result.template();
            ArenaInstance instance = arenaInstanceManager.getInstance(instanceId);
            messageManager.send(player, Message.ARENA_TEMPLATE_CAPTURED,
                    "id", instance.getArenaId(), "revision", template.revision(),
                    "sizeX", template.size().x(), "sizeY", template.size().y(), "sizeZ", template.size().z());
        }
        else
            messageManager.send(player, Message.ARENA_TEMPLATE_CAPTURE_FAILED,
                    "reason", ArenaTemplateCaptureResult.describeFailure(result.status()));
        context.reopen();
    }

    private void retry(Player player, int instanceId, MenuContext context)
    {
        ArenaInstance instance = requireInstance(player, instanceId, context);
        if (instance == null)
            return;
        if (!instance.isProvisioned() || instance.getDynamicState() != DynamicArenaState.FAILED)
        {
            messageManager.send(player, Message.ARENA_OPERATION_FAILED, "reason", "only FAILED provisioned copies can be retried");
            return;
        }
        provisioner.rebuild(instance).whenComplete((result, failure) ->
        {
            if (failure == null && result.status() == me.jackcw.duels.arena.DynamicArenaProvisionResult.Status.SUCCESS)
                messageManager.send(player, Message.ARENA_INSTANCE_REBUILT, "id", instanceId);
            else
                messageManager.send(player, Message.ARENA_OPERATION_FAILED, "reason", "retry failed; check the server log");
        });
        context.reopen();
    }

    private boolean editableSource(Player player, int instanceId, MenuContext context)
    {
        ArenaInstance instance = requireInstance(player, instanceId, context);
        if (instance == null)
            return false;
        if (instance.isSource())
            return true;
        messageManager.send(player, Message.ARENA_OPERATION_FAILED,
                "reason", "only a DYNAMIC arena's build source has structure corners and capture tools");
        context.reopen();
        return false;
    }

    private ArenaInstance requireInstance(Player player, int instanceId, MenuContext context)
    {
        ArenaInstance instance = arenaInstanceManager.getInstance(instanceId);

        if (instance == null)
        {
            messageManager.send(player, Message.ARENA_INSTANCE_NOT_FOUND, "id", instanceId);
            context.back();
        }

        return instance;
    }

    private void handleSpawnClick(Player player, int instanceId, int spawn, MenuContext context)
    {
        if (!editableManual(player, instanceId, context))
            return;
        if (context.clickType().isRightClick())
        {
            ArenaInstance current = requireInstance(player, instanceId, context);

            if (current == null)
                return;

            Location location = spawn == 1 ? current.getSpawn1() : current.getSpawn2();

            if (location == null)
                messageManager.send(player, Message.ARENA_SPAWN_NOT_SET, "spawn", spawn, "id", instanceId);
            else
                player.teleport(location);

            return;
        }

        if (!context.clickType().isLeftClick())
            return;

        ArenaInstance existing = arenaInstanceManager.getInstance(instanceId);
        ArenaInstanceMutationResult result = arenaInstanceManager.setSpawn(instanceId, spawn, player.getLocation());

        switch (result.status())
        {
            case NOT_FOUND -> messageManager.send(player, Message.ARENA_INSTANCE_NOT_FOUND, "id", instanceId);
            case IN_USE -> messageManager.send(player, Message.ARENA_INSTANCE_IN_USE, "id", instanceId,
                    "arenaId", existing != null ? existing.getArenaId() : -1);
            case SUCCESS -> messageManager.send(player, Message.ARENA_SPAWN_SET, "spawn", spawn, "id", instanceId);
        }

        context.reopen();
    }

    private void handleBoundsClick(Player player, int instanceId, int corner, MenuContext context)
    {
        if (!editableManual(player, instanceId, context))
            return;
        if (context.clickType().isRightClick())
        {
            ArenaInstance current = requireInstance(player, instanceId, context);

            if (current == null)
                return;

            Location location = corner == 1 ? current.getBoundsCorner1() : current.getBoundsCorner2();

            if (location == null)
                messageManager.send(player, Message.ARENA_BOUNDS_NOT_SET, "corner", corner, "id", instanceId);
            else
                player.teleport(location);

            return;
        }

        if (!context.clickType().isLeftClick())
            return;

        ArenaInstance existing = arenaInstanceManager.getInstance(instanceId);
        ArenaInstanceMutationResult result = arenaInstanceManager.setBoundsCorner(instanceId, corner, player.getLocation());

        switch (result.status())
        {
            case NOT_FOUND -> messageManager.send(player, Message.ARENA_INSTANCE_NOT_FOUND, "id", instanceId);
            case IN_USE -> messageManager.send(player, Message.ARENA_INSTANCE_IN_USE, "id", instanceId,
                    "arenaId", existing != null ? existing.getArenaId() : -1);
            case SUCCESS -> ArenaBoundsFeedback.sendCornerSet(messageManager, player, result.instance(), corner);
        }

        context.reopen();
    }

    private String describe(Location location)
    {
        if (location == null)
            return "not set";

        return location.getBlockX() + ", " + location.getBlockY() + ", " + location.getBlockZ();
    }
}
