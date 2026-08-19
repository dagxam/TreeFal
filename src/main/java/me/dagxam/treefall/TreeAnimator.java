package me.dagxam.treefall;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

public final class TreeAnimator {
    private TreeAnimator() {}

    public static void play(TreeFallPlugin plugin, World world, Location center, TreeBlocks falling,
                            TreeDropCalculator.DropResult drops, Player player, int toolSlot,
                            ItemStack toolSnapshot, String treeKey, Vector fallDirection) {
        Settings settings = plugin.settings;
        Random random = plugin.random;

        List<Block> allBlocks = new ArrayList<>(falling.logs().size() + falling.leaves().size());
        allBlocks.addAll(falling.logs());
        allBlocks.addAll(falling.leaves());
        if (allBlocks.isEmpty()) {
            plugin.releaseTree(treeKey);
            return;
        }

        // Keep the complete falling section. The configured limit is a safety limit only.
        int maxAnimated = Math.min(allBlocks.size(), settings.maxFallingBlocks);
        List<Block> animated = new ArrayList<>(maxAnimated);
        List<Block> logs = new ArrayList<>(falling.logs());
        List<Block> leaves = new ArrayList<>(falling.leaves());
        logs.sort(Comparator.comparingInt(Block::getY));
        leaves.sort(Comparator.comparingInt(Block::getY));
        for (Block block : logs) {
            if (animated.size() >= maxAnimated) break;
            animated.add(block);
        }
        for (Block block : leaves) {
            if (animated.size() >= maxAnimated) break;
            animated.add(block);
        }

        // Never delete blocks that were found but could not be animated.
        // They remain in the world and keep the server safe if the safety limit is reached.
        Set<Block> animatedSet = new HashSet<>(animated);
        for (Block block : allBlocks) {
            if (animatedSet.contains(block)) block.setType(Material.AIR, false);
        }

        Vector direction = fallDirection == null ? new Vector(0, 0, 1) : fallDirection.clone().setY(0);
        if (direction.lengthSquared() < 0.001) direction = new Vector(0, 0, 1);
        direction.normalize();
        final Vector finalDirection = direction;

        if (settings.sounds) world.playSound(center, Sound.BLOCK_WOOD_BREAK, 1.15f, 0.55f);

        new BukkitRunnable() {
            private int ticks;
            private boolean rewardsGiven;
            private final List<BlockDisplay> displays = new ArrayList<>();
            private final Location pivot = center.clone();
            private final Vector rotationAxis = new Vector(finalDirection.getZ(), 0, -finalDirection.getX()).normalize();
            private final double maxAngle = Math.toRadians(82.0);
            private final int duration = Math.max(10, Math.min(60, (int) Math.max(20, settings.animationTimeoutTicks / 2)));

            @Override public void run() {
                try {
                    if (ticks == 0) spawnDisplays();
                    ticks++;

                    double progress = Math.min(1.0, ticks / (double) duration);
                    double eased = progress * progress * (3.0 - 2.0 * progress);
                    double angle = maxAngle * eased;
                    Quaternionf rotation = new Quaternionf(new AxisAngle4f(
                            (float) angle,
                            (float) rotationAxis.getX(),
                            (float) rotationAxis.getY(),
                            (float) rotationAxis.getZ()));

                    double horizontal = settings.horizontalVelocity * eased * Math.max(1.0, animated.size() / 32.0);
                    double vertical = settings.upwardVelocity * (1.0 - eased);

                    for (int i = 0; i < displays.size(); i++) {
                        Block block = animated.get(i);
                        Vector local = block.getLocation().add(0.5, 0.5, 0.5).toVector().subtract(pivot.toVector());
                        Vector3f transformed = new Vector3f((float) local.getX(), (float) local.getY(), (float) local.getZ());
                        rotation.transform(transformed);

                        Location target = pivot.clone().add(
                                transformed.x() + finalDirection.getX() * horizontal,
                                transformed.y() + vertical,
                                transformed.z() + finalDirection.getZ() * horizontal);

                        BlockDisplay display = displays.get(i);
                        display.teleport(target);
                        display.setTransformation(new Transformation(
                                new Vector3f(0f, 0f, 0f),
                                rotation,
                                new Vector3f(1f, 1f, 1f),
                                new Quaternionf()));
                    }

                    if (settings.particles && ticks % settings.particleInterval == 0) {
                        Location effect = pivot.clone().add(finalDirection.clone().multiply(0.5 + eased * 2.0));
                        world.spawnParticle(Particle.CLOUD, effect, 4, 0.45, 0.25, 0.45, 0.02);
                        world.spawnParticle(Particle.CRIT, effect, 2, 0.3, 0.3, 0.3, 0.02);
                    }
                    if (settings.sounds && ticks % settings.soundInterval == 0) {
                        world.playSound(pivot, Sound.BLOCK_WOOD_BREAK, 0.5f,
                                0.7f + random.nextFloat() * 0.25f);
                    }

                    if (progress >= 1.0) {
                        if (!rewardsGiven) {
                            giveRewards(plugin, world, pivot, drops, player, toolSlot, toolSnapshot, falling, random);
                            rewardsGiven = true;
                        }
                        if (settings.sounds) {
                            world.playSound(pivot, Sound.BLOCK_WOOD_BREAK, 1.25f, 0.6f);
                            world.spawnParticle(Particle.CLOUD, pivot, 18, 0.8, 0.2, 0.8, 0.04);
                        }
                        for (BlockDisplay display : displays) if (display.isValid()) display.remove();
                        displays.clear();
                        plugin.releaseTree(treeKey);
                        cancel();
                    }
                } catch (Throwable throwable) {
                    for (BlockDisplay display : displays) if (display.isValid()) display.remove();
                    displays.clear();
                    if (!rewardsGiven) {
                        giveRewards(plugin, world, pivot, drops, player, toolSlot, toolSnapshot, falling, random);
                    }
                    plugin.releaseTree(treeKey);
                    plugin.getLogger().warning("TreeFall animation recovered: "
                            + throwable.getClass().getSimpleName() + ": " + throwable.getMessage());
                    cancel();
                }
            }

            private void spawnDisplays() {
                for (Block block : animated) {
                    BlockData data = block.getBlockData();
                    Location location = block.getLocation().add(0.5, 0.5, 0.5);
                    BlockDisplay display = (BlockDisplay) world.spawnEntity(location, EntityType.BLOCK_DISPLAY);
                    display.setBlock(data);
                    display.setGravity(false);
                    display.setInvulnerable(true);
                    display.setPersistent(false);
                    display.setTeleportDuration(1);
                    display.setInterpolationDuration(1);
                    display.setInterpolationDelay(0);
                    display.addScoreboardTag(TreeFallPlugin.FALLING_TAG);
                    displays.add(display);
                }
            }
        }.runTaskTimer(plugin, 0L, settings.animTickDelay);
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
