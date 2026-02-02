package me.dagxam.treefall;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;

public class TreeFallPlugin extends JavaPlugin implements Listener {

    private static final String PERMISSION_USE = "treefall.use";
    private final Random random = new Random();
    private boolean worldGuardPresent;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(this, this);
        Plugin wg = getServer().getPluginManager().getPlugin("WorldGuard");
        worldGuardPresent = wg != null && wg.isEnabled();
        getLogger().info("TreeFall enabled | WorldGuard=" + worldGuardPresent);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onLogBreak(BlockBreakEvent event) {
        if (!getConfig().getBoolean("enabled", true)) return;

        Block base = event.getBlock();
        if (!Tag.LOGS.isTagged(base.getType())) return;

        Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.CREATIVE) return;

        if (getConfig().getBoolean("require-permission", true)
                && !player.hasPermission(PERMISSION_USE)) return;

        if (getConfig().getBoolean("require-axe", true)
                && !player.getInventory().getItemInMainHand().getType().name().endsWith("_AXE")) return;

        if (worldGuardPresent && !canBreakWorldGuard(player, base)) return;

        // Anti-building checks
        TrunkInfo trunk = analyzeTrunk(base);
        int minHeight = Math.max(3, getConfig().getInt("min-trunk-height", 4));
        if (trunk.height < minHeight) return;
        if (hasSideLogsAtBase(trunk.base)) return;
        if (!hasModdedCanopyAbove(trunk.top)) return;

        int maxBlocks = Math.max(64, getConfig().getInt("max-blocks", 512));
        TreeBlocks tree = collectTree(trunk.base, maxBlocks);
        if (tree.logs.size() < minHeight) return;

        // Take over
        event.setCancelled(true);

        World world = base.getWorld();
        Location dropLoc = findGroundBelow(world, base.getLocation());
        boolean effects = getConfig().getBoolean("effects.enabled", true);

        // Chances per leaf (converted to aggregated 1..3)
        double stickChance   = clamp01(getConfig().getDouble("drop.chance.stick", 0.02));
        double fruitChance   = clamp01(getConfig().getDouble("drop.chance.fruit", 0.01));
        double saplingChance = clamp01(getConfig().getDouble("drop.chance.sapling", 0.05));

        int leafCount = tree.leaves.size();

        // ✅ ВАЖНО: определяем тип дерева ДО удаления листьев
        Material leafSample = getAnyLeafMaterial(tree);
        Material saplingType = getSaplingForLeaf(leafSample);
        boolean appleTree = isAppleLeaf(leafSample);

        // ─── DROP LEAVES (как блоки) ───
        Map<Material, Integer> leafDrops = new HashMap<>();
        for (Block b : tree.leaves) {
            if (effects) fx(world, b);
            leafDrops.merge(b.getType(), 1, Integer::sum);
            b.setType(Material.AIR, false);
        }
        for (var e : leafDrops.entrySet()) {
            dropInStacks(world, dropLoc, new ItemStack(e.getKey()), e.getValue());
        }

        // ─── DROP LOGS/WOOD (exact type, exact count) ───
        Map<Material, Integer> logDrops = new HashMap<>();
        for (Block b : tree.logs) {
            if (effects) fx(world, b);
            logDrops.merge(b.getType(), 1, Integer::sum);
            b.setType(Material.AIR, false);
        }
        for (var e : logDrops.entrySet()) {
            dropInStacks(world, dropLoc, new ItemStack(e.getKey()), e.getValue());
        }

        // ─── BONUS: sticks 1..3 ───
        int sticks = calculateAggregatedAmount(leafCount, stickChance, 1, 3);
        if (sticks > 0) {
            world.dropItemNaturally(dropLoc, new ItemStack(Material.STICK, sticks));
        }

        // ─── BONUS: saplings 1..3 ───
        if (saplingType != null) {
            int saplings = calculateAggregatedAmount(leafCount, saplingChance, 1, 3);
            if (saplings > 0) {
                world.dropItemNaturally(dropLoc, new ItemStack(saplingType, saplings));
            }
        } else {
            // Если листья модовые/непонятные — саженцы не дропаем (иначе будет бредовый дроп)
        }

