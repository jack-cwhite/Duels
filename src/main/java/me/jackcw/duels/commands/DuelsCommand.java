package me.jackcw.duels.commands;

import me.jackcw.duels.Duels;
import me.jackcw.duels.arena.Arena;
import me.jackcw.duels.arena.ArenaManager;
import me.jackcw.duels.arena.ArenaMutationResult;
import me.jackcw.duels.kit.Kit;
import me.jackcw.duels.kit.KitManager;
import me.jackcw.duels.menu.admin.AdminMainMenu;
import me.jackcw.duels.menu.admin.arena.ArenaMainMenu;
import me.jackcw.duels.menu.admin.arena.ArenaListMenu;
import me.jackcw.duels.menu.admin.arena.ArenaDetailMenu;
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
import org.bukkit.entity.Player;

import java.util.List;

public final class DuelsCommand
{
    private final ArenaManager arenaManager;
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

    public DuelsCommand(Duels plugin)
    {
        this.arenaManager = plugin.getArenaManager();
        this.kitManager = plugin.getKitManager();
        this.messageManager = plugin.getJCore().messages();
        this.menus = plugin.getJCore().menus();
        this.adminMenu = plugin.getAdminMainMenu();
        this.arenaMenu = plugin.getArenaMainMenu();
        this.arenaListMenu = plugin.getArenaListMenu();
        this.arenaDetailMenu = plugin.getArenaDetailMenu();
        this.kitMenu = plugin.getKitMainMenu();
        this.kitListMenu = plugin.getKitListMenu();
        this.kitDetailMenu = plugin.getKitDetailMenu();
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
                                .usage("/duels arena [id|create|delete|list|setspawn]")
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
                                        CommandBuilder.command("setspawn")
                                                .description("Set a spawn point for an arena")
                                                .usage("/duels arena setspawn <id> <1|2>")
                                                .permission("duels.admin.arena.setspawn")
                                                .alias("s")
                                                .playerOnly()
                                                .argument("id", ArgumentTypes.integer())
                                                .argument("spawn", ArgumentTypes.integer())
                                                .executes(this::setSpawn)))
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
                                                .executes(this::listKits)))
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
            String spawn1 = arena.getSpawn1() != null ? "set" : "not set";
            String spawn2 = arena.getSpawn2() != null ? "set" : "not set";

            messageManager.send(
                    context.getSender(),
                    Message.ARENA_LIST_ENTRY,
                    "id", arena.getId(),
                    "name", arena.getName(),
                    "spawn1", spawn1,
                    "spawn2", spawn2,
                    "ready", arena.isReady(),
                    "available", arena.isEnabled()
            );
        }
    }

    private void setSpawn(CommandContext context)
    {
        int id = context.get("id");
        int spawn = context.get("spawn");

        if (spawn != 1 && spawn != 2)
        {
            messageManager.send(context.getSender(), Message.ARENA_INVALID_SPAWN, "spawn", spawn);
            return;
        }

        Player player = context.getPlayer();
        ArenaMutationResult result = arenaManager.setSpawn(id, spawn, player.getLocation());

        switch (result.status())
        {
            case NOT_FOUND -> messageManager.send(context.getSender(), Message.ARENA_NOT_FOUND, "id", id);
            case IN_USE -> messageManager.send(context.getSender(), Message.ARENA_IN_USE, "id", id);
            case SUCCESS -> messageManager.send(
                    context.getSender(),
                    Message.ARENA_SPAWN_SET,
                    "spawn", spawn,
                    "id", id
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
}
