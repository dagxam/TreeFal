package me.dagxam.treefall;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import java.util.*;

public class TreeFallPlugin extends JavaPlugin implements org.bukkit.event.Listener {

    private static final int MAX_TREE_HEIGHT = 50;
    private static final int MAX_RADIUS = 5;
    private static final Map<Material, Material> logToLeaf = new HashMap<>();

    static {
        logToLeaf.put(Material.OAK_LOG, Material.OAK_LEAVES);
        logToLeaf.put(Material.BIRCH_LOG, Material.BIRCH_LEAVES);
        logToLeaf.put(Material.SPRUCE_LOG, Material.SPRUCE_LEAVES);
        logToLeaf.put(Material.JUNGLE_LOG, Material.JUNGLE_LEAVES);
        logToLeaf.put(Material.ACACIA_LOG, Material.ACACIA_LEAVES);
        logToLeaf.put(Material.DARK_OAK_LOG, Material.DARK_OAK_LEAVES);
        logToLeaf.put(Material.MANGROVE_LOG, Material.MANGROVE_LEAVES);
    }

    @Override
    public void onEnable() {
        Bukkit.getPluginManager().registerEvents(this, this);
        getLogger().info("TreeFallPlugin включён");
    }

    @org.bukkit.event.EventHandler
    public void onLogBreak(org.bukkit.event.block.BlockBreakEvent event) {
        Block block = event.getBlock();
        Material brokenType = block.getType();

        if (!logToLeaf.containsKey(brokenType)) return;
        org.bukkit.entity.Player player = event.getPlayer();

        Material leafType = logToLeaf.get(brokenType);
        Set<Block> treeBlocks = collectTree(block, brokenType, leafType);
        if (treeBlocks.isEmpty()) return;

        event.setCancelled(true);
        worldFallAnimation(treeBlocks, player, brokenType, leafType);
    }

    private Set<Block> collectTree(Block start, Material logType, Material leafType) {
        Set<Block> result = new HashSet<>();
        Queue<Block> queue = new LinkedList<>();
        queue.add(start);

        while (!queue.isEmpty()) {
            Block current = queue.poll();
            if (result.contains(current)) continue;

            Material type = current.getType();
            if (type == logType || type == leafType) {
                result.add(current);
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            Block nearby = current.getRelative(dx, dy, dz);
                            if (!result.contains(nearby) &&
                                nearby.getType().isSolid() &&
                                (nearby.getType() == logType || nearby.getType() == leafType)) {
                                queue.add(nearby);
                            }
                        }
                    }
                }
            }
        }
        return result;
    }

    private void worldFallAnimation(Set<Block> blocks, org.bukkit.entity.Player player,
                                    Material logType, Material leafType) {
        World world = player.getWorld();
        Random random = new Random();

        new BukkitRunnable() {
            double progress = 0;
            final double maxProgress = 1.0;

            @Override
            public void run() {
                progress += 0.1;
                for (Block b : blocks) {
                    world.spawnParticle(Particle.LEAVES, b.getLocation().add(0.5, 1, 0.5), 3);
                }

                if (progress >= maxProgress) {
                    new BukkitRunnable() {
                        @Override
                        public void run() {
                            for (Block b : blocks) {
                                Material type = b.getType();
                                if (type == logType || type == leafType) {
                                    world.spawnParticle(Particle.BLOCK_CRACK, b.getLocation().add(0.5, 0.5, 0.5),
                                            10, 0.3, 0.3, 0.3, b.getBlockData());
                                    world.playSound(b.getLocation(), Sound.BLOCK_GRASS_BREAK, 0.5f, 1.2f);
                                    b.setType(Material.AIR);
                                    b.getWorld().dropItemNaturally(b.getLocation(), new org.bukkit.inventory.ItemStack(type));
                                }
                            }
                            dropExtraLoot(world, blocks, leafType);
                        }
                    }.runTaskLater(TreeFallPlugin.this, 5L);
                    cancel();
                }
            }
        }.runTaskTimer(this, 0L, 3L);
    }

    private void dropExtraLoot(World world, Set<Block> blocks, Material leafType) {
        Random random = new Random();
        Material sapling = getSaplingForLeaf(leafType);
        Material fruit = getFruitForLeaf(leafType);

        for (Block b : blocks) {
            if (random.nextFloat() < 0.25f) {
                world.dropItemNaturally(b.getLocation(), new org.bukkit.inventory.ItemStack(Material.STICK, random.nextInt(2) + 1));
            }
            if (sapling != null && random.nextFloat() < 0.2f) {
                world.dropItemNaturally(b.getLocation(), new org.bukkit.inventory.ItemStack(sapling, 1));
            }
            if (fruit != null && random.nextFloat() < 0.15f) {
                world.dropItemNaturally(b.getLocation(), new org.bukkit.inventory.ItemStack(fruit, 1));
            }
        }
    }

    private Material getSaplingForLeaf(Material leafType) {
        return switch (leafType) {
            case OAK_LEAVES -> Material.OAK_SAPLING;
            case SPRUCE_LEAVES -> Material.SPRUCE_SAPLING;
            case BIRCH_LEAVES -> Material.BIRCH_SAPLING;
            case JUNGLE_LEAVES -> Material.JUNGLE_SAPLING;
            case ACACIA_LEAVES -> Material.ACACIA_SAPLING;
            case DARK_OAK_LEAVES -> Material.DARK_OAK_SAPLING;
            case MANGROVE_LEAVES -> Material.MANGROVE_PROPAGULE;
            case CHERRY_LEAVES -> Material.CHERRY_SAPLING;
            case AZALEA_LEAVES -> Material.AZALEA;
            case FLOWERING_AZALEA_LEAVES -> Material.FLOWERING_AZALEА;
            default -> Material.OAK_SAPLING;
        };
    }

    private Material getFruitForLeaf(Material leafType) {
        return switch (leafType) {
            case OAK_LEAVES -> Material.APPLE;
            case AZALEA_LEAVES, FLOWERING_AZALEA_LEAVES -> Material.GLOW_BERRIES;
            case CHERRY_LEAVES -> Material.CHERRY;
            case MANGROVE_LEAVES -> Material.MANGROVE_PROPAGULE;
            default -> null;
        };
    }
}
