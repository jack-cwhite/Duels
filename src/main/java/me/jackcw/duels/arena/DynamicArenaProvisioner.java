package me.jackcw.duels.arena;

import me.jackcw.duels.Duels;
import org.bukkit.World;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Constructs physical copies; it never decides which copy a match should get.
 * The allocator remains the one owner of allocation claims.
 */
public final class DynamicArenaProvisioner
{
    private final Duels plugin;
    private final ArenaInstanceManager instanceManager;
    private final ArenaTemplateManager templateManager;
    private final DynamicArenaSlotManager slotManager;
    private final DynamicArenaWorldManager worldManager;

    public DynamicArenaProvisioner(Duels plugin)
    {
        this.plugin = plugin;
        this.instanceManager = plugin.getArenaInstanceManager();
        this.templateManager = plugin.getArenaTemplateManager();
        this.slotManager = plugin.getDynamicArenaSlotManager();
        this.worldManager = plugin.getDynamicArenaWorldManager();
    }

    public CompletableFuture<DynamicArenaProvisionResult> provision(Arena arena)
    {
        ArenaTemplateDefinition template = arena == null ? null : arena.getTemplateDefinition();
        if (template == null || !template.providerId().equals(templateManager.getProvider().id())
                || !Files.isRegularFile(templateManager.getStructurePath(template)))
            return CompletableFuture.completedFuture(DynamicArenaProvisionResult.failure(DynamicArenaProvisionResult.Status.TEMPLATE_UNAVAILABLE));

        DynamicArenaSlot slot = slotManager.reserveNext();
        if (slot == null)
            return CompletableFuture.completedFuture(DynamicArenaProvisionResult.failure(DynamicArenaProvisionResult.Status.CAPACITY_REACHED));
        if (!slot.fits(template.size()))
        {
            slotManager.releaseReservation(slot.index());
            return CompletableFuture.completedFuture(DynamicArenaProvisionResult.failure(DynamicArenaProvisionResult.Status.TEMPLATE_UNAVAILABLE));
        }

        final World world;
        final ArenaInstance instance;
        try
        {
            world = worldManager.getOrCreateWorld();
            // Persist the non-ready record before changing the world. If the
            // server stops during a paste, startup sees PROVISIONING and never
            // allocates this potentially partial slot.
            instance = instanceManager.createProvisionedInstance(arena.getId(), slot.index(), template.revision(), template.size());
            slotManager.markOccupied(slot.index());
            addChunkTickets(world, slot, template.size());
        }
        catch (RuntimeException exception)
        {
            slotManager.releaseReservation(slot.index());
            plugin.getLogger().log(Level.WARNING, "Could not begin dynamic provisioning for arena #" + arena.getId(), exception);
            return CompletableFuture.completedFuture(DynamicArenaProvisionResult.failure(DynamicArenaProvisionResult.Status.FAILED));
        }

        CompletableFuture<?>[] chunkLoads = chunkLoads(world, slot, template.size()).toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(chunkLoads)
                .orTimeout(plugin.getSettings().dynamicArenas().provisionTimeoutSeconds(), TimeUnit.SECONDS)
                .handle((ignored, throwable) -> completeOnMain(() -> finishProvision(world, slot, template, instance, throwable)))
                .thenCompose(future -> future);
    }

    public void retainChunks(ArenaInstance instance)
    {
        if (!instance.isProvisioned())
            return;
        World world = worldManager.getOrCreateWorld();
        DynamicArenaSlot slot = slotManager.getOrCreateLayout().slot(instance.getDynamicSlotIndex());
        addChunkTickets(world, slot, instance.getStructureSize());
    }

    public void releaseChunks(ArenaInstance instance)
    {
        if (!instance.isProvisioned())
            return;
        World world = worldManager.getOrCreateWorld();
        DynamicArenaSlot slot = slotManager.getOrCreateLayout().slot(instance.getDynamicSlotIndex());
        forEachChunk(slot, instance.getStructureSize(), (x, z) -> world.removePluginChunkTicket(x, z, plugin));
    }

    private DynamicArenaProvisionResult finishProvision(World world, DynamicArenaSlot slot, ArenaTemplateDefinition template,
                                                        ArenaInstance instance, Throwable throwable)
    {
        try
        {
            if (throwable != null)
                throw new IllegalStateException("Timed out or failed while loading provisioned arena chunks", throwable);

            templateManager.getProvider().place(templateManager.getStructurePath(template), world, slot.originX(), slot.originY(), slot.originZ());
            instance.setSpawn1(template.spawn1().toLocation(world, slot.originX(), slot.originY(), slot.originZ()));
            instance.setSpawn2(template.spawn2().toLocation(world, slot.originX(), slot.originY(), slot.originZ()));
            instance.setBoundsCorner1(template.boundsCorner1().toLocation(world, slot.originX(), slot.originY(), slot.originZ()));
            instance.setBoundsCorner2(template.boundsCorner2().toLocation(world, slot.originX(), slot.originY(), slot.originZ()));
            instanceManager.setDynamicState(instance, DynamicArenaState.READY);
            releaseChunks(instance);
            return DynamicArenaProvisionResult.success(instance);
        }
        catch (Exception exception)
        {
            plugin.getLogger().log(Level.WARNING, "Dynamic provisioning failed for arena instance #" + instance.getId()
                    + " in slot " + slot.index(), exception);
            try { instanceManager.setDynamicState(instance, DynamicArenaState.FAILED); }
            catch (RuntimeException stateFailure) { plugin.getLogger().log(Level.SEVERE, "Could not mark failed dynamic arena instance #" + instance.getId(), stateFailure); }
            releaseChunks(instance);
            return DynamicArenaProvisionResult.failure(DynamicArenaProvisionResult.Status.FAILED);
        }
    }

    private CompletableFuture<DynamicArenaProvisionResult> completeOnMain(java.util.concurrent.Callable<DynamicArenaProvisionResult> work)
    {
        CompletableFuture<DynamicArenaProvisionResult> future = new CompletableFuture<>();
        Runnable run = () ->
        {
            try { future.complete(work.call()); }
            catch (Exception exception) { future.completeExceptionally(exception); }
        };
        if (plugin.getServer().isPrimaryThread())
            run.run();
        else
            plugin.getServer().getScheduler().runTask(plugin, run);
        return future;
    }

    private List<CompletableFuture<?>> chunkLoads(World world, DynamicArenaSlot slot, ArenaStructureSize size)
    {
        List<CompletableFuture<?>> futures = new ArrayList<>();
        forEachChunk(slot, size, (x, z) -> futures.add(world.getChunkAtAsync(x, z, true)));
        return futures;
    }

    private void addChunkTickets(World world, DynamicArenaSlot slot, ArenaStructureSize size)
    {
        forEachChunk(slot, size, (x, z) -> world.addPluginChunkTicket(x, z, plugin));
    }

    private static void forEachChunk(DynamicArenaSlot slot, ArenaStructureSize size, ChunkConsumer consumer)
    {
        int minX = Math.floorDiv(slot.originX(), 16);
        int maxX = Math.floorDiv(slot.originX() + size.x() - 1, 16);
        int minZ = Math.floorDiv(slot.originZ(), 16);
        int maxZ = Math.floorDiv(slot.originZ() + size.z() - 1, 16);
        for (int x = minX; x <= maxX; x++)
            for (int z = minZ; z <= maxZ; z++)
                consumer.accept(x, z);
    }

    @FunctionalInterface
    private interface ChunkConsumer { void accept(int x, int z); }
}
