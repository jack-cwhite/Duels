package me.jackcw.duels.arena;

import me.jackcw.duels.Duels;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.structure.Palette;
import org.bukkit.structure.Structure;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Compares a dynamic arena's generated copies against the structure file they
 * were pasted from, block for block.
 *
 * <p>This exists because the alternative is an admin standing in two places
 * comparing builds by eye. A generated copy can be tens of thousands of blocks,
 * and the failure this is meant to catch - a paste trimmed to the gameplay
 * bounds rather than the full captured volume - looks entirely normal from
 * inside the arena, because everything a player would walk over is present.
 * Only the captured margin outside the bounds would be missing, which is
 * exactly the part nobody thinks to go and look at.
 *
 * <p>The comparison is against the stored file rather than the original
 * hand-built source region, because the capture corners are only held in a
 * transient edit session - once a capture is published, the file is the only
 * record of what was selected.
 */
public final class ArenaTemplateVerifier
{
    private static final int REPORTED_MISMATCHES = 6;

    private final Duels plugin;

    public ArenaTemplateVerifier(Duels plugin)
    {
        this.plugin = plugin;
    }

    /**
     * Report lines for one arena, or a single line explaining why nothing could
     * be verified. Resolves once the last generated copy has been walked.
     *
     * <p>The walk is spread over ticks at
     * {@code dynamic-arenas.cleanup-blocks-per-tick} blocks each: a template is
     * allowed up to two million blocks, and reading that many block states
     * inside one tick would stall the server for seconds.
     */
    public CompletableFuture<List<String>> verify(int arenaId)
    {
        Arena arena = plugin.getArenaManager().getArena(arenaId);

        if (arena == null)
            return single("&cNo arena with id " + arenaId + ".");

        ArenaTemplateDefinition template = arena.getTemplateDefinition();

        if (template == null)
            return single("&cArena " + arenaId + " has no captured template.");

        Path file = plugin.getArenaTemplateManager().getStructurePath(template);

        if (!Files.isRegularFile(file))
            return single("&cTemplate file &f" + template.fileName() + "&c is missing from plugins/Duels/structures.");

        Structure structure;

        try
        {
            structure = plugin.getServer().getStructureManager().loadStructure(file.toFile());
        }
        catch (IOException exception)
        {
            return single("&cPaper could not read &f" + template.fileName() + "&c: " + exception.getMessage());
        }

        List<String> lines = new ArrayList<>(describeFile(template, structure));
        List<BlockState> blocks = paletteBlocks(structure);

        if (blocks == null)
        {
            lines.add("&cThe file has no block palette, so there is nothing to compare copies against.");
            return CompletableFuture.completedFuture(lines);
        }

        List<ArenaInstance> copies = new ArrayList<>();

        for (ArenaInstance instance : plugin.getArenaInstanceManager().getInstancesForArena(arenaId))
            if (instance.isProvisioned())
                copies.add(instance);

        if (copies.isEmpty())
        {
            lines.add("&7No generated copies exist yet - provision one, then run this again.");
            return CompletableFuture.completedFuture(lines);
        }

        lines.addAll(describeSlots(copies));

        return compareEach(copies, blocks, 0, lines);
    }

    /**
     * What the file itself claims, independent of any copy. The size read back
     * from the file disagreeing with the stored metadata, and the block count
     * falling short of the volume, are both defects in capture rather than in
     * placement - worth separating out, because they have different causes.
     */
    private List<String> describeFile(ArenaTemplateDefinition template, Structure structure)
    {
        List<String> lines = new ArrayList<>();
        ArenaStructureSize metadata = template.size();
        ArenaStructureSize onDisk = new ArenaStructureSize(
                structure.getSize().getBlockX(), structure.getSize().getBlockY(), structure.getSize().getBlockZ());

        lines.add("&7Template revision: &f" + template.revision() + " &7(" + template.providerId() + ", " + template.fileName() + ")");
        lines.add("&7Recorded size: &f" + describe(metadata) + " &7= &f" + metadata.volume() + "&7 blocks");

        if (!metadata.equals(onDisk))
            lines.add("&cFile size disagrees: &f" + describe(onDisk)
                    + "&c - the metadata and the structure were written from different selections.");

        long stored = 0;

        for (Palette palette : structure.getPalettes())
            stored = Math.max(stored, palette.getBlockCount());

        lines.add("&7Blocks stored in file: &f" + stored);

        if (stored < metadata.volume())
            lines.add("&cThat is &f" + (metadata.volume() - stored)
                    + "&c short of the recorded volume - part of the selection was not captured.");

        if (structure.getPaletteCount() > 1)
            lines.add("&7Note: the file holds " + structure.getPaletteCount() + " palettes; the largest is used for comparison.");

        return lines;
    }

