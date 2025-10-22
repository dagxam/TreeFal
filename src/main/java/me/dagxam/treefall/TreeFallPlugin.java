package me.dagxam.treefall;

import org.bukkit.Material;
import org.bukkit.TreeType;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashSet;
import java.util.Set;

public final class TreeFallPlugin extends JavaPlugin implements Listener {

    @Override
    public void onEnable() {
        // Регистрируем обработчик событий
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("TreeFallPlugin включён");
    }

    @Override
    public void onDisable() {
        getLogger().info("TreeFallPlugin выключен");
    }

    /**
     * Обработка события разрушения блока (рубка дерева)
     */
    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (!isLog(block.getType())) {
            return; // Не бревно — пропускаем
        }

        // Собираем все связанные брёвна и листья дерева
        Set<Block> treeBlocks = new HashSet<>();
        collectTree(block, treeBlocks);

        // Копаем дерево асинхронно (эффект "падает дерево")
        new BukkitRunnable() {
            @Override
            public void run() {
                for (Block b : treeBlocks) {
                    b.breakNaturally();
                }
            }
        }.runTaskLater(this, 1L);
    }

    /**
     * Проверяем, является ли блок бревном
     */
    private boolean isLog(Material material) {
        return material.name().endsWith("_LOG");
    }

    /**
     * Проверяем, является ли блок листом
     */
    private boolean isLeaf(Material material) {
        return material.name().endsWith("_LEAVES");
    }

    /**
     * Рекурсивно собираем все части дерева (брёвна и листья)
     */
    private void collectTree(Block block, Set<Block> collected) {
        if (collected.contains(block)) return;

        if (isLog(block.getType()) || isLeaf(block.getType())) {
            collected.add(block);

            for (Block relative : getAdjacentBlocks(block)) {
                collectTree(relative, collected);
            }
        }
    }

    /**
     * Получаем соседние блоки для проверки связности дерева
     */
    private Block[] getAdjacentBlocks(Block block) {
        return new Block[]{
                block.getRelative(1, 0, 0),
                block.getRelative(-1, 0, 0),
                block.getRelative(0, 1, 0),
                block.getRelative(0, -1, 0),
                block.getRelative(0, 0, 1),
                block.getRelative(0, 0, -1)
        };
    }
}
