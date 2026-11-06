package com.solarbiscuit.atmosphera.lib.world;

import com.solarbiscuit.atmosphera.lib.AtmospheraLib;
import com.solarbiscuit.atmosphera.lib.gpu.AtmosGl;
import com.solarbiscuit.atmosphera.lib.gpu.GpuResources;
import com.solarbiscuit.atmosphera.lib.gpu.Uploads;
import it.unimi.dsi.fastutil.ints.IntArrayFIFOQueue;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.system.MemoryUtil;

import java.nio.ShortBuffer;
import java.util.Arrays;

/**
 * Per-column world data in an N x N block window around the camera, on the CPU and in one RGBA16 texture (lib
 * 0.5.0; the R channel is Lumis's sun-shadow heightfield, moved here so Torrente and Gale can use it too):
 * <ul>
 *   <li><b>R, top caster:</b> 0 = none, else {@code (y - minY) * 16 + h}, y the highest sun-casting block of the
 *       column and h its height in sixteenths ({@link CasterTable}). Found by walking down from the WORLD_SURFACE
 *       heightmap past non-casters (at most 64).</li>
 *   <li><b>G, water top:</b> 0 = none, else {@code (y - minY) * 16 + h} for the highest water block met on that walk
 *       above the top caster, h its surface height in sixteenths.</li>
 *   <li><b>B, rain top:</b> {@code (y - minY) * 16} with y the MOTION_BLOCKING heightmap (where rain stops), 0 when
 *       the column is not known.</li>
 *   <li><b>A, flags:</b> bit 0 = the top caster is leaves.</li>
 * </ul>
 * The window is whole chunks, {@code [cameraChunk - T/2, cameraChunk + T/2)} with T = N / 16, stored toroidally (block
 * x at texel {@code x & (N - 1)}); N divides 4096, the wrap of {@code atmos_worldPos}. Each 16 x 16 tile remembers its
 * chunk; when the camera crosses a chunk border, tiles that leave the window are cleared and take the chunk that
 * moved in. Columns are scanned within a time budget per frame; block changes update one column.
 * <p>
 * <b>Use:</b> a consumer calls {@link #use} every frame it needs the field, with the window size it wants; the field
 * follows the largest size asked for in the last frame and is freed one frame after nobody asks. Readers outside the
 * window get "no data" (0), so nothing reads across the toroidal seam. Render thread only.
 */
public final class ColumnField {
    private static final long EMPTY = Long.MIN_VALUE;
    private static final byte MISSING = 0;
    private static final byte QUEUED = 1;
    private static final byte SCANNED = 2;
    /** Non-casters walked past before a column counts as empty (deep water, glass towers). */
    private static final int MAX_WALK = 64;
    private static final long SCAN_BUDGET_NS = 150_000L;
    private static final int MAX_TILE_UPLOADS = 128;
    public static final int FLAG_LEAVES = 1;

    private static int n;
    private static int tiles;
    private static int minY;
    private static short[] caster;
    private static short[] water;
    private static short[] rain;
    private static short[] flags;
    private static long[] slotChunk;
    private static byte[] slotState;
    private static boolean[] slotDirty;
    private static final IntArrayList DIRTY = new IntArrayList();
    private static final IntArrayFIFOQueue QUEUE = new IntArrayFIFOQueue();
    private static boolean fullUpload;
    private static int texture;
    private static ShortBuffer staging;
    private static ShortBuffer fullStaging;
    private static int centreChunkX;
    private static int centreChunkZ;
    private static boolean centred;
    private static float maxTop = -1e9f;
    private static long uploads;
    private static int generation;
    private static ClientLevel level;
    private static final BlockPos.MutableBlockPos POS = new BlockPos.MutableBlockPos();

    /** Consumers (by identity), the size each asked for and the frame it last asked; arrays, so nothing allocates. */
    private static final int MAX_USERS = 8;
    private static final Object[] USER = new Object[MAX_USERS];
    private static final int[] USER_SIZE = new int[MAX_USERS];
    private static final long[] USER_FRAME = new long[MAX_USERS];
    private static int users;
    private static long updatedFrame = -1;

