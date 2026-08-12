package com.example.orepatch;

import it.unimi.dsi.fastutil.longs.Long2BooleanOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

@Environment(EnvType.CLIENT)
final class OreGapScanner {

    /** Fill block for the stone region (Y >= 0) in the Overworld. */
    static final BlockState FILL_STONE = Blocks.STONE.defaultBlockState();
    /** Fill block for the deepslate region (Y < 0) in the Overworld. */
    static final BlockState FILL_DEEPSLATE = Blocks.DEEPSLATE.defaultBlockState();
    /** Fill block used everywhere in the Nether — matches the surrounding terrain. */
    static final BlockState FILL_NETHERRACK = Blocks.NETHERRACK.defaultBlockState();

    /**
     * Picks the patch block for a position, based on dimension rather than just
     * Y, so masked ore/bait cavities blend into the surrounding terrain instead
     * of leaving obvious overworld-stone patches in the Nether.
     */
    private static BlockState fillFor(ClientLevel level, int y) {
        if (level.dimension().equals(Level.NETHER)) {
            return FILL_NETHERRACK;
        }
        return y < 0 ? FILL_DEEPSLATE : FILL_STONE;
    }

    /**
     * Vertical scan window. Overworld ore generation runs from bedrock up to
     * ~Y=320 (coal/copper in mountains), but the overwhelming majority of ore
     * — and everything an xrayer cares about — sits below Y=128. Capping the
     * scan here skips the entire sky and most surface columns. The cap is
     * intersected with the level's real bounds at runtime, so it is safe on
     * any dimension/height — in the Nether (Y 0-127) this cap sits above the
     * bedrock ceiling and has no effect, so the whole dimension gets scanned.
     */
    private static final int SCAN_MIN_Y = -64;
    private static final int SCAN_MAX_Y = 128;

    /** An air pocket must exceed this many connected air blocks to count as a real opening. */
    private static final int MIN_VISIBLE_POCKET = 4;

    /** Flood-fill cap: once a pocket reaches this size it's definitely "real". */
    private static final int FLOOD_CAP = 64;

    @SuppressWarnings("unchecked")
    private static final TagKey<Block>[] ORE_TAGS = new TagKey[]{
            oreTag("coal_ores"),
            oreTag("copper_ores"),
            oreTag("diamond_ores"),
            oreTag("emerald_ores"),
            oreTag("gold_ores"),
            oreTag("iron_ores"),
            oreTag("lapis_ores"),
            oreTag("redstone_ores")
    };

    /**
     * Builds a vanilla block tag key directly from its id rather than through
     * a {@code BlockTags} constant, since the exact constant names for these
     * ore tags have moved around between Minecraft versions while the
     * underlying tag ids (`#minecraft:coal_ores`, etc.) have not.
     */
    private static TagKey<Block> oreTag(String path) {
        return TagKey.create(Registries.BLOCK, Identifier.withDefaultNamespace(path));
    }

    private OreGapScanner() {}

