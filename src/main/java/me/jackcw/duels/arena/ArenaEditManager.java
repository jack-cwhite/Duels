package me.jackcw.duels.arena;

import me.jackcw.duels.Duels;
import me.jackcw.jcore.item.ItemStackParser;
import me.jackcw.jcore.menu.SlotResolver;
import me.jackcw.jcore.storage.YamlFile;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

public final class ArenaEditManager
{
    private static final int HOTBAR_SIZE = 9;
    private static final long PARTICLE_PERIOD_TICKS = 10L;
    private static final double PARTICLE_STEP = 0.5;
    private static final Particle.DustOptions BOUNDS_DUST = new Particle.DustOptions(Color.AQUA, 1.0f);
    private static final Particle.DustOptions STRUCTURE_DUST = new Particle.DustOptions(Color.ORANGE, 1.0f);

    private final Duels plugin;

    private final NamespacedKey toolKey;
    private final ConfigurationSection toolsSection;
    private final Map<ArenaEditTool, Integer> toolSlots;

    public Map<UUID, ArenaEditSession> getSessions()
    {
        return sessions;
    }

    /**
     * Temporary per-admin structure-corner drafts. They are deliberately not
     * persisted and are cleared on disconnect or when another instance is
     * selected, so a surviving draft is a leak - which is what
     * {@code /duels diagnostics} reports this for.
     */
    public int getCaptureDraftCount()
    {
        return captureDrafts.size();
    }

    private final Map<UUID, ArenaEditSession> sessions = new HashMap<>();
    private final Map<UUID, CaptureDraft> captureDrafts = new HashMap<>();

    private static final class CaptureDraft
    {
        private final int instanceId;
        private final Location[] corners = new Location[2];

        private CaptureDraft(int instanceId)
        {
            this.instanceId = instanceId;
        }
    }

    private CaptureDraft draft(Player player, int instanceId)
    {
        CaptureDraft current = captureDrafts.get(player.getUniqueId());
        if (current != null && current.instanceId == instanceId)
            return current;

        CaptureDraft replacement = new CaptureDraft(instanceId);
        captureDrafts.put(player.getUniqueId(), replacement);
        return replacement;
    }

    public Location getStructureCorner(Player player, int instanceId, int corner)
    {
        return draft(player, instanceId).corners[corner - 1];
    }

    /**
     * Block-aligned on the way in, matching bounds corners. {@link
     * ArenaTemplateManager} already reduced these to block coordinates when
     * capturing, so this changes no captured output - it makes the stored draft
     * agree with what the capture will actually use, so the corner an admin
     * teleports back to is the corner the template will be cut from.
     */
    public void setStructureCorner(Player player, int instanceId, int corner, Location location)
    {
        Location snapped = BlockCoordinates.snapToBlock(location);

        draft(player, instanceId).corners[corner - 1] = snapped;
        ArenaEditSession session = getSession(player);
        if (session != null && session.getInstanceId() == instanceId)
        {
            if (corner == 1)
                session.setStructureCorner1(snapped.clone());
            else
                session.setStructureCorner2(snapped.clone());
        }
    }

    public void clearCaptureDraft(UUID uuid)
    {
        captureDrafts.remove(uuid);
    }

    public ArenaEditManager(Duels plugin)
    {
        this.plugin = plugin;
        this.toolKey = new NamespacedKey(plugin, "arena-edit-tool");

        YamlFile file = plugin.core().files().yaml("arena-edit.yml", true);
        file.updateDefaults();

        this.toolsSection = file.getConfig().getConfigurationSection("tools");
        this.toolSlots = resolveToolSlots(toolsSection, plugin.getLogger());
    }

    /**
     * Opens an edit session, unless the player is in (or about to be in) a duel.
     *
     * <p>The refusal is not cosmetic. {@link ArenaEditSession} snapshots the
     * player's hotbar so {@link #end} can put it back, and a match replaces that
     * hotbar with a kit. Allowing a session to open mid-duel means the snapshot
     * captures kit items, and the later restore would write those items into the
     * player's real inventory - duplicating kit gear. Match-end restoration also
     * has no knowledge of edit sessions, so the session (and its particle task)
     * would outlive the match and lock the player out of edit mode entirely.
     *
     * @return {@code true} if the session was opened
     */
    public boolean start(Player player, ArenaInstance instance)
    {
        if (plugin.getMatchManager().getMatch(player.getUniqueId()) != null
                || plugin.getMatchManager().isPending(player.getUniqueId()))
            return false;

        if (isEditing(player))
            end(player);

        ArenaEditSession session = new ArenaEditSession(player, instance);
        if (instance.isSource())
        {
            session.setStructureCorner1(getStructureCorner(player, instance.getId(), 1));
            session.setStructureCorner2(getStructureCorner(player, instance.getId(), 2));
        }
        sessions.put(player.getUniqueId(), session);

        giveTools(player);
        startBoundsParticles(session);
        return true;
    }

