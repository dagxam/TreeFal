// ⚠️ ВАЖНО: файл большой, но это ПОЛНАЯ РАБОЧАЯ ВЕРСИЯ
// Все изменения помечены комментариями // ★

package me.dagxam.treefall;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
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

    private static final int BIG_TREE_LEAVES = 160; // ★ порог большого дерева

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(this, this);
        Plugin wg = getServer().getPluginManager().getPlugin("WorldGuard");
        worldGuardPresent = wg != null && wg.isEnabled();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onLogBreak(BlockBreakEvent event) {

        Block base = event.getBlock();
        if (!Tag.LOGS.isTagged(base.getType())) return;

        Player p = event.getPlayer();
        if (p.getGameMode() == GameMode.CREATIVE) return;
        if (!p.hasPermission(PERMISSION_USE)) return;
        if (!p.getInventory().getItemInMainHand().getType().name().endsWith("_AXE")) return;

        TrunkInfo trunk = analyzeTrunk(base);
        if (trunk.height < 4) return;

        // ★ увеличенный лимит для больших деревьев
        TreeBlocks tree = collectTree(trunk.base, trunk.height > 10 ? 2000 : 700);
        if (tree.logs.size() < 4) return;

        event.setCancelled(true);

        boolean bigTree = tree.leaves.size() >= BIG_TREE_LEAVES;

        World world = base.getWorld();
        Location center = base.getLocation();

        // ★ радиус разброса
        double spread = bigTree ? 3.5 : 1.8;

        // ─── ЛИСТЬЯ (10–20) ───
        int leafDrop = computeLeafDrop(tree.leaves.size());
        List<Block> shuffled = new ArrayList<>(tree.leaves);
        Collections.shuffle(shuffled, random);

        Map<Material, Integer> leafDrops = new HashMap<>();
        for (int i = 0; i < shuffled.size(); i++) {
            Block b = shuffled.get(i);
            if (i < leafDrop) leafDrops.merge(b.getType(), 1, Integer::sum);
            b.setType(Material.AIR, false);
        }
        dropScattered(world, center, leafDrops, spread);

        // ─── ЛОГИ (1:1) ───
        Map<Material, Integer> logs = new HashMap<>();
        for (Block b : tree.logs) {
            logs.merge(b.getType(), 1, Integer::sum);
            b.setType(Material.AIR, false);
        }
        dropScattered(world, center, logs, spread);

        // ─── ЯБЛОКИ ★ ───
        Material leafType = getAnyLeaf(tree);
        if (leafType == Material.OAK_LEAVES || leafType == Material.DARK_OAK_LEAVES) {
            int apples = bigTree ? 5 : 3;
            scatterItem(world, center, new ItemStack(Material.APPLE, apples), spread);
        }

        // ─── САЖЕНЦЫ (как было) ───
        Material sapling = getSaplingForLeaf(leafType);
        if (sapling != null) {
            int amount = bigTree ? 3 : 2;
            scatterItem(world, center, new ItemStack(sapling, amount), spread);
        }

        damageTool(p, tree.logs.size());
    }

    // ─────────────────────────────
    // ★ РАЗБРОС
    // ─────────────────────────────

    private void dropScattered(World w, Location c, Map<Material,Integer> items, double r) {
        for (var e : items.entrySet()) {
            int left = e.getValue();
            while (left > 0) {
                int give = Math.min(64, left);
                ItemStack it = new ItemStack(e.getKey(), give);
                scatterItem(w, c, it, r);
                left -= give;
            }
        }
    }

    private void scatterItem(World w, Location c, ItemStack it, double r) {
        double dx = (random.nextDouble() - 0.5) * r;
        double dz = (random.nextDouble() - 0.5) * r;
        Location l = c.clone().add(dx, 0.2, dz);
        w.dropItemNaturally(l, it);
    }

    // ─────────────────────────────
    // ВСПОМОГАТЕЛЬНОЕ
    // ─────────────────────────────

    private int computeLeafDrop(int leaves) {
        if (leaves <= 40) return 10;
        if (leaves >= 160) return 20;
        double t = (leaves - 40) / 120.0;
        return 10 + (int) Math.round(t * 10);
    }

    private Material getAnyLeaf(TreeBlocks t) {
        for (Block b : t.leaves) return b.getType();
        return null;
    }

    private Material getSaplingForLeaf(Material m) {
        return switch (m) {
            case OAK_LEAVES -> Material.OAK_SAPLING;
            case BIRCH_LEAVES -> Material.BIRCH_SAPLING;
            case SPRUCE_LEAVES -> Material.SPRUCE_SAPLING;
            case JUNGLE_LEAVES -> Material.JUNGLE_SAPLING;
            case ACACIA_LEAVES -> Material.ACACIA_SAPLING;
            case DARK_OAK_LEAVES -> Material.DARK_OAK_SAPLING;
            default -> null;
        };
    }

    private void damageTool(Player p, int uses) {
        ItemStack tool = p.getInventory().getItemInMainHand();
        if (!(tool.getItemMeta() instanceof Damageable d)) return;
        d.setDamage(d.getDamage() + uses);
        tool.setItemMeta(d);
    }

    // ─────────────────────────────
    // СБОР ДЕРЕВА
    // ─────────────────────────────

    private TrunkInfo analyzeTrunk(Block b) {
        int h = 0;
        Block c = b;
        while (Tag.LOGS.isTagged(c.getType())) {
            h++;
            c = c.getRelative(0,1,0);
        }
        return new TrunkInfo(b, c.getRelative(0,-1,0), h);
    }

    private TreeBlocks collectTree(Block start, int limit) {
        Set<Block> logs = new HashSet<>();
        Set<Block> leaves = new HashSet<>();
        Queue<Block> q = new ArrayDeque<>();
        q.add(start);

        while (!q.isEmpty() && logs.size() + leaves.size() < limit) {
            Block b = q.poll();
            if (logs.contains(b) || leaves.contains(b)) continue;

            if (Tag.LOGS.isTagged(b.getType())) logs.add(b);
            else if (Tag.LEAVES.isTagged(b.getType())) leaves.add(b);
            else continue;

            for (int x=-1;x<=1;x++)
                for (int y=-1;y<=1;y++)
                    for (int z=-1;z<=1;z++)
                        if (!(x==0&&y==0&&z==0))
                            q.add(b.getRelative(x,y,z));
        }
        return new TreeBlocks(logs, leaves);
    }

    private record TrunkInfo(Block base, Block top, int height){}
    private record TreeBlocks(Set<Block> logs, Set<Block> leaves){}
}
