package me.jackcw.duels.commands;

import me.jackcw.duels.Duels;
import me.jackcw.duels.arena.Arena;
import me.jackcw.duels.arena.ArenaEditManager;
import me.jackcw.duels.arena.ArenaEditSession;
import me.jackcw.duels.arena.ArenaInstance;
import me.jackcw.duels.arena.ArenaInstanceManager;
import me.jackcw.duels.arena.ArenaInstanceMutationResult;
import me.jackcw.duels.arena.ArenaManager;
import me.jackcw.duels.arena.ArenaMutationResult;
import me.jackcw.duels.arena.ArenaProvisioningMode;
import me.jackcw.duels.arena.ArenaTemplateCaptureResult;
import me.jackcw.duels.arena.ArenaTemplateDefinition;
import me.jackcw.duels.arena.ArenaTemplateManager;
import me.jackcw.duels.arena.ArenaTemplateStatus;
import me.jackcw.duels.arena.BoundaryMode;
import me.jackcw.duels.kit.Kit;
import me.jackcw.duels.kit.KitManager;
import me.jackcw.duels.menu.admin.AdminMainMenu;
import me.jackcw.duels.menu.admin.arena.ArenaMainMenu;
import me.jackcw.duels.menu.admin.arena.ArenaListMenu;
import me.jackcw.duels.menu.admin.arena.ArenaDetailMenu;
import me.jackcw.duels.menu.admin.kit.KitEditMenu;
import me.jackcw.duels.menu.admin.kit.KitMainMenu;
import me.jackcw.duels.menu.admin.kit.KitListMenu;
import me.jackcw.duels.menu.admin.kit.KitDetailMenu;
import me.jackcw.duels.message.Message;
import me.jackcw.jcore.command.ArgumentTypes;
import me.jackcw.jcore.command.CommandBuilder;
import me.jackcw.jcore.command.CommandContext;
import me.jackcw.jcore.command.CommandNode;
import me.jackcw.jcore.menu.MenuManager;
import me.jackcw.jcore.message.MessageManager;
import me.jackcw.jcore.message.CoreMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;

public final class DuelsCommand
{
    private final ArenaManager arenaManager;
    private final ArenaInstanceManager arenaInstanceManager;
    private final ArenaEditManager arenaEditManager;
    private final ArenaTemplateManager arenaTemplateManager;
    private final KitManager kitManager;
    private final MessageManager messageManager;
    private final MenuManager menus;
    private final AdminMainMenu adminMenu;
    private final ArenaMainMenu arenaMenu;
    private final ArenaListMenu arenaListMenu;
    private final ArenaDetailMenu arenaDetailMenu;
    private final KitMainMenu kitMenu;
    private final KitListMenu kitListMenu;
    private final KitDetailMenu kitDetailMenu;
    private final KitEditMenu kitEditMenu;

    public DuelsCommand(Duels plugin)
    {
        this.arenaManager = plugin.getArenaManager();
        this.arenaInstanceManager = plugin.getArenaInstanceManager();
        this.arenaEditManager = plugin.getArenaEditManager();
        this.arenaTemplateManager = plugin.getArenaTemplateManager();
        this.kitManager = plugin.getKitManager();
        this.messageManager = plugin.core().messages();
        this.menus = plugin.core().menus();
        this.adminMenu = plugin.getAdminMainMenu();
        this.arenaMenu = plugin.getArenaMainMenu();
        this.arenaListMenu = plugin.getArenaListMenu();
        this.arenaDetailMenu = plugin.getArenaDetailMenu();
        this.kitMenu = plugin.getKitMainMenu();
        this.kitListMenu = plugin.getKitListMenu();
        this.kitDetailMenu = plugin.getKitDetailMenu();
        this.kitEditMenu = plugin.getKitEditMenu();
    }

