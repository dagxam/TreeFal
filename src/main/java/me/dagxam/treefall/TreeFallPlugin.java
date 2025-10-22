package me.dagxam.treefall;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

public class TreeFallPlugin extends JavaPlugin implements Listener {

    private static TreeFallPlugin instance;
    private final Random random = new Random();

    public static TreeFallPlugin getInstance() {
        return instance;
    }

    @Override
    public void onEnable() {
        instance = this;
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("TreeFallPlugin enabled!");
    }

    @Override
    public void onDisable() {
        getLogger().info("TreeFallPlugin disabled!");
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        if (event.isCancelled()) return;

        Block block = event.getBlock();

        // Проверяем, что сломанный блок — древесина
        if (!isLog(block.getType())) return;

        // Проверяем, нет ли под ним другого лог-блока (значит, это не нижняя часть ствола)
        if (isLog(block.getRelative(BlockFace.DOWN).getType())) return;

        Set<Block> blocks = new HashSet<>();
        findTree(block, blocks, 0, 500);

        if (blocks.isEmpty()) return;

        animateDestruction(block.getWorld(), blocks);
    }

    private boolean isLog(Material type) {
        String name = type.name().toLowerCase();
        return name.contains("_log") || name.contains("_stem") || name.contains("_hyphae");
    }

    private boolean isLeaf(Material type) {
        String name = type.name().toLowerCase();
        return name.contains("_leaves") || name.contains("_wart_block");
    }

    private void findTree(Block block, Set<Block> allBlocks, int depth, int limit) {
        if (depth > limit) return;
        if (allBlocks.contains(block)) return;

        if (isLog(block.getType()) || isLeaf(block.getType())) {
            allBlocks.add(block);
            for (BlockFace face : BlockFace.values()) {
                Block adjacent = block.getRelative(face);
                findTree(adjacent, allBlocks, depth + 1, limit);
            }
        }
    }

    private void animateDestruction(World world, Set<Block> blocks) {
        Block[] arr = blocks.toArray(new Block[0]);

        for (int i = 0; i < arr.length; i++) {
            final Block b = arr[i];
            new BukkitRunnable() {
                int step = 0;
                final int total = 8;

                @Override
                public void run() {
                    if (b.getType() == Material.AIR) {
                        cancel();
                        return;
                    }

                    world.spawnParticle(
                            Particle.BLOCK,
                            b.getLocation().add(0.5, 0.5, 0.5),
                            10, 0.25, 0.25, 0.25,
                            b.getBlockData()
                    );

                    world.playSound(
                            b.getLocation(),
                            Sound.BLOCK_WOOD_HIT,
                            0.5f,
                            0.9f + random.nextFloat() * 0.2f
                    );

                    step++;
                    if (step >= total) {
                        Material m = b.getType();
                        b.setType(Material.AIR);
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

    /**
     * Форсированный дроп листвы как предметов + стандартный шанс саженцев, палок, фруктов.
     */
    private void dropLeafLoot(World world, Block leaf) {
        double saplingChance = 0.05;
        double stickChance = 0.02;
        double fruitChance = 0.01;

        Material sapling = getSaplingForLeaf(leaf.getType());
        Material fruit = getFruitForLeaf(leaf.getType());

        // ✅ форсируем выпадение блока листвы
        ItemStack leafItem = new ItemStack(leaf.getType());
        leafItem.setItemMeta(Bukkit.getItemFactory().getItemMeta(leaf.getType()));
        world.dropItemNaturally(leaf.getLocation(), leafItem);

        // 🌱 шанс выпадения саженца
        if (sapling != null && random.nextDouble() < saplingChance) {
            world.dropItemNaturally(leaf.getLocation(), new ItemStack(sapling));
        }

        // 🪵 шанс палки
        if (random.nextDouble() < stickChance) {
            world.dropItemNaturally(leaf.getLocation(), new ItemStack(Material.STICK));
        }

        // 🍎 шанс фрукта (яблоко и т. д.)
        if (fruit != null && random.nextDouble() < fruitChance) {
            world.dropItemNaturally(leaf.getLocation(), new ItemStack(fruit));
        }
    }

    private Material getSaplingForLeaf(Material leafType) {
        String name = leafType.name().toLowerCase();
        if (name.contains("oak")) return Material.OAK_SAPLING;
        if (name.contains("birch")) return Material.BIRCH_SAPLING;
        if (name.contains("spruce")) return Material.SPRUCE_SAPLING;
        if (name.contains("jungle")) return Material.JUNGLE_SAPLING;
        if (name.contains("acacia")) return Material.ACACIA_SAPLING;
        if (name.contains("dark_oak")) return Material.DARK_OAK_SAPLING;
        if (name.contains("cherry")) return Material.CHERRY_SAPLING;
        if (name.contains("mangrove")) return Material.MANGROVE_PROPAGULE;
        return null;
    }

    private Material getFruitForLeaf(Material leafType) {
        String name = leafType.name().toLowerCase();
        if (name.contains("oak")) return Material.APPLE;
        return null;
    }
}
