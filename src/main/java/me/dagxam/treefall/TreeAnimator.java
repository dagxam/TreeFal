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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

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
        if (allBlocks.isEmpty()) {
            plugin.releaseTree(treeKey);
            return;
        }

        // Capture the real block data before the tree slice is removed.
        Map<Block, BlockData> originalData = new HashMap<>();
        for (Block block : allBlocks) {
            originalData.put(block, block.getBlockData().clone());
        }

        int maxAnimated = Math.min(allBlocks.size(), settings.maxFallingBlocks);
        List<Block> visualBlocks = new ArrayList<>(maxAnimated);
        for (Block block : falling.logs()) {
            if (visualBlocks.size() >= maxAnimated) break;
            visualBlocks.add(block);
        }
        for (Block block : falling.leaves()) {
            if (visualBlocks.size() >= maxAnimated) break;
            visualBlocks.add(block);
        }

        // Remove only the falling slice. Blocks below the cut stay untouched.
        for (Block block : allBlocks) {
            if (block.getType() != Material.AIR) {
                block.setType(Material.AIR, false);
            }
        }

        Vector direction = fallDirection.clone().setY(0);
        if (direction.lengthSquared() < 0.001) {
            direction = new Vector(0, 0, 1);
        } else {
            direction.normalize();
        }

        // Give every tree a new horizontal direction: left/right/forward/back/diagonal.
        if (settings.randomFallDirection) {
            double randomAngle = random.nextDouble() * Math.PI * 2.0;
            double cos = Math.cos(randomAngle);
            double sin = Math.sin(randomAngle);
            double x = direction.getX() * cos - direction.getZ() * sin;
            double z = direction.getX() * sin + direction.getZ() * cos;
            direction = new Vector(x, 0, z).normalize();
        }

        Vector axis = new Vector(-direction.getZ(), 0, direction.getX()).normalize();
        Location pivot = center.clone();
        List<VisualBlock> visuals = new ArrayList<>(visualBlocks.size());

        for (Block block : visualBlocks) {
            BlockData data = originalData.get(block);
            if (data == null) continue;

            Vector relative = block.getLocation().add(0.5, 0.5, 0.5).toVector()
                    .subtract(pivot.toVector());

            FallingBlock entity = world.spawnFallingBlock(
                    block.getLocation().add(0.5, 0.5, 0.5), data);
            entity.setDropItem(false);
            entity.setCancelDrop(true);
            entity.setHurtEntities(false);
            entity.setGravity(false);
            entity.setInvulnerable(true);
            entity.setVelocity(new Vector(0, 0, 0));
            entity.addScoreboardTag(TreeFallPlugin.FALLING_TAG);
            visuals.add(new VisualBlock(entity, relative));
        }

        if (settings.sounds) {
            world.playSound(pivot, Sound.BLOCK_WOOD_BREAK, 1.1f, 0.55f);
        }

        final Vector finalAxis = axis;
        final Vector finalDirection = direction;
        final int duration = Math.max(12, settings.fallDurationTicks);
        final int timeout = Math.max(duration + 5, (int) Math.min(Integer.MAX_VALUE, settings.animationTimeoutTicks));

        new BukkitRunnable() {
            private int tick;
            private boolean rewardsGiven;

            @Override
            public void run() {
                try {
                    tick++;

                    // IMPORTANT: do not use getHighestBlockYAt() here.
                    // It can see terrain/trees nearby and report a false collision on tick 1,
                    // which makes the whole animation disappear immediately.
                    double progress = Math.min(1.0, tick / (double) duration);
                    double eased = progress * progress * (3.0 - 2.0 * progress);
                    double angle = Math.toRadians(settings.fallAngleDegrees) * eased;
                    double drift = settings.fallDistance * eased;
                    Location impact = pivot.clone().add(finalDirection.clone().multiply(drift));

                    for (VisualBlock visual : visuals) {
                        if (!visual.entity.isValid()) continue;

                        Vector rotated = rotate(visual.relative, finalAxis, -angle);
                        Vector position = pivot.toVector()
                                .add(rotated)
                                .add(finalDirection.clone().multiply(drift));

                        Location target = new Location(world,
                                position.getX(), position.getY(), position.getZ());
                        visual.entity.teleport(target);
                        visual.entity.setVelocity(new Vector(0, 0, 0));
                    }

                    if (settings.particles && tick % Math.max(1, settings.particleInterval) == 0) {
                        world.spawnParticle(Particle.CLOUD, impact, 3,
                                0.35, 0.2, 0.35, 0.015);
                    }

                    if (settings.sounds && tick % Math.max(1, settings.soundInterval) == 0
                            && progress < 0.9) {
                        world.playSound(pivot, Sound.BLOCK_WOOD_BREAK, 0.45f,
                                0.8f + random.nextFloat() * 0.25f);
                    }

                    // The animation always gets its full duration. Rewards are generated
                    // only after the visual fall is complete, never on the first tick.
                    if (progress >= 1.0 || tick >= timeout) {
                        if (!rewardsGiven) {
                            rewardsGiven = true;
                            giveRewards(plugin, world, impact, drops, player, toolSlot,
                                    toolSnapshot, falling, random);
                            if (settings.sounds) {
                                world.playSound(impact, Sound.BLOCK_WOOD_BREAK, 1.25f, 0.65f);
                            }
                            if (settings.particles) {
                                world.spawnParticle(Particle.CLOUD, impact, 18,
                                        0.8, 0.2, 0.8, 0.04);
                            }
                        }

                        removeVisuals();
                        plugin.releaseTree(treeKey);
                        cancel();
                    }
                } catch (Throwable throwable) {
                    if (!rewardsGiven) {
                        rewardsGiven = true;
                        giveRewards(plugin, world, pivot, drops, player, toolSlot,
                                toolSnapshot, falling, random);
                    }
                    removeVisuals();
                    plugin.releaseTree(treeKey);
                    plugin.getLogger().warning("TreeFall animation recovered from an error: "
                            + throwable.getClass().getSimpleName() + ": " + throwable.getMessage());
                    cancel();
                }
            }

            private void removeVisuals() {
                for (VisualBlock visual : visuals) {
                    if (visual.entity.isValid()) visual.entity.remove();
                }
            }
        }.runTaskTimer(plugin, 0L, Math.max(1L, settings.animTickDelay));
    }

    private static Vector rotate(Vector vector, Vector axis, double angle) {
        double cos = Math.cos(angle);
        double sin = Math.sin(angle);
        Vector a = axis.clone().normalize();
        Vector cross = a.clone().crossProduct(vector);
        double dot = a.dot(vector);
        return vector.clone().multiply(cos)
                .add(cross.multiply(sin))
                .add(a.multiply(dot * (1.0 - cos)));
    }

    private record VisualBlock(FallingBlock entity, Vector relative) {}

    private static void giveRewards(TreeFallPlugin plugin,
                                    World world,
                                    Location center,
                                    TreeDropCalculator.DropResult drops,
                                    Player player,
                                    int toolSlot,
                                    ItemStack toolSnapshot,
                                    TreeBlocks falling,
                                    Random random) {
        dropScattered(world, center, drops.leafDrops(), 2.5, random);
        dropScattered(world, center, drops.logDrops(), 2.5, random);
        if (drops.sticks() > 0) {
            scatterItem(world, center, new ItemStack(Material.STICK, drops.sticks()), 2.5, random);
        }
        if (drops.saplingType() != null && drops.saplings() > 0) {
            scatterItem(world, center, new ItemStack(drops.saplingType(), drops.saplings()), 2.5, random);
        }
        if (drops.fruitType() != null && drops.fruits() > 0) {
            scatterItem(world, center, new ItemStack(drops.fruitType(), drops.fruits()), 2.5, random);
        }
        if (plugin.settings.damageTool) {
            ToolDamageHandler.damageTool(player, toolSlot, toolSnapshot, falling.logs().size(), random);
        }
    }

    private static void dropScattered(World world, Location center,
                                      Map<Material, Integer> items,
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
