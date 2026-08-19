package me.dagxam.treefall;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

public final class TreeAnimator {

    private TreeAnimator() {}

    public static void play(TreeFallPlugin plugin,
                            World world,
                            Location center,
                            TreeBlocks falling,
                            TreeDropCalculator.DropResult drops,
                            Player player,
                            int toolSlot,
                            ItemStack toolSnapshot,
                            String treeKey) {

        Settings settings = plugin.settings;
        Random random = plugin.random;

        List<Block> allBlocks = new ArrayList<>(falling.logs().size() + falling.leaves().size());
        allBlocks.addAll(falling.logs());
        allBlocks.addAll(falling.leaves());

        double spread = falling.leaves().size() >= Settings.BIG_TREE_LEAVES ? 3.5 : 1.8;
        int maxAnimated = Math.min(allBlocks.size(), settings.maxFallingBlocks);

        List<Block> animated = new ArrayList<>(maxAnimated);
        List<Block> logs = new ArrayList<>(falling.logs());
        List<Block> leaves = new ArrayList<>(falling.leaves());
        logs.sort((a, b) -> Integer.compare(b.getY(), a.getY()));
        leaves.sort((a, b) -> Integer.compare(b.getY(), a.getY()));

        for (Block block : logs) {
            if (animated.size() >= maxAnimated) break;
            animated.add(block);
        }
        for (Block block : leaves) {
            if (animated.size() >= maxAnimated) break;
            animated.add(block);
        }

        Set<Block> animatedSet = new HashSet<>(animated);
        for (Block block : allBlocks) {
            if (!animatedSet.contains(block)) {
                block.setType(Material.AIR, false);
            }
        }

        world.playSound(center, Sound.BLOCK_WOOD_BREAK, 1.2f, 0.6f);

        new BukkitRunnable() {
            private int index;
            private long ticks;
            private boolean rewardsGiven;
            private long cleanupAt = -1L;
            private final List<FallingBlock> entities = new ArrayList<>();

            @Override
            public void run() {
                try {
                    ticks++;

                    int batch = settings.animBlocksPerTick;
                    int processed = 0;

                    while (index < animated.size() && processed < batch) {
                        Block block = animated.get(index++);
                        Material type = block.getType();
                        if (type == Material.AIR) {
                            processed++;
                            continue;
                        }

                        BlockData data = block.getBlockData();
                        Location spawnLocation = block.getLocation().add(0.5, 0.2, 0.5);
                        block.setType(Material.AIR, false);

                        FallingBlock entity = world.spawnFallingBlock(spawnLocation, data);
                        entity.setDropItem(false);
                        entity.setHurtEntities(false);
                        entity.addScoreboardTag(TreeFallPlugin.FALLING_TAG);

                        double vx = (random.nextDouble() - 0.5) * 0.08;
                        double vz = (random.nextDouble() - 0.5) * 0.08;
                        entity.setVelocity(entity.getVelocity().setX(vx).setZ(vz));
                        entities.add(entity);
                        processed++;
                    }

                    if (random.nextInt(3) == 0 && index < animated.size()) {
                        world.playSound(center, Sound.BLOCK_WOOD_BREAK, 0.6f, 1.0f);
                    }

                    if (index >= animated.size() && cleanupAt < 0L) {
                        if (!rewardsGiven) {
                            giveRewards(plugin, world, center, drops, player, toolSlot, toolSnapshot,
                                    falling, spread, random);
                            rewardsGiven = true;
                            world.playSound(center, Sound.BLOCK_WOOD_BREAK, 1.0f, 0.8f);
                        }
                        cleanupAt = ticks + settings.animationTimeoutTicks;
                    }

                    if (ticks >= settings.animationTimeoutTicks && !rewardsGiven) {
                        removeRemainingBlocks(animated, index);
                        giveRewards(plugin, world, center, drops, player, toolSlot, toolSnapshot,
                                falling, spread, random);
                        rewardsGiven = true;
                        cleanupAt = ticks + 1L;
                    }

                    if (cleanupAt >= 0L && ticks >= cleanupAt) {
                        cleanupEntities();
                        plugin.releaseTree(treeKey);
                        cancel();
                    }
                } catch (Throwable throwable) {
                    removeRemainingBlocks(animated, index);
                    cleanupEntities();
                    if (!rewardsGiven) {
                        giveRewards(plugin, world, center, drops, player, toolSlot, toolSnapshot,
                                falling, spread, random);
                    }
                    plugin.releaseTree(treeKey);
                    plugin.getLogger().warning("TreeFall animation recovered from an error: "
                            + throwable.getClass().getSimpleName() + ": " + throwable.getMessage());
                    cancel();
                }
            }

            private void cleanupEntities() {
                for (FallingBlock entity : entities) {
                    if (entity.isValid()) entity.remove();
                }
                entities.clear();
            }
        }.runTaskTimer(plugin, 0L, settings.animTickDelay);
    }

    private static void removeRemainingBlocks(List<Block> blocks, int fromIndex) {
        for (int i = Math.max(0, fromIndex); i < blocks.size(); i++) {
            Block block = blocks.get(i);
            if (block.getType() != Material.AIR) block.setType(Material.AIR, false);
        }
    }

    private static void giveRewards(TreeFallPlugin plugin,
                                    World world,
                                    Location center,
                                    TreeDropCalculator.DropResult drops,
                                    Player player,
                                    int toolSlot,
                                    ItemStack toolSnapshot,
                                    TreeBlocks falling,
                                    double spread,
                                    Random random) {
        dropScattered(world, center, drops.leafDrops(), spread, random);
        dropScattered(world, center, drops.logDrops(), spread, random);

        if (drops.sticks() > 0) {
            scatterItem(world, center, new ItemStack(Material.STICK, drops.sticks()), spread, random);
        }
        if (drops.saplingType() != null && drops.saplings() > 0) {
            scatterItem(world, center, new ItemStack(drops.saplingType(), drops.saplings()), spread, random);
        }
        if (drops.apples() > 0) {
            scatterItem(world, center, new ItemStack(Material.APPLE, drops.apples()), spread, random);
        }

        if (plugin.settings.damageTool) {
            ToolDamageHandler.damageTool(player, toolSlot, toolSnapshot, falling.logs().size(), random);
        }
    }

    private static void dropScattered(World world,
                                      Location center,
                                      java.util.Map<Material, Integer> items,
                                      double radius,
                                      Random random) {
        for (var entry : items.entrySet()) {
            int left = entry.getValue();
            while (left > 0) {
                int amount = Math.min(64, left);
                scatterItem(world, center, new ItemStack(entry.getKey(), amount), radius, random);
                left -= amount;
            }
        }
    }

    private static void scatterItem(World world,
                                    Location center,
                                    ItemStack item,
                                    double radius,
                                    Random random) {
        double dx = (random.nextDouble() - 0.5) * radius;
        double dz = (random.nextDouble() - 0.5) * radius;
        world.dropItemNaturally(center.clone().add(dx, 0.2, dz), item);
    }
}
