package me.dagxam.treefall;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;

public class TreeAnimator {

    public static final String FALLING_TAG = "treefall_falling";

    private final Plugin plugin;
    private final Settings settings;
    private final Random random = new Random();

    public TreeAnimator(Plugin plugin, Settings settings) {
        this.plugin = plugin;
        this.settings = settings;
    }

    /**
     * Все данные, необходимые для дропа после анимации.
     */
    public record FallData(
            Map<Material, Integer> leafDrops,
            Map<Material, Integer> logDrops,
            int sticks,
            Material saplingType,
            int saplings,
            int apples,
            double spread,
            Player player,
            int totalFallingLogs
    ) {}

    /**
     * Запускает анимацию падения: сверху вниз удаляем блоки,
     * спавним FallingBlock, по окончании дропаем предметы.
     */
    public void animate(World world, Location center,
                        TreeDetector.TreeBlocks falling, FallData data) {

        List<Block> allBlocks = new ArrayList<>(falling.logs().size() + falling.leaves().size());
        allBlocks.addAll(falling.logs());
        allBlocks.addAll(falling.leaves());

        // Сортировка сверху вниз
        allBlocks.sort((a, b) -> Integer.compare(b.getY(), a.getY()));

        int maxFalling = Math.min(allBlocks.size(), settings.maxFallingBlocks);

        // Блоки за пределом лимита: удаляем без анимации
        for (int i = maxFalling; i < allBlocks.size(); i++) {
            allBlocks.get(i).setType(Material.AIR, false);
        }

        List<Block> toAnimate = new ArrayList<>(allBlocks.subList(0, maxFalling));

        // Звук начала падения
        world.playSound(center, Sound.BLOCK_WOOD_BREAK, 1.2f, 0.6f);

        new BukkitRunnable() {
            int idx = 0;

            @Override
            public void run() {
                // Анимация закончена — дропаем всё
                if (idx >= toAnimate.size()) {
                    onAnimationComplete(world, center, data);
                    cancel();
                    return;
                }

                int done = 0;
                while (idx < toAnimate.size() && done < settings.blocksPerTick) {
                    Block b = toAnimate.get(idx++);
                    Material type = b.getType();
                    if (type == Material.AIR) {
                        done++;
                        continue;
                    }

                    BlockData blockData = b.getBlockData();
                    Location spawnLoc = b.getLocation().add(0.5, 0.2, 0.5);

                    b.setType(Material.AIR, false);

                    FallingBlock fb = world.spawnFallingBlock(spawnLoc, blockData);
                    fb.setDropItem(false);
                    fb.setHurtEntities(false);
                    fb.addScoreboardTag(FALLING_TAG);

                    double vx = (random.nextDouble() - 0.5) * 0.08;
                    double vz = (random.nextDouble() - 0.5) * 0.08;
                    fb.setVelocity(fb.getVelocity().setX(vx).setZ(vz));

                    // Принудительное удаление через N тиков
                    scheduleRemoval(fb);
                    done++;
                }

                // Случайные звуки треска
                if (random.nextInt(3) == 0) {
                    world.playSound(center, Sound.BLOCK_WOOD_BREAK, 0.6f, 1.0f);
                }
            }
        }.runTaskTimer(plugin, 0L, settings.tickDelay);
    }

    // ========================
    //  Внутренние методы
    // ========================

    private void onAnimationComplete(World world, Location center, FallData data) {
        // Звук приземления
        world.playSound(center, Sound.BLOCK_WOOD_BREAK, 0.8f, 0.8f);

        // Разброс предметов
        dropScattered(world, center, data.leafDrops(), data.spread());
        dropScattered(world, center, data.logDrops(), data.spread());

        if (data.sticks() > 0) {
            scatterItem(world, center, new ItemStack(Material.STICK, data.sticks()), data.spread());
        }
        if (data.saplingType() != null && data.saplings() > 0) {
            scatterItem(world, center, new ItemStack(data.saplingType(), data.saplings()), data.spread());
        }
        if (data.apples() > 0) {
            scatterItem(world, center, new ItemStack(Material.APPLE, data.apples()), data.spread());
        }

        // Дамаг инструмента
        if (settings.damageTool) {
            ToolDamageHandler.damageTool(data.player(), data.totalFallingLogs());
        }
    }

    private void scheduleRemoval(FallingBlock fb) {
        new BukkitRunnable() {
            @Override
            public void run() {
                if (fb.isValid()) fb.remove();
            }
        }.runTaskLater(plugin, settings.fallingBlockLifetimeTicks);
    }

    private void dropScattered(World w, Location center,
                               Map<Material, Integer> items, double radius) {
        for (var entry : items.entrySet()) {
            int left = entry.getValue();
            while (left > 0) {
                int give = Math.min(64, left);
                scatterItem(w, center, new ItemStack(entry.getKey(), give), radius);
                left -= give;
            }
        }
    }

    private void scatterItem(World w, Location center, ItemStack item, double radius) {
        double dx = (random.nextDouble() - 0.5) * radius;
        double dz = (random.nextDouble() - 0.5) * radius;
        Location loc = center.clone().add(dx, 0.2, dz);
        w.dropItemNaturally(loc, item);
    }
}
