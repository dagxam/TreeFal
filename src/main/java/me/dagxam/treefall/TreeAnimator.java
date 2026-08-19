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
        int maxAnimated = Math.min(allBlocks.size(), settings.maxFallingBlocks);
        List<Block> animated = new ArrayList<>(maxAnimated);
        List<Block> logs = new ArrayList<>(falling.logs());
        List<Block> leaves = new ArrayList<>(falling.leaves());
        logs.sort(Comparator.comparingInt(Block::getY));
        leaves.sort(Comparator.comparingInt(Block::getY));
        for (Block block : logs) { if (animated.size() >= maxAnimated) break; animated.add(block); }
        for (Block block : leaves) { if (animated.size() >= maxAnimated) break; animated.add(block); }

        Set<Block> animatedSet = new HashSet<>(animated);
        for (Block block : allBlocks) if (!animatedSet.contains(block)) block.setType(Material.AIR, false);

        Vector direction = fallDirection == null ? new Vector(0, 0, 1) : fallDirection.clone().setY(0);
        if (direction.lengthSquared() < 0.001) direction = new Vector(0, 0, 1);
        direction.normalize();
        if (settings.sounds) world.playSound(center, Sound.BLOCK_WOOD_BREAK, 1.15f, 0.55f);

        final Vector finalDirection = direction;
        new BukkitRunnable() {
            private int ticks;
            private boolean rewardsGiven;
            private final List<BlockDisplay> displays = new ArrayList<>();
            private final Location pivot = center.clone().add(0.5, 0.5, 0.5);
            private final Vector rotationAxis = new Vector(finalDirection.getZ(), 0, -finalDirection.getX()).normalize();
            private final double maxAngle = Math.toRadians(82.0);

            @Override public void run() {
                try {
                    ticks++;
                    if (ticks == 1) spawnDisplays();
                    double progress = Math.min(1.0, ticks / (double) Math.max(8, Math.min(24, settings.animationTimeoutTicks / 4)));
                    double eased = progress * progress * (3.0 - 2.0 * progress);
                    double angle = maxAngle * eased;
                    Quaternionf rotation = new Quaternionf(new AxisAngle4f((float) angle,
                            (float) rotationAxis.getX(), (float) rotationAxis.getY(), (float) rotationAxis.getZ()));
                    Vector3f axisTranslation = new Vector3f(
                            (float) (finalDirection.getX() * settings.horizontalVelocity * eased),
                            (float) (settings.upwardVelocity * (1.0 - eased)),
                            (float) (finalDirection.getZ() * settings.horizontalVelocity * eased));

                    for (int i = 0; i < displays.size(); i++) {
                        Block block = animated.get(i);
                        Vector local = block.getLocation().add(0.5, 0.5, 0.5).toVector().subtract(pivot.toVector());
                        Vector3f transformed = new Vector3f((float) local.getX(), (float) local.getY(), (float) local.getZ());
                        rotation.transform(transformed);
                        Vector delta = new Vector(transformed.x() - local.x(), transformed.y() - local.y(), transformed.z() - local.z());
                        delta.add(new Vector(axisTranslation.x(), axisTranslation.y(), axisTranslation.z()));
                        applyTransform(displays.get(i), delta, rotation);
                    }

                    if (settings.particles && ticks % settings.particleInterval == 0) {
                        Location effect = pivot.clone().add(finalDirection.clone().multiply(0.5 + eased * 1.5));
                        world.spawnParticle(Particle.CLOUD, effect, 4, 0.45, 0.25, 0.45, 0.02);
                        world.spawnParticle(Particle.CRIT, effect, 2, 0.3, 0.3, 0.3, 0.02);
                    }
                    if (settings.sounds && ticks % settings.soundInterval == 0)
                        world.playSound(pivot, Sound.BLOCK_WOOD_BREAK, 0.5f, 0.7f + random.nextFloat() * 0.25f);

                    if (progress >= 1.0) {
                        if (!rewardsGiven) {
                            giveRewards(plugin, world, pivot, drops, player, toolSlot, toolSnapshot, falling, random);
                            rewardsGiven = true;
                            if (settings.sounds) {
                                world.playSound(pivot, Sound.BLOCK_WOOD_BREAK, 1.25f, 0.6f);
                                world.spawnParticle(Particle.CLOUD, pivot, 18, 0.8, 0.2, 0.8, 0.04);
                            }
                        }
                        for (BlockDisplay display : displays) if (display.isValid()) display.remove();
                        displays.clear();
                        plugin.releaseTree(treeKey);
                        cancel();
                    }
                } catch (Throwable throwable) {
                    for (BlockDisplay display : displays) if (display.isValid()) display.remove();
                    displays.clear();
                    if (!rewardsGiven) giveRewards(plugin, world, pivot, drops, player, toolSlot, toolSnapshot, falling, random);
                    plugin.releaseTree(treeKey);
                    plugin.getLogger().warning("TreeFall display animation recovered from an error: "
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
                    display.setInterpolationDuration((int) Math.max(1, Math.min(4, settings.animTickDelay)));
                    display.setInterpolationDelay(0);
                    display.addScoreboardTag(TreeFallPlugin.FALLING_TAG);
                    displays.add(display);
                    block.setType(Material.AIR, false);
                }
            }

            private void applyTransform(BlockDisplay display, Vector delta, Quaternionf rotation) {
                Vector3f translation = new Vector3f((float) delta.getX(), (float) delta.getY(), (float) delta.getZ());
                display.setTransformation(new Transformation(translation, rotation,
                        new Vector3f(1f, 1f, 1f), new Quaternionf()));
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
