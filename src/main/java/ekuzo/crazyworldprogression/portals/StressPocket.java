package ekuzo.crazyworldprogression.portals;

import ekuzo.crazyworldprogression.CrazyWorldProgression;
import ekuzo.crazyworldprogression.cleanup.TickBudget;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Test tool: fills a pocket's editor dimension with a large grid of rooms (walls, doorways, torches, glowstone,
 * chests with items, water and lava pools, pillars) and then saves it as the pocket template. It runs within the
 * instance-creation share of the tick budget, like real instance building.
 */
public final class StressPocket {
    private static final BlockPos ORIGIN = new BlockPos(0, 64, 0);
    private static final Map<MinecraftServer, Job> JOBS = new WeakHashMap<>();

    private StressPocket() { }

    private static final class Job {
        final Identifier id;
        final ServerLevel level;
        final int sizeX, sizeY, sizeZ;
        final long volume;
        final UUID requester;
        final FastBlockWriter writer;
        final long startedNanos = System.nanoTime();
        /** Positions handled so far; generation goes chunk column by chunk column, bottom-up in each. */
        long next;
        int column;
        long inner;
        long solid;

        Job(Identifier id, ServerLevel level, int sizeX, int sizeY, int sizeZ, UUID requester) {
            this.id = id; this.level = level; this.sizeX = sizeX; this.sizeY = sizeY; this.sizeZ = sizeZ;
            this.volume = (long) sizeX * sizeY * sizeZ; this.requester = requester;
            this.writer = new FastBlockWriter(level);
        }
    }

    public static void initialize() {
        ServerTickEvents.END_SERVER_TICK.register(StressPocket::tick);
        ServerLifecycleEvents.SERVER_STOPPED.register(JOBS::remove);
    }

    public static void start(ServerPlayer player, Identifier id, int sizeX, int sizeY, int sizeZ) {
        MinecraftServer server = player.level().getServer();
        if (JOBS.containsKey(server)) throw new IllegalStateException("A test pocket is already being generated");
        if (sizeX < 16 || sizeZ < 16 || sizeY < 6) throw new IllegalArgumentException("Use at least 16 x 6 x 16");
        Identifier dimension = PocketEditorService.exists(server, id) ? PocketEditorService.dimension(server, id)
                : PocketEditorService.create(server, id);
        if (dimension == null) dimension = PocketEditorService.ensureEditor(server, id);
        ServerLevel level = server.getLevel(net.minecraft.resources.ResourceKey.create(
                net.minecraft.core.registries.Registries.DIMENSION, dimension));
        if (level == null) throw new IllegalStateException("Pocket editor dimension is not loaded: " + dimension);
        if (ORIGIN.getY() + sizeY > level.getMinY() + level.getLogicalHeight())
            throw new IllegalArgumentException("Height " + sizeY + " does not fit the pocket dimension");
        JOBS.put(server, new Job(id, level, sizeX, sizeY, sizeZ, player.getUUID()));
        player.sendSystemMessage(Component.literal("Generating test pocket " + id + " (" + sizeX + " x " + sizeY + " x "
                + sizeZ + " = " + String.format("%,d", (long) sizeX * sizeY * sizeZ) + " blocks)…"));
    }

