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

        // Проверим, что это нижний блок дерева: под ним не лог
        Block below = block.getRelative(BlockFace.DOWN);
        if (isLog(below.getType())) return;

        int maxBlocks = 512;

        Set<Block> treeBlocks = new HashSet<>();
        findTree(block, treeBlocks, new HashSet<>(), 0, maxBlocks);
        if (treeBlocks.isEmpty()) return;

        World world = block.getWorld();

        // Сохраняем оригинальные блоки
        List<BlockStateData> blocks = new ArrayList<>();
        for (Block b : treeBlocks) {
            blocks.add(new BlockStateData(b.getLocation().clone(), b.getBlockData()));
        }

        // Удаляем оригинальные блоки
        for (Block b : treeBlocks) {
            b.setType(Material.AIR, false);
        }

        playFallAnimation(world, blocks);
    }

    private void playFallAnimation(World world, List<BlockStateData> treeBlocks) {
        Location center = getCenter(treeBlocks);
        List<BlockDisplay> displays = new ArrayList<>();

        // создаём дисплеи для каждого блока
        for (BlockStateData data : treeBlocks) {
            try {
                BlockDisplay display = (BlockDisplay) world.spawnEntity(data.pos, EntityType.BLOCK_DISPLAY);
                display.setBlock(data.data);
                display.setViewRange(32);
                display.setInterpolationDuration(10);
                displays.add(display);
            } catch (Throwable ignored) {
                // если Spigot — без анимации
            }
        }

        // анимация падения
        new BukkitRunnable() {
            int tick = 0;
            final Vector velocity = new Vector(0, -0.15, 0);
            final int duration = 30; // ~1.5 сек

            @Override
            public void run() {
                tick++;
                for (BlockDisplay d : displays) {
                    d.teleport(d.getLocation().add(velocity));
                }

                // Эффекты
                if (tick % 5 == 0) {
                    world.spawnParticle(Particle.BLOCK_CRACK, center, 10, 0.4, 0.4, 0.4, Material.OAK_LOG.createBlockData());
                    world.playSound(center, Sound.BLOCK_WOOD_BREAK, 0.8f, 1.0f);
                }

                if (tick >= duration) {
                    // закончили падение
                    this.cancel();
                    landTree(world, displays);
                }
            }
        }.runTaskTimer(TreeFallPlugin.getInstance(), 1L, 1L);
    }

    private void landTree(World world, List<BlockDisplay> displays) {
        // Проверяем, на какой "высоте" можно поставить дерево
        int minY = Integer.MAX_VALUE;
        for (BlockDisplay d : displays) {
            int y = d.getLocation().getBlockY();
            if (y < minY) minY = y;
        }

        int dropY = findGroundY(world, displays.get(0).getLocation());

        // Ставим дерево на землю и удаляем дисплеи
        for (BlockDisplay d : displays) {
            Location pos = d.getLocation();
            Block target = world.getBlockAt(pos.getBlockX(), dropY + (pos.getBlockY() - minY), pos.getBlockZ());
            try {
                if (isLog(d.getBlock().getMaterial()) || isLeaf(d.getBlock().getMaterial())) {
                    target.setBlockData(d.getBlock());
                }
            } catch (Throwable ignored) {}
            d.remove();
        }

        // Дроп фруктов/палок из листвы
        for (BlockDisplay d : displays) {
            if (isLeaf(d.getBlock().getMaterial())) {
                dropLeafLoot(world.getBlockAt(d.getLocation()));
            }
        }
    }

    private int findGroundY(World world, Location start) {
        int y = start.getBlockY();
        while (y > world.getMinHeight() && world.getBlockAt(start.getBlockX(), y - 1, start.getBlockZ()).getType() == Material.AIR) {
            y--;
        }
        return y;
    }

    private static class BlockStateData {
        Location pos;
        org.bukkit.block.data.BlockData data;
        BlockStateData(Location pos, org.bukkit.block.data.BlockData data) {
            this.pos = pos;
            this.data = data;
        }
    }

    private Location getCenter(List<BlockStateData> blocks) {
        double x=0, y=0, z=0;
        for (BlockStateData b : blocks) {
            x += b.pos.getX(); y += b.pos.getY(); z += b.pos.getZ();
        }
        return blocks.isEmpty() ? new Location(Bukkit.getWorlds().get(0), 0,0,0)
                : new Location(blocks.get(0).pos.getWorld(), x/blocks.size(), y/blocks.size(), z/blocks.size());
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

    /** Дроп предметов как при распаде листвы */
    private void dropLeafLoot(Block leaf) {
        World world = leaf.getWorld();
        double saplingChance = 0.05;
        double stickChance   = 0.02;
        double fruitChance   = 0.01;

        String name = leaf.getType().name();
        Material sapling = getSaplingForLeaf(name);
        Material fruit   = getFruitForLeaf(name);

        if (sapling != null && random.nextDouble() < saplingChance) {
            world.dropItemNaturally(leaf.getLocation(), new org.bukkit.inventory.ItemStack(sapling));
        }
        if (random.nextDouble() < stickChance) {
            world.dropItemNaturally(leaf.getLocation(), new org.bukkit.inventory.ItemStack(Material.STICK));
        }
        if (fruit != null && random.nextDouble() < fruitChance) {
            world.dropItemNaturally(leaf.getLocation(), new org.bukkit.inventory.ItemStack(fruit));
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
            return Material.PINK_PETALS;
        }
        return null;
    }
}