    private void startBoundsParticles(ArenaEditSession session)
    {
        BukkitTask task = plugin.core().tasks().runSyncTimer(
                () -> tickBoundsParticles(session), 0L, PARTICLE_PERIOD_TICKS
        );

        session.setBoundsParticleTask(task);
    }

    private void tickBoundsParticles(ArenaEditSession session)
    {
        Player player = getPlayer(session.getPlayerUuid());
        ArenaInstance instance = plugin.getArenaInstanceManager().getInstance(session.getInstanceId());

        if (player == null || !player.isOnline() || instance == null)
            return;

        if (instance.hasBounds() && player.getWorld().equals(instance.getBoundsCorner1().getWorld()))
            tickBoxOutline(player, instance.getBoundsCorner1(), instance.getBoundsCorner2(), BOUNDS_DUST);

        if (instance.isSource() && session.getStructureCorner1() != null && session.getStructureCorner2() != null
                && player.getWorld().equals(session.getStructureCorner1().getWorld())
                && player.getWorld().equals(session.getStructureCorner2().getWorld()))
            tickBoxOutline(player, session.getStructureCorner1(), session.getStructureCorner2(), STRUCTURE_DUST);
    }

    /**
     * Draws the twelve edges of a block box as a particle wireframe.
     *
     * <p>The maximum side of each axis is deliberately rendered at
     * {@code max + 1}. A bounds box is a volume of <em>blocks</em> with both
     * corner blocks inside it, so a box whose corner blocks are X=10 and X=12
     * physically occupies the space from 10.0 up to 13.0. Drawing the frame
     * from corner to corner instead outlined 10.0 to 12.0 - a box one block
     * short on every maximum face, which read in game as the whole frame
     * sitting a block below and inside the area actually being enforced, and
     * made a correctly set corner look as though it had snapped inwards.
     *
     * <p>Corners are reduced to block indices with the same helper
     * {@link ArenaInstance#contains(Location)} uses, so the frame cannot
     * disagree with the box it is describing - including for arenas saved
     * before corners were stored block-aligned, whose raw coordinates would
     * otherwise render at fractional positions.
     */
    private void tickBoxOutline(Player player, Location corner1, Location corner2, Particle.DustOptions dust)
    {
        BlockBox box = BlockBox.of(corner1, corner2);

        if (box == null)
            return;

        World world = box.world();

        double lowX = box.minX();
        double lowY = box.minY();
        double lowZ = box.minZ();
        double highX = box.maxCornerX();
        double highY = box.maxCornerY();
        double highZ = box.maxCornerZ();
        for (double x = lowX; x <= highX; x += PARTICLE_STEP)
        {
            spawnOutlineParticle(player, world, x, lowY, lowZ, dust);
            spawnOutlineParticle(player, world, x, lowY, highZ, dust);
            spawnOutlineParticle(player, world, x, highY, lowZ, dust);
            spawnOutlineParticle(player, world, x, highY, highZ, dust);
        }

        for (double y = lowY; y <= highY; y += PARTICLE_STEP)
        {
            spawnOutlineParticle(player, world, lowX, y, lowZ, dust);
            spawnOutlineParticle(player, world, lowX, y, highZ, dust);
            spawnOutlineParticle(player, world, highX, y, lowZ, dust);
            spawnOutlineParticle(player, world, highX, y, highZ, dust);
        }

        for (double z = lowZ; z <= highZ; z += PARTICLE_STEP)
        {
            spawnOutlineParticle(player, world, lowX, lowY, z, dust);
            spawnOutlineParticle(player, world, lowX, highY, z, dust);
            spawnOutlineParticle(player, world, highX, lowY, z, dust);
            spawnOutlineParticle(player, world, highX, highY, z, dust);
        }
    }

    private void spawnOutlineParticle(Player player, World world, double x, double y, double z, Particle.DustOptions dust)
    {
        player.spawnParticle(Particle.DUST, x, y, z, 1, 0, 0, 0, 0, dust);
    }
    public void giveTools(Player player)
    {
        PlayerInventory inventory = player.getInventory();

        for (int slot = 0; slot < HOTBAR_SIZE; slot++)
            inventory.setItem(slot, null);

        inventory.setItemInOffHand(null);

        ArenaEditSession session = getSession(player);
        ArenaInstance instance = session == null ? null : plugin.getArenaInstanceManager().getInstance(session.getInstanceId());
        for (Map.Entry<ArenaEditTool, Integer> entry : toolSlots.entrySet())
        {
            ArenaEditTool tool = entry.getKey();
            if (instance != null && !instance.isSource()
                    && (tool == ArenaEditTool.STRUCTURE_CORNER_1 || tool == ArenaEditTool.STRUCTURE_CORNER_2 || tool == ArenaEditTool.CAPTURE))
                continue;
            inventory.setItem(entry.getValue(), createToolItem(tool));
        }
    }

