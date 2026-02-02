package me.dagxam.treefall;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;

/**
 * TreeFall — "tree feller": breaks a whole tree when player breaks a log.
 *
 * Safety goals:
 * - only works on real trees (requires nearby leaves by default)
 * - hard cap on amount of blocks to prevent lag/grief
 * - optional permission + axe requirement
 */
public class TreeFallPlugin extends JavaPlugin implements Listener {

    private static final String PERMISSION_USE = "treefall.use";

    /** log/wood variants -> leaf type (fallback if we can't find leaves around) */
    private static final Map<Material, Material> LOG_TO_LEAF = Map.ofEntries(
            Map.entry(Material.OAK_LOG, Material.OAK_LEAVES),
            Map.entry(Material.STRIPPED_OAK_LOG, Material.OAK_LEAVES),
            Map.entry(Material.OAK_WOOD, Material.OAK_LEAVES),
            Map.entry(Material.STRIPPED_OAK_WOOD, Material.OAK_LEAVES),

            Map.entry(Material.BIRCH_LOG, Material.BIRCH_LEAVES),
            Map.entry(Material.STRIPPED_BIRCH_LOG, Material.BIRCH_LEAVES),
            Map.entry(Material.BIRCH_WOOD, Material.BIRCH_LEAVES),
            Map.entry(Material.STRIPPED_BIRCH_WOOD, Material.BIRCH_LEAVES),

            Map.entry(Material.SPRUCE_LOG, Material.SPRUCE_LEAVES),
            Map.entry(Material.STRIPPED_SPRUCE_LOG, Material.SPRUCE_LEAVES),
            Map.entry(Material.SPRUCE_WOOD, Material.SPRUCE_LEAVES),
            Map.entry(Material.STRIPPED_SPRUCE_WOOD, Material.SPRUCE_LEAVES),

            Map.entry(Material.JUNGLE_LOG, Material.JUNGLE_LEAVES),
            Map.entry(Material.STRIPPED_JUNGLE_LOG, Material.JUNGLE_LEAVES),
            Map.entry(Material.JUNGLE_WOOD, Material.JUNGLE_LEAVES),
            Map.entry(Material.STRIPPED_JUNGLE_WOOD, Material.JUNGLE_LEAVES),

            Map.entry(Material.ACACIA_LOG, Material.ACACIA_LEAVES),
            Map.entry(Material.STRIPPED_ACACIA_LOG, Material.ACACIA_LEAVES),
            Map.entry(Material.ACACIA_WOOD, Material.ACACIA_LEAVES),
            Map.entry(Material.STRIPPED_ACACIA_WOOD, Material.ACACIA_LEAVES),

            Map.entry(Material.DARK_OAK_LOG, Material.DARK_OAK_LEAVES),
            Map.entry(Material.STRIPPED_DARK_OAK_LOG, Material.DARK_OAK_LEAVES),
            Map.entry(Material.DARK_OAK_WOOD, Material.DARK_OAK_LEAVES),
            Map.entry(Material.STRIPPED_DARK_OAK_WOOD, Material.DARK_OAK_LEAVES),

            Map.entry(Material.MANGROVE_LOG, Material.MANGROVE_LEAVES),
            Map.entry(Material.STRIPPED_MANGROVE_LOG, Material.MANGROVE_LEAVES),
            Map.entry(Material.MANGROVE_WOOD, Material.MANGROVE_LEAVES),
            Map.entry(Material.STRIPPED_MANGROVE_WOOD, Material.MANGROVE_LEAVES),

            Map.entry(Material.CHERRY_LOG, Material.CHERRY_LEAVES),
            Map.entry(Material.STRIPPED_CHERRY_LOG, Material.CHERRY_LEAVES),
            Map.entry(Material.CHERRY_WOOD, Material.CHERRY_LEAVES),
            Map.entry(Material.STRIPPED_CHERRY_WOOD, Material.CHERRY_LEAVES)
    );