    private ColumnField() {
    }

    // ---- consumers ----------------------------------------------------------------------------------------------

    /**
     * Keeps the field running this frame with at least a {@code size} window (256, 512 or 1024) and brings it up to
     * date: allocation, the window following the camera, the scan budget and the tile uploads (once per frame, however
     * many consumers call). Call before reading it each frame. Render thread.
     */
    public static void use(Object owner, ClientLevel current, int size) {
        long frame = com.solarbiscuit.atmosphera.lib.client.AtmosClient.frame();
        int slot = -1;
        for (int i = 0; i < users; i++) {
            if (USER[i] == owner) {
                slot = i;
                break;
            }
        }
        if (slot < 0) {
            if (users == MAX_USERS) {
                return;
            }
            slot = users++;
            USER[slot] = owner;
        }
        USER_SIZE[slot] = size;
        USER_FRAME[slot] = frame;
        int wanted = size;
        for (int i = 0; i < users; i++) {
            if (USER_FRAME[i] >= frame - 1) {
                wanted = Math.max(wanted, USER_SIZE[i]);
            }
        }
        ensure(current, wanted);
        if (updatedFrame != frame) {
            updatedFrame = frame;
            var camera = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
            update(Mth.floor(camera.x) >> 4, Mth.floor(camera.z) >> 4);
        }
    }

    /** The owner no longer needs the field; it is freed when nobody does. */
    public static void stop(Object owner) {
        for (int i = 0; i < users; i++) {
            if (USER[i] == owner) {
                remove(i);
                break;
            }
        }
        if (users == 0) {
            release();
        }
    }

    private static void remove(int i) {
        users--;
        USER[i] = USER[users];
        USER_SIZE[i] = USER_SIZE[users];
        USER_FRAME[i] = USER_FRAME[users];
        USER[users] = null;
    }

    /** Per frame (lib): frees the field when no consumer asked for it in the last two frames. */
    public static void frame(long frame) {
        if (n == 0) {
            return;
        }
        for (int i = users - 1; i >= 0; i--) {
            if (USER_FRAME[i] < frame - 2) {
                remove(i);
            }
        }
        if (users == 0) {
            release();
        }
    }

    public static boolean ready() {
        return caster != null && centred;
    }

    /** Window size in blocks (0 while off). */
    public static int size() {
        return n;
    }

    public static int minY() {
        return minY;
    }

    public static int centreChunkX() {
        return centreChunkX;
    }

    public static int centreChunkZ() {
        return centreChunkZ;
    }

    /** The RGBA16 texture (0 while off): texel (x & (N-1), z & (N-1)) = (caster, water, rain, flags) / 65535. */
    public static int texture() {
        return texture;
    }

    /** Highest caster top seen since the last reset (world y), an upper bound for CPU marches. */
    public static float maxTop() {
        return maxTop;
    }

    /** Counts tile uploads; a consumer that derives data from the texture compares it with the count it last used. */
    public static long uploadCount() {
        return uploads;
    }

    /** Changes when the field is reset (new level, new size): derived data must be rebuilt. */
    public static int generation() {
        return generation;
    }

    /** Raw top caster at a world column, 0 outside the window or unknown. */
    public static int casterRaw(int x, int z) {
        return inWindow(x, z) ? caster[index(x, z)] & 0xFFFF : 0;
    }

    /** Raw water top at a world column, 0 when none, outside the window or unknown. */
    public static int waterRaw(int x, int z) {
        return inWindow(x, z) ? water[index(x, z)] & 0xFFFF : 0;
    }

    /** Raw rain top at a world column, 0 outside the window or unknown. */
    public static int rainRaw(int x, int z) {
        return inWindow(x, z) ? rain[index(x, z)] & 0xFFFF : 0;
    }