    /**
     * Anti-xray pass for one chunk, optimized to skip work aggressively:
     *
     *  - Y-cap: only sections overlapping [SCAN_MIN_Y, SCAN_MAX_Y] are looked at.
     *  - Section/palette skip: LevelChunkSection.maybeHas(isOre) checks the
     *    section's block palette and returns false instantly if the section
     *    contains no ore at all, letting us skip all 4096 of its blocks.
     *  - Primitive collections: pocket bookkeeping uses fastutil long-keyed
     *    sets/maps (no autoboxing, no per-pocket allocation).
     */
    static void scanChunk(ClientLevel level, int chunkX, int chunkZ) {
        LevelChunk chunk = level.getChunk(chunkX, chunkZ);

        int levelMinY = level.getMinY();
        int levelMaxY = levelMinY + level.getHeight() - 1;
        int scanMinY = Math.max(SCAN_MIN_Y, levelMinY);
        int scanMaxY = Math.min(SCAN_MAX_Y, levelMaxY);
        if (scanMinY > scanMaxY) return;

        int baseX = chunkX << 4;
        int baseZ = chunkZ << 4;

        // Per-chunk pocket caches (reused across every ore in the chunk).
        Long2BooleanOpenHashMap airIsReal = new Long2BooleanOpenHashMap();
        LongOpenHashSet smallPocketAir = new LongOpenHashSet();
        LongArrayList oresToMask = new LongArrayList();

        // Scratch collections reused by each flood-fill (cleared between calls).
        LongOpenHashSet floodVisited = new LongOpenHashSet();
        LongArrayList floodQueue = new LongArrayList();
        LongArrayList floodPocket = new LongArrayList();

        LevelChunkSection[] sections = chunk.getSections();
        int minSection = chunk.getMinSectionY();

        BlockPos.MutableBlockPos cur = new BlockPos.MutableBlockPos();

        for (int si = 0; si < sections.length; si++) {
            LevelChunkSection section = sections[si];
            if (section == null) continue;

            int sectionBottomY = (minSection + si) << 4;
            int sectionTopY = sectionBottomY + 15;

            // Y-cap: skip sections fully outside the scan window.
            if (sectionTopY < scanMinY || sectionBottomY > scanMaxY) continue;

            // Palette skip: if this section's palette contains no ore, skip all
            // 4096 of its blocks. Air-only sections also fall out here for free.
            if (!section.maybeHas(OreGapScanner::isOre)) continue;

            int yStart = Math.max(sectionBottomY, scanMinY);
            int yEnd = Math.min(sectionTopY, scanMaxY);

            for (int y = yStart; y <= yEnd; y++) {
                for (int lx = 0; lx < 16; lx++) {
                    for (int lz = 0; lz < 16; lz++) {
                        cur.set(baseX + lx, y, baseZ + lz);
                        BlockState state = level.getBlockState(cur);
                        if (!isOre(state)) continue;

                        // Non-deepslate ore below Y=0 is bait — always mask, but
                        // still classify pockets so its cavity gets sealed. This
                        // is an Overworld-only signal: the Nether's Y range never
                        // goes below 0, so this branch simply never fires there.
                        if (y < 0 && !isDeepslateOre(state)) {
                            oreSeesRealPocket(level, cur, airIsReal, smallPocketAir,
                                    floodVisited, floodQueue, floodPocket, scanMinY, scanMaxY);
                            oresToMask.add(cur.asLong());
                            continue;
                        }

                        if (oreSeesRealPocket(level, cur, airIsReal, smallPocketAir,
                                floodVisited, floodQueue, floodPocket, scanMinY, scanMaxY)) {
                            continue; // visible in a real cave — leave it
                        }
                        oresToMask.add(cur.asLong());
                    }
                }
            }
        }

        if (oresToMask.isEmpty() && smallPocketAir.isEmpty()) return;

        BlockPos.MutableBlockPos w = new BlockPos.MutableBlockPos();

        // Step 2 — mask hidden / bait ores.
        for (int i = 0; i < oresToMask.size(); i++) {
            long key = oresToMask.getLong(i);
            unpack(key, w);
            level.setBlock(w.immutable(), fillFor(level, w.getY()), Block.UPDATE_SUPPRESS_DROPS | Block.UPDATE_KNOWN_SHAPE);
        }

        // Step 3 — seal small air pockets so no bait cavity remains.
        int sealed = 0;
        for (long key : smallPocketAir) {
            unpack(key, w);
            if (level.getBlockState(w).isAir()) {
                level.setBlock(w.immutable(), fillFor(level, w.getY()), Block.UPDATE_SUPPRESS_DROPS | Block.UPDATE_KNOWN_SHAPE);
                sealed++;
            }
        }

        OrePatchMod.LOGGER.info("[OrePatch] Chunk ({}, {}): masked {} ore(s), sealed {} bait air block(s)",
                chunkX, chunkZ, oresToMask.size(), sealed);
    }

    /**
     * Returns true if any face-neighbouring air block of the ore belongs to a
     * "real" pocket (> MIN_VISIBLE_POCKET connected air blocks). Records every
     * classified air position so small pockets can later be sealed.
     */
    private static boolean oreSeesRealPocket(ClientLevel level, BlockPos orePos,
                                             Long2BooleanOpenHashMap airIsReal,
                                             LongOpenHashSet smallPocketAir,
                                             LongOpenHashSet floodVisited,
                                             LongArrayList floodQueue,
                                             LongArrayList floodPocket,
                                             int scanMinY, int scanMaxY) {
        boolean seesReal = false;
        BlockPos.MutableBlockPos n = new BlockPos.MutableBlockPos();
        for (Direction dir : Direction.values()) {
            n.setWithOffset(orePos, dir);
            if (!level.getBlockState(n).isAir()) continue;

            long nk = n.asLong();
            boolean real;
            if (airIsReal.containsKey(nk)) {
                real = airIsReal.get(nk);
            } else {
                real = classifyPocket(level, nk, airIsReal, smallPocketAir,
                        floodVisited, floodQueue, floodPocket, scanMinY, scanMaxY);
            }
            if (real) seesReal = true;
        }
        return seesReal;
    }