    private final Random random = new Random();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("TreeFall включён");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onLogBreak(BlockBreakEvent event) {
        if (!getConfig().getBoolean("enabled", true)) return;

        Block start = event.getBlock();
        Material brokenType = start.getType();

        // only logs/woods
        if (!Tag.LOGS.isTagged(brokenType)) return;

        Player player = event.getPlayer();

        // ignore creative: let vanilla behavior happen
        if (player.getGameMode() == GameMode.CREATIVE) return;

        // permission
        if (getConfig().getBoolean("require-permission", true) && !player.hasPermission(PERMISSION_USE)) return;

        // require axe
        if (getConfig().getBoolean("require-axe", true) && !isAxe(player.getInventory().getItemInMainHand().getType())) return;

        // anti-grief: require leaves around (real tree check)
        boolean requireLeaves = getConfig().getBoolean("require-leaves-nearby", true);
        int leafRadius = Math.max(1, getConfig().getInt("leaves-check-radius", 3));
        int leafMin = Math.max(1, getConfig().getInt("leaves-min-count", 6));
        if (requireLeaves && countLeavesAround(start, leafRadius) < leafMin) return;

        Material leafType = guessLeafType(start);

        int maxBlocks = Math.max(16, getConfig().getInt("max-blocks", 512));
        TreeBlocks tree = collectTree(start, maxBlocks);

        // if it is only 1 log — looks like not a tree or tiny structure
        if (tree.logBlocks.isEmpty() || tree.logBlocks.size() < 2) return;

        // now we take over
        event.setCancelled(true);

        boolean effects = getConfig().getBoolean("effects.enabled", true);
        boolean dropLeafBlocks = getConfig().getBoolean("drop.leaf-blocks", false);

        // loot chances (per leaf block removed)
        double chanceSapling = clamp01(getConfig().getDouble("drop.chance.sapling", 0.05));
        double chanceStick = clamp01(getConfig().getDouble("drop.chance.stick", 0.02));
        double chanceFruit = clamp01(getConfig().getDouble("drop.chance.fruit", 0.01));

        World world = start.getWorld();

        int leafCount = 0;
        int logCount = 0;

        int sticks = 0;
        int saplings = 0;
        int fruits = 0;

        Material saplingMat = getSaplingForLeaf(leafType);
        Material fruitMat = getFruitForLeaf(leafType);

        for (Block b : tree.allBlocksInRemovalOrder()) {
            Material t = b.getType();

            if (Tag.LOGS.isTagged(t)) {
                logCount++;
                if (effects) playBreakFx(world, b);
                b.setType(Material.AIR, false);
                continue;
            }

            if (Tag.LEAVES.isTagged(t)) {
                leafCount++;
                if (effects) playBreakFx(world, b);

                // per-leaf rolls
                if (chanceStick > 0 && roll(chanceStick)) sticks++;
                if (saplingMat != null && chanceSapling > 0 && roll(chanceSapling)) saplings++;
                if (fruitMat != null && chanceFruit > 0 && roll(chanceFruit)) fruits++;

                b.setType(Material.AIR, false);

                if (dropLeafBlocks) {
                    // optional: drop 1 leaf block item occasionally (kept conservative)
                    if (roll(0.10)) {
                        world.dropItemNaturally(findGroundBelow(world, b.getLocation()), new ItemStack(t, 1));
                    }
                }
            }
        }

        // drop logs 1:1
        ItemStack logDrop = new ItemStack(brokenType, 1);
        Location base = findGroundBelow(world, start.getLocation());
        for (int i = 0; i < logCount; i++) {
            world.dropItemNaturally(base, logDrop);
        }

        // bonus loot
        if (sticks > 0) world.dropItemNaturally(base, new ItemStack(Material.STICK, sticks));
        if (saplingMat != null && saplings > 0) world.dropItemNaturally(base, new ItemStack(saplingMat, saplings));
        if (fruitMat != null && fruits > 0) world.dropItemNaturally(base, new ItemStack(fruitMat, fruits));

        // damage tool (optional)
        if (getConfig().getBoolean("damage-tool", true)) {
            damageTool(player, logCount);
        }
    }

    private static boolean isAxe(Material mat) {
        return mat.name().endsWith("_AXE");
    }