    public static int flags(int x, int z) {
        return inWindow(x, z) ? flags[index(x, z)] & 0xFFFF : 0;
    }

    /** World y of a raw value (caster or water top: block y plus its height). */
    public static float top(int raw) {
        return minY + raw / 16f;
    }

    private static boolean inWindow(int x, int z) {
        if (caster == null) {
            return false;
        }
        int half = tiles / 2;
        int rx = (x >> 4) - centreChunkX;
        int rz = (z >> 4) - centreChunkZ;
        return rx >= -half && rx < half && rz >= -half && rz < half;
    }

    // ---- lifecycle ------------------------------------------------------------------------------------------------

    private static void ensure(ClientLevel current, int size) {
        if (caster == null || n != size) {
            release();
            n = size;
            tiles = size / 16;
            caster = new short[size * size];
            water = new short[size * size];
            rain = new short[size * size];
            flags = new short[size * size];
            slotChunk = new long[tiles * tiles];
            slotState = new byte[tiles * tiles];
            slotDirty = new boolean[tiles * tiles];
            Arrays.fill(slotChunk, EMPTY);
            staging = MemoryUtil.memAllocShort(256 * 4);
            createTexture();
            level = null;
            AtmospheraLib.LOGGER.info("Column field on: {}x{} blocks ({} KB texture).", size, size, size * size * 8 / 1024);
        }
        if (current != level) {
            level = current;
            minY = current.getMinBuildHeight();
            verifyLeft = 1024;
            Arrays.fill(caster, (short) 0);
            Arrays.fill(water, (short) 0);
            Arrays.fill(rain, (short) 0);
            Arrays.fill(flags, (short) 0);
            Arrays.fill(slotChunk, EMPTY);
            Arrays.fill(slotState, MISSING);
            Arrays.fill(slotDirty, false);
            DIRTY.clear();
            QUEUE.clear();
            centred = false;
            maxTop = -1e9f;
            fullUpload = true;
            generation++;
        }
    }

    public static void release() {
        if (texture != 0) {
            GpuResources.deleteTexture(texture);
            texture = 0;
            AtmospheraLib.LOGGER.info("Column field off.");
        }
        if (staging != null) {
            MemoryUtil.memFree(staging);
            staging = null;
        }
        if (fullStaging != null) {
            MemoryUtil.memFree(fullStaging);
            fullStaging = null;
        }
        caster = water = rain = flags = null;
        slotChunk = null;
        slotState = null;
        slotDirty = null;
        DIRTY.clear();
        QUEUE.clear();
        centred = false;
        level = null;
        n = 0;
        generation++;
    }

    private static void update(int cameraChunkX, int cameraChunkZ) {
        if (!centred || cameraChunkX != centreChunkX || cameraChunkZ != centreChunkZ) {
            recentre(cameraChunkX, cameraChunkZ);
        }
        long start = System.nanoTime();
        while (!QUEUE.isEmpty() && System.nanoTime() - start < SCAN_BUDGET_NS) {
            scanSlot(QUEUE.dequeueInt());
        }
        upload();
    }

    private static void recentre(int ccx, int ccz) {
        int changed = 0;
        int baseX = ccx - tiles / 2;
        int baseZ = ccz - tiles / 2;
        int mask = tiles - 1;
        for (int sz = 0; sz < tiles; sz++) {
            int cz = baseZ + ((sz - baseZ) & mask);
            for (int sx = 0; sx < tiles; sx++) {
                int cx = baseX + ((sx - baseX) & mask);
                int slot = sz * tiles + sx;
                long key = ChunkPos.asLong(cx, cz);
                if (slotChunk[slot] == key) {
                    continue;
                }
                slotChunk[slot] = key;
                slotState[slot] = MISSING;
                clearTile(slot);
                changed++;
                if (level.getChunkSource().getChunk(cx, cz, false) != null) {
                    enqueue(slot);
                }
            }
        }
        centreChunkX = ccx;
        centreChunkZ = ccz;
        centred = true;
        if (changed > tiles * tiles / 4) {
            fullUpload = true;
        }
    }

