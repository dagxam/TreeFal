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

import java.io.File;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public class TreeFallPlugin extends JavaPlugin implements Listener {

    private static final String PERMISSION_USE = "treefall.use";
    private final Random random = new Random();
    private boolean worldGuardPresent;

    // ===== "big tree" threshold =====
    private static final int BIG_TREE_LEAVES = 160;

    // ===== RealisticSeasons hook (dynamic class discovery) =====
    private RealisticSeasonsHook rsHook;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(this, this);

        Plugin wg = getServer().getPluginManager().getPlugin("WorldGuard");
        worldGuardPresent = wg != null && wg.isEnabled();

        Plugin rs = getServer().getPluginManager().getPlugin("RealisticSeasons");
        if (rs != null && rs.isEnabled()) {
            rsHook = new RealisticSeasonsHook(rs, getLogger());
            if (!rsHook.init()) {
                rsHook = null;
                getLogger().warning("RealisticSeasons detected, but API hook failed. Seasonal logic disabled.");
            } else {
                getLogger().info("RealisticSeasons detected. Seasonal logic enabled.");
            }
        } else {
            rsHook = null;
        }
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

        // ===== Fix for big trees leaving top blocks =====
        // dynamic limit: bigger trees -> higher scan cap
        int baseLimit = Math.max(64, getConfig().getInt("max-blocks", 512));
        int firstTryLimit = Math.max(baseLimit, 700);
        TreeBlocks tree = collectTree(trunk.base, firstTryLimit);

        // if looks like big tree or we nearly hit limit -> rescan with much bigger cap
        if (tree.logs.size() + tree.leaves.size() >= firstTryLimit - 10 || tree.leaves.size() >= BIG_TREE_LEAVES) {
            tree = collectTree(trunk.base, Math.max(firstTryLimit, 2200));
        }

        if (tree.logs.size() < minHeight) return;

        event.setCancelled(true);

        World world = base.getWorld();
        Location center = base.getLocation();

        int leafCount = tree.leaves.size();
        boolean bigTree = leafCount >= BIG_TREE_LEAVES;

        // ===== spread drop under tree size =====
        double spread = bigTree ? 3.5 : 1.8;

        // Determine type BEFORE removing leaves
        Material leafSample = getAnyLeafMaterial(tree);
        Material saplingType = getSaplingForLeaf(leafSample);
        boolean appleTree = (leafSample == Material.OAK_LEAVES || leafSample == Material.DARK_OAK_LEAVES);

        // ===== Base leaf drop (10..20 depending on size) =====
        int leafDropTarget = computeLeafDropTarget(leafCount);

        // ===== RealisticSeasons season =====
        String season = (rsHook != null) ? rsHook.getSeasonName(world) : null; // "SPRING", "SUMMER", "AUTUMN"/"FALL", "WINTER"

        // ===== Seasonal tweaks =====
        // AUTUMN/FALL: more falling leaves
        if (season != null && (season.equals("AUTUMN") || season.equals("FALL"))) {
            leafDropTarget = Math.min(leafDropTarget + 6, 26);
        }
        // WINTER: almost no leaves + no apples
        boolean winter = season != null && season.equals("WINTER");
        if (winter) {
            leafDropTarget = Math.max(0, leafDropTarget - 8); // часто 0..12
        }

        // ===== Leaf drops: pick random leaf blocks to drop (target count), but remove ALL leaves =====
        List<Block> leafList = new ArrayList<>(tree.leaves);
        Collections.shuffle(leafList, random);

        Set<Block> toDropLeaves = new HashSet<>();
        for (int i = 0; i < leafList.size() && toDropLeaves.size() < leafDropTarget; i++) {
            toDropLeaves.add(leafList.get(i));
        }

        Map<Material, Integer> leafDrops = new HashMap<>();
        for (Block b : tree.leaves) {
            if (toDropLeaves.contains(b)) {
                leafDrops.merge(b.getType(), 1, Integer::sum);
            }
            b.setType(Material.AIR, false);
        }
        dropScattered(world, center, leafDrops, spread);

        // ===== Logs drop 1:1 exact type =====
        Map<Material, Integer> logDrops = new HashMap<>();
        for (Block b : tree.logs) {
            logDrops.merge(b.getType(), 1, Integer::sum);
            b.setType(Material.AIR, false);
        }
        dropScattered(world, center, logDrops, spread);

        // ===== Sticks (keep as-is, aggregated 1..3) =====
        double stickChance = clamp01(getConfig().getDouble("drop.chance.stick", 0.02));
        int sticks = calculateAggregatedAmount(leafCount, stickChance, 1, 3);
        if (sticks > 0) scatterItem(world, center, new ItemStack(Material.STICK, sticks), spread);

        // ===== Saplings (leave as-is, aggregated 1..3), but SPRING bonus =====
        double sapChance = clamp01(getConfig().getDouble("drop.chance.sapling", 0.05));
        if (saplingType != null) {
            int saplings = calculateAggregatedAmount(leafCount, sapChance, 1, 3);

            // SPRING bonus: +1 sapling (cap 3)
            if (season != null && season.equals("SPRING")) {
                saplings = Math.min(3, saplings + 1);
            }

            if (saplings > 0) scatterItem(world, center, new ItemStack(saplingType, saplings), spread);
        }

        // ===== Apples: small=3 big=5, but WINTER => 0 =====
        if (appleTree && !winter) {
            int apples = bigTree ? 5 : 3;

            // small summer vibe (optional): +1 apple in SUMMER, cap 6
            if (season != null && season.equals("SUMMER")) {
                apples = Math.min(6, apples + 1);
            }

            scatterItem(world, center, new ItemStack(Material.APPLE, apples), spread);
        }

        // Tool damage (as-is)
        if (getConfig().getBoolean("damage-tool", true)) {
            damageTool(player, tree.logs.size());
        }
    }

    // =========================================================
    // Leaf drop scaling: 10..20 (small->10, big->20)
    // =========================================================
    private int computeLeafDropTarget(int leafCount) {
        if (leafCount <= 0) return 0;

        int min = 10;
        int max = 20;

        int low = 40;
        int high = 160;

        if (leafCount <= low) return Math.min(min, leafCount);
        if (leafCount >= high) return Math.min(max, leafCount);

        double t = (leafCount - low) / (double) (high - low); // 0..1
        int target = (int) Math.round(min + t * (max - min));
        target = Math.max(min, Math.min(max, target));
        return Math.min(target, leafCount);
    }

    // =========================================================
    // Scattered drops (spread by tree size)
    // =========================================================
    private void dropScattered(World w, Location center, Map<Material, Integer> items, double radius) {
        for (var e : items.entrySet()) {
            int left = e.getValue();
            while (left > 0) {
                int give = Math.min(64, left);
                ItemStack it = new ItemStack(e.getKey(), give);
                scatterItem(w, center, it, radius);
                left -= give;
            }
        }
    }

    private void scatterItem(World w, Location center, ItemStack it, double radius) {
        double dx = (random.nextDouble() - 0.5) * radius;
        double dz = (random.nextDouble() - 0.5) * radius;
        Location l = center.clone().add(dx, 0.2, dz);
        w.dropItemNaturally(l, it);
    }

    // =========================================================
    // Aggregated drop calculation (same as before)
    // =========================================================
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

    // =========================================================
    // Tree detection (same as before)
    // =========================================================
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

    private Material getAnyLeafMaterial(TreeBlocks tree) {
        for (Block b : tree.leaves) return b.getType();
        return null;
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
            default -> null; // modded leaves: no mapping
        };
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

    // =========================================================
    // WorldGuard (keep as-is / reflection) — same style as before
    // =========================================================
    private boolean canBreakWorldGuard(Player player, Block block) {
        try {
            Class<?> wgPluginClass = Class.forName("com.sk89q.worldguard.bukkit.WorldGuardPlugin");
            Object wgPlugin = wgPluginClass.getMethod("inst").invoke(null);
            Object localPlayer = wgPluginClass.getMethod("wrapPlayer", Player.class).invoke(wgPlugin, player);

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

    // =========================================================
    // Records
    // =========================================================
    private record TrunkInfo(Block base, Block top, int height) {}
    private record TreeBlocks(Set<Block> logs, Set<Block> leaves) {}

    // =========================================================
    // RealisticSeasons dynamic API hook
    // =========================================================
    private static final class RealisticSeasonsHook {
        private final Plugin realisticSeasons;
        private final java.util.logging.Logger log;

        private ClassLoader rsLoader;
        private Class<?> seasonsApiClass;
        private Method getInstance;
        private Method getSeason; // (World)->Season
        private Object seasonsApiInstance;

        RealisticSeasonsHook(Plugin realisticSeasons, java.util.logging.Logger log) {
            this.realisticSeasons = realisticSeasons;
            this.log = log;
        }

        boolean init() {
            try {
                rsLoader = realisticSeasons.getClass().getClassLoader();

                // find FQCN of SeasonsAPI by scanning the jar for .../SeasonsAPI.class
                String seasonsApiFqcn = findClassInJar(realisticSeasons, "SeasonsAPI.class");
                if (seasonsApiFqcn == null) {
                    log.warning("[TreeFall] Could not find SeasonsAPI.class inside RealisticSeasons jar");
                    return false;
                }

                seasonsApiClass = Class.forName(seasonsApiFqcn, true, rsLoader);
                getInstance = seasonsApiClass.getMethod("getInstance");
                seasonsApiInstance = getInstance.invoke(null);
                getSeason = seasonsApiClass.getMethod("getSeason", World.class);

                // quick sanity check call
                Object seasonObj = getSeason.invoke(seasonsApiInstance, Bukkit.getWorlds().get(0));
                if (seasonObj == null) {
                    log.warning("[TreeFall] RealisticSeasons API returned null season");
                }

                return true;
            } catch (Throwable t) {
                log.warning("[TreeFall] RealisticSeasons hook error: " + t.getClass().getSimpleName() + ": " + t.getMessage());
                return false;
            }
        }

        String getSeasonName(World w) {
            try {
                Object seasonObj = getSeason.invoke(seasonsApiInstance, w);
                if (seasonObj == null) return null;
                // enum -> "SPRING"/"SUMMER"/"AUTUMN"/"FALL"/"WINTER"
                return seasonObj.toString().toUpperCase(Locale.ROOT);
            } catch (Throwable t) {
                return null;
            }
        }

        private static String findClassInJar(Plugin plugin, String classFileName) {
            try {
                URL url = plugin.getClass().getProtectionDomain().getCodeSource().getLocation();
                File jarFile = new File(url.toURI());
                try (JarFile jar = new JarFile(jarFile)) {
                    Enumeration<JarEntry> en = jar.entries();
                    while (en.hasMoreElements()) {
                        JarEntry e = en.nextElement();
                        String name = e.getName();
                        if (name.endsWith(classFileName)) {
                            // convert path -> fqcn
                            String fqcn = name.replace('/', '.');
                            fqcn = fqcn.substring(0, fqcn.length() - ".class".length());
                            return fqcn;
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
            return null;
        }
    }
}
