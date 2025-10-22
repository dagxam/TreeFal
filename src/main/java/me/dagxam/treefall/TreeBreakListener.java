package me.dagxam.treefall;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

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

        var plugin = TreeFallPlugin.getInstance();
var blocks = new java.util.ArrayList<>(treeBlocks);
blocks.sort(java.util.Comparator.comparingInt(Block::getY).reversed());

// плавное разрушение сверху вниз
int delay = 0;
for (Block b : blocks) {
    new BukkitRunnable() {
        @Override
        public void run() {
            Material type = b.getType();
            if (isLog(type) || isLeaf(type)) {
                animateBlock(b); // см. ниже
            }
        }
    }.runTaskLater(plugin, delay);

    delay += 3; // каждые 3 тика ≈ 0.15 с
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

    private void animateBlock(Block b) {
    var world = b.getWorld();
    var loc = b.getLocation().add(0.5, 0.5, 0.5);
    var type = b.getType();

    // попытка использовать DisplayEntity (если Paper)
    try {
        BlockDisplay disp = (BlockDisplay) world.spawnEntity(loc, EntityType.BLOCK_DISPLAY);
        disp.setBlock(b.getBlockData());
        disp.setViewRange(32);
        disp.setInterpolationDuration(10);

        Vector vel = new Vector(
                (Math.random() - 0.5) * 0.05,
                -0.10 - Math.random() * 0.05,
                (Math.random() - 0.5) * 0.05
        );
        disp.setVelocity(vel);

        world.spawnParticle(Particle.BLOCK_DUST, loc, 8, 0.3, 0.3, 0.3, b.getBlockData());
        world.playSound(loc, Sound.BLOCK_WOOD_BREAK, 0.8f, 1.0f);

        b.setType(Material.AIR);

        // убираем через 0.8 с
        Bukkit.getScheduler().runTaskLater(TreeFallPlugin.getInstance(), disp::remove, 16L);
    } catch (Throwable ex) {
        // если это Spigot — fallback
        if (isLog(type)) {
            b.breakNaturally();
        } else if (isLeaf(type)) {
            b.setType(Material.AIR);
            dropLeafLoot(b);
        }
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
            // В игре нет материала CHERRY, используем лепестки сакуры
            return Material.PINK_PETALS;
        }
        return null;
    }
}
