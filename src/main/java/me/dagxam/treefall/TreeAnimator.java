// src/main/java/me/dagxam/treefall/TreeAnimator.java

package me.dagxam.treefall;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;

public final class TreeAnimator {

    private TreeAnimator() {}

    public static void play(TreeFallPlugin plugin, World world, Location center,
                            TreeBlocks falling,
                            TreeDropCalculator.DropResult drops,
                            Player player) {

        Settings settings = plugin.settings;
        Random random = plugin.random;

        double spread = falling.leaves().size() >= Settings.BIG_TREE_LEAVES ? 3.5 : 1.8;

        List<Block> animBlocks = new ArrayList<>(falling.logs().size() + falling.leaves().size());
        animBlocks.addAll(falling.logs());
        animBlocks.addAll(falling.leaves());

        // Сортировка сверху вниз
        animBlocks.sort((a, b) -> Integer.compare(b.getY(), a.getY()));

        // Ограничение числа FallingBlock; остальные просто удаляются
        int maxFalling = Math.min(animBlocks.size(), settings.maxFallingBlocks);

        // Блоки за пределами лимита — просто удаляем без анимации
        for (int i = maxFalling; i < animBlocks.size(); i++) {
            animBlocks.get(i).setType(Material.AIR, false);
        }
        List<Block> animated = animBlocks.subList(0, maxFalling);

        int batch = settings.animBlocksPerTick;
        long tickDelay = settings.animTickDelay;

        // Звук начала падения
        world.playSound(center, Sound.BLOCK_WOOD_BREAK, 1.2f, 0.6f);

        new BukkitRunnable() {
            int idx = 0;

            @Override
            public void run() {
                if (idx >= animated.size()) {
                    // Звук приземления
                    world.playSound(center, Sound.BLOCK_WOOD_BREAK, 1.0f, 0.8f);

                    // Дропы
                    dropScattered(world, center, drops.leafDrops(), spread, random);
                    dropScattered(world, center, drops.logDrops(), spread, random);

                    if (drops.sticks() > 0)
                        scatterItem(world, center, new ItemStack(Material.STICK, drops.sticks()), spread, random);
                    if (drops.saplingType() != null && drops.saplings() > 0)
                        scatterItem(world, center, new ItemStack(drops.saplingType(), drops.saplings()), spread, random);
                    if (drops.apples() > 0)
                        scatterItem(world, center, new ItemStack(Material.APPLE, drops.apples()), spread, random);

                    if (settings.damageTool) {
                        ToolDamageHandler.damageTool(player, falling.logs().size(), random);
                    }

                    cancel();
                    return;
                }

                int done = 0;
                while (idx < animated.size() && done < batch) {
                    Block b = animated.get(idx++);
                    Material type = b.getType();
                    if (type == Material.AIR) { done++; continue; }

                    BlockData data = b.getBlockData();
                    Location spawnLoc = b.getLocation().add(0.5, 0.2, 0.5);

                    b.setType(Material.AIR, false);

                    FallingBlock fb = world.spawnFallingBlock(spawnLoc, data);
                    fb.setDropItem(false);
                    fb.setHurtEntities(false);
                    fb.addScoreboardTag(TreeFallPlugin.FALLING_TAG);

                    double vx = (random.nextDouble() - 0.5) * 0.08;
                    double vz = (random.nextDouble() - 0.5) * 0.08;
                    fb.setVelocity(fb.getVelocity().setX(vx).setZ(vz));

                    // Авто-удаление через 5 секунд
                    new BukkitRunnable() {
                        @Override
                        public void run() {
                            if (fb.isValid()) fb.remove();
                        }
                    }.runTaskLater(plugin, 100L);

                    done++;
                }

                if (random.nextInt(3) == 0) {
                    world.playSound(center, Sound.BLOCK_WOOD_BREAK, 0.6f, 1.0f);
                }
            }
        }.runTaskTimer(plugin, 0L, tickDelay);
    }

    private static void dropScattered(World w, Location center, Map<Material, Integer> items,
                                      double radius, Random random) {
        for (var e : items.entrySet()) {
            int left = e.getValue();
            while (left > 0) {
                int give = Math.min(64, left);
                scatterItem(w, center, new ItemStack(e.getKey(), give), radius, random);
                left -= give;
            }
        }
    }

    private static void scatterItem(World w, Location center, ItemStack it,
                                    double radius, Random random) {
        double dx = (random.nextDouble() - 0.5) * radius;
        double dz = (random.nextDouble() - 0.5) * radius;
        Location l = center.clone().add(dx, 0.2, dz);
        w.dropItemNaturally(l, it);
    }
}