    private int countLeavesAround(Block start, int radius) {
        int count = 0;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    Block b = start.getRelative(dx, dy, dz);
                    if (Tag.LEAVES.isTagged(b.getType())) count++;
                }
            }
        }
        return count;
    }

    private Material guessLeafType(Block start) {
        // 1) try find nearest leaves around
        for (int r = 1; r <= 3; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dy = -r; dy <= r; dy++) {
                    for (int dz = -r; dz <= r; dz++) {
                        Block b = start.getRelative(dx, dy, dz);
                        Material t = b.getType();
                        if (Tag.LEAVES.isTagged(t)) return t;
                    }
                }
            }
        }
        // 2) fallback by broken log type
        return LOG_TO_LEAF.getOrDefault(start.getType(), Material.OAK_LEAVES);
    }

    private TreeBlocks collectTree(Block start, int maxBlocks) {
        Set<Block> visited = new HashSet<>();
        Deque<Block> q = new ArrayDeque<>();
        q.add(start);

        Set<Block> logs = new HashSet<>();
        Set<Block> leaves = new HashSet<>();

        while (!q.isEmpty()) {
            Block cur = q.pollFirst();
            if (!visited.add(cur)) continue;

            Material t = cur.getType();
            boolean isLog = Tag.LOGS.isTagged(t);
            boolean isLeaf = Tag.LEAVES.isTagged(t);

            if (!isLog && !isLeaf) continue;

            if (isLog) logs.add(cur);
            else leaves.add(cur);

            // hard cap
            if (logs.size() + leaves.size() >= maxBlocks) break;

            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        Block n = cur.getRelative(dx, dy, dz);
                        if (!visited.contains(n)) q.addLast(n);
                    }
                }
            }
        }

        return new TreeBlocks(logs, leaves);
    }

    private void playBreakFx(World world, Block b) {
        world.spawnParticle(Particle.BLOCK, b.getLocation().add(0.5, 0.5, 0.5),
                8, 0.3, 0.3, 0.3, b.getBlockData());
        world.playSound(b.getLocation(), Sound.BLOCK_GRASS_BREAK, 0.5f, 1.2f);
    }

    private Location findGroundBelow(World world, Location start) {
        Location loc = start.clone();
        Block current = world.getBlockAt(loc);
        while (current.getY() > world.getMinHeight()
                && (current.getType() == Material.AIR || current.isPassable())) {
            loc.subtract(0, 1, 0);
            current = world.getBlockAt(loc);
        }
        loc.add(0, 1, 0);
        return loc;
    }

    private boolean roll(double chance) {
        return random.nextDouble() < chance;
    }

    private static double clamp01(double v) {
        if (v < 0) return 0;
        if (v > 1) return 1;
        return v;
    }

    private void damageTool(Player player, int amount) {
        ItemStack tool = player.getInventory().getItemInMainHand();
        if (tool == null || tool.getType() == Material.AIR) return;

        if (!(tool.getItemMeta() instanceof Damageable dmg)) return;

        // Unbreaking support (very lightweight)
        int unbreaking = tool.getEnchantmentLevel(Enchantment.UNBREAKING);
        int applied = 0;
        for (int i = 0; i < amount; i++) {
            if (unbreaking > 0) {
                // vanilla-like: chance to not consume durability = 1/(level+1)
                if (random.nextInt(unbreaking + 1) != 0) applied++;
            } else {
                applied++;
            }
        }

        if (applied <= 0) return;

        dmg.setDamage(dmg.getDamage() + applied);
        tool.setItemMeta(dmg);

        // if broken, remove it
        if (dmg.getDamage() >= tool.getType().getMaxDurability()) {
            player.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
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
            default -> null;
        };
    }

    private Material getFruitForLeaf(Material leafType) {
        return switch (leafType) {
            case OAK_LEAVES, DARK_OAK_LEAVES -> Material.APPLE;
            default -> null;
        };
    }

    private record TreeBlocks(Set<Block> logBlocks, Set<Block> leafBlocks) {
        /**
         * Removal order: leaves first (less visible pop), logs after.
         */
        List<Block> allBlocksInRemovalOrder() {
            List<Block> all = new ArrayList<>(leafBlocks.size() + logBlocks.size());
            all.addAll(leafBlocks);
            all.addAll(logBlocks);
            return all;
        }
    }
}