    public CommandNode build()
    {
        return CommandBuilder.command("duels")
                .description("Main entry point for the duels plugin")
                .usage("/duels")
                .permission("duels.admin.help")
                .alias("ds")
                .executes(this::openMenuOrHelp)
                .child(
                        CommandBuilder.command("arena")
                                .description("Manage arenas")
                                .usage("/duels arena [id|create|delete|list|instance]")
                                .permission("duels.admin.arena")
                                .alias("a")
                                .optionalArgument("id", ArgumentTypes.integer())
                                .executes(this::openArenaMenuOrDetail)
                                .child(
                                        CommandBuilder.command("create")
                                                .description("Create an arena")
                                                .usage("/duels arena create <name>")
                                                .permission("duels.admin.arena.create")
                                                .alias("c")
                                                .argument("name", ArgumentTypes.string())
                                                .executes(this::createArena))
                                .child(
                                        CommandBuilder.command("delete")
                                                .description("Delete an arena")
                                                .usage("/duels arena delete <id>")
                                                .permission("duels.admin.arena.delete")
                                                .alias("d")
                                                .argument("id", ArgumentTypes.integer())
                                                .executes(this::deleteArena))
                                .child(
                                        CommandBuilder.command("list")
                                                .description("List all arenas")
                                                .usage("/duels arena list")
                                                .permission("duels.admin.arena.list")
                                                .alias("l")
                                                .executes(this::listArenas))
                                .child(
                                        CommandBuilder.command("menu")
                                                .description("Open the arena management menu")
                                                .usage("/duels arena menu")
                                                .permission("duels.admin.arena.menu")
                                                .alias("gui")
                                                .playerOnly()
                                                .executes(this::openArenaMenuOrDetail))
                                .child(
                                        CommandBuilder.command("rename")
                                                .description("Rename an arena")
                                                .usage("/duels arena rename <id> <name>")
                                                .permission("duels.admin.arena.rename")
                                                .argument("id", ArgumentTypes.integer())
                                                .argument("name", ArgumentTypes.string())
                                                .executes(this::renameArena))
                                .child(
                                        CommandBuilder.command("toggle")
                                                .description("Toggle an arena enabled/disabled")
                                                .usage("/duels arena toggle <id>")
                                                .permission("duels.admin.arena.toggle")
                                                .argument("id", ArgumentTypes.integer())
                                                .executes(this::toggleArena))
                                .child(
                                        CommandBuilder.command("boundary")
                                                .description("Set the out-of-bounds behaviour for an arena")
                                                .usage("/duels arena boundary <id> <mode> [graceSeconds]")
                                                .permission("duels.admin.arena.boundary")
                                                .argument("id", ArgumentTypes.integer())
                                                .argument("mode", ArgumentTypes.enumType(BoundaryMode.class))
                                                .optionalArgument("graceSeconds", ArgumentTypes.integer())
                                                .executes(this::setBoundaryMode))
                                .child(
                                        CommandBuilder.command("provisioning")
                                                .description("Set whether an arena may provision copies on demand")
                                                .usage("/duels arena provisioning <id> <STATIC|DYNAMIC>")
                                                .permission("duels.admin.arena.provisioning")
                                                .argument("id", ArgumentTypes.integer())
                                                .argument("mode", ArgumentTypes.enumType(ArenaProvisioningMode.class))
                                                .executes(this::setProvisioningMode))
                                .child(
                                        CommandBuilder.command("template")
                                                .description("Capture and inspect an arena's dynamic template")
                                                .usage("/duels arena template [capture|info|clear]")
                                                .permission("duels.admin.arena.template")
                                                .child(
                                                        CommandBuilder.command("capture")
                                                                .description("Capture from the current edit session's structure corners")
                                                                .usage("/duels arena template capture <instanceId>")
                                                                .permission("duels.admin.arena.template.capture")
                                                                .playerOnly()
                                                                .argument("instanceId", ArgumentTypes.integer())
                                                                .executes(this::captureTemplate))
                                                .child(
                                                        CommandBuilder.command("info")
                                                                .description("Show an arena's template status")
                                                                .usage("/duels arena template info <arenaId>")
                                                                .permission("duels.admin.arena.template.info")
                                                                .argument("arenaId", ArgumentTypes.integer())
                                                                .executes(this::templateInfo))
                                                .child(
                                                        CommandBuilder.command("clear")
                                                                .description("Delete an unused captured template")
                                                                .usage("/duels arena template clear <arenaId> confirm")
                                                                .permission("duels.admin.arena.template.clear")
                                                                .argument("arenaId", ArgumentTypes.integer())
                                                                .argument("confirmation", ArgumentTypes.string())
                                                                .executes(this::clearTemplate)))
                                .child(
                                        CommandBuilder.command("allowkit")
                                                .description("Toggle whether a kit is allowed in an arena")
                                                .usage("/duels arena allowkit <id> <kitId>")
                                                .permission("duels.admin.arena.allowkit")
                                                .argument("id", ArgumentTypes.integer())
                                                .argument("kitId", ArgumentTypes.integer())
                                                .executes(this::toggleArenaKit))
                                .child(
                                        CommandBuilder.command("instance")
                                                .description("Manage an arena's registered instances")
                                                .usage("/duels arena instance [create|delete|list|setspawn|bounds|editmode]")
                                                .permission("duels.admin.arena.instance")
                                                .alias("i")
                                                .child(
                                                        CommandBuilder.command("create")
                                                                .description("Register a new physical instance of an arena")
                                                                .usage("/duels arena instance create <arenaId>")
                                                                .permission("duels.admin.arena.instance.create")
                                                                .argument("arenaId", ArgumentTypes.integer())
                                                                .executes(this::createArenaInstance))
                                                .child(
                                                        CommandBuilder.command("delete")
                                                                .description("Delete an arena instance")
                                                                .usage("/duels arena instance delete <instanceId>")
                                                                .permission("duels.admin.arena.instance.delete")
                                                                .argument("instanceId", ArgumentTypes.integer())
                                                                .executes(this::deleteArenaInstance))
                                                .child(
                                                        CommandBuilder.command("list")
                                                                .description("List an arena's registered instances")
                                                                .usage("/duels arena instance list <arenaId>")
                                                                .permission("duels.admin.arena.instance.list")
                                                                .argument("arenaId", ArgumentTypes.integer())
                                                                .executes(this::listArenaInstances))
                                                .child(
                                                        CommandBuilder.command("setspawn")
                                                                .description("Set a spawn point for an arena instance")
                                                                .usage("/duels arena instance setspawn <instanceId> <1|2>")
                                                                .permission("duels.admin.arena.instance.setspawn")
                                                                .alias("s")
                                                                .playerOnly()
                                                                .argument("instanceId", ArgumentTypes.integer())
                                                                .argument("spawn", ArgumentTypes.integer())
                                                                .executes(this::setInstanceSpawn))
                                                .child(
                                                        CommandBuilder.command("bounds")
                                                                .description("Set a bounds corner for an arena instance")
                                                                .usage("/duels arena instance bounds <instanceId> <1|2>")
                                                                .permission("duels.admin.arena.instance.bounds")
                                                                .playerOnly()
                                                                .argument("instanceId", ArgumentTypes.integer())
                                                                .argument("corner", ArgumentTypes.integer())
                                                                .executes(this::setInstanceBoundsCorner))
                                                .child(
                                                        CommandBuilder.command("editmode")
                                                                .description("Enter arena edit mode for an arena instance")
                                                                .usage("/duels arena instance editmode <instanceId>")
                                                                .permission("duels.admin.arena.instance.editmode")
                                                                .playerOnly()
                                                                .argument("instanceId", ArgumentTypes.integer())
                                                                .executes(this::enterInstanceEditMode))))
                .child(
                        CommandBuilder.command("kit")
                                .description("Manage kits")
                                .usage("/duels kit [id|create|delete|list]")
                                .permission("duels.admin.kit")
                                .optionalArgument("id", ArgumentTypes.integer())
                                .executes(this::openKitMenuOrDetail)
                                .child(
                                        CommandBuilder.command("menu")
                                                .description("Open the kit management menu")
                                                .usage("/duels kit menu")
                                                .permission("duels.admin.kit.menu")
                                                .alias("gui")
                                                .playerOnly()
                                                .executes(this::openKitMenuOrDetail))
                                .child(
                                        CommandBuilder.command("create")
                                                .description("Create a kit")
                                                .usage("/duels kit create <name>")
                                                .permission("duels.admin.kit.create")
                                                .playerOnly()
                                                .argument("name", ArgumentTypes.string())
                                                .executes(this::createKit))
                                .child(
                                        CommandBuilder.command("delete")
                                                .description("Delete a kit")
                                                .usage("/duels kit delete <id>")
                                                .permission("duels.admin.kit.delete")
                                                .argument("id", ArgumentTypes.integer())
                                                .executes(this::deleteKit))
                                .child(
                                        CommandBuilder.command("list")
                                                .description("List all kits")
                                                .usage("/duels kit list")
                                                .permission("duels.admin.kit.list")
                                                .executes(this::listKits))
                                .child(
                                        CommandBuilder.command("rename")
                                                .description("Rename a kit")
                                                .usage("/duels kit rename <id> <name>")
                                                .permission("duels.admin.kit.rename")
                                                .argument("id", ArgumentTypes.integer())
                                                .argument("name", ArgumentTypes.string())
                                                .executes(this::renameKit))
                                .child(
                                        CommandBuilder.command("icon")
                                                .description("Set a kit's icon to the item in your hand")
                                                .usage("/duels kit icon <id>")
                                                .permission("duels.admin.kit.icon")
                                                .playerOnly()
                                                .argument("id", ArgumentTypes.integer())
                                                .executes(this::setKitIcon))
                                .child(
                                        CommandBuilder.command("edit")
                                                .description("Open a kit's item editor directly")
                                                .usage("/duels kit edit <id>")
                                                .permission("duels.admin.kit.edit")
                                                .playerOnly()
                                                .argument("id", ArgumentTypes.integer())
                                                .executes(this::editKit)))
                .build();
    }