    public boolean isTool(ItemStack item)
    {
        return getTool(item) != null;
    }

    public void end(UUID uuid)
    {
        Player player = getPlayer(uuid);

        if (player != null)
            end(player);
    }

    public void end(Player player)
    {
        ArenaEditSession session = sessions.remove(player.getUniqueId());

        if (session == null)
            return;

        if (session.getBoundsParticleTask() != null)
            session.getBoundsParticleTask().cancel();

        ItemStack[] savedHotbar = session.getSavedHotbar();
        ItemStack savedOffhand = session.getSavedOffhand();

        for (int i = 0; i < savedHotbar.length; i++)
            player.getInventory().setItem(i, savedHotbar[i]);

        if (savedOffhand.getType() != Material.AIR)
            player.getInventory().setItemInOffHand(savedOffhand);

    }

    public ArenaEditTool getTool(ItemStack item)
    {
        if (item == null || !item.hasItemMeta())
            return null;

        String value = item.getItemMeta().getPersistentDataContainer().get(toolKey, PersistentDataType.STRING);

        if (value == null)
            return null;

        try
        {
            return ArenaEditTool.valueOf(value);
        }
        catch (IllegalArgumentException e)
        {
            return null;
        }
    }

    private static Map<ArenaEditTool, Integer> resolveToolSlots(ConfigurationSection section, Logger logger)
    {
        Map<String, Integer> configured = SlotResolver.resolve(1, section, logger::warning);
        Map<ArenaEditTool, Integer> slots = new EnumMap<>(ArenaEditTool.class);
        Set<Integer> used = new HashSet<>();

        for (Map.Entry<String, Integer> entry : configured.entrySet())
        {
            ArenaEditTool tool = ArenaEditTool.byConfigKey(entry.getKey());

            if (tool == null)
            {
                logger.warning("Ignoring unknown arena edit tool '" + entry.getKey() + "' in arena-edit.yml");
                continue;
            }

            slots.put(tool, entry.getValue());
            used.add(entry.getValue());
        }

        for (ArenaEditTool tool : ArenaEditTool.values())
        {
            if (slots.containsKey(tool))
                continue;

            int slot = nextFreeSlot(used);

            if (slot == -1)
            {
                logger.warning("Arena edit tool '" + tool.getConfigKey() + "' could not be placed - the hotbar is full");
                continue;
            }

            logger.warning("Arena edit tool '" + tool.getConfigKey() + "' is not configured in arena-edit.yml; placing it at slot " + slot);
            slots.put(tool, slot);
            used.add(slot);
        }

        return slots;
    }

    private static int nextFreeSlot(Set<Integer> used)
    {
        for (int slot = 0; slot < HOTBAR_SIZE; slot++)
            if (!used.contains(slot))
                return slot;

        return -1;
    }

    private ItemStack createToolItem(ArenaEditTool tool)
    {
        ConfigurationSection section = toolsSection != null
                ? toolsSection.getConfigurationSection(tool.getConfigKey())
                : null;

        ItemStack item = ItemStackParser.parseSafely(section, defaultToolItem(tool));

        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(toolKey, PersistentDataType.STRING, tool.name());
        item.setItemMeta(meta);

        return item;
    }

    private ItemStack defaultToolItem(ArenaEditTool tool)
    {
        ItemStack item = new ItemStack(tool.getMaterial());
        ItemMeta meta = item.getItemMeta();

        meta.displayName(LegacyComponentSerializer.legacyAmpersand().deserialize(tool.getDisplayName()));

        item.setItemMeta(meta);
        return item;
    }

    public ArenaEditSession getSession(UUID uuid)
    {
        return sessions.get(uuid);
    }

    public ArenaEditSession getSession(Player player)
    {
        return getSession(player.getUniqueId());
    }

    public boolean isEditing(UUID uuid)
    {
        return sessions.containsKey(uuid);
    }

    public boolean isEditing(Player player)
    {
        return isEditing(player.getUniqueId());
    }

    public Player getPlayer(UUID uuid)
    {
        return plugin.getServer().getPlayer(uuid);
    }
}