    /**
     * Capped flood-fill from a seed air block (passed as a packed long). Marks
     * every air block in the connected pocket with the pocket's verdict and
     * returns whether the pocket is real. Uses the shared scratch collections,
     * clearing them on entry.
     */
    private static boolean classifyPocket(ClientLevel level, long seedKey,
                                          Long2BooleanOpenHashMap airIsReal,
                                          LongOpenHashSet smallPocketAir,
                                          LongOpenHashSet visited,
                                          LongArrayList queue,
                                          LongArrayList pocket,
                                          int scanMinY, int scanMaxY) {
        visited.clear();
        queue.clear();
        pocket.clear();

        visited.add(seedKey);
        queue.add(seedKey);

        boolean real = false;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos np = new BlockPos.MutableBlockPos();

        int head = 0;
        while (head < queue.size()) {
            long pk = queue.getLong(head++);
            pocket.add(pk);

            if (pocket.size() > MIN_VISIBLE_POCKET) real = true;
            if (pocket.size() >= FLOOD_CAP) { real = true; break; }

            unpack(pk, p);
            for (Direction dir : Direction.values()) {
                np.setWithOffset(p, dir);
                int ny = np.getY();
                if (ny < scanMinY || ny > scanMaxY) continue;
                long nk = np.asLong();
                if (visited.contains(nk)) continue;
                if (!level.getBlockState(np).isAir()) continue;
                visited.add(nk);
                queue.add(nk);
            }
        }

        for (int i = 0; i < pocket.size(); i++) {
            long k = pocket.getLong(i);
            airIsReal.put(k, real);
            if (real) smallPocketAir.remove(k);
            else smallPocketAir.add(k);
        }
        return real;
    }

    /**
     * Re-evaluates a single block after a server block-update packet.
     */
    static void scanSingleBlock(ClientLevel level, int x, int y, int z) {
        BlockPos pos = new BlockPos(x, y, z);
        BlockState current = level.getBlockState(pos);
        if (!isOre(current)) return;

        int levelMinY = level.getMinY();
        int levelMaxY = levelMinY + level.getHeight() - 1;
        int scanMinY = Math.max(SCAN_MIN_Y, levelMinY);
        int scanMaxY = Math.min(SCAN_MAX_Y, levelMaxY);

        Long2BooleanOpenHashMap airIsReal = new Long2BooleanOpenHashMap();
        LongOpenHashSet smallPocketAir = new LongOpenHashSet();
        LongOpenHashSet floodVisited = new LongOpenHashSet();
        LongArrayList floodQueue = new LongArrayList();
        LongArrayList floodPocket = new LongArrayList();

        boolean baitBelowZero = y < 0 && !isDeepslateOre(current);
        if (baitBelowZero) {
            oreSeesRealPocket(level, pos, airIsReal, smallPocketAir,
                    floodVisited, floodQueue, floodPocket, scanMinY, scanMaxY);
        }

        if (baitBelowZero || !oreSeesRealPocket(level, pos, airIsReal, smallPocketAir,
                floodVisited, floodQueue, floodPocket, scanMinY, scanMaxY)) {
            level.setBlock(pos, fillFor(level, y), Block.UPDATE_SUPPRESS_DROPS | Block.UPDATE_KNOWN_SHAPE);
            BlockPos.MutableBlockPos w = new BlockPos.MutableBlockPos();
            for (long key : smallPocketAir) {
                unpack(key, w);
                if (level.getBlockState(w).isAir()) {
                    level.setBlock(w.immutable(), fillFor(level, w.getY()), Block.UPDATE_SUPPRESS_DROPS | Block.UPDATE_KNOWN_SHAPE);
                }
            }
        }
    }

    static boolean isOre(BlockState state) {
        // Ancient Debris and the two Nether ores aren't part of any of the
        // vanilla *_ORES tags (those only cover the stone/deepslate ore
        // pairs), so they're checked directly.
        if (state.is(Blocks.ANCIENT_DEBRIS)) return true;
        if (state.is(Blocks.NETHER_GOLD_ORE)) return true;
        if (state.is(Blocks.NETHER_QUARTZ_ORE)) return true;
        for (TagKey<Block> tag : ORE_TAGS) {
            if (state.is(tag)) return true;
        }
        return false;
    }

    static boolean isDeepslateOre(BlockState state) {
        return state.is(Blocks.DEEPSLATE_COAL_ORE)
                || state.is(Blocks.DEEPSLATE_COPPER_ORE)
                || state.is(Blocks.DEEPSLATE_DIAMOND_ORE)
                || state.is(Blocks.DEEPSLATE_EMERALD_ORE)
                || state.is(Blocks.DEEPSLATE_GOLD_ORE)
                || state.is(Blocks.DEEPSLATE_IRON_ORE)
                || state.is(Blocks.DEEPSLATE_LAPIS_ORE)
                || state.is(Blocks.DEEPSLATE_REDSTONE_ORE);
    }

    private static void unpack(long key, BlockPos.MutableBlockPos out) {
        out.set(BlockPos.getX(key), BlockPos.getY(key), BlockPos.getZ(key));
    }
}