    private void openMenuOrHelp(CommandContext context)
    {
        if (context.getSender() instanceof Player player)
            menus.open(player, () -> adminMenu.open(player));
        else
            messageManager.sendList(context.getSender(), Message.ADMIN_HELP);
    }

    private void openArenaMenuOrDetail(CommandContext context)
    {
        if (!(context.getSender() instanceof Player player))
        {
            if (context.has("id"))
                messageManager.send(context.getSender(), CoreMessage.PLAYER_ONLY);
            else
                messageManager.sendList(context.getSender(), Message.ADMIN_HELP);
            return;
        }

        if (!context.has("id"))
        {
            menus.openPath(player, List.of(
                    () -> adminMenu.open(player),
                    () -> arenaMenu.open(player)
            ));
            return;
        }

        int id = context.get("id");

        if (arenaManager.getArena(id) == null)
        {
            messageManager.send(player, Message.ARENA_NOT_FOUND, "id", id);
            return;
        }

        menus.openPath(player, List.of(
                () -> adminMenu.open(player),
                () -> arenaMenu.open(player),
                () -> arenaListMenu.open(player),
                () -> arenaDetailMenu.open(player, id)
        ));
    }

    private void openKitMenuOrDetail(CommandContext context)
    {
        if (!(context.getSender() instanceof Player player))
        {
            if (context.has("id"))
                messageManager.send(context.getSender(), CoreMessage.PLAYER_ONLY);
            else
                messageManager.sendList(context.getSender(), Message.ADMIN_HELP);
            return;
        }

        if (!context.has("id"))
        {
            menus.openPath(player, List.of(
                    () -> adminMenu.open(player),
                    () -> kitMenu.open(player)
            ));
            return;
        }

        int id = context.get("id");

        if (kitManager.getKit(id) == null)
        {
            messageManager.send(player, Message.KIT_NOT_FOUND, "id", id);
            return;
        }

        menus.openPath(player, List.of(
                () -> adminMenu.open(player),
                () -> kitMenu.open(player),
                () -> kitListMenu.open(player),
                () -> kitDetailMenu.open(player, id)
        ));
    }

