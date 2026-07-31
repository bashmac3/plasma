package bm3.plasma;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class ChunkCorruptor implements Runnable {
    @Override
    public void run() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player == null || mc.level == null) {
            System.out.println("ChunkCorruptor: no client, player, or level available");
            return;
        }

        CountDownLatch latch = new CountDownLatch(1);
        mc.execute(() -> {
            try {
                corrupt(mc);
            } finally {
                latch.countDown();
            }
        });

        try {
            if (!latch.await(60, TimeUnit.SECONDS)) {
                System.out.println("ChunkCorruptor: timed out waiting for main thread");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void corrupt(Minecraft mc) {
        Level level = mc.level;
        BlockPos pos = mc.player.blockPosition();
        int chunkX = Math.floorDiv(pos.getX(), 16);
        int chunkZ = Math.floorDiv(pos.getZ(), 16);
        int originX = chunkX * 16;
        int originZ = chunkZ * 16;
        int minY = level.getMinY();
        int maxY = level.getMaxY();

        RandomSource random = RandomSource.create();
        int count = 0;
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = minY; y < maxY; y++) {
                    BlockPos blockPos = new BlockPos(originX + x, y, originZ + z);
                    level.setBlock(blockPos, randomBlockState(random), Block.UPDATE_NEIGHBORS | Block.UPDATE_CLIENTS, 0);
                    count++;
                }
            }
        }
        System.out.println("ChunkCorruptor: filled chunk (" + chunkX + ", " + chunkZ + ") with "
            + count + " random blocks");
    }

    private BlockState randomBlockState(RandomSource random) {
        return BuiltInRegistries.BLOCK.getRandom(random)
            .map(holder -> holder.value().defaultBlockState())
            .orElse(Blocks.STONE.defaultBlockState());
    }
}
