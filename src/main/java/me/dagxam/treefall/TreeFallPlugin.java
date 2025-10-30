package me.dagxam.treefall;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;          //  ← добавь эту строку
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;

public class TreeFallPlugin extends JavaPlugin implements Listener {

    private static final Map<Material, Material> LOG_TO_LEAF = new HashMap<>();

    static {
        LOG_TO_LEAF.put(Material.OAK_LOG, Material.OAK_LEAVES);
        LOG_TO_LEAF.put(Material.BIRCH_LOG, Material.BIRCH_LEAVES);
        LOG_TO_LEAF.put(Material.SPRUCE_LOG, Material.SPRUCE_LEAVES);
        LOG_TO_LEAF.put(Material.JUNGLE_LOG, Material.JUNGLE_LEAVES);
        LOG_TO_LEAF.put(Material.ACACIA_LOG, Material.ACACIA_LEAVES);
        LOG_TO_LEAF.put(Material.DARK_OAK_LOG, Material.DARK_OAK_LEAVES);
        LOG_TO_LEAF.put(Material.MANGROVE_LOG, Material.MANGROVE_LEAVES);
        // новые типы можно будет добавить при обновлении ядра
    }

    @Override
    public void onEnable() {
        getLogger().info("TreeFallPlugin включён");
        getServer().getPluginManager().registerEvents(this, this);
    }

    @EventHandler
    public void onLogBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        Material brokenType = block.getType();
        if (!LOG_TO_LEAF.containsKey(brokenType)) return;

        event.setCancelled(true);
        Player player = event.getPlayer();
        Material leafType = LOG_TO_LEAF.get(brokenType);

        Set<Block> treeBlocks = collectTree(block, brokenType, leafType);
        if (treeBlocks.isEmpty()) return;

        playFallAnimation(treeBlocks, player, brokenType, leafType);
    }

    private Set<Block> collectTree(Block start, Material logType, Material leafType) {
        Set<Block> result = new HashSet<>();
        Queue<Block> queue = new LinkedList<>();
        queue.add(start);

        while (!queue.isEmpty()) {
            Block current = queue.poll();
            if (!result.add(current)) continue;

            Material type = current.getType();
            if (type != logType && type != leafType) continue;

            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        Block nearby = current.getRelative(dx, dy, dz);
                        if (!result.contains(nearby)) queue.add(nearby);
                    }
                }
            }
        }
        return result;
    }

    private void playFallAnimation(Set<Block> blocks, Player player,
                                   Material logType, Material leafType) {

        World world = player.getWorld();

        new BukkitRunnable() {
            double progress = 0;

            @Override
            public void run() {
                progress += 0.1;
                for (Block b : blocks) {
                    world.spawnParticle(
                            Particle.BLOCK,                     // универсальная частица
                            b.getLocation().add(0.5, 1, 0.5),
                            3, 0.2, 0.2, 0.2,
                            b.getBlockData()
                    );
                }

                if (progress >= 1.0) {
                    new BukkitRunnable() {
                        @Override
                        public void run() {
                            for (Block b : blocks) {
                                Material type = b.getType();
                                if (type == logType || type == leafType) {
                                    world.spawnParticle(
                                            Particle.BLOCK,
                                            b.getLocation().add(0.5, 0.5, 0.5),
                                            10, 0.3, 0.3, 0.3,
                                            b.getBlockData()
                                    );
                                    world.playSound(b.getLocation(), Sound.BLOCK_GRASS_BREAK, 0.6f, 1.2f);
                                    b.setType(Material.AIR);
                                    world.dropItemNaturally(b.getLocation(), new ItemStack(type));
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

        for (Block b : blocks) {
            if (random.nextFloat() < 0.25f)
                world.dropItemNaturally(b.getLocation(), new ItemStack(Material.STICK, random.nextInt(2) + 1));
            if (sapling != null && random.nextFloat() < 0.2f)
                world.dropItemNaturally(b.getLocation(), new ItemStack(sapling, 1));
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
            default -> null;
        };
    }
}