    private void createArena(CommandContext context)
    {
        String name = context.get("name");
        Arena arena = arenaManager.createArena(name);

        messageManager.send(
                context.getSender(),
                Message.ARENA_CREATED,
                "name", arena.getName(),
                "id", arena.getId()
        );
    }

    private void deleteArena(CommandContext context)
    {
        int id = context.get("id");
        ArenaMutationResult result = arenaManager.deleteArena(id);

        switch (result.status())
        {
            case NOT_FOUND -> messageManager.send(context.getSender(), Message.ARENA_NOT_FOUND, "id", id);
            case IN_USE -> messageManager.send(context.getSender(), Message.ARENA_IN_USE, "id", id);
            case SUCCESS -> messageManager.send(context.getSender(), Message.ARENA_DELETED, "id", id);
        }
    }

    private void listArenas(CommandContext context)
    {
        List<Arena> arenas = arenaManager.getArenas();

        if (arenas.isEmpty())
        {
            messageManager.send(context.getSender(), Message.NO_ARENAS);
            return;
        }

        for (Arena arena : arenas)
        {
            messageManager.send(
                    context.getSender(),
                    Message.ARENA_LIST_ENTRY,
                    "id", arena.getId(),
                    "name", arena.getName(),
                    "instances", arenaInstanceManager.getInstancesForArena(arena.getId()).size(),
                    "ready", arenaInstanceManager.countReady(arena.getId()),
                    "free", arenaInstanceManager.countFree(arena.getId()),
                    "enabled", arena.isEnabled()
            );
        }
    }

