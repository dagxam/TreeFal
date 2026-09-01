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

        double spread = falling.leaves().size() >= Settings.BIG_TREE_LEAVES ? 3.5 : 1.8;
        int configuredLimit = settings.maxFallingBlocks;
        if (settings.adaptiveAnimation && plugin.activeTreeCount() > settings.busyAnimationThreshold) {
            configuredLimit = Math.max(10, configuredLimit / 2);
        }
        int maxAnimated = Math.min(allBlocks.size(), configuredLimit);

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
            if (!animatedSet.contains(block)) block.setType(Material.AIR, false);
        }

        int minY = falling.logs().stream().mapToInt(Block::getY).min().orElse(center.getBlockY());
        int maxY = falling.logs().stream().mapToInt(Block::getY).max().orElse(minY + 1);
        int height = Math.max(1, maxY - minY);
        Vector direction = fallDirection.clone().setY(0);
        if (direction.lengthSquared() < 0.001) direction = new Vector(0, 0, 1);
        direction.normalize();

        if (settings.sounds) world.playSound(center, Sound.BLOCK_WOOD_BREAK, 1.15f, 0.55f);

        final Vector finalDirection = direction;
        new BukkitRunnable() {
            private int index;
            private long ticks;
            private long cleanupAt = -1L;
            private boolean rewardsGiven;
            private final List<FallingBlock> entities = new ArrayList<>();

            @Override
            public void run() {
                try {
                    ticks++;
                    int batch = Math.max(1, settings.animBlocksPerTick);
                    int processed = 0;

                    while (index < animated.size() && processed < batch) {
                        Block block = animated.get(index++);
                        if (block.getType() == Material.AIR) {
                            processed++;
                            continue;
                        }

                        BlockData data = block.getBlockData();
                        Location spawnLocation = block.getLocation().add(0.5, 0.2, 0.5);
                        block.setType(Material.AIR, false);

                        FallingBlock entity = world.spawnFallingBlock(spawnLocation, data);
                        entity.setDropItem(false);
                        entity.setHurtEntities(false);
                        entity.setGravity(true);
                        entity.addScoreboardTag(TreeFallPlugin.FALLING_TAG);

                        double heightFactor = Math.max(0.25,
                                Math.min(1.35, 0.30 + ((block.getY() - minY) / (double) height) * 1.05));
                        double horizontal = settings.directionalFall
                                ? settings.horizontalVelocity * heightFactor : 0.0;

                        Vector velocity = finalDirection.clone().multiply(horizontal);
                        // A positive upward velocity was previously being applied,
                        // which could keep the visual blocks suspended. Start them
                        // downward and let Minecraft gravity create the fall.
                        velocity.setY(-0.08 + Math.min(settings.upwardVelocity, 0.02));
                        velocity.add(new Vector(
                                (random.nextDouble() - 0.5) * settings.randomSpread,
                                0,
                                (random.nextDouble() - 0.5) * settings.randomSpread));
                        entity.setVelocity(velocity);

                        entities.add(entity);
                        processed++;
                    }

                    // Never let TreeFall FallingBlocks place real blocks back.
                    // Loot is intentionally not generated here; it is generated
                    // only after the visual fall has had time to reach the ground.
                    if (!rewardsGiven && index >= animated.size()) {
                        boolean landed = false;
                        for (FallingBlock entity : entities) {
                            if (!entity.isValid()) continue;
                            if (entity.isOnGround()) {
                                landed = true;
                                break;
                            }
                        }

                        // Some servers/plugins cancel the entity-change event and
                        // Bukkit may not expose isOnGround(). After a full visible
                        // fall window, use a safe fallback so loot is never lost.
                        long minimumFallTicks = Math.max(20L, Math.min(80L, height * 3L));
                        if (landed || ticks >= minimumFallTicks) {
                            rewardsGiven = true;
                            giveRewards(plugin, world, center, drops, player, toolSlot,
                                    toolSnapshot, falling, spread, random);
                            playLandingEffect(settings, world, center);
                            cleanupAt = ticks + 4L;
                        }
                    }

                    if (settings.particles && ticks % Math.max(1, settings.particleInterval) == 0) {
                        Location effect = center.clone().add(0.5, 0.8, 0.5);
                        world.spawnParticle(Particle.CLOUD, effect, 3, 0.35, 0.25, 0.35, 0.015);
                        world.spawnParticle(Particle.CRIT, effect, 2, 0.3, 0.3, 0.3, 0.02);
                    }

                    if (settings.sounds && ticks % Math.max(1, settings.soundInterval) == 0 && index < animated.size()) {
                        world.playSound(center, Sound.BLOCK_WOOD_BREAK, 0.55f,
                                0.85f + random.nextFloat() * 0.25f);
                    }

                    if (ticks >= settings.animationTimeoutTicks) {
                        if (!rewardsGiven) {
                            rewardsGiven = true;
                            giveRewards(plugin, world, center, drops, player, toolSlot,
                                    toolSnapshot, falling, spread, random);
                            playLandingEffect(settings, world, center);
                        }
                        removeRemainingBlocks(animated, index);
                        cleanupAt = ticks + 1L;
                    }

                    if (cleanupAt >= 0L && ticks >= cleanupAt) {
                        cleanupEntities();
                        plugin.releaseTree(treeKey);
                        cancel();
                    }
                } catch (Throwable throwable) {
                    if (!rewardsGiven) {
                        try {
                            rewardsGiven = true;
                            giveRewards(plugin, world, center, drops, player, toolSlot,
                                    toolSnapshot, falling, spread, random);
                        } catch (Throwable ignored) {
                            // Preserve cleanup even if a reward operation fails.
                        }
                    }
                    removeRemainingBlocks(animated, index);
                    cleanupEntities();
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
        }.runTaskTimer(plugin, 0L, Math.max(1L, settings.animTickDelay));
    }

    private static void playLandingEffect(Settings settings, World world, Location center) {
        if (settings.sounds) {
            world.playSound(center, Sound.BLOCK_WOOD_BREAK, 1.25f, 0.65f);
        }
        if (settings.particles) {
            world.spawnParticle(Particle.CLOUD,
                    center.clone().add(0.5, 0.35, 0.5),
                    14, 0.7, 0.2, 0.7, 0.035);
        }
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
        if (drops.sticks() > 0) scatterItem(world, center,
                new ItemStack(Material.STICK, drops.sticks()), spread, random);
        if (drops.saplingType() != null && drops.saplings() > 0) scatterItem(world, center,
                new ItemStack(drops.saplingType(), drops.saplings()), spread, random);
        if (drops.apples() > 0) scatterItem(world, center,
                new ItemStack(Material.APPLE, drops.apples()), spread, random);
        if (plugin.settings.damageTool) {
            ToolDamageHandler.damageTool(player, toolSlot, toolSnapshot, falling.logs().size(), random);
        }
    }

    private static void dropScattered(World world, Location center,
                                      java.util.Map<Material, Integer> items,
                                      double radius, Random random) {
        for (var entry : items.entrySet()) {
            int left = entry.getValue();
            while (left > 0) {
                int amount = Math.min(64, left);
                scatterItem(world, center, new ItemStack(entry.getKey(), amount), radius, random);
                left -= amount;
            }
        }
    }

    private static void scatterItem(World world, Location center, ItemStack item,
                                    double radius, Random random) {
        double dx = (random.nextDouble() - 0.5) * radius;
        double dz = (random.nextDouble() - 0.5) * radius;
        world.dropItemNaturally(center.clone().add(dx, 0.2, dz), item);
    }
}