        // ─── BONUS: apples 1..3 (только дуб/тёмный дуб) ───
        if (appleTree) {
            int fruits = calculateAggregatedAmount(leafCount, fruitChance, 1, 3);
            if (fruits > 0) {
                world.dropItemNaturally(dropLoc, new ItemStack(Material.APPLE, fruits));
            }
        }

        // Tool damage (optional)
        if (getConfig().getBoolean("damage-tool", true)) {
            damageTool(player, tree.logs.size());
        }
    }

    // ─────────────────────────────
    // Aggregated drop calculation
    // ─────────────────────────────

    private int calculateAggregatedAmount(int leafCount, double chancePerLeaf, int min, int max) {
        if (leafCount <= 0 || chancePerLeaf <= 0) return min;

        double expected = leafCount * chancePerLeaf;
        int base = (int) Math.floor(expected);
        if (random.nextDouble() < (expected - base)) base++;

        if (base < min) base = min;
        if (base > max) base = max;
        return base;
    }

    private static double clamp01(double v) {
        return Math.max(0, Math.min(1, v));
    }

    // ─────────────────────────────
    // Tree detection
    // ─────────────────────────────

    private TrunkInfo analyzeTrunk(Block start) {
        Block c = start;
        int h = 0;
        while (Tag.LOGS.isTagged(c.getType())) {
            h++;
            c = c.getRelative(0, 1, 0);
        }
        return new TrunkInfo(start, c.getRelative(0, -1, 0), h);
    }

    private boolean hasSideLogsAtBase(Block base) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                if (Tag.LOGS.isTagged(base.getRelative(dx, 0, dz).getType())) return true;
            }
        }
        return false;
    }

    private boolean hasModdedCanopyAbove(Block top) {
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = 1; dy <= 5; dy++) {
                for (int dz = -2; dz <= 2; dz++) {
                    if (isLeafLike(top.getRelative(dx, dy, dz))) return true;
                }
            }
        }
        return false;
    }

    private boolean isLeafLike(Block b) {
        Material t = b.getType();
        if (Tag.LEAVES.isTagged(t)) return true;

        BlockData data = b.getBlockData();
        if (data instanceof Leaves) return true;

        String n = t.name();
        return n.contains("LEAVES") || n.contains("FOLIAGE")
                || n.contains("NEEDLES") || n.contains("CANOPY");
    }

    private TreeBlocks collectTree(Block start, int limit) {
        Set<Block> logs = new HashSet<>();
        Set<Block> leaves = new HashSet<>();
        Set<Block> visited = new HashSet<>();
        ArrayDeque<Block> q = new ArrayDeque<>();
        q.add(start);

        while (!q.isEmpty() && visited.size() < limit) {
            Block b = q.poll();
            if (!visited.add(b)) continue;

            if (Tag.LOGS.isTagged(b.getType())) logs.add(b);
            else if (isLeafLike(b)) leaves.add(b);
            else continue;

            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        q.add(b.getRelative(dx, dy, dz));
                    }
                }
            }
        }
        return new TreeBlocks(logs, leaves);
    }

    // ─────────────────────────────
    // Leaf sample / saplings / apples
    // ─────────────────────────────

    private Material getAnyLeafMaterial(TreeBlocks tree) {
        for (Block b : tree.leaves) {
            return b.getType();
        }
        return null;
    }

    private boolean isAppleLeaf(Material leaf) {
        return leaf == Material.OAK_LEAVES || leaf == Material.DARK_OAK_LEAVES;
    }

    private Material getSaplingForLeaf(Material leaf) {
        if (leaf == null) return null;
        return switch (leaf) {
            case OAK_LEAVES -> Material.OAK_SAPLING;
            case BIRCH_LEAVES -> Material.BIRCH_SAPLING;
            case SPRUCE_LEAVES -> Material.SPRUCE_SAPLING;
            case JUNGLE_LEAVES -> Material.JUNGLE_SAPLING;
            case ACACIA_LEAVES -> Material.ACACIA_SAPLING;
            case DARK_OAK_LEAVES -> Material.DARK_OAK_SAPLING;
            case MANGROVE_LEAVES -> Material.MANGROVE_PROPAGULE;
            case CHERRY_LEAVES -> Material.CHERRY_SAPLING;
            default -> null; // модовые листья: без маппинга
        };
    }

    // ─────────────────────────────
    // Drops & FX
    // ─────────────────────────────

    private void fx(World w, Block b) {
        w.spawnParticle(Particle.BLOCK, b.getLocation().add(0.5, 0.5, 0.5),
                6, 0.25, 0.25, 0.25, b.getBlockData());
        w.playSound(b.getLocation(), Sound.BLOCK_GRASS_BREAK, 0.5f, 1.2f);
    }

    private void dropInStacks(World world, Location loc, ItemStack item, int amount) {
        int left = amount;
        while (left > 0) {
            int give = Math.min(64, left);
            ItemStack stack = item.clone();
            stack.setAmount(give);
            world.dropItemNaturally(loc, stack);
            left -= give;
        }
    }

    private Location findGroundBelow(World w, Location l) {
        Location c = l.clone();
        while (c.getY() > w.getMinHeight()
                && (w.getBlockAt(c).getType() == Material.AIR || w.getBlockAt(c).isPassable())) {
            c.subtract(0, 1, 0);
        }
        return c.add(0, 1, 0);
    }

    private void damageTool(Player p, int uses) {
        ItemStack tool = p.getInventory().getItemInMainHand();
        if (tool == null || tool.getType() == Material.AIR) return;
        if (!(tool.getItemMeta() instanceof Damageable dmg)) return;

        int unbreaking = tool.getEnchantmentLevel(Enchantment.UNBREAKING);
        int applied = 0;

        for (int i = 0; i < uses; i++) {
            if (unbreaking > 0) {
                if (random.nextInt(unbreaking + 1) != 0) applied++;
            } else {
                applied++;
            }
        }

        dmg.setDamage(dmg.getDamage() + applied);
        tool.setItemMeta(dmg);

        if (dmg.getDamage() >= tool.getType().getMaxDurability()) {
            p.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
        }
    }

    // ─────────────────────────────
    // WorldGuard (soft / reflection)
    // ─────────────────────────────

    private boolean canBreakWorldGuard(Player player, Block block) {
        try {
            Class<?> wgPluginClass = Class.forName("com.sk89q.worldguard.bukkit.WorldGuardPlugin");
            Object wgPlugin = wgPluginClass.getMethod("inst").invoke(null);
            Object localPlayer = wgPluginClass
                    .getMethod("wrapPlayer", Player.class)
                    .invoke(wgPlugin, player);

            Class<?> wgClass = Class.forName("com.sk89q.worldguard.WorldGuard");
            Object wg = wgClass.getMethod("getInstance").invoke(null);
            Object platform = wg.getClass().getMethod("getPlatform").invoke(wg);
            Object container = platform.getClass().getMethod("getRegionContainer").invoke(platform);

            Class<?> adapter = Class.forName("com.sk89q.worldedit.bukkit.BukkitAdapter");
            Object weWorld = adapter.getMethod("adapt", World.class).invoke(null, block.getWorld());
            Object regionManager = container.getClass()
                    .getMethod("get", Class.forName("com.sk89q.worldedit.world.World"))
                    .invoke(container, weWorld);

            if (regionManager == null) return true;

            Object bv = adapter.getMethod("asBlockVector", Location.class)
                    .invoke(null, block.getLocation());

            Object regions = regionManager.getClass()
                    .getMethod("getApplicableRegions",
                            Class.forName("com.sk89q.worldedit.math.BlockVector3"))
                    .invoke(regionManager, bv);

            Class<?> flags = Class.forName("com.sk89q.worldguard.protection.flags.Flags");
            Object build = flags.getField("BUILD").get(null);

            return (boolean) regions.getClass()
                    .getMethod("testState",
                            Class.forName("com.sk89q.worldguard.LocalPlayer"),
                            Class.forName("com.sk89q.worldguard.protection.flags.StateFlag"))
                    .invoke(regions, localPlayer, build);

        } catch (Throwable t) {
            return true;
        }
    }

    private record TrunkInfo(Block base, Block top, int height) {}
    private record TreeBlocks(Set<Block> logs, Set<Block> leaves) {}
}
