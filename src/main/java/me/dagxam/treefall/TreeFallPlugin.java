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

import java.lang.reflect.Method;
import java.util.*;

public class TreeFallPlugin extends JavaPlugin implements Listener {

    private static final String PERMISSION_USE = "treefall.use";
    private final Random random = new Random();

    private boolean worldGuardPresent = false;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(this, this);

        Plugin wg = getServer().getPluginManager().getPlugin("WorldGuard");
        worldGuardPresent = (wg != null && wg.isEnabled());

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

        // WorldGuard check (soft, via reflection)
        if (worldGuardPresent && !canBreakWorldGuard(player, base)) return;

        // Strong anti-building checks
        TrunkInfo trunk = analyzeTrunk(base);
        int minHeight = Math.max(3, getConfig().getInt("min-trunk-height", 4));
        if (trunk.height < minHeight) return;

        if (hasSideLogsAtBase(trunk.base)) return;
        if (!hasModdedCanopyAbove(trunk.top)) return;

        int maxBlocks = Math.max(64, getConfig().getInt("max-blocks", 512));
        TreeBlocks tree = collectTree(trunk.base, maxBlocks);

        // Ensure it really looks like a tree
        if (tree.logs.size() < minHeight) return;

        // Take over
        event.setCancelled(true);

        World world = base.getWorld();
        Location dropLoc = findGroundBelow(world, base.getLocation());

        boolean effects = getConfig().getBoolean("effects.enabled", true);
        boolean dropLeafBlocks = getConfig().getBoolean("drop.leaf-blocks", false);

        // Drop chances per leaf (used to compute an aggregated 0..5 / 1..5 result)
        double stickChancePerLeaf = clamp01(getConfig().getDouble("drop.chance.stick", 0.02));

        // support both keys: apple OR fruit (backwards compatibility)
        double appleChancePerLeaf = getConfig().contains("drop.chance.apple")
                ? clamp01(getConfig().getDouble("drop.chance.apple", 0.01))
                : clamp01(getConfig().getDouble("drop.chance.fruit", 0.01));

        // 1) Remove leaves (optionally drop leaf blocks in STACKS)
        int leafCount = tree.leaves.size();
        if (dropLeafBlocks) {
            Map<Material, Integer> leafDrops = new HashMap<>();
            for (Block b : tree.leaves) {
                if (effects) fx(world, b);
                leafDrops.merge(b.getType(), 1, Integer::sum);
                b.setType(Material.AIR, false);
            }
            // drop leaf blocks matching exact counts, but not one "кучей"
            for (Map.Entry<Material, Integer> e : leafDrops.entrySet()) {
                dropInStacks(world, dropLoc, new ItemStack(e.getKey()), e.getValue());
            }
        } else {
            for (Block b : tree.leaves) {
                if (effects) fx(world, b);
                b.setType(Material.AIR, false);
            }
        }

        // 2) Remove logs + exact drops per material (1:1)
        Map<Material, Integer> logDrops = new HashMap<>();
        for (Block b : tree.logs) {
            if (effects) fx(world, b);
            logDrops.merge(b.getType(), 1, Integer::sum);
            b.setType(Material.AIR, false);
        }
        for (Map.Entry<Material, Integer> e : logDrops.entrySet()) {
            dropInStacks(world, dropLoc, new ItemStack(e.getKey()), e.getValue());
        }

        // 3) Bonus loot (NOT кучей):
        // sticks: 1..5, apples: 0..5, both depend on leafCount
        int sticks = calculateAggregatedAmount(leafCount, stickChancePerLeaf, 1, 5);
        int apples = calculateAggregatedAmount(leafCount, appleChancePerLeaf, 0, 5);

        if (sticks > 0) {
            world.dropItemNaturally(dropLoc, new ItemStack(Material.STICK, sticks));
        }
        if (apples > 0) {
            world.dropItemNaturally(dropLoc, new ItemStack(Material.APPLE, apples));
        }

