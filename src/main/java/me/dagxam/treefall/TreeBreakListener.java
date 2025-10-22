package me.dagxam.treefall;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

public class TreeBreakListener implements Listener {
    private final Random random = new Random();

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Block base = event.getBlock();
        if (!isLog(base.getType())) return;

        // проверяем, что это нижний блок
        if (isLog(base.getRelative(BlockFace.DOWN).getType())) return;

        Set<Block> blocks = new HashSet<>();
        findTree(base, blocks, 0, 512);
        if (blocks.isEmpty()) return;

        animateDestruction(base.getWorld(), blocks);
    }

    private void animateDestruction(World world, Set<Block> blocks) {
        // разрушение послойно снизу вверх
        Block[] arr = blocks.toArray(new Block[0]);
        for (int i = 0; i < arr.length; i++) {
            final Block b = arr[i];
            new BukkitRunnable() {
                int step = 0;
                final int total = 8; // сколько «тресков» перед исчезновением

                @Override
                public void run() {
                    if (b.getType() == Material.AIR) {
                        cancel();
                        return;
                    }

                    // эффект ломания
                    world.spawnParticle(Particle.BLOCK_CRACK,
                            b.getLocation().add(0.5, 0.5, 0.5),
                            10, 0.25, 0.25, 0.25, b.getBlockData());
                    world.playSound(b.getLocation(),
                            Sound.BLOCK_WOOD_HIT, 0.5f, 0.9f + random.nextFloat() * 0.2f);

                    step++;
                    if (step >= total) {
                        Material m = b.getType();
                        b.setType(Material.AIR);
                        // выпадение лута
                        if (isLog(m)) {
                            world.dropItemNaturally(b.getLocation(), new ItemStack(m));
                        } else if (isLeaf(m)) {
                            dropLeafLoot(world, b);
                        }
                        cancel();
                    }
                }
            }.runTaskTimer(TreeFallPlugin.getInstance(), i * 2L, 4L);
        }
    }

    private void findTree(Block start, Set<Block> found, int depth, int max) {
        if (depth > max || found.contains(start)) return;
        Material type = start.getType();
        if (!isLog(type) && !isLeaf(type)) return;
        found.add(start);

        for (BlockFace f : BlockFace.values()) {
            findTree(start.getRelative(f), found, depth + 1, max);
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

    private boolean isLog(Material m) {
        String n = m.name();
        return n.endsWith("_LOG") || n.endsWith("_STEM");
    }

    private boolean isLeaf(Material m) {
        String n = m.name();
        return n.endsWith("_LEAVES") || n.endsWith("_WART_BLOCK");
    }

    private Material getSaplingForLeaf(Material leaf) {
        String n = leaf.name();
        if (n.contains("OAK")) return Material.OAK_SAPLING;
        if (n.contains("BIRCH")) return Material.BIRCH_SAPLING;
        if (n.contains("SPRUCE")) return Material.SPRUCE_SAPLING;
        if (n.contains("JUNGLE")) return Material.JUNGLE_SAPLING;
        if (n.contains("ACACIA")) return Material.ACACIA_SAPLING;
        if (n.contains("CHERRY")) return Material.CHERRY_SAPLING;
        if (n.contains("DARK_OAK")) return Material.DARK_OAK_SAPLING;
        if (n.contains("MANGROVE")) return Material.MANGROVE_PROPAGULE;
        return null;
    }

    private Material getFruitForLeaf(Material leaf) {
        String n = leaf.name();
        if (n.contains("OAK") || n.contains("DARK_OAK")) return Material.APPLE;
        if (n.contains("CHERRY")) return Material.PINK_PETALS;
        return null;
    }
}
