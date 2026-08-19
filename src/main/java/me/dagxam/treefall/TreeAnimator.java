package me.dagxam.treefall;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Comparator;
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
                            String treeKey,
                            Vector fallDirection) {
        Settings settings = plugin.settings;
        Random random = plugin.random;

        List<Block> allBlocks = new ArrayList<>(falling.logs().size() + falling.leaves().size());
        allBlocks.addAll(falling.logs());
        allBlocks.addAll(falling.leaves());

        int maxAnimated = Math.min(allBlocks.size(), settings.maxFallingBlocks);
        List<Block> animated = new ArrayList<>(maxAnimated);
        List<Block> logs = new ArrayList<>(falling.logs());
        List<Block> leaves = new ArrayList<>(falling.leaves());
        logs.sort(Comparator.comparingInt(Block::getY));
        leaves.sort(Comparator.comparingInt(Block::getY));

        // Keep the trunk visually continuous first, then fill the remaining entity budget with leaves.
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
            if (!animatedSet.contains(block)) block.setType(Material.AIR, false);
        }

        int minY = falling.logs().stream().mapToInt(Block::getY).min().orElse(center.getBlockY());
        int maxY = falling.logs().stream().mapToInt(Block::getY).max().orElse(minY + 1);
        int height = Math.max(1, maxY - minY);
        Vector direction = fallDirection == null ? new Vector(0, 0, 1) : fallDirection.clone().setY(0);
        if (direction.lengthSquared() < 0.001) direction = new Vector(0, 0, 1);
        direction.normalize();

        if (settings.sounds) world.playSound(center, Sound.BLOCK_WOOD_BREAK, 1.15f, 0.55f);

        final Vector finalDirection = direction;
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
                    int processed = 0;

                    while (index < animated.size() && processed < settings.animBlocksPerTick) {
                        Block block = animated.get(index++);
                        if (block.getType() == Material.AIR) {
                            processed++;
                            continue;
                        }

                        BlockData data = block.getBlockData();
                        Location spawn = block.getLocation().add(0.5, 0.25, 0.5);
                        block.setType(Material.AIR, false);

                        FallingBlock entity = world.spawnFallingBlock(spawn, data);
                        entity.setDropItem(false);
                        entity.setHurtEntities(false);
                        entity.addScoreboardTag(TreeFallPlugin.FALLING_TAG);

                        double normalizedHeight = Math.max(0.0,\ Math.min(1.0,
                                (block.getY() - minY) / (double) height));
                        double horizontal = settings.directionalFall
                                ? settings.horizontalVelocity * (0.25 + normalizedHeight * 1.10)
                                : 0.0;
                        Vector velocity = finalDirection.clone().multiply(horizontal);
                        // Small lift gives the visual tree a brief weight shift before gravity takes over.
                        velocity.setY(settings.upwardVelocity + normalizedHeight * settings.upwardVelocity * 0.5);
                        velocity.add(new Vector(
                                (random.nextDouble() - 0.5) * settings.randomSpread,
                                0,
                                (random.nextDouble() - 0.5) * settings.randomSpread
                        ));
                        entity.setVelocity(velocity);
                        entities.add(entity);
                        processed++;
                    }

                    if (settings.particles && ticks % settings.particleInterval == 0) {
                        Location effect = center.clone().add(0.5, 0.8, 0.5);
                        world.spawnParticle(Particle.CLOUD, effect, 3, 0.35, 0.25, 0.35, 0.015);
                        world.spawnParticle(Particle.CRIT, effect, 2, 0.3, 0.3, 0.3, 0.02);
                    }

                    if (settings.sounds && ticks % settings.soundInterval == 0 && index < animated.size()) {
                        world.playSound(center, Sound.BLOCK_WOOD_BREAK, 0.55f,
                                0.85f + random.nextFloat() * 0.25f);
                    }

                    if (index >= animated.size() && cleanupAt < 0L) {
                        if (!rewardsGiven) {
                            giveRewards(plugin, world, center, drops, player, toolSlot, toolSnapshot,
                                    falling, random);
                            rewardsGiven = true;
                            if (settings.sounds) {
                                world.playSound(center, Sound.BLOCK_WOOD_BREAK, 1.25f, 0.65f);
                                world.spawnParticle(Particle.CLOUD, center.clone().add(0.5, 0.35, 0.5),
                                        14, 0.7, 0.2, 0.7, 0.035);
                            }
                        }
                        cleanupAt = ticks + Math.min(20L, Math.max(1L, settings.animationTimeoutTicks / 4L));
                    }

                    if (ticks >= settings.animationTimeoutTicks && !rewardsGiven) {
                        removeRemainingBlocks(animated, index);
                        giveRewards(plugin, world, center, drops, player, toolSlot, toolSnapshot,
                                falling, random);
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
                                falling, random);
                    }
                    plugin.releaseTree(treeKey);
                    plugin.getLogger().warning("TreeFall animation recovered from an error: "
                            + throwable.getClass().getSimpleName() + ": " + throwable.getMessage());
                    cancel();
                }
            }

            private void cleanupEntities() {
                for (FallingBlock entity : entities) if (entity.isValid()) entity.remove();
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

    private static void giveRewards(TreeFallPlugin plugin, World world, Location center,
                                    TreeDropCalculator.DropResult drops, Player player,
                                    int toolSlot, ItemStack toolSnapshot, TreeBlocks falling,
                                    Random random) {
        dropScattered(world, center, drops.leafDrops(), random, 3.5);
        dropScattered(world, center, drops.logDrops(), random, 3.5);
        if (drops.sticks() > 0) scatterItem(world, center, new ItemStack(Material.STICK, drops.sticks()), 3.5, random);
        if (drops.saplingType() != null && drops.saplings() > 0)
            scatterItem(world, center, new ItemStack(drops.saplingType(), drops.saplings()), 3.5, random);
        if (drops.apples() > 0) scatterItem(world, center, new ItemStack(Material.APPLE, drops.apples()), 3.5, random);
        if (plugin.settings.damageTool)
            ToolDamageHandler.damageTool(player, toolSlot, toolSnapshot, falling.logs().size(), random);
    }

    private static void dropScattered(World world, Location center, java.util.Map<Material, Integer> items,
                                      Random random, double radius) {
        for (var entry : items.entrySet()) {
            int left = entry.getValue();
            while (left > 0) {
                int amount = Math.min(64, left);
                scatterItem(world, center, new ItemStack(entry.getKey(), amount), radius, random);
                left -= amount;
            }
        }
    }

    private static void scatterItem(World world, Location center, ItemStack item, double radius, Random random) {
        double dx = (random.nextDouble() - 0.5) * radius;
        double dz = (random.nextDouble() - 0.5) * radius;
        world.dropItemNaturally(center.clone().add(dx, 0.2, dz), item);
    }
}
