package me.dagxam.treefall;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public class TreeFallPlugin extends JavaPlugin implements Listener {

    private static final String PERMISSION_USE = "treefall.use";
    private static final String FALLING_TAG = "treefall_falling";

    private final Random random = new Random();
    private boolean worldGuardPresent;

    private static final int BIG_TREE_LEAVES = 160;

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
        }
    }

    // FallingBlock does NOT place blocks on landing
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFallingBlockLand(EntityChangeBlockEvent event) {
        Entity ent = event.getEntity();
        if (ent instanceof FallingBlock fb && fb.getScoreboardTags().contains(FALLING_TAG)) {
            event.setCancelled(true);
            fb.remove();
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onLogBreak(BlockBreakEvent event) {

        if (!getConfig().getBoolean("enabled", true)) return;

        Block cutBlock = event.getBlock();
        if (!Tag.LOGS.isTagged(cutBlock.getType())) return;

        Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.CREATIVE) return;

        if (getConfig().getBoolean("require-permission", true) && !player.hasPermission(PERMISSION_USE)) return;

        // Работает и рукой: проверку на топор НЕ делаем

        if (worldGuardPresent && !canBreakWorldGuard(player, cutBlock)) return;

        // ★ ВАЖНО: уровень сруба (всё >= cutY падает)
        final int cutY = cutBlock.getY();

        // Определяем нижнюю точку ствола для корректного поиска дерева
        Block trunkBottom = findTrunkBottom(cutBlock);

        // Минимальная высота — проверяем по стволу вверх от нижней точки
        int trunkHeight = measureTrunkHeight(trunkBottom);
        int minHeight = Math.max(3, getConfig().getInt("min-trunk-height", 4));
        if (trunkHeight < minHeight) return;

        // анти-дом: если у основания есть боковые логи — не трогаем
        if (hasSideLogsAtBase(trunkBottom)) return;

        // должно быть “похоже на дерево”: крона выше
        Block top = trunkBottom.getRelative(0, trunkHeight - 1, 0);
        if (!hasModdedCanopyAbove(top)) return;

        // Сбор дерева (для больших — расширяем)
        int baseLimit = Math.max(64, getConfig().getInt("max-blocks", 512));
        int firstTryLimit = Math.max(baseLimit, 700);
        TreeBlocks fullTree = collectTree(trunkBottom, firstTryLimit);

        if (fullTree.logs.size() + fullTree.leaves.size() >= firstTryLimit - 10 || fullTree.leaves.size() >= BIG_TREE_LEAVES) {
            fullTree = collectTree(trunkBottom, Math.max(firstTryLimit, 2200));
        }

        if (fullTree.logs.isEmpty()) return;

        // ★ Разделяем: что падает, что остаётся
        TreeBlocks falling = sliceAboveY(fullTree, cutY); // >= cutY
        if (falling.logs.isEmpty()) {
            // рубанули самый верхний одиночный блок? тогда просто даём обычный брейк
            return;
        }

        // Отменяем обычную ломку — всё делаем сами
        event.setCancelled(true);

        World world = cutBlock.getWorld();
        Location center = cutBlock.getLocation();

        int leafCount = falling.leaves.size();
        boolean bigPart = leafCount >= BIG_TREE_LEAVES;
        double spread = bigPart ? 3.5 : 1.8;

        // Типы определяем по падающей части (важно)
        Material leafSample = getAnyLeafMaterial(falling);
        Material saplingType = getSaplingForLeaf(leafSample);
        boolean appleTree = (leafSample == Material.OAK_LEAVES || leafSample == Material.DARK_OAK_LEAVES);

        // Листья 10..20 — по размеру падающей части
        int leafDropTarget = computeLeafDropTarget(leafCount);

        // Сезоны
        String season = (rsHook != null) ? rsHook.getSeasonName(world) : null;
        boolean winter = season != null && season.equals("WINTER");

        if (season != null && (season.equals("AUTUMN") || season.equals("FALL"))) {
            leafDropTarget = Math.min(leafDropTarget + 6, 26);
        }
        if (winter) {
            leafDropTarget = Math.max(0, leafDropTarget - 8);
        }

        // Дроп листьев (выбираем случайные из падающей части)
        Map<Material, Integer> leafDrops = new HashMap<>();
        {
            List<Block> leafList = new ArrayList<>(falling.leaves);
            Collections.shuffle(leafList, random);
            int take = Math.min(leafDropTarget, leafList.size());
            for (int i = 0; i < take; i++) {
                leafDrops.merge(leafList.get(i).getType(), 1, Integer::sum);
            }
        }

        // Дроп логов/wood 1:1 (только падающая часть!)
        Map<Material, Integer> logDrops = new HashMap<>();
        for (Block b : falling.logs) {
            logDrops.merge(b.getType(), 1, Integer::sum);
        }

        // Палки/саженцы как раньше (по падающей части)
        double stickChance = clamp01(getConfig().getDouble("drop.chance.stick", 0.02));
        double sapChance = clamp01(getConfig().getDouble("drop.chance.sapling", 0.05));

        int sticks = calculateAggregatedAmount(leafCount, stickChance, 1, 3);

        int saplings = 0;
        if (saplingType != null) {
            saplings = calculateAggregatedAmount(leafCount, sapChance, 1, 3);
            if (season != null && season.equals("SPRING")) saplings = Math.min(3, saplings + 1);
        }

        // ★ Яблоки: большое дерево 5, маленькое 3 (по падающей части), зимой 0
        int apples = 0;
        if (appleTree && !winter) {
            apples = (bigPart ? 5 : 3);
            if (season != null && season.equals("SUMMER")) apples = Math.min(6, apples + 1);
        }

        // Анимация “падает сверху”: только падающая часть удаляется
        playFallingAnimationThenDrop(
                world,
                center,
                falling,
                spread,
                leafDrops,
                logDrops,
                sticks,
                saplingType,
                saplings,
                apples,
                player
        );
    }

    // =========================
    // ★ Падает только верх
    // =========================
    private TreeBlocks sliceAboveY(TreeBlocks tree, int cutY) {
        Set<Block> logs = new HashSet<>();
        Set<Block> leaves = new HashSet<>();
        for (Block b : tree.logs) {
            if (b.getY() >= cutY) logs.add(b);
        }
        for (Block b : tree.leaves) {
            if (b.getY() >= cutY) leaves.add(b);
        }
        return new TreeBlocks(logs, leaves);
    }

    // =========================
    // ★ Animation: top-down removal + FallingBlock
    // =========================
    private void playFallingAnimationThenDrop(
            World world,
            Location center,
            TreeBlocks falling,
            double spread,
            Map<Material, Integer> leafDrops,
            Map<Material, Integer> logDrops,
            int sticks,
            Material saplingType,
            int saplings,
            int apples,
            Player player
    ) {
        List<Block> animBlocks = new ArrayList<>(falling.logs.size() + falling.leaves.size());
        animBlocks.addAll(falling.logs);
        animBlocks.addAll(falling.leaves);

        animBlocks.sort((a, b) -> Integer.compare(b.getY(), a.getY()));

        int batch = Math.max(8, getConfig().getInt("animation.blocks-per-tick", 18));
        long tickDelay = Math.max(1, getConfig().getLong("animation.tick-delay", 1L));

        new BukkitRunnable() {
            int idx = 0;

            @Override
            public void run() {
                if (idx >= animBlocks.size()) {
                    // after animation => drops
                    dropScattered(world, center, leafDrops, spread);
                    dropScattered(world, center, logDrops, spread);

                    if (sticks > 0) scatterItem(world, center, new ItemStack(Material.STICK, sticks), spread);
                    if (saplingType != null && saplings > 0) scatterItem(world, center, new ItemStack(saplingType, saplings), spread);
                    if (apples > 0) scatterItem(world, center, new ItemStack(Material.APPLE, apples), spread);

                    if (getConfig().getBoolean("damage-tool", true)) {
                        // Дамажим инструмент только за УПАВШИЕ логи
                        damageTool(player, falling.logs.size());
                    }

                    cancel();
                    return;
                }

                int done = 0;
                while (idx < animBlocks.size() && done < batch) {
                    Block b = animBlocks.get(idx++);
                    Material type = b.getType();
                    if (type == Material.AIR) { done++; continue; }

                    BlockData data = b.getBlockData();
                    Location spawnLoc = b.getLocation().add(0.5, 0.2, 0.5);

                    b.setType(Material.AIR, false);

                    FallingBlock fb = world.spawnFallingBlock(spawnLoc, data);
                    fb.setDropItem(false);
                    fb.setHurtEntities(false);
                    fb.addScoreboardTag(FALLING_TAG);

                    double vx = (random.nextDouble() - 0.5) * 0.08;
                    double vz = (random.nextDouble() - 0.5) * 0.08;
                    fb.setVelocity(fb.getVelocity().setX(vx).setZ(vz));

                    done++;
                }

                if (random.nextInt(3) == 0) {
                    world.playSound(center, Sound.BLOCK_WOOD_BREAK, 0.6f, 1.0f);
                }
            }
        }.runTaskTimer(this, 0L, tickDelay);
    }

    // =========================
    // Leaf drop scaling: 10..20
    // =========================
    private int computeLeafDropTarget(int leafCount) {
        if (leafCount <= 0) return 0;

        int min = 10;
        int max = 20;

        int low = 40;
        int high = 160;

        if (leafCount <= low) return Math.min(min, leafCount);
        if (leafCount >= high) return Math.min(max, leafCount);

        double t = (leafCount - low) / (double) (high - low);
        int target = (int) Math.round(min + t * (max - min));
        target = Math.max(min, Math.min(max, target));
        return Math.min(target, leafCount);
    }

    private void dropScattered(World w, Location center, Map<Material, Integer> items, double radius) {
        for (var e : items.entrySet()) {
            int left = e.getValue();
            while (left > 0) {
                int give = Math.min(64, left);
                scatterItem(w, center, new ItemStack(e.getKey(), give), radius);
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

    // =========================
    // ★ Trunk bottom & height
    // =========================
    private Block findTrunkBottom(Block start) {
        Block c = start;
        while (Tag.LOGS.isTagged(c.getRelative(0, -1, 0).getType())) {
            c = c.getRelative(0, -1, 0);
        }
        return c;
    }

    private int measureTrunkHeight(Block bottom) {
        int h = 0;
        Block c = bottom;
        while (Tag.LOGS.isTagged(c.getType())) {
            h++;
            c = c.getRelative(0, 1, 0);
        }
        return h;
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
            default -> null;
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

    // =========================
    // WorldGuard (reflection)
    // =========================
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

            Object bv = adapter.getMethod("asBlockVector", Location.class).invoke(null, block.getLocation());

            Object regions = regionManager.getClass()
                    .getMethod("getApplicableRegions", Class.forName("com.sk89q.worldedit.math.BlockVector3"))
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

    // =========================
    // Records
    // =========================
    private record TreeBlocks(Set<Block> logs, Set<Block> leaves) {}

    // =========================
    // RealisticSeasons hook (dynamic)
    // =========================
    private static final class RealisticSeasonsHook {
        private final Plugin realisticSeasons;
        private final java.util.logging.Logger log;

        private ClassLoader rsLoader;
        private Class<?> seasonsApiClass;
        private Method getInstance;
        private Method getSeason;
        private Object seasonsApiInstance;

        RealisticSeasonsHook(Plugin realisticSeasons, java.util.logging.Logger log) {
            this.realisticSeasons = realisticSeasons;
            this.log = log;
        }

        boolean init() {
            try {
                rsLoader = realisticSeasons.getClass().getClassLoader();

                String seasonsApiFqcn = findClassInJar(realisticSeasons, "SeasonsAPI.class");
                if (seasonsApiFqcn == null) {
                    log.warning("[TreeFall] Could not find SeasonsAPI.class inside RealisticSeasons jar");
                    return false;
                }

                seasonsApiClass = Class.forName(seasonsApiFqcn, true, rsLoader);
                getInstance = seasonsApiClass.getMethod("getInstance");
                seasonsApiInstance = getInstance.invoke(null);
                getSeason = seasonsApiClass.getMethod("getSeason", World.class);

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
                            String fqcn = name.replace('/', '.');
                            return fqcn.substring(0, fqcn.length() - ".class".length());
                        }
                    }
                }
            } catch (Throwable ignored) { }
            return null;
        }
    }
}
