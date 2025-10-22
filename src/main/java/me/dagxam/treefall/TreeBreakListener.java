package me.dagxam.treefall;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

public class TreeBreakListener implements Listener {

    private final Random random = new Random();

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (!isLog(block.getType())) return;

        // Под данными бревном должен быть воздух/трава, а не камень — значит это нижний блок
        Block below = block.getRelative(BlockFace.DOWN);
        if (below.getType().isSolid()) return;

        int maxBlocks = TreeFallPlugin.getInstance().getConfig().getInt("max-blocks", 512);

        Set<Block> treeBlocks = new HashSet<>();
        findTree(block, treeBlocks, new HashSet<>(), 0, maxBlocks);

        for (Block b : treeBlocks) {
            Material type = b.getType();
            if (isLog(type)) {
                b.breakNaturally();
            } else if (isLeaf(type)) {
                b.setType(Material.AIR);
                dropLeafLoot(b);
            }
        }
    }

    private void findTree(Block start, Set<Block> found, Set<Block> visited, int depth, int max) {
        if (depth > max || visited.contains(start)) return;
        visited.add(start);

        Material type = start.getType();
        if (!isLog(type) && !isLeaf(type)) return;

        found.add(start);
        for (BlockFace face : BlockFace.values()) {
            findTree(start.getRelative(face), found, visited, depth + 1, max);
        }
    }

    private boolean isLog(Material m) {
        String n = m.name();
        return n.endsWith("_LOG") || n.endsWith("_STEM");
    }

    private boolean isLeaf(Material m) {
        String n = m.name();
        return n.endsWith("_LEAVES") || n.endsWith("_WART_BLOCK");
    }

    // Дроп предметов из листвы
    private void dropLeafLoot(Block leaf) {
        var cfg = TreeFallPlugin.getInstance().getConfig();
        double saplingChance = cfg.getDouble("drop.sapling", 0.05);
        double stickChance   = cfg.getDouble("drop.stick", 0.02);
        double fruitChance   = cfg.getDouble("drop.fruit", 0.01);

        String name = leaf.getType().name();
        Material sapling = getSaplingForLeaf(name);
        Material fruit   = getFruitForLeaf(name);

        if (sapling != null && random.nextDouble() < saplingChance)
            leaf.getWorld().dropItemNaturally(leaf.getLocation(),
                    new org.bukkit.inventory.ItemStack(sapling));

        if (random.nextDouble() < stickChance)
            leaf.getWorld().dropItemNaturally(leaf.getLocation(),
                    new org.bukkit.inventory.ItemStack(Material.STICK));

        if (fruit != null && random.nextDouble() < fruitChance)
            leaf.getWorld().dropItemNaturally(leaf.getLocation(),
                    new org.bukkit.inventory.ItemStack(fruit));
    }

    private Material getSaplingForLeaf(String leafName) {
        if (leafName.contains("OAK")) return Material.OAK_SAPLING;
        if (leafName.contains("BIRCH")) return Material.BIRCH_SAPLING;
        if (leafName.contains("SPRUCE")) return Material.SPRUCE_SAPLING;
        if (leafName.contains("JUNGLE")) return Material.JUNGLE_SAPLING;
        if (leafName.contains("ACACIA")) return Material.ACACIA_SAPLING;
        if (leafName.contains("CHERRY")) return Material.CHERRY_SAPLING;
        if (leafName.contains("DARK_OAK")) return Material.DARK_OAK_SAPLING;
        if (leafName.contains("MANGROVE")) return Material.MANGROVE_PROPAGULE;
        return null;
    }

    private Material getFruitForLeaf(String leafName) {
        if (leafName.contains("OAK") || leafName.contains("DARK_OAK")) return Material.APPLE;
        if (leafName.contains("CHERRY")) return Material.CHERRY;
        return null;
    }
}
