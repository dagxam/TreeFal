package me.dagxam.treefallplugin;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

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
        getLogger().info("[TreeFallPlugin] Плагин выключен.");
    }

    // === Основное событие: сруб дерева ===
    @EventHandler
    public void onLogBreak(BlockBreakEvent event) {
        if (event.isCancelled() || event.getPlayer().getGameMode() == GameMode.CREATIVE) return;

        Block block = event.getBlock();
        Material type = block.getType();
        if (!isLog(type)) return;

        // Проверяем нижний блок
        Block below = block.getRelative(BlockFace.DOWN);
        if (isLogOrLeaves(below.getType())) return;

        // Проверяем, есть ли над блоком часть дерева
        Block above = block.getRelative(BlockFace.UP);
        if (!isLogOrLeaves(above.getType())) return;

        World world = event.getPlayer().getWorld();
        List<Block> connectedLogs = getConnectedLogs(block);
        List<Block> connectedLeaves = getConnectedLeaves(connectedLogs);
        Direction direction = determineFallDirection(block);

        // Удаляем блоки дерева из мира
        Set<Block> affected = new HashSet<>(connectedLogs);
        affected.addAll(connectedLeaves);
        affected.forEach(b -> b.setType(Material.AIR));

        event.setDropItems(false);

        new BukkitRunnable() {
            int step = 0;
            final Location base = block.getLocation();

            @Override
            public void run() {
                if (step >= 10) {
                    // После окончания анимации — лут
                    dropTreeLoot(world, connectedLogs, connectedLeaves);

                    // Всплеск пыли при ударе
                    world.spawnParticle(
                            Particle.FALLING_DUST,
                            base.clone().add(direction.toVector().multiply(5)),
                            80, 1.8, 1.2, 1.8, 0.02, Material.DIRT.createBlockData()
                    );
                    world.playSound(base, Sound.BLOCK_WOOD_BREAK, 1f, 0.8f);
                    cancel();
                    return;
                }

                Location moveVec = direction.toVector().multiply(step * 0.5).toLocation(world);

                for (Block log : connectedLogs) {
                    Location loc = base.clone()
                            .add(moveVec)
                            .add(log.getX() - block.getX(),
                                 log.getY() - block.getY(),
                                 log.getZ() - block.getZ())
                            .add(0.5, 0.5, 0.5);

                    // Частицы опилок (BLOCK_DUST)
                    world.spawnParticle(
                            Particle.BLOCK_DUST,
                            loc, 10,
                            0.3, 0.3, 0.3, 0.02,
                            log.getBlockData()
                    );

                    // Немного пыли, «падающей вниз»
                    world.spawnParticle(
                            Particle.FALLING_DUST,
                            loc.clone().add(0, -0.2, 0),
                            6, 0.5, 0.5, 0.5,
                            0.01, Material.DIRT.createBlockData()
                    );
                }

                // «Падающие листья» (имитация через FALLING_DUST)
                for (Block leaf : connectedLeaves) {
                    if (random.nextDouble() < 0.15) {
                        Location leafLoc = leaf.getLocation().add(0.5, 0.8, 0.5);
                        world.spawnParticle(
                                Particle.FALLING_DUST,
                                leafLoc.add(direction.toVector().multiply(step * 0.2)),
                                5, 0.4, 0.2, 0.4, 0.01,
                                leaf.getBlockData()
                        );
                    }
                }

                if (step % 2 == 0) {
                    world.playSound(base, Sound.BLOCK_WOOD_PLACE, 0.4f, 0.6f + step * 0.05f);
                }

                step++;
            }
        }.runTaskTimer(this, 0L, 2L);
    }

    // === Помощники ===

    private boolean isLog(Material m) {
        return m.name().endsWith("_LOG")
                || m.name().endsWith("_STEM")
                || m.name().endsWith("_HYPHAE");
    }

    private boolean isLogOrLeaves(Material m) {
        return isLog(m) || m.name().endsWith("_LEAVES");
    }

    private List<Block> getConnectedLogs(Block start) {
        List<Block> found = new ArrayList<>();
        Queue<Block> queue = new LinkedList<>();
        Set<Block> visited = new HashSet<>();
        queue.add(start);
        visited.add(start);

        while (!queue.isEmpty()) {
            Block b = queue.poll();
            if (isLog(b.getType())) {
                found.add(b);
                for (BlockFace face : Arrays.asList(BlockFace.UP, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST)) {
                    Block nb = b.getRelative(face);
                    if (!visited.contains(nb) && isLog(nb.getType())) {
                        visited.add(nb);
                        queue.add(nb);
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
                Block n = log.getRelative(face);
                if (n.getType().name().endsWith("_LEAVES")) leaves.add(n);
            }
        }
        return new ArrayList<>(leaves);
    }

    // === Направление падения ===
    private enum Direction {
        NORTH, SOUTH, EAST, WEST;
        public Vector toVector() {
            switch (this) {
                case NORTH: return new Vector(0, 0, -1);
                case SOUTH: return new Vector(0, 0, 1);
                case EAST:  return new Vector(1, 0, 0);
                case WEST:  return new Vector(-1, 0, 0);
            }
            return new Vector(0, 0, 0);
        }
    }

    private Direction determineFallDirection(Block b) {
        List<Direction> dirs = Arrays.asList(Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST);
        return dirs.get(random.nextInt(dirs.size()));
    }

    // === Дроп предметов ===
    private void dropTreeLoot(World world, List<Block> logs, List<Block> leaves) {
        for (Block log : logs)
            world.dropItemNaturally(log.getLocation(), new ItemStack(log.getType()));
        for (Block leaf : leaves)
            dropLeafLoot(world, leaf);
    }

    private void dropLeafLoot(World world, Block leaf) {
        double leafDropChance = 0.90;
        double stickChance = 0.60;
        double fruitChance = 0.80;

        Material sapling = getSaplingForLeaf(leaf.getType());
        Material fruit = getFruitForLeaf(leaf.getType());

        if (random.nextDouble() < leafDropChance)
            world.dropItemNaturally(leaf.getLocation(), new ItemStack(leaf.getType()));

        if (sapling != null && random.nextDouble() < 0.05)
            world.dropItemNaturally(leaf.getLocation(), new ItemStack(sapling));

        if (random.nextDouble() < stickChance)
            world.dropItemNaturally(leaf.getLocation(), new ItemStack(Material.STICK));

        if (fruit != null && random.nextDouble() < fruitChance)
            world.dropItemNaturally(leaf.getLocation(), new ItemStack(fruit));
    }

    private Material getSaplingForLeaf(Material type) {
        switch (type) {
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

    private Material getFruitForLeaf(Material leafType) {
        String name = leafType.name().toLowerCase();
        if (name.contains("oak"))      return Material.APPLE;
        if (name.contains("jungle"))   return Material.COCOA_BEANS;
        if (name.contains("cherry"))   return Material.SWEET_BERRIES;
        if (name.contains("mangrove")) return Material.MANGROVE_PROPAGULE;
        if (name.contains("crimson"))  return Material.CRIMSON_FUNGUS;
        if (name.contains("warped"))   return Material.WARPED_FUNGUS;
        return null;
    }
}