    private void renameArena(CommandContext context)
    {
        int id = context.get("id");
        String name = context.get("name");

        ArenaMutationResult result = arenaManager.rename(id, name);

        switch (result.status())
        {
            case NOT_FOUND -> messageManager.send(context.getSender(), Message.ARENA_NOT_FOUND, "id", id);
            case IN_USE -> messageManager.send(context.getSender(), Message.ARENA_IN_USE, "id", id);
            case SUCCESS -> messageManager.send(context.getSender(), Message.ARENA_RENAMED, "id", id, "name", name);
        }
    }

    private void toggleArena(CommandContext context)
    {
        int id = context.get("id");
        ArenaMutationResult result = arenaManager.toggleEnabled(id);

        switch (result.status())
        {
            case NOT_FOUND -> messageManager.send(context.getSender(), Message.ARENA_NOT_FOUND, "id", id);
            case IN_USE -> messageManager.send(context.getSender(), Message.ARENA_IN_USE, "id", id);
            case SUCCESS -> messageManager.send(
                    context.getSender(),
                    result.arena().isEnabled() ? Message.ARENA_ENABLED : Message.ARENA_DISABLED,
                    "id", id
            );
        }
    }

    private void setBoundaryMode(CommandContext context)
    {
        int id = context.get("id");
        BoundaryMode mode = context.get("mode");
        int graceSeconds = context.has("graceSeconds") ? (int) context.get("graceSeconds") : 0;

        ArenaMutationResult result = arenaManager.setBoundaryMode(id, mode, graceSeconds);

        switch (result.status())
        {
            case NOT_FOUND -> messageManager.send(context.getSender(), Message.ARENA_NOT_FOUND, "id", id);
            case IN_USE -> messageManager.send(context.getSender(), Message.ARENA_IN_USE, "id", id);
            case SUCCESS -> messageManager.send(
                    context.getSender(),
                    Message.ARENA_BOUNDARY_SET,
                    "id", id,
                    "mode", mode.name(),
                    "grace", graceSeconds
            );
        }
    }