    /**
     * Slot geometry for every copy, plus an explicit overlap check. Slots sit on
     * a padded grid so overlap should be arithmetically impossible, but a
     * structure wider than its slot would spill into the neighbour, and two
     * records claiming one slot would paste over each other.
     */
    private List<String> describeSlots(List<ArenaInstance> copies)
    {
        List<String> lines = new ArrayList<>();
        DynamicArenaLayout layout = plugin.getDynamicArenaSlotManager().getOrCreateLayout();
        boolean overlap = false;

        for (ArenaInstance instance : copies)
        {
            DynamicArenaSlot slot = layout.slot(instance.getDynamicSlotIndex());

            lines.add("&7Copy &f#" + instance.getId() + "&7 slot &f" + slot.index() + "&7 at &f"
                    + slot.originX() + ", " + slot.originY() + ", " + slot.originZ()
                    + " &7state &f" + instance.getDynamicState());

            if (!slot.fits(instance.getStructureSize()))
            {
                lines.add("&cCopy #" + instance.getId() + " is larger than its slot, so it overruns into the next one.");
                overlap = true;
            }

            for (ArenaInstance other : copies)
                if (other.getId() < instance.getId() && other.getDynamicSlotIndex().equals(instance.getDynamicSlotIndex()))
                {
                    lines.add("&cCopies #" + other.getId() + " and #" + instance.getId() + " both claim slot " + slot.index() + ".");
                    overlap = true;
                }
        }

        if (!overlap)
            lines.add("&aNo copy overlaps another, and each fits inside its own slot.");

        return lines;
    }

    private CompletableFuture<List<String>> compareEach(List<ArenaInstance> copies, List<BlockState> blocks, int index, List<String> lines)
    {
        if (index >= copies.size())
            return CompletableFuture.completedFuture(lines);

        return compare(copies.get(index), blocks, lines)
                .thenCompose(ignored -> compareEach(copies, blocks, index + 1, lines));
    }

    private CompletableFuture<Void> compare(ArenaInstance instance, List<BlockState> blocks, List<String> lines)
    {
        if (instance.getDynamicState() != DynamicArenaState.READY)
        {
            lines.add("&7Skipped copy &f#" + instance.getId() + "&7 - it is " + instance.getDynamicState() + ", not READY.");
            return CompletableFuture.completedFuture(null);
        }

        DynamicArenaProvisioner provisioner = plugin.getDynamicArenaProvisioner();
        DynamicArenaSlot slot = plugin.getDynamicArenaSlotManager().getOrCreateLayout().slot(instance.getDynamicSlotIndex());
        World world = plugin.getDynamicArenaWorldManager().getOrCreateWorld();

        // Reading a block loads its chunk anyway, but a ticket keeps the whole
        // slot resident for the duration of the walk rather than letting chunks
        // churn in and out behind the cursor.
        provisioner.retainChunks(instance);

        CompletableFuture<Void> finished = new CompletableFuture<>();
        int perTick = plugin.getSettings().dynamicArenas().cleanupBlocksPerTick();
        int[] cursor = {0};
        int[] counts = new int[2];
        List<String> examples = new ArrayList<>();

        plugin.getServer().getScheduler().runTaskTimer(plugin, task ->
        {
            int checked = 0;

            while (checked < perTick && cursor[0] < blocks.size())
            {
                BlockState expected = blocks.get(cursor[0]++);
                checked++;

                BlockData expectedData = expected.getBlockData();
                BlockData actualData = world.getBlockAt(
                        slot.originX() + expected.getX(),
                        slot.originY() + expected.getY(),
                        slot.originZ() + expected.getZ()).getBlockData();

                if (expectedData.equals(actualData))
                    continue;

                // Separated because they mean different things. A different
                // material is a block that was never pasted. The same material
                // with different properties is usually a fence or a stair
                // reconnecting to its new neighbours after placement, which is
                // cosmetic and expected.
                boolean sameMaterial = expectedData.getMaterial() == actualData.getMaterial();
                counts[sameMaterial ? 1 : 0]++;

                if (!sameMaterial && examples.size() < REPORTED_MISMATCHES)
                    examples.add("&7  +" + expected.getX() + ", " + expected.getY() + ", " + expected.getZ()
                            + ": expected &f" + expectedData.getMaterial() + "&7, found &f" + actualData.getMaterial());
            }

            if (cursor[0] < blocks.size())
                return;

            task.cancel();
            provisioner.releaseChunks(instance);
            report(lines, instance, blocks.size(), counts, examples);
            finished.complete(null);
        }, 1L, 1L);

        return finished;
    }

    private void report(List<String> lines, ArenaInstance instance, int checked, int[] counts, List<String> examples)
    {
        if (counts[0] == 0)
            lines.add("&aCopy &f#" + instance.getId() + "&a matches the template across all " + checked + " blocks.");
        else
        {
            lines.add("&cCopy &f#" + instance.getId() + "&c has &f" + counts[0] + "&c of " + checked + " blocks wrong:");
            lines.addAll(examples);

            if (counts[0] > examples.size())
                lines.add("&7  ... and " + (counts[0] - examples.size()) + " more.");
        }

        if (counts[1] > 0)
            lines.add("&7Copy #" + instance.getId() + ": " + counts[1]
                    + " blocks are the right material with different properties (expected from placement updates).");
    }

    private List<BlockState> paletteBlocks(Structure structure)
    {
        List<BlockState> largest = null;

        for (Palette palette : structure.getPalettes())
            if (largest == null || palette.getBlockCount() > largest.size())
                largest = palette.getBlocks();

        return largest;
    }

    private static String describe(ArenaStructureSize size)
    {
        return size.x() + "x" + size.y() + "x" + size.z();
    }

    private static CompletableFuture<List<String>> single(String line)
    {
        return CompletableFuture.completedFuture(List.of(line));
    }
}
