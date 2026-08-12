package com.example.orepatch;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Environment(EnvType.CLIENT)
public final class OrePatchMod implements ClientModInitializer {

    public static final String MOD_ID = "orepatch";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    // Singleton so Mixins can reach the instance
    public static OrePatchMod INSTANCE;

    public final OrePatchConfig config = new OrePatchConfig();

    private final List<ChunkScanTask> pendingScans = new ArrayList<>();
    private final Set<Long> queuedChunkKeys = new HashSet<>();

    @Override
    public void onInitializeClient() {
        INSTANCE = this;
        LOGGER.info("[OrePatch] Initializing (client-side)");

        ClientChunkEvents.CHUNK_LOAD.register(this::onChunkLoad);
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.level != null) onClientTick(client.level);
        });
        OrePatchClientCommand.register(config);

        LOGGER.info("[OrePatch] Ready");
    }

    private void onChunkLoad(ClientLevel level, LevelChunk chunk) {
        if (!config.isEnabled()) return;

        long key = chunkKey(chunk.getPos().x(), chunk.getPos().z());
        if (queuedChunkKeys.add(key)) {
            pendingScans.add(new ChunkScanTask(level, chunk.getPos().x(), chunk.getPos().z(), key));
        }
    }

    /** Called by Mixins when a single-block update packet arrives. */
    public void onBlockUpdate(ClientLevel level, int x, int y, int z) {
        if (!config.isEnabled()) return;
        OreGapScanner.scanSingleBlock(level, x, y, z);
        // Also re-check the 6 neighbours — their exposure status may have changed
        OreGapScanner.scanSingleBlock(level, x + 1, y,     z    );
        OreGapScanner.scanSingleBlock(level, x - 1, y,     z    );
        OreGapScanner.scanSingleBlock(level, x,     y + 1, z    );
        OreGapScanner.scanSingleBlock(level, x,     y - 1, z    );
        OreGapScanner.scanSingleBlock(level, x,     y,     z + 1);
        OreGapScanner.scanSingleBlock(level, x,     y,     z - 1);
    }

    private void onClientTick(ClientLevel level) {
        if (!config.isEnabled() || pendingScans.isEmpty()) return;

        // Per-tick wall-clock budget (nanoseconds). Even if chunksPerTick is high,
        // we stop once we've spent this long scanning so a few heavy chunks can't
        // blow the frame. ~2ms leaves plenty of headroom in a 50ms tick.
        final long budgetNanos = 2_000_000L;
        long deadline = System.nanoTime() + budgetNanos;

        int processed = 0;
        while (processed < config.getChunksPerTick() && !pendingScans.isEmpty()) {
            // Find nearest queued chunk to the camera
            int bestIdx = -1;
            long bestDist = Long.MAX_VALUE;
            for (int i = 0; i < pendingScans.size(); i++) {
                ChunkScanTask t = pendingScans.get(i);
                if (t.level != level) continue;
                long dist = camDistSq(level, t.chunkX, t.chunkZ);
                if (bestIdx == -1 || dist < bestDist) { bestIdx = i; bestDist = dist; }
            }
            if (bestIdx == -1) break;

            ChunkScanTask task = pendingScans.remove(bestIdx);
            queuedChunkKeys.remove(task.chunkKey);

            if (level.hasChunk(task.chunkX, task.chunkZ)) {
                OreGapScanner.scanChunk(level, task.chunkX, task.chunkZ);
            }
            processed++;

            // Stop early if we've used our time budget this tick; the rest stay
            // queued and get picked up next tick (closest-to-camera first).
            if (System.nanoTime() >= deadline) break;
        }
    }

    private static long camDistSq(ClientLevel level, int cx, int cz) {
        var cam = level.players();
        if (cam.isEmpty()) return Long.MAX_VALUE;
        var p = cam.get(0);
        long dx = (long)(p.getBlockX() - (cx * 16 + 8));
        long dz = (long)(p.getBlockZ() - (cz * 16 + 8));
        return dx * dx + dz * dz;
    }

    public static long chunkKey(int x, int z) {
        return (long) x << 32 | (z & 0xFFFFFFFFL);
    }

    private record ChunkScanTask(ClientLevel level, int chunkX, int chunkZ, long chunkKey) {}
}
