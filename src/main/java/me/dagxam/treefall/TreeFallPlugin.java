package me.dagxam.treefall;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import com.sk89q.worldguard.protection.util.WorldEditRegionConverter;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;

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
    private boolean worldGuardEnabled;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(this, this);

        Plugin wg = getServer().getPluginManager().getPlugin("WorldGuard");
        worldGuardEnabled = wg != null && wg.isEnabled();

        getLogger().info("TreeFall enabled | WG=" + worldGuardEnabled);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onLogBreak(BlockBreakEvent event) {
        Block base = event.getBlock();
        if (!Tag.LOGS.isTagged(base.getType())) return;

        Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.CREATIVE) return;

        if (getConfig().getBoolean("require-permission", true)
                && !player.hasPermission(PERMISSION_USE)) return;

        if (getConfig().getBoolean("require-axe", true)
                && !player.getInventory().getItemInMainHand().getType().name().endsWith("_AXE")) return;

        // 🛡 WorldGuard
        if (worldGuardEnabled && !canBreakWG(player, base)) return;

        // 🌲 анализ ствола
        TrunkInfo trunk = analyzeTrunk(base);
        int minHeight = Math.max(3, getConfig().getInt("min-trunk-height", 4));

        if (trunk.height < minHeight) return;
        if (!hasModdedCanopy(trunk.top)) return;
        if (hasSideLogsAtBase(base)) return;

        TreeBlocks tree = collectTree(trunk.base,
                Math.max(64, getConfig().getInt("max-blocks", 512)));

        if (tree.logs.size() < minHeight) return;

        event.setCancelled(true);

        World world = base.getWorld();
        Location drop = findGroundBelow(world, base.getLocation());

        Map<Material, Integer> drops = new HashMap<>();

        for (Block b : tree.leaves) {
            fx(world, b);
            b.setType(Material.AIR, false);
        }

        for (Block b : tree.logs) {
            fx(world, b);
            drops.merge(b.getType(), 1, Integer::sum);
            b.setType(Material.AIR, false);
        }

        for (var e : drops.entrySet()) {
            world.dropItemNaturally(drop, new ItemStack(e.getKey(), e.getValue()));
        }

        if (getConfig().getBoolean("damage-tool", true)) {
            damageTool(player, tree.logs.size());
        }
    }

    // ─────────────────────────────
    // 🌍 WorldGuard
    // ─────────────────────────────

    private boolean canBreakWG(Player p, Block b) {
        RegionContainer container = WorldGuard.getInstance().getPlatform().getRegionContainer();
        RegionManager manager = container.get(BukkitAdapter.adapt(b.getWorld()));
        if (manager == null) return true;

        ApplicableRegionSet regions = manager.getApplicableRegions(
                BukkitAdapter.asBlockVector(b.getLocation()));

        return regions.testState(
                WorldGuardPlugin.inst().wrapPlayer(p),
                Flags.BUILD
        );
    }

    // ─────────────────────────────
    // 🌲 Modded canopy detection
    // ─────────────────────────────

    private boolean hasModdedCanopy(Block top) {
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = 0; dy <= 4; dy++) {
                for (int dz = -2; dz <= 2; dz++) {
                    Block b = top.getRelative(dx, dy, dz);
                    if (isLeafLike(b)) return true;
                }
            }
        }
        return false;
    }

    private boolean isLeafLike(Block b) {
        Material t = b.getType();
        BlockData data = b.getBlockData();

        if (Tag.LEAVES.isTagged(t)) return true;
        if (data instanceof Leaves) return true;

        String name = t.name();
        return name.contains("LEAVES")
                || name.contains("FOLIAGE")
                || name.contains("NEEDLES")
                || name.contains("CANOPY");
    }

    // ─────────────────────────────
    // 🌳 Trunk / Tree
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
                if (Tag.LOGS.isTagged(base.getRelative(dx, 0, dz).getType())) {
                    return true;
                }
            }
        }
        return false;
    }

    private TreeBlocks collectTree(Block start, int limit) {
        Set<Block> logs = new HashSet<>();
        Set<Block> leaves = new HashSet<>();
        Queue<Block> q = new ArrayDeque<>();
        Set<Block> visited = new HashSet<>();

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
    // 🛠 Utils
    // ─────────────────────────────

    private void fx(World w, Block b) {
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
