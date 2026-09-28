package me.jackcw.duels.commands;

import me.jackcw.duels.Duels;
import me.jackcw.duels.arena.Arena;
import me.jackcw.duels.arena.ArenaBoundsFeedback;
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
import me.jackcw.duels.arena.ArenaTemplateVerifier;
import me.jackcw.duels.arena.DynamicArenaProvisioner;
import me.jackcw.duels.arena.DynamicArenaState;
import me.jackcw.duels.arena.BoundaryMode;
import me.jackcw.duels.diagnostics.DuelsDiagnostics;
import me.jackcw.duels.kit.Kit;
import me.jackcw.duels.kit.KitEffect;
import me.jackcw.duels.kit.KitEffectDisplay;
import me.jackcw.duels.kit.KitEffectFeedback;
import me.jackcw.duels.kit.KitEffectMutationResult;
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
import me.jackcw.duels.player.PlayerStateManager;
import me.jackcw.jcore.command.ArgumentTypes;
import me.jackcw.jcore.command.CommandBuilder;
import me.jackcw.jcore.command.CommandContext;
import me.jackcw.jcore.command.CommandNode;
import me.jackcw.jcore.menu.MenuManager;
import me.jackcw.jcore.message.MessageManager;
import me.jackcw.jcore.message.CoreMessage;
import me.jackcw.jcore.serialization.PlayerState;
import me.jackcw.jcore.util.StringUtil;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class DuelsCommand
{
    private static final UUID CONSOLE_BASELINE_KEY = new UUID(0L, 0L);

    private final Duels plugin;
    private final ArenaManager arenaManager;
    private final ArenaInstanceManager arenaInstanceManager;
    private final ArenaEditManager arenaEditManager;
    private final ArenaTemplateManager arenaTemplateManager;
    private final DynamicArenaProvisioner dynamicArenaProvisioner;
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
    private final DuelsDiagnostics diagnostics;
    private final ArenaTemplateVerifier templateVerifier;

    public DuelsCommand(Duels plugin)
    {
        this.plugin = plugin;
        this.arenaManager = plugin.getArenaManager();
        this.arenaInstanceManager = plugin.getArenaInstanceManager();
        this.arenaEditManager = plugin.getArenaEditManager();
        this.arenaTemplateManager = plugin.getArenaTemplateManager();
        this.dynamicArenaProvisioner = plugin.getDynamicArenaProvisioner();
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
        this.diagnostics = plugin.getDiagnostics();
        this.templateVerifier = new ArenaTemplateVerifier(plugin);
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
                                                .usage("/duels arena create <name> [STATIC|DYNAMIC]")
                                                .permission("duels.admin.arena.create")
                                                .alias("c")
                                                .argument("name", ArgumentTypes.string())
                                                .optionalArgument("mode", ArgumentTypes.enumType(ArenaProvisioningMode.class))
                                                .executes(this::createArena))
                                .child(
                                        CommandBuilder.command("convert")
                                                .description("Convert a one-copy static arena into a dynamic source")
                                                .usage("/duels arena convert <arenaId> <sourceInstanceId>")
                                                .permission("duels.admin.arena.provisioning")
                                                .argument("arenaId", ArgumentTypes.integer())
                                                .argument("sourceInstanceId", ArgumentTypes.integer())
                                                .executes(this::convertArena))
                                .child(
                                        CommandBuilder.command("source")
                                                .description("Create the single build source for a dynamic arena")
                                                .usage("/duels arena source create <arenaId>")
                                                .permission("duels.admin.arena.instance.create")
                                                .child(CommandBuilder.command("create")
                                                        .argument("arenaId", ArgumentTypes.integer())
                                                        .executes(this::createArenaSource))
                                                .child(CommandBuilder.command("adopt")
                                                        .description("Choose a legacy manual copy as the dynamic build source")
                                                        .permission("duels.admin.arena.provisioning")
                                                        .argument("instanceId", ArgumentTypes.integer())
                                                        .executes(this::adoptArenaSource)))
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
                                                        CommandBuilder.command("retry")
                                                                .description("Retry a failed provisioned arena instance")
                                                                .usage("/duels arena instance retry <instanceId>")
                                                                .permission("duels.admin.arena.instance.retry")
                                                                .argument("instanceId", ArgumentTypes.integer())
                                                                .executes(this::retryArenaInstance))
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
                                        kitEffectCommands())
                                .child(
                                        CommandBuilder.command("edit")
                                                .description("Open a kit's item editor directly")
                                                .usage("/duels kit edit <id>")
                                                .permission("duels.admin.kit.edit")
                                                .playerOnly()
                                                .argument("id", ArgumentTypes.integer())
                                                .executes(this::editKit)))
                .child(
                        CommandBuilder.command("diagnostics")
                                .description("Show Duels' live internal state, or compare it against a baseline")
                                .usage("/duels diagnostics [baseline|compare|template <arenaId>]")
                                .permission("duels.admin.diagnostics")
                                .alias("diag")
                                .executes(this::showDiagnostics)
                                .child(
                                        CommandBuilder.command("baseline")
                                                .description("Remember the current state to compare against later")
                                                .usage("/duels diagnostics baseline")
                                                .permission("duels.admin.diagnostics")
                                                .executes(this::saveDiagnosticsBaseline))
                                .child(
                                        CommandBuilder.command("compare")
                                                .description("Show what has changed since your baseline")
                                                .usage("/duels diagnostics compare")
                                                .permission("duels.admin.diagnostics")
                                                .executes(this::compareDiagnostics))
                                .child(
                                        CommandBuilder.command("template")
                                                .description("Check a dynamic arena's generated copies against the structure file they came from")
                                                .usage("/duels diagnostics template <arenaId>")
                                                .permission("duels.admin.diagnostics")
                                                .argument("arenaId", ArgumentTypes.integer())
                                                .executes(this::verifyTemplate))
                                .child(
                                        CommandBuilder.command("state")
                                                .description("Show the pre-duel state Duels has saved for a player, and where it would put them back")
                                                .usage("/duels diagnostics state <player>")
                                                .permission("duels.admin.diagnostics")
                                                .argument("player", ArgumentTypes.player())
                                                .executes(this::showPlayerState)))
                .child(
                        CommandBuilder.command("reload")
                                .description("Reload config.yml and messages.yml without restarting the server")
                                .usage("/duels reload")
                                .permission("duels.admin.reload")
                                .executes(this::reloadConfig))
                .build();
    }

    /**
     * Only config.yml and messages.yml are reloaded here. Kits, arenas and
     * menus.yml are live-edited through the GUI/commands and persisted on
     * every change, so there is nothing stale on disk for them to pick up -
     * reloading their files would just re-read what is already in memory.
     */
    private void reloadConfig(CommandContext context)
    {
        plugin.core().config().reload();
        plugin.getSettings().reload(plugin.core().config(), plugin.getLogger());
        plugin.core().messages().reload();

        messageManager.send(context.getSender(), Message.CONFIG_RELOADED);
    }

    /**
     * Spread over ticks rather than answered immediately, so the sender is told
     * it has started and the report follows once the walk finishes.
     */
    private void verifyTemplate(CommandContext context)
    {
        CommandSender sender = context.getSender();
        int arenaId = context.get("arenaId");

        sender.sendMessage(StringUtil.color("&7Comparing arena " + arenaId + "'s generated copies against their template..."));

        templateVerifier.verify(arenaId).whenComplete((lines, throwable) ->
        {
            if (throwable != null)
            {
                sender.sendMessage(StringUtil.color("&cVerification failed: " + throwable.getMessage()));
                return;
            }

            sendLines(sender, lines);
        });
    }

    private void showDiagnostics(CommandContext context)
    {
        sendLines(context.getSender(), diagnostics.describe(diagnostics.snapshot()));
    }

    private void saveDiagnosticsBaseline(CommandContext context)
    {
        diagnostics.saveBaseline(baselineKey(context.getSender()), diagnostics.snapshot());
        messageManager.send(context.getSender(), Message.DIAGNOSTICS_BASELINE_SAVED);
    }

    private void compareDiagnostics(CommandContext context)
    {
        DuelsDiagnostics.Snapshot baseline = diagnostics.getBaseline(baselineKey(context.getSender()));

        if (baseline == null)
        {
            messageManager.send(context.getSender(), Message.DIAGNOSTICS_NO_BASELINE);
            return;
        }

        List<String> changes = diagnostics.compare(baseline, diagnostics.snapshot());

        if (changes.isEmpty())
        {
            messageManager.send(context.getSender(), Message.DIAGNOSTICS_NO_CHANGES);
            return;
        }

        messageManager.send(context.getSender(), Message.DIAGNOSTICS_CHANGES_HEADER);
        sendLines(context.getSender(), changes);
    }

    /**
     * Reports the snapshot Duels is holding for a player and where a restore
     * would put them.
     *
     * <p>A saved state is invisible otherwise: it lives in memory plus
     * playerstates.yml, so the only symptoms an admin sees are the bad ones - a
     * player stuck with a kit, or an inventory that did not come back. It also
     * answers the question that matters when a world has been removed, which is
     * whether the snapshot still points somewhere Duels can use, since the
     * position is the one part of a snapshot that can go stale on its own.
     */
    private void showPlayerState(CommandContext context)
    {
        CommandSender sender = context.getSender();
        Player target = context.get("player");
        PlayerState state = plugin.getPlayerStateManager().get(target);

        if (state == null)
        {
            sender.sendMessage(StringUtil.color("&7" + target.getName() + " has no saved pre-duel state."));
            return;
        }

        PlayerStateManager states = plugin.getPlayerStateManager();
        Location captured = state.getLocation();
        Location destination = states.restoreDestination(target);
        boolean capturedUsable = states.canRestoreToCapturedLocation(target);

        List<String> lines = new ArrayList<>();

        lines.add("&6&lSaved state: &f" + target.getName());
        lines.add("&7Captured at: &f" + DuelsDiagnostics.describeLocation(captured));
        lines.add("&7Captured world loaded: " + (capturedUsable ? "&ayes" : "&cno"));
        lines.add("&7Would restore to: &f" + DuelsDiagnostics.describeLocation(destination)
                + (capturedUsable ? "" : " &7(fallback spawn)"));
        lines.add("&7Health: &f" + state.getHealth() + "&7, food: &f" + state.getFoodLevel()
                + "&7, xp: &f" + state.getTotalExperience() + "&7, gamemode: &f" + state.getGameMode());
        lines.add("&7Fire ticks: &f" + state.getFireTicks() + "&7, potion effects: &f" + state.getPotionEffects().size());
        lines.add("&7Inventory items: &f" + countItems(state.getStorageContents())
                + "&7, armour: &f" + countItems(state.getArmorContents()));

        sendLines(sender, lines);
    }

    private static int countItems(ItemStack[] contents)
    {
        if (contents == null)
            return 0;

        int count = 0;

        for (ItemStack item : contents)
            if (item != null && !item.getType().isAir())
                count++;

        return count;
    }

    /**
     * Baselines are keyed by player, so the console needs a key of its own. A
     * fixed UUID is enough: there is only ever one console.
     */
    private static UUID baselineKey(CommandSender sender)
    {
        return sender instanceof Player player ? player.getUniqueId() : CONSOLE_BASELINE_KEY;
    }

    /**
     * Sent as coloured raw lines rather than through message keys, because the
     * report is a table of numbers rather than copy - see {@link DuelsDiagnostics}
     * for why it is not in {@code messages.yml}.
     */
    private static void sendLines(CommandSender sender, List<String> lines)
    {
        for (String line : lines)
            sender.sendMessage(StringUtil.color(line));
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
        ArenaProvisioningMode mode = context.has("mode") ? context.get("mode") : ArenaProvisioningMode.STATIC;
        Arena arena = arenaManager.createArena(name, mode);

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
                    "instances", arenaInstanceManager.getInstancesForArena(arena.getId()).stream()
                            .filter(arenaInstanceManager::isPlayable).count(),
                    "mode", arena.getProvisioningMode().name(),
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

    private void createArenaSource(CommandContext context)
    {
        int arenaId = context.get("arenaId");
        Arena arena = arenaManager.getArena(arenaId);
        if (arena == null)
        {
            messageManager.send(context.getSender(), Message.ARENA_NOT_FOUND, "id", arenaId);
            return;
        }
        if (arena.getProvisioningMode() != ArenaProvisioningMode.DYNAMIC || arenaInstanceManager.getSource(arenaId) != null)
        {
            messageManager.send(context.getSender(), Message.ARENA_OPERATION_FAILED,
                    "reason", "only a DYNAMIC arena without a source can create one");
            return;
        }
        try
        {
            ArenaInstance source = arenaInstanceManager.createSource(arenaId);
            messageManager.send(context.getSender(), Message.ARENA_INSTANCE_CREATED, "id", source.getId(), "arenaId", arenaId);
        }
        catch (IllegalStateException exception)
        {
            messageManager.send(context.getSender(), Message.ARENA_OPERATION_FAILED, "reason", exception.getMessage());
        }
    }

    private void adoptArenaSource(CommandContext context)
    {
        int instanceId = context.get("instanceId");
        ArenaInstance instance = arenaInstanceManager.getInstance(instanceId);
        if (instance == null)
        {
            messageManager.send(context.getSender(), Message.ARENA_INSTANCE_NOT_FOUND, "id", instanceId);
            return;
        }
        Arena arena = arenaManager.getArena(instance.getArenaId());
        if (arena == null || arena.getProvisioningMode() != ArenaProvisioningMode.DYNAMIC
                || instance.getOrigin() != me.jackcw.duels.arena.ArenaInstanceOrigin.MANUAL
                || arenaInstanceManager.getSource(arena.getId()) != null || arenaInstanceManager.isActive(instanceId))
        {
            messageManager.send(context.getSender(), Message.ARENA_OPERATION_FAILED,
                    "reason", "only an idle legacy manual copy of a DYNAMIC arena without a source can be adopted");
            return;
        }
        arenaInstanceManager.promoteToSource(instance);
        messageManager.send(context.getSender(), Message.ARENA_PROVISIONING_SET,
                "id", arena.getId(), "mode", "DYNAMIC (source #" + instanceId + ")");
    }

    private void convertArena(CommandContext context)
    {
        int arenaId = context.get("arenaId");
        int sourceId = context.get("sourceInstanceId");
        if (!arenaInstanceManager.convertToDynamicSource(arenaId, sourceId))
        {
            messageManager.send(context.getSender(), Message.ARENA_OPERATION_FAILED,
                    "reason", "conversion needs exactly one idle hand-built copy of a STATIC arena; nothing was changed");
            return;
        }
        messageManager.send(context.getSender(), Message.ARENA_PROVISIONING_SET, "id", arenaId, "mode", "DYNAMIC (source #" + sourceId + ")");
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

        ArenaMutationResult result = arenaManager.setProvisioningMode(id, mode);
        if (result.status() == ArenaMutationResult.Status.IN_USE)
            messageManager.send(context.getSender(), Message.ARENA_OPERATION_FAILED,
                    "reason", "arena type is fixed once setup begins; use the explicit one-copy conversion for existing arenas");
        else
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
            messageManager.send(player, Message.ARENA_TEMPLATE_CAPTURE_FAILED, "reason", ArenaTemplateCaptureResult.describeFailure(result.status()));
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
            messageManager.send(context.getSender(), Message.ARENA_OPERATION_FAILED,
                    "reason", "repeat the command with 'confirm' to delete the saved template");
            return;
        }

        Arena arena = arenaManager.getArena(arenaId);

        if (arena == null)
        {
            messageManager.send(context.getSender(), Message.ARENA_NOT_FOUND, "id", arenaId);
            return;
        }

        if (!arenaTemplateManager.clear(arenaId))
        {
            messageManager.send(context.getSender(), Message.ARENA_OPERATION_FAILED,
                    "reason", "retire generated copies first, or no captured template exists");
            return;
        }

        messageManager.send(context.getSender(), Message.ARENA_TEMPLATE_CLEARED, "id", arenaId);
    }

    private void createArenaInstance(CommandContext context)
    {
        int arenaId = context.get("arenaId");

        Arena arena = arenaManager.getArena(arenaId);
        if (arena == null)
        {
            messageManager.send(context.getSender(), Message.ARENA_NOT_FOUND, "id", arenaId);
            return;
        }

        if (arena.getProvisioningMode() != ArenaProvisioningMode.STATIC)
        {
            messageManager.send(context.getSender(), Message.ARENA_OPERATION_FAILED,
                    "reason", "DYNAMIC arenas have one source, not hand-built playable instances; use /duels arena source create " + arenaId);
            return;
        }
        ArenaInstance instance = arenaInstanceManager.createInstance(arenaId);
        messageManager.send(context.getSender(), Message.ARENA_INSTANCE_CREATED, "id", instance.getId(), "arenaId", arenaId);
    }

    private void deleteArenaInstance(CommandContext context)
    {
        int instanceId = context.get("instanceId");
        ArenaInstance instance = arenaInstanceManager.getInstance(instanceId);

        if (instance != null && instance.isProvisioned())
        {
            if (arenaInstanceManager.isActive(instanceId))
            {
                messageManager.send(context.getSender(), Message.ARENA_INSTANCE_IN_USE, "id", instanceId, "arenaId", instance.getArenaId());
                return;
            }
            dynamicArenaProvisioner.retire(instance).whenComplete((ignored, throwable) ->
            {
                if (throwable == null)
                    messageManager.send(context.getSender(), Message.ARENA_INSTANCE_DELETED, "id", instanceId, "arenaId", instance.getArenaId());
                else
                    messageManager.send(context.getSender(), Message.ARENA_OPERATION_FAILED, "reason", "retirement cleanup failed; check the server log");
            });
            return;
        }

        ArenaInstanceMutationResult result = arenaInstanceManager.deleteInstance(instanceId);

        switch (result.status())
        {
            case NOT_FOUND -> messageManager.send(context.getSender(), Message.ARENA_INSTANCE_NOT_FOUND, "id", instanceId);
            case IN_USE ->
            {
                if (instance != null && instance.isSource())
                    messageManager.send(context.getSender(), Message.ARENA_OPERATION_FAILED,
                            "reason", "clear the captured template and retire generated copies before deleting this source");
                else
                    messageManager.send(context.getSender(), Message.ARENA_INSTANCE_IN_USE, "id", instanceId,
                            "arenaId", instance != null ? instance.getArenaId() : -1);
            }
            case SUCCESS -> messageManager.send(context.getSender(), Message.ARENA_INSTANCE_DELETED, "id", instanceId,
                    "arenaId", instance != null ? instance.getArenaId() : -1);
        }
    }

    private void retryArenaInstance(CommandContext context)
    {
        int instanceId = context.get("instanceId");
        ArenaInstance instance = arenaInstanceManager.getInstance(instanceId);
        if (instance == null)
        {
            messageManager.send(context.getSender(), Message.ARENA_INSTANCE_NOT_FOUND, "id", instanceId);
            return;
        }
        if (!instance.isProvisioned() || instance.getDynamicState() != DynamicArenaState.FAILED)
        {
            messageManager.send(context.getSender(), Message.ARENA_OPERATION_FAILED, "reason", "only a failed provisioned instance can be retried");
            return;
        }

        dynamicArenaProvisioner.rebuild(instance).whenComplete((result, throwable) ->
        {
            if (throwable == null && result.status() == me.jackcw.duels.arena.DynamicArenaProvisionResult.Status.SUCCESS)
                messageManager.send(context.getSender(), Message.ARENA_INSTANCE_REBUILT, "id", instanceId);
            else
                messageManager.send(context.getSender(), Message.ARENA_OPERATION_FAILED, "reason", "retry failed; check the server log");
        });
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
                    "arenaId", arenaId,
                    "spawn1", spawn1,
                    "spawn2", spawn2,
                    "origin", instance.getOrigin().name(),
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
        ArenaInstance existing = arenaInstanceManager.getInstance(instanceId);
        ArenaInstanceMutationResult result = arenaInstanceManager.setSpawn(instanceId, spawn, player.getLocation());

        switch (result.status())
        {
            case NOT_FOUND -> messageManager.send(context.getSender(), Message.ARENA_INSTANCE_NOT_FOUND, "id", instanceId);
            case IN_USE -> messageManager.send(context.getSender(), Message.ARENA_INSTANCE_IN_USE, "id", instanceId,
                    "arenaId", existing != null ? existing.getArenaId() : -1);
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
        ArenaInstance existing = arenaInstanceManager.getInstance(instanceId);
        ArenaInstanceMutationResult result = arenaInstanceManager.setBoundsCorner(instanceId, corner, player.getLocation());

        switch (result.status())
        {
            case NOT_FOUND -> messageManager.send(context.getSender(), Message.ARENA_INSTANCE_NOT_FOUND, "id", instanceId);
            case IN_USE -> messageManager.send(context.getSender(), Message.ARENA_INSTANCE_IN_USE, "id", instanceId,
                    "arenaId", existing != null ? existing.getArenaId() : -1);
            case SUCCESS -> ArenaBoundsFeedback.sendCornerSet(messageManager, player, result.instance(), corner);
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

        if (!arenaEditManager.start(context.getPlayer(), instance))
            messageManager.send(context.getSender(), Message.ARENA_EDIT_WHILE_IN_MATCH);
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

    private CommandBuilder kitEffectCommands()
    {
        return CommandBuilder.command("effect")
                .description("Manage a kit's permanent effects")
                .usage("/duels kit effect <list|add|remove|level|ambient|particles|icon> ...")
                .permission("duels.admin.kit.edit")
                .child(CommandBuilder.command("list")
                        .usage("/duels kit effect list <kitId>")
                        .argument("kitId", ArgumentTypes.integer())
                        .executes(this::listKitEffects))
                .child(CommandBuilder.command("add")
                        .usage("/duels kit effect add <kitId> <effectKey> [level]")
                        .argument("kitId", ArgumentTypes.integer())
                        .argument("effectKey", ArgumentTypes.string())
                        .optionalArgument("level", ArgumentTypes.integer())
                        .executes(this::addKitEffect))
                .child(CommandBuilder.command("remove")
                        .usage("/duels kit effect remove <kitId> <effectKey>")
                        .argument("kitId", ArgumentTypes.integer())
                        .argument("effectKey", ArgumentTypes.string())
                        .executes(this::removeKitEffect))
                .child(CommandBuilder.command("level")
                        .usage("/duels kit effect level <kitId> <effectKey> <1-255>")
                        .argument("kitId", ArgumentTypes.integer())
                        .argument("effectKey", ArgumentTypes.string())
                        .argument("level", ArgumentTypes.integer())
                        .executes(this::setKitEffectLevel))
                .child(effectFlagCommand("ambient", KitManager.EffectFlag.AMBIENT))
                .child(effectFlagCommand("particles", KitManager.EffectFlag.PARTICLES))
                .child(effectFlagCommand("icon", KitManager.EffectFlag.ICON));
    }

    private CommandBuilder effectFlagCommand(String name, KitManager.EffectFlag flag)
    {
        return CommandBuilder.command(name)
                .usage("/duels kit effect " + name + " <kitId> <effectKey> <true|false>")
                .argument("kitId", ArgumentTypes.integer())
                .argument("effectKey", ArgumentTypes.string())
                .argument("enabled", ArgumentTypes.bool())
                .executes(context ->
                {
                    int kitId = context.get("kitId");
                    String key = context.get("effectKey");
                    reportEffect(context.getSender(), kitId, key,
                            kitManager.setEffectFlag(kitId, key, flag, context.get("enabled")), false);
                });
    }

    private void listKitEffects(CommandContext context)
    {
        int kitId = context.get("kitId");
        Kit kit = kitManager.getKit(kitId);
        if (kit == null)
        {
            messageManager.send(context.getSender(), Message.KIT_NOT_FOUND, "id", kitId);
            return;
        }
        if (kit.getEffects().isEmpty())
        {
            messageManager.send(context.getSender(), Message.KIT_EFFECT_LIST_EMPTY, "id", kitId);
            return;
        }
        for (KitEffect effect : kit.getEffects())
            messageManager.send(context.getSender(), Message.KIT_EFFECT_LIST_ENTRY,
                    "type", KitEffectDisplay.name(effect.typeKey()), "level", KitEffectDisplay.level(effect.level()),
                    "ambient", effect.ambient(), "particles", effect.particles(), "icon", effect.icon());
    }

    private void addKitEffect(CommandContext context)
    {
        int kitId = context.get("kitId");
        String key = context.get("effectKey");
        int level = context.has("level") ? context.get("level") : 1;
        reportEffect(context.getSender(), kitId, key, kitManager.addEffect(kitId, key, level), false);
    }

    private void removeKitEffect(CommandContext context)
    {
        int kitId = context.get("kitId");
        String key = context.get("effectKey");
        reportEffect(context.getSender(), kitId, key, kitManager.removeEffect(kitId, key), true);
    }

    private void setKitEffectLevel(CommandContext context)
    {
        int kitId = context.get("kitId");
        String key = context.get("effectKey");
        reportEffect(context.getSender(), kitId, key,
                kitManager.setEffectLevel(kitId, key, context.get("level")), false);
    }

    private void reportEffect(CommandSender sender, int kitId, String key,
                              KitEffectMutationResult result, boolean removed)
    {
        KitEffectFeedback.send(messageManager, sender, kitId, key, result, removed);
    }
}
