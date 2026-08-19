package me.dagxam.treefall;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class TreeFallPlugin extends JavaPlugin implements Listener {
    private static final String PERMISSION_USE = "treefall.use";
    static final String FALLING_TAG = "treefall_falling";
    final java.util.Random random = new java.util.Random();
    Settings settings;
    private boolean worldGuardPresent;
    private WorldGuardHook wgHook;
    RealisticSeasonsHook rsHook;
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private final Set<String> activeTrees = ConcurrentHashMap.newKeySet();

    @Override public void onEnable() {
        saveDefaultConfig();
        settings = new Settings(this);
        getServer().getPluginManager().registerEvents(this, this);
        setupWorldGuard();
        setupRealisticSeasons();
        getLogger().info("TreeFall 1.5.0 enabled.");
    }

    @Override public void onDisable() { activeTrees.clear(); cooldowns.clear(); }

    private void setupWorldGuard() {
        Plugin wg = getServer().getPluginManager().getPlugin("WorldGuard");
        worldGuardPresent = wg != null && wg.isEnabled();
        if (worldGuardPresent) {
            wgHook = new WorldGuardHook(getLogger());
            getLogger().info("WorldGuard detected. Region protection enabled.");
        }
    }

    private void setupRealisticSeasons() {
        Plugin rs = getServer().getPluginManager().getPlugin("RealisticSeasons");
        if (rs == null || !rs.isEnabled()) return;
        rsHook = new RealisticSeasonsHook(rs, getLogger());
        if (!rsHook.init()) {
            rsHook = null;
            getLogger().warning("RealisticSeasons hook failed. Seasonal logic disabled.");
        } else getLogger().info("RealisticSeasons detected. Seasonal logic enabled.");
    }

    @Override public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!cmd.getName().equalsIgnoreCase("treefall")) return false;
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("treefall.admin")) { sender.sendMessage(settings.noPermissionMessage); return true; }
            settings = new Settings(this);
            sender.sendMessage(settings.reloadMessage);
            return true;
        }
        sender.sendMessage(settings.usageMessage);
        return true;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFallingBlockLand(EntityChangeBlockEvent event) {
        Entity entity = event.getEntity();
        if (entity instanceof FallingBlock fallingBlock && fallingBlock.getScoreboardTags().contains(FALLING_TAG)) {
            event.setCancelled(true);
            fallingBlock.remove();
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onLogBreak(BlockBreakEvent event) {
        if (!settings.enabled) return;
        Block cutBlock = event.getBlock();
        if (!Tag.LOGS.isTagged(cutBlock.getType())) return;

        Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.CREATIVE) return;
        if (settings.isWorldBlacklisted(cutBlock.getWorld().getName())) return;
        if (settings.sneakToDisable && player.isSneaking()) return;
        if (player.hasPermission(settings.bypassPermission)) return;
        if (settings.requirePermission && !player.hasPermission(PERMISSION_USE)) return;

        long now = System.currentTimeMillis();
        Long last = cooldowns.get(player.getUniqueId());
        if (last != null && now - last < settings.cooldownMs) return;
        cleanupCooldowns(now);
        if (worldGuardPresent && wgHook != null && !wgHook.canBreak(player, cutBlock)) return;

        // The clicked block is the cut point. Build the tree from this block so the part below it
        // is never included in the falling animation.
        TreeBlocks fullTree = TreeDetector.collectTree(cutBlock, Math.min(5000, Math.max(settings.maxBlocks, 2200)));
        if (fullTree.truncated() || fullTree.logs().isEmpty()) return;

        TreeBlocks falling = sliceAtOrAbove(fullTree, cutBlock.getY());
        if (falling.logs().isEmpty()) return;

        // A tree-fall needs something above the cut. If the player breaks the only log, let
        // vanilla Minecraft handle that block normally.
        boolean hasUpperLog = false;
        for (Block block : falling.logs()) {
            if (block.getY() > cutBlock.getY()) { hasUpperLog = true; break; }
        }
        boolean hasUpperLeaves = false;
        for (Block block : falling.leaves()) {
            if (block.getY() >= cutBlock.getY()) { hasUpperLeaves = true; break; }
        }
        if (!hasUpperLog && !hasUpperLeaves) return;

        String treeKey = TreeDetector.getTreeKey(cutBlock);
        if (!activeTrees.add(treeKey)) return;

        try {
            event.setCancelled(true);
            cooldowns.put(player.getUniqueId(), now);

            World world = cutBlock.getWorld();
            Location center = cutBlock.getLocation();
            String season = rsHook != null ? rsHook.getSeasonName(world) : null;
            TreeDropCalculator.DropResult drops = TreeDropCalculator.calculate(this, falling, season, settings);
            int toolSlot = player.getInventory().getHeldItemSlot();
            ItemStack toolSnapshot = player.getInventory().getItemInMainHand().clone();

            Vector fallDirection = player.getLocation().getDirection().setY(0);
            if (fallDirection.lengthSquared() < 0.001) fallDirection = new Vector(0, 0, 1);
            else fallDirection.normalize();

            TreeAnimator.play(this, world, center, falling, drops, player, toolSlot,
                    toolSnapshot, treeKey, fallDirection);
        } catch (Throwable throwable) {
            activeTrees.remove(treeKey);
            getLogger().severe("TreeFall failed safely: " + throwable.getClass().getSimpleName()
                    + ": " + throwable.getMessage());
        }
    }

    void releaseTree(String treeKey) { if (treeKey != null) activeTrees.remove(treeKey); }

    private void cleanupCooldowns(long now) {
        if (cooldowns.size() < 256) return;
        cooldowns.entrySet().removeIf(entry -> now - entry.getValue() > Math.max(settings.cooldownMs * 4L, 5000L));
    }

    private TreeBlocks sliceAtOrAbove(TreeBlocks tree, int cutY) {
        Set<Block> logs = new HashSet<>();
        Set<Block> leaves = new HashSet<>();
        for (Block block : tree.logs()) if (block.getY() >= cutY) logs.add(block);
        for (Block block : tree.leaves()) if (block.getY() >= cutY) leaves.add(block);
        return new TreeBlocks(logs, leaves);
    }
}