        // 4) Damage tool (optional)
        if (getConfig().getBoolean("damage-tool", true)) {
            damageTool(player, tree.logs.size());
        }
    }

    // ─────────────────────────────
    // Aggregated drop calculation
    // ─────────────────────────────

    /**
     * Convert "chance per leaf" into an aggregated amount for the whole tree.
     * Example: leafCount=80, chance=0.02 => expected=1.6 => 1 or 2 (biased), clamped to [min..max]
     */
    private int calculateAggregatedAmount(int leafCount, double chancePerLeaf, int min, int max) {
        if (leafCount <= 0 || chancePerLeaf <= 0) return min == 0 ? 0 : min;

        double expected = leafCount * chancePerLeaf;   // expected value
        int base = (int) Math.floor(expected);
        double frac = expected - base;

        // probabilistic rounding
        int result = base + (random.nextDouble() < frac ? 1 : 0);

        if (result < min) result = min;
        if (result > max) result = max;
        return result;
    }

    private static double clamp01(double v) {
        if (v < 0) return 0;
        if (v > 1) return 1;
        return v;
    }

    private void dropInStacks(World world, Location loc, ItemStack template, int amount) {
        int left = amount;
        while (left > 0) {
            int give = Math.min(64, left);
            ItemStack stack = template.clone();
            stack.setAmount(give);
            world.dropItemNaturally(loc, stack);
            left -= give;
        }
    }

    // ─────────────────────────────
    // Tree detection & safety
    // ─────────────────────────────

    private TrunkInfo analyzeTrunk(Block start) {
        Block c = start;
        int h = 0;
        while (Tag.LOGS.isTagged(c.getType())) {
            h++;
            c = c.getRelative(0, 1, 0);
        }
        Block topLog = c.getRelative(0, -1, 0);
        return new TrunkInfo(start, topLog, h);
    }

    /**
     * Leaves must be above / around the top, not somewhere sideways near the base.
     * This kills most "wooden buildings with random leaves nearby".
     */
    private boolean hasModdedCanopyAbove(Block topLog) {
        // search above and around top log
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = 1; dy <= 5; dy++) {
                for (int dz = -2; dz <= 2; dz++) {
                    Block b = topLog.getRelative(dx, dy, dz);
                    if (isLeafLike(b)) return true;
                }
            }
        }
        return false;
    }

    /**
     * If there are logs adjacent at the same Y-level of the broken block (base),
     * treat it as a structure, not a tree.
     */
    private boolean hasSideLogsAtBase(Block base) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                if (Tag.LOGS.isTagged(base.getRelative(dx, 0, dz).getType())) return true;
            }
        }
        return false;
    }

    /**
     * Leaf-like check for modded trees:
     * - vanilla Tag.LEAVES
     * - BlockData instanceof Leaves
     * - or material enum name contains common leaf keywords
     */
    private boolean isLeafLike(Block b) {
        Material t = b.getType();
        if (Tag.LEAVES.isTagged(t)) return true;

        BlockData data = b.getBlockData();
        if (data instanceof Leaves) return true;

        String n = t.name();
        return n.contains("LEAVES") || n.contains("FOLIAGE") || n.contains("NEEDLES") || n.contains("CANOPY");
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

            if (Tag.LOGS.isTagged(b.getType())) {
                logs.add(b);
            } else if (isLeafLike(b)) {
                leaves.add(b);
            } else {
                continue;
            }

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
    // WorldGuard support (soft / reflection)
    // ─────────────────────────────

    /**
     * Soft WorldGuard build check via reflection (no compile dependency).
     * If anything fails, we default to allowing (to avoid breaking servers).
     */
    private boolean canBreakWorldGuard(Player player, Block block) {
        try {
            Plugin wg = getServer().getPluginManager().getPlugin("WorldGuard");
            if (wg == null || !wg.isEnabled()) return true;

            // WorldGuardPlugin.inst()
            Class<?> wgPluginClass = Class.forName("com.sk89q.worldguard.bukkit.WorldGuardPlugin");
            Method inst = wgPluginClass.getMethod("inst");
            Object wgPlugin = inst.invoke(null);

            // wrapPlayer(player)
            Method wrapPlayer = wgPluginClass.getMethod("wrapPlayer", Player.class);
            Object localPlayer = wrapPlayer.invoke(wgPlugin, player);

            // WorldGuard.getInstance().getPlatform().getRegionContainer()
            Class<?> worldGuardClass = Class.forName("com.sk89q.worldguard.WorldGuard");
            Method getInstance = worldGuardClass.getMethod("getInstance");
            Object wgInstance = getInstance.invoke(null);

            Method getPlatform = wgInstance.getClass().getMethod("getPlatform");
            Object platform = getPlatform.invoke(wgInstance);

            Method getRegionContainer = platform.getClass().getMethod("getRegionContainer");
            Object regionContainer = getRegionContainer.invoke(platform);

            // BukkitAdapter.adapt(world)
            Class<?> bukkitAdapter = Class.forName("com.sk89q.worldedit.bukkit.BukkitAdapter");
            Method adaptWorld = bukkitAdapter.getMethod("adapt", World.class);
            Object weWorld = adaptWorld.invoke(null, block.getWorld());

            // RegionContainer.get(weWorld) -> RegionManager
            Method get = regionContainer.getClass().getMethod("get", Class.forName("com.sk89q.worldedit.world.World"));
            Object regionManager = get.invoke(regionContainer, weWorld);
            if (regionManager == null) return true;

            // BukkitAdapter.asBlockVector(location)
            Method asBlockVector = bukkitAdapter.getMethod("asBlockVector", Location.class);
            Object blockVector = asBlockVector.invoke(null, block.getLocation());

            // RegionManager.getApplicableRegions(BlockVector3)
            Method getApplicableRegions = regionManager.getClass().getMethod(
                    "getApplicableRegions",
                    Class.forName("com.sk89q.worldedit.math.BlockVector3")
            );
            Object applicableRegionSet = getApplicableRegions.invoke(regionManager, blockVector);

            // Flags.BUILD
            Class<?> flagsClass = Class.forName("com.sk89q.worldguard.protection.flags.Flags");
            Object buildFlag = flagsClass.getField("BUILD").get(null);

            // ApplicableRegionSet.testState(LocalPlayer, StateFlag...)
            Method testState = applicableRegionSet.getClass().getMethod(
                    "testState",
                    Class.forName("com.sk89q.worldguard.LocalPlayer"),
                    Class.forName("com.sk89q.worldguard.protection.flags.StateFlag")
            );

            Object result = testState.invoke(applicableRegionSet, localPlayer, buildFlag);
            return (boolean) result;

        } catch (Throwable ignored) {
            // if reflection fails for any reason, do not block breaking
            return true;
        }
    }

    // ─────────────────────────────
    // FX + tool damage
    // ─────────────────────────────

    private void fx(World w, Block b) {
        w.spawnParticle(Particle.BLOCK, b.getLocation().add(0.5, 0.5, 0.5),
                6, 0.25, 0.25, 0.25, b.getBlockData());
        w.playSound(b.getLocation(), Sound.BLOCK_GRASS_BREAK, 0.5f, 1.2f);
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

    private record TrunkInfo(Block base, Block top, int height) {}
    private record TreeBlocks(Set<Block> logs, Set<Block> leaves) {}
}
