package me.dagxam.treefall;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

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

        // Проверяем: это ли блок дерева
        if (!LOG_TO_LEAF.containsKey(brokenType))
            return;

        event.setCancelled(true);
        Player player = event.getPlayer();
        Material leafType = LOG_TO_LEAF.get(brokenType);

        // Собираем все блоки дерева
        Set<Block> treeBlocks = collectTree(block, brokenType, leafType);
        if (treeBlocks.isEmpty()) return;

        // Сразу "роняем" дерево (удаляем + дропаем)
        breakTreeImmediately(treeBlocks, player, brokenType, leafType);
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
                        if (!result.contains(nearby))
                            queue.add(nearby);
                    }
                }
            }
        }
        return result;
    }

    private void breakTreeImmediately(Set<Block> blocks, Player player,
                                      Material logType, Material leafType) {

        World world = player.getWorld();
        int logsCount = 0;
        int leavesCount = 0;

        // Подсчёт количества блоков дерева
        for (Block b : blocks) {
            Material type = b.getType();
            if (type == logType) logsCount++;
            else if (type == leafType) leavesCount++;
        }

        // Удаляем дерево и проигрываем эффект
        for (Block b : blocks) {
            Material type = b.getType();
            if (type == logType || type == leafType) {
                world.spawnParticle(
                        Particle.BLOCK,
                        b.getLocation().add(0.5, 0.5, 0.5),
                        8, 0.3, 0.3, 0.3,
                        b.getBlockData()
                );
                world.playSound(b.getLocation(), Sound.BLOCK_GRASS_BREAK, 0.6f, 1.2f);
                b.setType(Material.AIR);
            }
        }

        // Дроп древесины и листвы
        if (logsCount > 0)
            world.dropItemNaturally(player.getLocation(), new ItemStack(logType, logsCount));
        if (leavesCount > 0)
            world.dropItemNaturally(player.getLocation(), new ItemStack(leafType, leavesCount));

        // Добавочный лут (палки, фрукты, саженцы)
        dropExtraLoot(world, player.getLocation(), leafType);
    }

    private void dropExtraLoot(World world, Location loc, Material leafType) {
        Random random = new Random();
        Material sapling = getSaplingForLeaf(leafType);
        Material fruit = getFruitForLeaf(leafType);

        int stickCount = random.nextInt(3) + 5;         // 5–7 палок
        int fruitCount = (fruit != null) ? random.nextInt(3) + 2 : 0;   // 2–4 яблок/плодов
        int saplingCount = (sapling != null) ? random.nextInt(3) + 1 : 0; // 1–3 саженца

        if (stickCount > 0)
            world.dropItemNaturally(loc, new ItemStack(Material.STICK, stickCount));
        if (fruitCount > 0)
            world.dropItemNaturally(loc, new ItemStack(fruit, fruitCount));
        if (saplingCount > 0)
            world.dropItemNaturally(loc, new ItemStack(sapling, saplingCount));
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

    private Material getFruitForLeaf(Material leafType) {
        return switch (leafType) {
            case OAK_LEAVES, DARK_OAK_LEAVES -> Material.APPLE;
            case MANGROVE_LEAVES -> Material.MANGROVE_PROPAGULE;
            default -> null;
        };
    }
}
