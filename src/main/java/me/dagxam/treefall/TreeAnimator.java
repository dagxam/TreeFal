package me.dagxam.treefall;

import org.bukkit.GameMode;
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
import java.util.Map;
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

        int maxAnimated = Math.min(allBlocks.size(), settings.maxFallingBlocks);
        List<Block> animated = new ArrayList<>(maxAnimated);
        List<BlockData> blockData = new ArrayList<>(maxAnimated);

        List<Block> logs = new ArrayList<>(falling.logs());
        List<Block> leaves = new ArrayList<>(falling.leaves());
        logs.sort(Comparator.comparingInt(Block::getY));
        leaves.sort(Comparator.comparingInt(Block::getY));

        for (Block block : logs) {
            if (animated.size() >= maxAnimated) break;
            animated.add(block);
            blockData.add(block.getBlockData().clone());
        }
        for (Block block : leaves) {
            if (animated.size() >= maxAnimated) break;
            animated.add(block);
            blockData.add(block.getBlockData().clone());
        }

        Set<Block> animatedSet = new HashSet<>(animated);
        for (Block block : allBlocks) if (animatedSet.contains(block)) block.setType(Material.AIR, false);

        Vector resolvedDirection = fallDirection == null ? new Vector(0, 0, 1) : fallDirection.clone().setY(0);
        if (resolvedDirection.lengthSquared() < 0.001) resolvedDirection = new Vector(0, 0, 1);
        resolvedDirection.normalize();
        final Vector direction = resolvedDirection.clone();

        if (settings.sounds) world.playSound(center, Sound.BLOCK_WOOD_BREAK, 1.15f, 0.55f);

        new BukkitRunnable() {
            private int ticks;
            private boolean rewardsGiven;
            private final List<BlockDisplay> displays = new ArrayList<>(animated.size());
            private final Location pivot = center.clone();
            private final Vector rotationAxis = new Vector(direction.getZ(), 0, -direction.getX()).normalize();
            private final double maxAngle = Math.toRadians(90.0);
            private final int duration = Math.max(36, Math.min(90, (int) Math.max(55, settings.animationTimeoutTicks)));
            private final Vector[] localPositions = precomputeLocalPositions();
            private final double dropRadius = calculateDropRadius();
            private double groundOffset;

            @Override public void run() {
                try {
                    if (ticks == 0) {
                        spawnDisplays();
                        groundOffset = calculateGroundOffset();
                    }

                    ticks++;
                    double progress = Math.min(1.0, ticks / (double) duration);
                    double eased = progress * progress * progress * (progress * (progress * 6.0 - 15.0) + 10.0);
                    double angle = maxAngle * eased;
                    Quaternionf rotation = new Quaternionf(new AxisAngle4f(
                            (float) angle,
                            (float) rotationAxis.getX(),
                            (float) rotationAxis.getY(),
                            (float) rotationAxis.getZ()));

                    double horizontal = settings.horizontalVelocity * eased * Math.max(1.0, animated.size() / 32.0);
                    double vertical = settings.upwardVelocity * (1.0 - eased) - groundOffset * eased;
                    updateDisplays(rotation, horizontal, vertical);

                    if (settings.particles && ticks % settings.particleInterval == 0) {
                        Location effect = pivot.clone().add(direction.clone().multiply(0.5 + eased * 2.0));
                        world.spawnParticle(Particle.CLOUD, effect, 4, 0.45, 0.25, 0.45, 0.02);
                        world.spawnParticle(Particle.CRIT, effect, 2, 0.3, 0.3, 0.3, 0.02);
                    }
                    if (settings.sounds && ticks % settings.soundInterval == 0) {
                        world.playSound(pivot, Sound.BLOCK_WOOD_BREAK, 0.5f, 0.7f + random.nextFloat() * 0.25f);
                    }

                    if (progress >= 1.0) finish();
                } catch (Throwable throwable) {
                    cleanupDisplays();
                    if (!rewardsGiven) {
                        giveRewards(plugin, world, pivot, drops, player, toolSlot, toolSnapshot, falling, random, dropRadius);
                        rewardsGiven = true;
                    }
                    plugin.releaseTree(treeKey);
                    plugin.getLogger().warning("TreeFall animation recovered: " + throwable.getClass().getSimpleName() + ": " + throwable.getMessage());
                    cancel();
                }
            }

            private Vector[] precomputeLocalPositions() {
                Vector[] result = new Vector[animated.size()];
                Vector pivotVector = pivot.toVector();
                for (int i = 0; i < animated.size(); i++) {
                    result[i] = animated.get(i).getLocation().add(0.5, 0.5, 0.5).toVector().subtract(pivotVector);
                }
                return result;
            }

            private void updateDisplays(Quaternionf rotation, double horizontal, double vertical) {
                float dx = (float) (direction.getX() * horizontal);
                float dz = (float) (direction.getZ() * horizontal);
                for (int i = 0; i < displays.size(); i++) {
                    Vector local = localPositions[i];
                    Vector3f transformed = new Vector3f((float) local.getX(), (float) local.getY(), (float) local.getZ());
                    rotation.transform(transformed);
                    BlockDisplay display = displays.get(i);
                    display.teleport(pivot.clone().add(transformed.x() + dx, transformed.y() + vertical, transformed.z() + dz));
                    display.setTransformation(new Transformation(
                            new Vector3f(), new Quaternionf(rotation), new Vector3f(1f, 1f, 1f), new Quaternionf()));
                }
            }

            private void finish() {
                if (!rewardsGiven) {
                    giveRewards(plugin, world, pivot, drops, player, toolSlot, toolSnapshot, falling, random, dropRadius);
                    rewardsGiven = true;
                }
                if (settings.sounds) {
                    world.playSound(pivot, Sound.BLOCK_WOOD_BREAK, 1.25f, 0.6f);
                    world.spawnParticle(Particle.CLOUD, pivot, 18, 0.8, 0.2, 0.8, 0.04);
                }
                cleanupDisplays();
                plugin.releaseTree(treeKey);
                cancel();
            }

            private void spawnDisplays() {
                for (int i = 0; i < animated.size(); i++) {
                    Location location = animated.get(i).getLocation().add(0.5, 0.5, 0.5);
                    BlockDisplay display = (BlockDisplay) world.spawnEntity(location, EntityType.BLOCK_DISPLAY);
                    display.setBlock(blockData.get(i));
                    display.setGravity(false);
                    display.setInvulnerable(true);
                    display.setPersistent(false);
                    display.setTeleportDuration(2);
                    display.setInterpolationDuration(2);
                    display.setInterpolationDelay(0);
                    display.addScoreboardTag(TreeFallPlugin.FALLING_TAG);
                    displays.add(display);
                }
            }

            private void cleanupDisplays() {
                for (BlockDisplay display : displays) if (display.isValid()) display.remove();
                displays.clear();
            }

            private double calculateGroundOffset() {
                Quaternionf finalRotation = new Quaternionf(new AxisAngle4f(
                        (float) maxAngle,
                        (float) rotationAxis.getX(),
                        (float) rotationAxis.getY(),
                        (float) rotationAxis.getZ()));
                double requiredOffset = Double.NEGATIVE_INFINITY;
                for (Vector local : localPositions) {
                    Vector3f transformed = new Vector3f((float) local.getX(), (float) local.getY(), (float) local.getZ());
                    finalRotation.transform(transformed);
                    int x = (int) Math.floor(pivot.getX() + transformed.x());
                    int z = (int) Math.floor(pivot.getZ() + transformed.z());
                    int highest = world.getHighestBlockYAt(x, z);
                    double needed = highest + 0.5 - (pivot.getY() + transformed.y());
                    requiredOffset = Math.max(requiredOffset, needed);
                }
                return Double.isFinite(requiredOffset) ? Math.max(0.0, requiredOffset) : 0.0;
            }

            private double calculateDropRadius() {
                if (animated.isEmpty()) return 2.0;
                int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
                int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
                int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
                for (Block block : animated) {
                    minX = Math.min(minX, block.getX()); maxX = Math.max(maxX, block.getX());
                    minY = Math.min(minY, block.getY()); maxY = Math.max(maxY, block.getY());
                    minZ = Math.min(minZ, block.getZ()); maxZ = Math.max(maxZ, block.getZ());
                }
                double horizontalSize = Math.max(maxX - minX + 1, maxZ - minZ + 1);
                double height = maxY - minY + 1;
                return Math.max(2.0, Math.min(14.0, 1.5 + horizontalSize * 0.55 + height * 0.18));
            }
        }.runTaskTimer(plugin, 0L, settings.animTickDelay);
    }

    private static void giveRewards(TreeFallPlugin plugin, World world, Location center,
                                    TreeDropCalculator.DropResult drops, Player player,
                                    int toolSlot, ItemStack toolSnapshot, TreeBlocks falling,
                                    Random random, double radius) {
        dropScattered(world, center, drops.leafDrops(), random, radius);
        dropScattered(world, center, drops.logDrops(), random, radius);
        if (drops.sticks() > 0) scatterStack(world, center, Material.STICK, drops.sticks(), radius, random);
        if (drops.saplingType() != null && drops.saplings() > 0)
            scatterStack(world, center, drops.saplingType(), drops.saplings(), radius, random);
        if (drops.apples() > 0) scatterStack(world, center, Material.APPLE, drops.apples(), radius, random);
        if (plugin.settings.damageTool && player.getGameMode() != GameMode.CREATIVE)
            ToolDamageHandler.damageTool(player, toolSlot, toolSnapshot, falling.logs().size(), random);
    }

    private static void dropScattered(World world, Location center, Map<Material, Integer> items,
                                      Random random, double radius) {
        for (Map.Entry<Material, Integer> entry : items.entrySet()) {
            scatterStack(world, center, entry.getKey(), entry.getValue(), radius, random);
        }
    }

    private static void scatterStack(World world, Location center, Material material, int total,
                                     double radius, Random random) {
        int left = total;
        while (left > 0) {
            int chunk = Math.min(left, 1 + random.nextInt(Math.min(8, left)));
            scatterItem(world, center, new ItemStack(material, chunk), radius, random);
            left -= chunk;
        }
    }

    private static void scatterItem(World world, Location center, ItemStack item, double radius, Random random) {
        double angle = random.nextDouble() * Math.PI * 2.0;
        double distance = Math.sqrt(random.nextDouble()) * radius;
        double dx = Math.cos(angle) * distance;
        double dz = Math.sin(angle) * distance;
        Location location = center.clone().add(dx, 0.35, dz);
        var dropped = world.dropItemNaturally(location, item);
        dropped.setVelocity(new Vector((random.nextDouble() - 0.5) * 0.12, 0.10 + random.nextDouble() * 0.08,
                (random.nextDouble() - 0.5) * 0.12));
    }
}
