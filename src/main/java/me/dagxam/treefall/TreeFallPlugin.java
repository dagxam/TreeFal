package me.dagxam.treefallplugin;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;
import java.util.stream.Collectors;

public class TreeFallPlugin extends JavaPlugin implements Listener {

    private FileConfiguration config;
    private final Random random = new Random();

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        saveDefaultConfig();
        config = getConfig();
        getLogger().info("[TreeFallPlugin] Плагин активирован!");
    }

    @Override
    public void onDisable() {
        getLogger().info("[TreeFallPlugin] Плагин отключён.");
    }

    // === Событие срубания дерева ===
    @EventHandler
    public void onLogBreak(BlockBreakEvent event) {
        if (event.isCancelled() || event.getPlayer().getGameMode() == GameMode.CREATIVE) return;

        Block block = event.getBlock();
        Material type = block.getType();
        if (!isLog(type)) return; // не дерево

        // Проверяем, что сверху действительно дерево
        Block above = block.getRelative(BlockFace.UP);
        if (!isLogOrLeaves(above.getType())) return;

        World world = event.getPlayer().getWorld();
        List<Block> connectedLogs = getConnectedLogs(block);
        List<Block> connectedLeaves = getConnectedLeaves(connectedLogs);
        Direction direction = determineFallDirection(block);

        // удаляем все связанные блоки
        Set<Block> affectedBlocks = new HashSet<>(connectedLogs);
        affectedBlocks.addAll(connectedLeaves);
        affectedBlocks.forEach(b -> b.setType(Material.AIR));

        event.setDropItems(false);

        // Анимация падения и выпадение лута
        new BukkitRunnable() {
            int step = 0;
            final Location baseLoc = block.getLocation();

            @Override
            public void run() {
                if (step >= 10) {
                    // В конце выдаём лут
                    dropTreeLoot(world, connectedLogs, connectedLeaves);
                    cancel();
                    return;
                }

                Location moveVector = direction.toVector().multiply(step * 0.5).toLocation(world);
                for (Block log : connectedLogs) {
                    Location loc = baseLoc.clone().add(moveVector).add(
                            log.getX() - block.getX(),
                            log.getY() - block.getY(),
                            log.getZ() - block.getZ()
                    ).add(0.5, 0.5, 0.5);

                    world.spawnParticle(Particle.BLOCK_CRACK, loc, 4, 0, 0, 0, log.getBlockData());
                }

                step++;
            }
        }.runTaskTimer(this, 0L, 2L);
    }

    // === Вспомогательные функции поиска ===
    private boolean isLog(Material m) {
        return m.name().endsWith("_LOG") ||
               m.name().endsWith("_STEM") ||
               m.name().endsWith("_HYPHAE");
    }

    private boolean isLogOrLeaves(Material m) {
        return isLog(m) || m.name().endsWith("_LEAVES");
    }

    private List<Block> getConnectedLogs(Block start) {
        List<Block> found = new ArrayList<>();
        Queue<Block> q = new LinkedList<>();
        Set<Block> visited = new HashSet<>();
        q.add(start);
        visited.add(start);

        while (!q.isEmpty()) {
            Block b = q.poll();
            if (isLog(b.getType())) {
                found.add(b);
                for (BlockFace face : Arrays.asList(BlockFace.UP, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST)) {
                    Block nb = b.getRelative(face);
                    if (!visited.contains(nb) && isLog(nb.getType())) {
                        visited.add(nb);
                        q.add(nb);
                    }
                }
            }
        }
        return found.stream().limit(config.getInt("max-tree-size", 1200)).collect(Collectors.toList());
    }

    private List<Block> getConnectedLeaves(List<Block> logs) {
        Set<Block> leaves = new HashSet<>();
        for (Block log : logs) {
            for (BlockFace face : BlockFace.values()) {
                Block adj = log.getRelative(face);
                if (adj.getType().name().endsWith("_LEAVES")) {
                    leaves.add(adj);
                }
            }
        }
        return new ArrayList<>(leaves);
    }

    // === Направление падения ===
    private enum Direction { NORTH, SOUTH, EAST, WEST;
        public org.bukkit.util.Vector toVector() {
            switch (this) {
                case NORTH: return new org.bukkit.util.Vector(0, 0, -1);
                case SOUTH: return new org.bukkit.util.Vector(0, 0, 1);
                case EAST:  return new org.bukkit.util.Vector(1, 0, 0);
                case WEST:  return new org.bukkit.util.Vector(-1, 0, 0);
                default: return new org.bukkit.util.Vector(0, 0, 0);
            }
        }
    }

    private Direction determineFallDirection(Block block) {
        List<Direction> dirs = Arrays.asList(Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST);
        return dirs.get(random.nextInt(dirs.size()));
    }

    // === Лут ===
    private void dropTreeLoot(World world, List<Block> logs, List<Block> leaves) {
        for (Block log : logs) {
            world.dropItemNaturally(log.getLocation(), new ItemStack(log.getType()));
        }
        for (Block leaf : leaves) {
            dropLeafLoot(world, leaf);
        }
    }

    private void dropLeafLoot(World world, Block leaf) {
        double saplingChance = 0.05;
        double stickChance = 0.02;
        double fruitChance = 0.01;

        Material sapling = getSaplingForLeaf(leaf.getType());
        Material fruit = getFruitForLeaf(leaf.getType());

        // лиственный блок (80%)
        if (random.nextDouble() < 0.8) {
            ItemStack leafItem = new ItemStack(leaf.getType());
            leafItem.setItemMeta(Bukkit.getItemFactory().getItemMeta(leaf.getType()));
            world.dropItemNaturally(leaf.getLocation(), leafItem);
        }

        // саженец
        if (sapling != null && random.nextDouble() < saplingChance) {
            world.dropItemNaturally(leaf.getLocation(), new ItemStack(sapling));
        }

        // палка
        if (random.nextDouble() < stickChance) {
            world.dropItemNaturally(leaf.getLocation(), new ItemStack(Material.STICK));
        }

        // увеличиваем шанс для плодовых пород
        if (leaf.getType().name().matches(".*(OAK|CHERRY|JUNGLE).*")) {
            fruitChance = 0.08;
        }

        // плод
        if (fruit != null && random.nextDouble() < fruitChance) {
            world.dropItemNaturally(leaf.getLocation(), new ItemStack(fruit));
        }
    }

    // соответствие листвы и саженца
    private Material getSaplingForLeaf(Material leafType) {
        switch (leafType) {
            case OAK_LEAVES:       return Material.OAK_SAPLING;
            case BIRCH_LEAVES:     return Material.BIRCH_SAPLING;
            case SPRUCE_LEAVES:    return Material.SPRUCE_SAPLING;
            case JUNGLE_LEAVES:    return Material.JUNGLE_SAPLING;
            case ACACIA_LEAVES:    return Material.ACACIA_SAPLING;
            case DARK_OAK_LEAVES:  return Material.DARK_OAK_SAPLING;
            case MANGROVE_LEAVES:  return Material.MANGROVE_PROPAGULE;
            case CHERRY_LEAVES:    return Material.CHERRY_SAPLING;
            default:               return null;
        }
    }

    // соответствие листвы и фрукта
    private Material getFruitForLeaf(Material leafType) {
        String name = leafType.name().toLowerCase();
        if (name.contains("oak"))      return Material.APPLE;
        if (name.contains("cherry"))   return Material.CHERRY;
        if (name.contains("jungle"))   return Material.COCOA_BEANS;
        if (name.contains("mangrove")) return Material.MANGROVE_PROPAGULE;
        if (name.contains("crimson"))  return Material.CRIMSON_FUNGUS;
        if (name.contains("warped"))   return Material.WARPED_FUNGUS;
        return null;
    }
}
