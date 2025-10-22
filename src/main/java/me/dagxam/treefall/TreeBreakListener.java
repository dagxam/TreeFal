package me.dagxam.treefall;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

public class TreeBreakListener implements Listener {

    private final Random random = new Random();

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        Material type = block.getType();

        // Не дерево — выходим
        if (!isLog(type)) return;

        // если это не нижний блок (под ним лог) — тоже выходим
        Block below = block.getRelative(BlockFace.DOWN);
        if (isLog(below.getType())) return;

        Set<Block> treeBlocks = new HashSet<>();
        findTree(block, treeBlocks, 0, 512);

        if (treeBlocks.isEmpty()) return;

        World world = block.getWorld();
        // Удаляем дерево и выбрасываем предметы
        for (Block b : treeBlocks) {
            Material m = b.getType();
            b.setType(Material.AIR, false);

            if (isLog(m)) {
                world.dropItemNaturally(b.getLocation(), new ItemStack(m));
            } else if (isLeaf(m)) {
                dropLeafLoot(world, b);
            }
        }
    }

    // Рекурсивный поиск всех блоков ствола и листвы
    private void findTree(Block start, Set<Block> found, int depth, int maxDepth) {
        if (depth > maxDepth || found.contains(start)) return;

        Material type = start.getType();
        if (!isLog(type) && !isLeaf(type)) return;
        found.add(start);

        for (BlockFace face : BlockFace.values()) {
            findTree(start.getRelative(face), found, depth + 1, maxDepth);
        }
    }

    private void dropLeafLoot(World world, Block leaf) {
        double saplingChance = 0.05;
        double stickChance = 0.02;
        double fruitChance = 0.01;

        Material sapling = getSaplingForLeaf(leaf.getType());
        Material fruit = getFruitForLeaf(leaf.getType());

        if (sapling != null && random.nextDouble() < saplingChance) {
            world.dropItemNaturally(leaf.getLocation(), new ItemStack(sapling));
        }
        if (random.nextDouble() < stickChance) {
            world.dropItemNaturally(leaf.getLocation(), new ItemStack(Material.STICK));
        }
        if (fruit != null && random.nextDouble() < fruitChance) {
            world.dropItemNaturally(leaf.getLocation(), new ItemStack(fruit));
        }
    }

    // Определить, является ли блок брёвном
    private boolean isLog(Material m) {
        String n = m.name();
        return n.endsWith("_LOG") || n.endsWith("_STEM");
    }

    // Определить, является ли блок листвой
    private boolean isLeaf(Material m) {
        String n = m.name();
        return n.endsWith("_LEAVES") || n.endsWith("_WART_BLOCK");
    }

    // Вернуть соответствующий саженец
    private Material getSaplingForLeaf(Material leaf) {
        String name = leaf.name();
        if (name.contains("OAK")) return Material.OAK_SAPLING;
        if (name.contains("BIRCH")) return Material.BIRCH_SAPLING;
        if (name.contains("SPRUCE")) return Material.SPRUCE_SAPLING;
        if (name.contains("JUNGLE")) return Material.JUNGLE_SAPLING;
        if (name.contains("ACACIA")) return Material.ACACIA_SAPLING;
        if (name.contains("CHERRY")) return Material.CHERRY_SAPLING;
        if (name.contains("DARK_OAK")) return Material.DARK_OAK_SAPLING;
        if (name.contains("MANGROVE")) return Material.MANGROVE_PROPAGULE;
        return null;
    }

    // Вернуть соответствующий плод (яблоко, лепестки и т.п.)
    private Material getFruitForLeaf(Material leaf) {
        String name = leaf.name();
        if (name.contains("OAK") || name.contains("DARK_OAK")) {
            return Material.APPLE;
        }
        if (name.contains("CHERRY")) {
            return Material.PINK_PETALS;
        }
        return null;
    }
}