    private void setProvisioningMode(CommandContext context)
    {
        int id = context.get("id");
        ArenaProvisioningMode mode = context.get("mode");
        Arena arena = arenaManager.getArena(id);

        if (arena == null)
        {
            messageManager.send(context.getSender(), Message.ARENA_NOT_FOUND, "id", id);
            return;
        }

        if (mode == ArenaProvisioningMode.DYNAMIC && !arena.canProvisionDynamically())
        {
            messageManager.send(context.getSender(), Message.ARENA_TEMPLATE_CAPTURE_FAILED, "reason", "capture a valid template first");
            return;
        }

        arenaManager.setProvisioningMode(id, mode);
        messageManager.send(context.getSender(), Message.ARENA_PROVISIONING_SET, "id", id, "mode", mode.name());
    }

    private void captureTemplate(CommandContext context)
    {
        Player player = context.getPlayer();
        int instanceId = context.get("instanceId");
        ArenaEditSession session = arenaEditManager.getSession(player);

        if (session == null || session.getInstanceId() != instanceId)
        {
            messageManager.send(player, Message.ARENA_TEMPLATE_CAPTURE_FAILED,
                    "reason", "enter edit mode for instance #" + instanceId + " and set both structure corners");
            return;
        }

        ArenaTemplateCaptureResult result = arenaTemplateManager.capture(
                instanceId, session.getStructureCorner1(), session.getStructureCorner2()
        );

        if (result.status() != ArenaTemplateCaptureResult.Status.SUCCESS)
        {
            messageManager.send(player, Message.ARENA_TEMPLATE_CAPTURE_FAILED, "reason", describeCaptureFailure(result.status()));
            return;
        }

        ArenaTemplateDefinition template = result.template();
        ArenaInstance instance = arenaInstanceManager.getInstance(instanceId);
        messageManager.send(player, Message.ARENA_TEMPLATE_CAPTURED,
                "id", instance.getArenaId(), "revision", template.revision(),
                "sizeX", template.size().x(), "sizeY", template.size().y(), "sizeZ", template.size().z());
    }

    private void templateInfo(CommandContext context)
    {
        int arenaId = context.get("arenaId");
        Arena arena = arenaManager.getArena(arenaId);

        if (arena == null)
        {
            messageManager.send(context.getSender(), Message.ARENA_NOT_FOUND, "id", arenaId);
            return;
        }

        ArenaTemplateDefinition template = arena.getTemplateDefinition();
        String revision = template == null ? "-" : Integer.toString(template.revision());
        String size = template == null ? "-" : template.size().x() + "x" + template.size().y() + "x" + template.size().z();
        messageManager.send(context.getSender(), Message.ARENA_TEMPLATE_INFO,
                "id", arenaId, "status", arena.getTemplateStatus().name(), "revision", revision, "size", size);
    }

    private void clearTemplate(CommandContext context)
    {
        int arenaId = context.get("arenaId");
        String confirmation = context.get("confirmation");

        if (!"confirm".equalsIgnoreCase(confirmation))
        {
            messageManager.send(context.getSender(), Message.ARENA_TEMPLATE_CAPTURE_FAILED,
                    "reason", "repeat the command with 'confirm' to delete the saved template");
            return;
        }

        Arena arena = arenaManager.getArena(arenaId);

        if (arena == null)
        {
            messageManager.send(context.getSender(), Message.ARENA_NOT_FOUND, "id", arenaId);
            return;
        }

        if (arena.getProvisioningMode() == ArenaProvisioningMode.DYNAMIC)
        {
            messageManager.send(context.getSender(), Message.ARENA_TEMPLATE_CAPTURE_FAILED,
                    "reason", "switch the arena to STATIC before clearing its template");
            return;
        }

        if (!arenaTemplateManager.clear(arenaId))
        {
            messageManager.send(context.getSender(), Message.ARENA_TEMPLATE_CAPTURE_FAILED, "reason", "no captured template exists");
            return;
        }

        messageManager.send(context.getSender(), Message.ARENA_TEMPLATE_CLEARED, "id", arenaId);
    }

