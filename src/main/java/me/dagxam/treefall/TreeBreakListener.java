package me.dagxam.treefall;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.EntityType;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.*;

public class TreeBreakListener implements Listener {

    private final Random random = new Random();

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (!isLog(block.getType())) return;

        // Под данным бревном должен быть воздух/трава, а не камень — значит это нижний блок
        Block below = block.getRelative(BlockFace.DOWN);
        if (below.getType().isSolid()) return;

        int maxBlocks = TreeFallPlugin.getInstance().getConfig().getInt("max-blocks", 512);

        Set<Block> treeBlocks = new HashSet<>();
        findTree(block, treeBlocks, new HashSet<>(), 0, maxBlocks);

        // Сортируем сверху вниз для эффекта "падения"
        List<Block> blocks = new ArrayList<>(treeBlocks);
        blocks.sort(Comparator.comparingInt(Block::getY).reversed());

        int delay = 0;
        for (Block b : blocks) {
            new BukkitRunnable() {
                @Override
                public void run() {
                    Material type = b.getType();
                    if (isLog(type) || isLeaf(type)) {
                        animateBlock(b);
                    }
                }
            }.runTaskLater(TreeFallPlugin.getInstance(), delay);
            delay += 3; // каждые 3 тика ≈0.15 с
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

    private void animateBlock(Block b) {
        World world = b.getWorld();
        Location loc = b.getLocation().add(0.5, 0.5, 0.5);
        Material type = b.getType();

        // попытка использовать BlockDisplay (Paper 1.20+)
        try {
            BlockDisplay display = (BlockDisplay) world.spawnEntity(loc, EntityType.BLOCK_DISPLAY);
            display.setBlock(b.getBlockData());
            display.setViewRange(32);
            display.setInterpolationDuration(10);

            Vector vel = new Vector(
                    (Math.random() - 0.5) * 0.05,
                    -0.10 - Math.random() * 0.05,
                    (Math.random() - 0.5) * 0.05
            );
            display.setVelocity(vel);

            world.spawnParticle(Particle.BLOCK_DUST, loc, 8, 0.3, 0.3, 0.3, b.getBlockData());
            world.playSound(loc, Sound.BLOCK_WOOD_BREAK, 0.8f, 1.0f);

            b.setType(Material.AIR);

            Bukkit.getScheduler().runTaskLater(TreeFallPlugin.getInstance(), display::remove, 16L);
        } catch (Throwable t) {
            // если DisplayEntity не поддерживается, обычное разрушение
            if (isLog(type)) {
                b.breakNaturally();
            } else if (isLeaf(type)) {
                b.setType(Material.AIR);
                dropLeafLoot(b);
            }
        }
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

        if (sapling != null && random.nextDouble() < saplingChance) {
            leaf.getWorld().dropItemNaturally(leaf.getLocation(),
                    new org.bukkit.inventory.ItemStack(sapling));
        }

        if (random.nextDouble() < stickChance) {
            leaf.getWorld().dropItemNaturally(leaf.getLocation(),
                    new org.bukkit.inventory.ItemStack(Material.STICK));
        }

        if (fruit != null && random.nextDouble() < fruitChance) {
            leaf.getWorld().dropItemNaturally(leaf.getLocation(),
                    new org.bukkit.inventory.ItemStack(fruit));
        }
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
        if (leafName.contains("OAK") || leafName.contains("DARK_OAK")) {
            return Material.APPLE;
        }
        if (leafName.contains("CHERRY")) {
            // В игре нет CHERRY, используем лепестки сакуры
            return Material.PINK_PETALS;
        }
        return null;
    }
}