    // ---- world events (lib wiring) --------------------------------------------------------------------------------

    /** A chunk arrived (or was resent) on the client. */
    public static void onChunkLoad(LevelChunk chunk) {
        if (caster == null || chunk.getLevel() != level) {
            return;
        }
        ChunkPos pos = chunk.getPos();
        int slot = slotOf(pos.x, pos.z);
        if (slotChunk[slot] == pos.toLong()) {
            enqueue(slot);
        }
    }

    public static void onChunkUnload(LevelChunk chunk) {
        if (caster == null || chunk.getLevel() != level) {
            return;
        }
        ChunkPos pos = chunk.getPos();
        int slot = slotOf(pos.x, pos.z);
        if (slotChunk[slot] == pos.toLong()) {
            slotState[slot] = MISSING;
            clearTile(slot);
        }
    }

    /**
     * A block changed on the client (already set). The top caster usually needs no walk (O(1)); water, rain and flags
     * of the column are refreshed with it.
     */
    public static void onBlockChanged(BlockPos pos, BlockState state) {
        if (caster == null) {
            return;
        }
        int x = pos.getX();
        int z = pos.getZ();
        int slot = slotOf(x >> 4, z >> 4);
        if (slotChunk[slot] != ChunkPos.asLong(x >> 4, z >> 4) || slotState[slot] != SCANNED) {
            return;
        }
        LevelChunk chunk = level.getChunkSource().getChunk(x >> 4, z >> 4, false);
        if (chunk == null) {
            return;
        }
        int index = index(x, z);
        int oldCaster = caster[index] & 0xFFFF;
        int oldWater = water[index] & 0xFFFF;
        int oldRain = rain[index] & 0xFFFF;
        int oldFlags = flags[index] & 0xFFFF;
        int topY = oldCaster == 0 ? Integer.MIN_VALUE : minY + (oldCaster - 1) / 16;
        int y = pos.getY();
        int kind = CasterTable.of(state);
        boolean fluid = !state.getFluidState().isEmpty();
        if (kind != CasterTable.NONE && y >= topY && !fluid && oldWater == 0) {
            // A new caster on top (the common case: building): no walk needed.
            caster[index] = (short) encode(y, kind);
            flags[index] = (short) (state.getBlock() instanceof LeavesBlock ? FLAG_LEAVES : 0);
        } else if (y >= topY || fluid || oldWater != 0) {
            scanColumnInto(chunk, x, z, chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x & 15, z & 15), index);
        }
        rain[index] = (short) rainOf(chunk, x, z);
        if ((caster[index] & 0xFFFF) != oldCaster || (water[index] & 0xFFFF) != oldWater
                || (rain[index] & 0xFFFF) != oldRain || (flags[index] & 0xFFFF) != oldFlags) {
            noteTop(caster[index] & 0xFFFF);
            markDirty(slot);
        }
    }

    // ---- scanning -------------------------------------------------------------------------------------------------

    private static int index(int x, int z) {
        return (z & (n - 1)) * n + (x & (n - 1));
    }

    private static int slotOf(int cx, int cz) {
        return (cz & (tiles - 1)) * tiles + (cx & (tiles - 1));
    }

    private static int encode(int y, int height) {
        return Math.max(1, Math.min(0xFFFF, (y - minY) * 16 + height));
    }

    private static void enqueue(int slot) {
        if (slotState[slot] != QUEUED) {
            slotState[slot] = QUEUED;
            QUEUE.enqueue(slot);
        }
    }

    private static void scanSlot(int slot) {
        if (slotState[slot] != QUEUED) {
            return;
        }
        long key = slotChunk[slot];
        LevelChunk chunk = level.getChunkSource().getChunk(ChunkPos.getX(key), ChunkPos.getZ(key), false);
        if (chunk == null) {
            slotState[slot] = MISSING;
            return;
        }
        int x0 = ChunkPos.getX(key) << 4;
        int z0 = ChunkPos.getZ(key) << 4;
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                int index = index(x0 + lx, z0 + lz);
                scanColumnInto(chunk, x0 + lx, z0 + lz, chunk.getHeight(Heightmap.Types.WORLD_SURFACE, lx, lz), index);
                rain[index] = (short) rainOf(chunk, x0 + lx, z0 + lz);
                noteTop(caster[index] & 0xFFFF);
            }
        }
        slotState[slot] = SCANNED;
        markDirty(slot);
    }

    private static int rainOf(LevelChunk chunk, int x, int z) {
        // MOTION_BLOCKING: the first free y above the highest block that stops rain (or holds water).
        int y = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, x & 15, z & 15) + 1;
        return Math.max(1, Math.min(0xFFFF, (y - minY) * 16));
    }

    private static final SectionReader READER = new SectionReader();
    /** Column walks still compared with the getBlockState path this session (reset when the window is set up). */
    private static int verifyLeft = 1024;
    private static int walkCaster;
    private static int walkWater;
    private static int walkFlags;

    /**
     * Walks a column from {@code fromY} down to its top caster (at most {@link #MAX_WALK} non-casters) and stores
     * caster, water top and flags at {@code index}. Reads the sections' palettes directly ({@link SectionReader},
     * all-air sections skipped whole); the first walks of a session are checked against {@code getBlockState}.
     */
    private static void scanColumnInto(LevelChunk chunk, int x, int z, int fromY, int index) {
        if (SectionReader.usable()) {
            walkDirect(chunk, x, z, fromY);
            if (verifyLeft > 0) {
                verifyLeft--;
                int c = walkCaster;
                int w = walkWater;
                int f = walkFlags;
                walkStates(chunk, x, z, fromY);
                if (c != walkCaster || w != walkWater || f != walkFlags) {
                    SectionReader.disable("column field walk " + x + " " + z + " differed from getBlockState");
                }
            }
        } else {
            walkStates(chunk, x, z, fromY);
        }
        caster[index] = (short) walkCaster;
        water[index] = (short) walkWater;
        flags[index] = (short) walkFlags;
    }

    private static void visit(BlockState state, int y) {
        // Water above the caster: the highest water block met is the surface.
        if (walkWater == 0) {
            FluidState fluid = state.getFluidState();
            if (!fluid.isEmpty() && fluid.is(FluidTags.WATER)) {
                walkWater = encode(y, Math.max(1, Math.min(16, Math.round(fluid.getOwnHeight() * 16f))));
            }
        }
    }

    private static void walkDirect(LevelChunk chunk, int x, int z, int fromY) {
        walkCaster = walkWater = walkFlags = 0;
        int bottom = Math.max(minY, fromY - MAX_WALK);
        LevelChunkSection[] sections = chunk.getSections();
        int lx = x & 15;
        int lz = z & 15;
        int y = fromY;
        while (y >= bottom) {
            int stop = Math.max(bottom, y & ~15);
            int sectionIndex = chunk.getSectionIndex(y);
            LevelChunkSection section = sectionIndex >= 0 && sectionIndex < sections.length ? sections[sectionIndex] : null;
            if (section == null || section.hasOnlyAir()) {
                y = stop - 1;
                continue;
            }
            boolean direct = READER.bind(section);
            for (; y >= stop; y--) {
                BlockState state = direct ? READER.get(lx, y & 15, lz) : section.getBlockState(lx, y & 15, lz);
                int kind = CasterTable.of(state);
                if (kind != CasterTable.NONE) {
                    walkCaster = encode(y, kind);
                    walkFlags = state.getBlock() instanceof LeavesBlock ? FLAG_LEAVES : 0;
                    return;
                }
                visit(state, y);
            }
        }
    }

    /** The getBlockState path: fallback and self-check. */
    private static void walkStates(LevelChunk chunk, int x, int z, int fromY) {
        walkCaster = walkWater = walkFlags = 0;
        int bottom = Math.max(minY, fromY - MAX_WALK);
        for (int y = fromY; y >= bottom; y--) {
            BlockState state = chunk.getBlockState(POS.set(x, y, z));
            int kind = CasterTable.of(state);
            if (kind != CasterTable.NONE) {
                walkCaster = encode(y, kind);
                walkFlags = state.getBlock() instanceof LeavesBlock ? FLAG_LEAVES : 0;
                return;
            }
            visit(state, y);
        }
    }

    private static void noteTop(int raw) {
        if (raw != 0) {
            maxTop = Math.max(maxTop, top(raw));
        }
    }

    private static void clearTile(int slot) {
        int sx = slot % tiles;
        int sz = slot / tiles;
        for (int lz = 0; lz < 16; lz++) {
            int row = (sz * 16 + lz) * n + sx * 16;
            Arrays.fill(caster, row, row + 16, (short) 0);
            Arrays.fill(water, row, row + 16, (short) 0);
            Arrays.fill(rain, row, row + 16, (short) 0);
            Arrays.fill(flags, row, row + 16, (short) 0);
        }
        markDirty(slot);
    }

    private static void markDirty(int slot) {
        if (!slotDirty[slot]) {
            slotDirty[slot] = true;
            DIRTY.add(slot);
        }
    }

    // ---- GPU ------------------------------------------------------------------------------------------------------

    private static void createTexture() {
        texture = GpuResources.texture("Atmosphera column field");
        int previousUnit = AtmosGl.getInteger(GL13C.GL_ACTIVE_TEXTURE);
        GL13C.glActiveTexture(GL13C.GL_TEXTURE15);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, texture);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_NEAREST);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_NEAREST);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_S, GL11C.GL_REPEAT);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_T, GL11C.GL_REPEAT);
        GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, GL11C.GL_RGBA16, n, n, 0, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_SHORT,
                (ShortBuffer) null);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, 0);
        GL13C.glActiveTexture(previousUnit);
        GpuResources.textureBytes(texture, (long) n * n * 8);
        fullUpload = true;
    }

    private static void upload() {
        if (!fullUpload && DIRTY.isEmpty()) {
            return;
        }
        Uploads.begin(2);
        if (fullUpload) {
            fullUpload = false;
            for (int i = 0; i < DIRTY.size(); i++) {
                slotDirty[DIRTY.getInt(i)] = false;
            }
            DIRTY.clear();
            if (fullStaging == null) {
                fullStaging = MemoryUtil.memAllocShort(n * n * 4);
            }
            fullStaging.clear();
            for (int i = 0; i < n * n; i++) {
                fullStaging.put(caster[i]).put(water[i]).put(rain[i]).put(flags[i]);
            }
            fullStaging.flip();
            AtmosGl.texSubImage2D(15, texture, 0, 0, n, n, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_SHORT, fullStaging);
        } else {
            int count = Math.min(DIRTY.size(), MAX_TILE_UPLOADS);
            for (int i = 0; i < count; i++) {
                int slot = DIRTY.getInt(DIRTY.size() - 1 - i);
                slotDirty[slot] = false;
                int sx = slot % tiles;
                int sz = slot / tiles;
                staging.clear();
                for (int lz = 0; lz < 16; lz++) {
                    int row = (sz * 16 + lz) * n + sx * 16;
                    for (int lx = 0; lx < 16; lx++) {
                        int k = row + lx;
                        staging.put(caster[k]).put(water[k]).put(rain[k]).put(flags[k]);
                    }
                }
                staging.flip();
                AtmosGl.texSubImage2D(15, texture, sx * 16, sz * 16, 16, 16, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_SHORT, staging);
            }
            DIRTY.size(DIRTY.size() - count);
        }
        uploads++;
        Uploads.end();
    }

    public static String describe() {
        if (n == 0) {
            return "column field off";
        }
        return "column field " + n + "x" + n + " (" + users + " users, " + QUEUE.size() + " chunks queued)";
    }
}
