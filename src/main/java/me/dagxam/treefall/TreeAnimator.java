package me.dagxam.treefall;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

public final class TreeAnimator {
    private TreeAnimator() {}

    public static void play(TreeFallPlugin plugin, World world, Location center,
                            TreeBlocks falling, TreeDropCalculator.DropResult drops,
                            Player player, int toolSlot, ItemStack toolSnapshot,
                            String treeKey, Vector fallDirection) {
        Settings settings = plugin.settings;
        Random random = plugin.random;

        List<Block> allBlocks = new ArrayList<>(falling.logs().size() + falling.leaves().size());
        allBlocks.addAll(falling.logs());
        allBlocks.addAll(falling.leaves());
        if (allBlocks.isEmpty()) {
            plugin.releaseTree(treeKey);
            return;
        }

        Map<Block, BlockData> originalData = new HashMap<>();
        for (Block block : allBlocks) originalData.put(block, block.getBlockData().clone());

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

        // Remove only the falling slice. Blocks below the cut remain untouched.
        for (Block block : allBlocks) {
            if (block.getType() != Material.AIR) block.setType(Material.AIR, false);
        }

        Vector direction = fallDirection.clone().setY(0);
        if (direction.lengthSquared() < 0.001) direction = new Vector(0, 0, 1);
        else direction.normalize();
        if (settings.randomFallDirection) {
            double a = random.nextDouble() * Math.PI * 2.0;
            double cos = Math.cos(a), sin = Math.sin(a);
            direction = new Vector(direction.getX() * cos - direction.getZ() * sin,
                    0, direction.getX() * sin + direction.getZ() * cos).normalize();
        }

        Vector axis = new Vector(-direction.getZ(), 0, direction.getX());
        if (axis.lengthSquared() < 0.001) axis = new Vector(1, 0, 0);
        else axis.normalize();
        final Vector finalAxis = axis.clone();
        final Vector finalDirection = direction.clone();
        Location pivot = center.clone();
        List<VisualBlock> visuals = new ArrayList<>(visualBlocks.size());

        try {
            for (Block block : visualBlocks) {
                BlockData data = originalData.get(block);
                if (data == null) continue;
                Vector relative = block.getLocation().add(0.5, 0.5, 0.5).toVector()
                        .subtract(pivot.toVector());
                BlockDisplay display = world.spawn(pivot, BlockDisplay.class, entity -> {
                    entity.setBlock(data);
                    entity.setPersistent(false);
                    entity.setInvulnerable(true);
                    entity.setViewRange(64.0f);
                    entity.setInterpolationDuration(1);
                    entity.setInterpolationDelay(0);
                });
                display.addScoreboardTag(TreeFallPlugin.FALLING_TAG);
                visuals.add(new VisualBlock(display, relative));
            }
        } catch (Throwable throwable) {
            for (VisualBlock visual : visuals) visual.entity.remove();
            plugin.releaseTree(treeKey);
            plugin.getLogger().warning("TreeFall could not create animation: "
                    + throwable.getClass().getSimpleName() + ": " + throwable.getMessage());
            return;
        }

        if (visuals.isEmpty()) {
            plugin.releaseTree(treeKey);
            return;
        }

        for (VisualBlock visual : visuals) setTransform(visual.entity, visual.relative, finalAxis, 0.0f);
        if (settings.sounds) world.playSound(pivot, Sound.BLOCK_WOOD_BREAK, 1.1f, 0.55f);

        final int duration = Math.max(16, settings.fallDurationTicks);
        final int timeout = Math.max(duration + 10,
                (int) Math.min(Integer.MAX_VALUE, settings.animationTimeoutTicks));

        new BukkitRunnable() {
            private int tick;
            private boolean rewardsGiven;

            @Override public void run() {
                try {
                    tick++;
                    double progress = Math.min(1.0, tick / (double) duration);
                    double eased = progress * progress * (3.0 - 2.0 * progress);
                    double angle = Math.toRadians(settings.fallAngleDegrees) * eased;
                    double drift = settings.fallDistance * eased;
                    Location impact = pivot.clone().add(finalDirection.clone().multiply(drift));

                    for (VisualBlock visual : visuals) {
                        if (!visual.entity.isValid()) continue;
                        Vector moved = rotate(visual.relative, finalAxis, -angle)
                                .add(finalDirection.clone().multiply(drift));
                        setTransform(visual.entity, moved, finalAxis, (float) -angle);
                    }

                    if (settings.particles && tick % Math.max(1, settings.particleInterval) == 0) {
                        world.spawnParticle(Particle.CLOUD, impact, 3, 0.35, 0.15, 0.35, 0.015);
                    }
                    if (settings.sounds && tick % Math.max(1, settings.soundInterval) == 0 && progress < 0.9) {
                        world.playSound(pivot, Sound.BLOCK_WOOD_BREAK, 0.35f,
                                0.8f + random.nextFloat() * 0.25f);
                    }

                    // The animation is time based; terrain can never cancel it on tick 1.
                    if (progress >= 1.0 || tick >= timeout) {
                        if (!rewardsGiven) {
                            rewardsGiven = true;
                            giveRewards(plugin, world, impact, drops, player, toolSlot,
                                    toolSnapshot, falling, random);
                            if (settings.sounds) world.playSound(impact,
                                    Sound.BLOCK_WOOD_BREAK, 1.25f, 0.65f);
                            if (settings.particles) world.spawnParticle(Particle.CLOUD,
                                    impact, 18, 0.8, 0.2, 0.8, 0.04);
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
                for (VisualBlock visual : visuals) if (visual.entity.isValid()) visual.entity.remove();
            }
        }.runTaskTimer(plugin, 0L, Math.max(1L, settings.animTickDelay));
    }

    private static void setTransform(BlockDisplay display, Vector offset, Vector axis, float angle) {
        display.setTransformation(new Transformation(
                new Vector3f((float) offset.getX() - 0.5f,
                        (float) offset.getY() - 0.5f,
                        (float) offset.getZ() - 0.5f),
                new AxisAngle4f(angle, (float) axis.getX(), (float) axis.getY(), (float) axis.getZ()),
                new Vector3f(1, 1, 1), new AxisAngle4f(0, 0, 1, 0)));
    }

    private static Vector rotate(Vector vector, Vector axis, double angle) {
        double cos = Math.cos(angle), sin = Math.sin(angle);
        Vector a = axis.clone().normalize();
        Vector cross = a.clone().crossProduct(vector);
        double dot = a.dot(vector);
        return vector.clone().multiply(cos).add(cross.multiply(sin))
                .add(a.multiply(dot * (1.0 - cos)));
    }

    private record VisualBlock(BlockDisplay entity, Vector relative) {}

    private static void giveRewards(TreeFallPlugin plugin, World world, Location center,
                                    TreeDropCalculator.DropResult drops, Player player,
                                    int toolSlot, ItemStack toolSnapshot, TreeBlocks falling,
                                    Random random) {
        dropScattered(world, center, drops.leafDrops(), 2.5, random);
        dropScattered(world, center, drops.logDrops(), 2.5, random);
        if (drops.sticks() > 0) scatterItem(world, center,
                new ItemStack(Material.STICK, drops.sticks()), 2.5, random);
        if (drops.saplingType() != null && drops.saplings() > 0) scatterItem(world, center,
                new ItemStack(drops.saplingType(), drops.saplings()), 2.5, random);
        if (drops.fruitType() != null && drops.fruits() > 0) scatterItem(world, center,
                new ItemStack(drops.fruitType(), drops.fruits()), 2.5, random);
        if (plugin.settings.damageTool) ToolDamageHandler.damageTool(player, toolSlot,
                toolSnapshot, falling.logs().size(), random);
    }

    private static void dropScattered(World world, Location center,
                                      Map<Material, Integer> items, double radius, Random random) {
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