    private static void tick(MinecraftServer server) {
        Job job = JOBS.get(server);
        if (job == null) return;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        try (TickBudget.Slice slice = TickBudget.start(server, TickBudget.Priority.CREATION)) {
            int done = 0;
            int columnsX = (job.sizeX + 15) / 16;
            while (job.next < job.volume && slice.more(done++)) {
                int baseX = job.column % columnsX * 16, baseZ = job.column / columnsX * 16;
                int width = Math.min(16, job.sizeX - baseX), depth = Math.min(16, job.sizeZ - baseZ);
                int y = (int) (job.inner / (width * depth));
                int within = (int) (job.inner % (width * depth));
                int z = baseZ + within / width, x = baseX + within % width;
                job.next++;
                if (++job.inner >= (long) width * depth * job.sizeY) {
                    job.inner = 0;
                    job.column++;
                }
                BlockState state = pattern(x, y, z, job.sizeY);
                if (state.isAir()) continue;
                job.solid++;
                CompoundTag nbt = state.is(Blocks.CHEST) ? chestLoot(x, z) : null;
                job.writer.place(ORIGIN.getX() + x, ORIGIN.getY() + y, ORIGIN.getZ() + z, state, nbt);
                if (x % 16 == 0 && z % 16 == 0 && y == 0)
                    PocketEditorService.noteBlockChange(job.level, cursor.set(ORIGIN.getX() + x, ORIGIN.getY(), ORIGIN.getZ() + z));
            }
        }
        ServerPlayer requester = server.getPlayerList().getPlayer(job.requester);
        if (job.next < job.volume) {
            if (requester != null && server.getTickCount() % 20 == 0)
                requester.sendOverlayMessage(Component.literal("Generating test pocket… " + (job.next * 100 / job.volume) + "%"));
            return;
        }
        JOBS.remove(server);
        job.writer.finish();
        for (int cx = ORIGIN.getX() >> 4; cx <= (ORIGIN.getX() + job.sizeX - 1) >> 4; cx++)
            for (int cz = ORIGIN.getZ() >> 4; cz <= (ORIGIN.getZ() + job.sizeZ - 1) >> 4; cz++)
                PocketEditorService.noteBlockChange(job.level, new BlockPos(cx << 4, ORIGIN.getY(), cz << 4));
        double generateSeconds = (System.nanoTime() - job.startedNanos) / 1e9;
        long captureStart = System.nanoTime();
        try {
            PortalTemplateService.captureAll(server, job.level, job.id, ORIGIN.offset(8, 1, 8), "stress test");
            double captureSeconds = (System.nanoTime() - captureStart) / 1e9;
            String message = String.format("Test pocket %s saved: %,d blocks of volume, %,d non-air. Generation %.1f s "
                    + "(spread over ticks), saving %.1f s (one step). Link a portal to it to test instance building.",
                    job.id, job.volume, job.solid, generateSeconds, captureSeconds);
            CrazyWorldProgression.LOGGER.info(message);
            if (requester != null) requester.sendSystemMessage(Component.literal(message));
        } catch (RuntimeException exception) {
            CrazyWorldProgression.LOGGER.error("Saving test pocket {} failed", job.id, exception);
            if (requester != null) requester.sendSystemMessage(Component.literal("Saving the test pocket failed: "
                    + exception.getMessage()));
        }
    }

    /** Rooms of 16 x 16: floor, walls with doorways, ceiling with skylights, and per-room contents. */
    static BlockState pattern(int x, int y, int z, int height) {
        int roomX = x / 16, roomZ = z / 16, lx = x % 16, lz = z % 16;
        int kind = Math.floorMod(roomX * 31 + roomZ * 17, 6);
        if (y == 0) return (lx + lz) % 7 == 0 ? Blocks.POLISHED_ANDESITE.defaultBlockState() : Blocks.STONE.defaultBlockState();
        if (y == height - 1) {
            boolean skylight = kind == 0 && lx >= 6 && lx <= 9 && lz >= 6 && lz <= 9;
            return skylight ? Blocks.GLASS.defaultBlockState() : Blocks.DEEPSLATE_TILES.defaultBlockState();
        }
        boolean wall = lx == 0 || lz == 0;
        boolean doorway = (lx >= 6 && lx <= 9 || lz >= 6 && lz <= 9) && y <= 3;
        if (wall) return doorway ? Blocks.AIR.defaultBlockState() : Blocks.STONE_BRICKS.defaultBlockState();
        if (y == height - 2 && kind == 1 && lx % 5 == 2 && lz % 5 == 2) return Blocks.GLOWSTONE.defaultBlockState();
        if (y == 1 && lx == 8 && lz == 8 && kind != 2 && kind != 3) return Blocks.TORCH.defaultBlockState();
        if (y == 1 && lx == 3 && lz == 3 && kind == 4)
            return Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.SOUTH);
        if (kind == 2 && y <= 2 && lx >= 5 && lx <= 10 && lz >= 5 && lz <= 10) return Blocks.WATER.defaultBlockState();
        if (kind == 3 && y == 1 && lx >= 6 && lx <= 9 && lz >= 6 && lz <= 9) return Blocks.LAVA.defaultBlockState();
        if (kind == 5 && lx % 4 == 2 && lz % 4 == 2) return Blocks.POLISHED_DEEPSLATE.defaultBlockState();
        return Blocks.AIR.defaultBlockState();
    }

    private static CompoundTag chestLoot(int x, int z) {
        CompoundTag tag = new CompoundTag();
        ListTag items = new ListTag();
        String[] ids = {"minecraft:diamond", "minecraft:iron_ingot", "minecraft:bread", "minecraft:arrow"};
        for (int slot = 0; slot < ids.length; slot++) {
            CompoundTag item = new CompoundTag();
            item.putByte("Slot", (byte) (slot * 2));
            item.putString("id", ids[slot]);
            item.putInt("count", 1 + Math.floorMod(x + z + slot, 16));
            items.add(item);
        }
        tag.put("Items", items);
        return tag;
    }
}
