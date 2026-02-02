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

public class TreeFallPlugin extends JavaPlugin implements Listener {

    private static final String PERMISSION_USE = "treefall.use";
    private final Random random = new Random();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("TreeFall включён (усиленная защита)");
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

        // 🔒 УСИЛЕННАЯ ПРОВЕРКА НА ДЕРЕВО
        TrunkInfo trunk = analyzeTrunk(base);
        int minHeight = Math.max(3, getConfig().getInt("min-trunk-height", 4));

        if (trunk.height < minHeight) return;
        if (!hasLeafCanopy(trunk.top)) return;
        if (hasSideLogsAtBase(base)) return; // здание

        int maxBlocks = Math.max(32, getConfig().getInt("max-blocks", 512));
        TreeBlocks tree = collectTree(trunk.base, maxBlocks);

        if (tree.logs.isEmpty() || tree.logs.size() < minHeight) return;

        event.setCancelled(true);

        World world = base.getWorld();
        Location dropLoc = findGroundBelow(world, base.getLocation());

        Map<Material, Integer> logDrops = new HashMap<>();

        for (Block b : tree.leaves) {
            playFx(world, b);
            b.setType(Material.AIR, false);
        }

        for (Block b : tree.logs) {
            playFx(world, b);
            logDrops.merge(b.getType(), 1, Integer::sum);
            b.setType(Material.AIR, false);
        }

        // 💎 ДРОП ТОЧНОГО ТИПА ЛОГОВ
        for (Map.Entry<Material, Integer> e : logDrops.entrySet()) {
            world.dropItemNaturally(dropLoc, new ItemStack(e.getKey(), e.getValue()));
        }

        if (getConfig().getBoolean("damage-tool", true)) {
            damageTool(player, tree.logs.size());
        }
    }

    // ─────────────────────────────
    // 🔍 АНАЛИЗ СТВОЛА
    // ─────────────────────────────

    private TrunkInfo analyzeTrunk(Block start) {
        Block current = start;
        int height = 0;

        while (Tag.LOGS.isTagged(current.getType())) {
            height++;
            current = current.getRelative(0, 1, 0);
        }

        return new TrunkInfo(start, current.getRelative(0, -1, 0), height);
    }

    private boolean hasLeafCanopy(Block top) {
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = 0; dy <= 3; dy++) {
                for (int dz = -2; dz <= 2; dz++) {
                    if (Tag.LEAVES.isTagged(top.getRelative(dx, dy, dz).getType())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean hasSideLogsAtBase(Block base) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                if (Tag.LOGS.isTagged(base.getRelative(dx, 0, dz).getType())) {
                    return true;
                }
            }
        }
        return false;
    }

    // ─────────────────────────────
    // 🌳 СБОР ДЕРЕВА
    // ─────────────────────────────

    private TreeBlocks collectTree(Block start, int limit) {
        Set<Block> visited = new HashSet<>();
        Queue<Block> q = new ArrayDeque<>();
        q.add(start);

        Set<Block> logs = new HashSet<>();
        Set<Block> leaves = new HashSet<>();

        while (!q.isEmpty() && visited.size() < limit) {
            Block b = q.poll();
            if (!visited.add(b)) continue;

            if (Tag.LOGS.isTagged(b.getType())) logs.add(b);
            else if (Tag.LEAVES.isTagged(b.getType())) leaves.add(b);
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
    // 🛠 УТИЛИТЫ
    // ─────────────────────────────

    private void playFx(World w, Block b) {
        w.spawnParticle(Particle.BLOCK, b.getLocation().add(0.5, 0.5, 0.5),
                6, 0.25, 0.25, 0.25, b.getBlockData());
        w.playSound(b.getLocation(), Sound.BLOCK_GRASS_BREAK, 0.5f, 1.2f);
    }

    private Location findGroundBelow(World w, Location l) {
        Location c = l.clone();
        while (c.getY() > w.getMinHeight() &&
                (w.getBlockAt(c).getType() == Material.AIR || w.getBlockAt(c).isPassable())) {
            c.subtract(0, 1, 0);
        }
        return c.add(0, 1, 0);
    }

    private void damageTool(Player p, int uses) {
        ItemStack tool = p.getInventory().getItemInMainHand();
        if (!(tool.getItemMeta() instanceof Damageable dmg)) return;

        int unbreaking = tool.getEnchantmentLevel(Enchantment.UNBREAKING);
        int applied = 0;

        for (int i = 0; i < uses; i++) {
            if (unbreaking > 0 && random.nextInt(unbreaking + 1) != 0) applied++;
            else if (unbreaking == 0) applied++;
        }

        dmg.setDamage(dmg.getDamage() + applied);
        tool.setItemMeta(dmg);

        if (dmg.getDamage() >= tool.getType().getMaxDurability()) {
            p.getInventory().setItemInMainHand(null);
        }
    }

    private record TrunkInfo(Block base, Block top, int height) {}
    private record TreeBlocks(Set<Block> logs, Set<Block> leaves) {}
}