    private static String describeCaptureFailure(ArenaTemplateCaptureResult.Status status)
    {
        return switch (status)
        {
            case ARENA_NOT_FOUND -> "the arena no longer exists";
            case INSTANCE_NOT_FOUND -> "the arena instance no longer exists";
            case INSTANCE_IN_USE -> "that instance is hosting a match";
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

    private void createArenaInstance(CommandContext context)
    {
        int arenaId = context.get("arenaId");

        if (arenaManager.getArena(arenaId) == null)
        {
            messageManager.send(context.getSender(), Message.ARENA_NOT_FOUND, "id", arenaId);
            return;
        }

        ArenaInstance instance = arenaInstanceManager.createInstance(arenaId);
        messageManager.send(context.getSender(), Message.ARENA_INSTANCE_CREATED, "id", instance.getId(), "arenaId", arenaId);
    }

    private void deleteArenaInstance(CommandContext context)
    {
        int instanceId = context.get("instanceId");
        ArenaInstanceMutationResult result = arenaInstanceManager.deleteInstance(instanceId);

        switch (result.status())
        {
            case NOT_FOUND -> messageManager.send(context.getSender(), Message.ARENA_INSTANCE_NOT_FOUND, "id", instanceId);
            case IN_USE -> messageManager.send(context.getSender(), Message.ARENA_INSTANCE_IN_USE, "id", instanceId);
            case SUCCESS -> messageManager.send(context.getSender(), Message.ARENA_INSTANCE_DELETED, "id", instanceId);
        }
    }

    private void listArenaInstances(CommandContext context)
    {
        int arenaId = context.get("arenaId");

        if (arenaManager.getArena(arenaId) == null)
        {
            messageManager.send(context.getSender(), Message.ARENA_NOT_FOUND, "id", arenaId);
            return;
        }

        List<ArenaInstance> instances = arenaInstanceManager.getInstancesForArena(arenaId);

        if (instances.isEmpty())
        {
            messageManager.send(context.getSender(), Message.NO_ARENAS);
            return;
        }

        for (ArenaInstance instance : instances)
        {
            String spawn1 = instance.getSpawn1() != null ? "set" : "not set";
            String spawn2 = instance.getSpawn2() != null ? "set" : "not set";

            messageManager.send(
                    context.getSender(),
                    Message.ARENA_INSTANCE_LIST_ENTRY,
                    "id", instance.getId(),
                    "spawn1", spawn1,
                    "spawn2", spawn2,
                    "ready", instance.isReady()
            );
        }
    }

    private void setInstanceSpawn(CommandContext context)
    {
        int instanceId = context.get("instanceId");
        int spawn = context.get("spawn");

        if (spawn != 1 && spawn != 2)
        {
            messageManager.send(context.getSender(), Message.ARENA_INVALID_SPAWN, "spawn", spawn);
            return;
        }

        Player player = context.getPlayer();
        ArenaInstanceMutationResult result = arenaInstanceManager.setSpawn(instanceId, spawn, player.getLocation());

        switch (result.status())
        {
            case NOT_FOUND -> messageManager.send(context.getSender(), Message.ARENA_INSTANCE_NOT_FOUND, "id", instanceId);
            case IN_USE -> messageManager.send(context.getSender(), Message.ARENA_INSTANCE_IN_USE, "id", instanceId);
            case SUCCESS -> messageManager.send(
                    context.getSender(),
                    Message.ARENA_SPAWN_SET,
                    "spawn", spawn,
                    "id", instanceId
            );
        }
    }

    private void setInstanceBoundsCorner(CommandContext context)
    {
        int instanceId = context.get("instanceId");
        int corner = context.get("corner");

        if (corner != 1 && corner != 2)
        {
            messageManager.send(context.getSender(), Message.ARENA_INVALID_BOUNDS_CORNER, "corner", corner);
            return;
        }

        Player player = context.getPlayer();
        ArenaInstanceMutationResult result = arenaInstanceManager.setBoundsCorner(instanceId, corner, player.getLocation());

        switch (result.status())
        {
            case NOT_FOUND -> messageManager.send(context.getSender(), Message.ARENA_INSTANCE_NOT_FOUND, "id", instanceId);
            case IN_USE -> messageManager.send(context.getSender(), Message.ARENA_INSTANCE_IN_USE, "id", instanceId);
            case SUCCESS -> messageManager.send(context.getSender(), Message.ARENA_BOUNDS_SET, "corner", corner, "id", instanceId);
        }
    }

    private void enterInstanceEditMode(CommandContext context)
    {
        int instanceId = context.get("instanceId");
        ArenaInstance instance = arenaInstanceManager.getInstance(instanceId);

        if (instance == null)
        {
            messageManager.send(context.getSender(), Message.ARENA_INSTANCE_NOT_FOUND, "id", instanceId);
            return;
        }

        arenaEditManager.start(context.getPlayer(), instance);
    }

    private void toggleArenaKit(CommandContext context)
    {
        int id = context.get("id");
        int kitId = context.get("kitId");

        if (kitManager.getKit(kitId) == null)
        {
            messageManager.send(context.getSender(), Message.KIT_NOT_FOUND, "id", kitId);
            return;
        }

        ArenaMutationResult result = arenaManager.toggleKitAllowed(id, kitId);

        switch (result.status())
        {
            case NOT_FOUND -> messageManager.send(context.getSender(), Message.ARENA_NOT_FOUND, "id", id);
            case IN_USE -> messageManager.send(context.getSender(), Message.ARENA_IN_USE, "id", id);
            case SUCCESS -> messageManager.send(
                    context.getSender(),
                    Message.ARENA_KIT_TOGGLED,
                    "kitId", kitId,
                    "id", id,
                    "status", result.arena().isKitAllowed(kitId) ? "allowed" : "disallowed"
            );
        }
    }

    private void createKit(CommandContext context)
    {
        String name = context.get("name");
        Player player = context.getPlayer();

        Kit kit = kitManager.createKit(name, player);

        messageManager.send(context.getSender(), Message.KIT_CREATED, "name", kit.getName(), "id", kit.getId());
    }

    private void deleteKit(CommandContext context)
    {
        int id = context.get("id");

        if (kitManager.getKit(id) == null)
        {
            messageManager.send(context.getSender(), Message.KIT_NOT_FOUND, "id", id);
            return;
        }

        kitManager.deleteKit(id);
        messageManager.send(context.getSender(), Message.KIT_DELETED, "id", id);
    }

    private void listKits(CommandContext context)
    {
        List<Kit> kits = kitManager.getKits();

        if (kits.isEmpty())
        {
            messageManager.send(context.getSender(), Message.NO_KITS);
            return;
        }

        for (Kit kit : kits)
            messageManager.send(context.getSender(), Message.KIT_LIST_ENTRY, "id", kit.getId(), "name", kit.getName());
    }

    private void renameKit(CommandContext context)
    {
        int id = context.get("id");
        String name = context.get("name");
        Kit kit = kitManager.getKit(id);

        if (kit == null)
        {
            messageManager.send(context.getSender(), Message.KIT_NOT_FOUND, "id", id);
            return;
        }

        kit.setName(name);
        kitManager.save(kit);

        messageManager.send(context.getSender(), Message.KIT_RENAMED, "id", id, "name", name);
    }

    private void setKitIcon(CommandContext context)
    {
        int id = context.get("id");
        Kit kit = kitManager.getKit(id);

        if (kit == null)
        {
            messageManager.send(context.getSender(), Message.KIT_NOT_FOUND, "id", id);
            return;
        }

        Player player = context.getPlayer();
        ItemStack held = player.getInventory().getItemInMainHand();

        if (held.getType() == Material.AIR)
        {
            messageManager.send(context.getSender(), Message.KIT_ICON_EMPTY_HAND);
            return;
        }

        kit.setIcon(held.clone());
        kitManager.save(kit);

        messageManager.send(context.getSender(), Message.KIT_ICON_SET, "name", kit.getName());
    }

    private void editKit(CommandContext context)
    {
        int id = context.get("id");

        if (kitManager.getKit(id) == null)
        {
            messageManager.send(context.getSender(), Message.KIT_NOT_FOUND, "id", id);
            return;
        }

        kitEditMenu.open(context.getPlayer(), id);
    }
}
