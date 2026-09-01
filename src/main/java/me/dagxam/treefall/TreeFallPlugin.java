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
        getLogger().info("TreeFall 1.6.0 enabled.");
    }

    @Override public void onDisable() {
        activeTrees.clear();
        cooldowns.clear();
    }

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
            getLogger().warning("RealisticSeasons detected, but API hook failed. Seasonal logic disabled.");
        } else getLogger().info("RealisticSeasons detected. Seasonal logic enabled.");
    }

    @Override public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!cmd.getName().equalsIgnoreCase("treefall")) return false;
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("treefall.admin")) {
                sender.sendMessage(settings.noPermissionMessage);
                return true;
            }
            settings = new Settings(this);
            sender.sendMessage(settings.reloadMessage);
            getLogger().info("Configuration reloaded by " + sender.getName() + ".");
            return true;
        }
        sender.sendMessage(settings.usageMessage);
        return true;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFallingBlockLand(EntityChangeBlockEvent event) {
        Entity entity = event.getEntity();
        if (entity instanceof FallingBlock fallingBlock
                && fallingBlock.getScoreboardTags().contains(FALLING_TAG)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onLogBreak(BlockBreakEvent event) {
        if (!settings.enabled) return;
        Block cutBlock = event.getBlock();
        if (!Tag.LOGS.isTagged(cutBlock.getType())) return;

        Player player = event.getPlayer();
        // TreeFall must work in both survival and creative. Creative only changes
        // the normal tool-durability behavior; it must not disable the tree mechanic.
        if (settings.isWorldBlacklisted(cutBlock.getWorld().getName())) return;
        if (player.hasPermission(settings.bypassPermission)) return;
        if (settings.sneakToDisable && player.isSneaking()) return;

        long now = System.currentTimeMillis();
        Long last = cooldowns.get(player.getUniqueId());
        if (last != null && now - last < settings.cooldownMs) return;
        cleanupCooldowns(now);

        if (worldGuardPresent && wgHook != null && !wgHook.canBreak(player, cutBlock)) return;

        TreeBlocks fullTree;
        try {
            fullTree = collectTreeWithRetry(cutBlock);
        } catch (Throwable throwable) {
            getLogger().warning("TreeFall detector error at " + cutBlock.getLocation() + ": "
                    + throwable.getClass().getSimpleName() + ": " + throwable.getMessage());
            return;
        }
        if (fullTree.truncated() || fullTree.logs().isEmpty()) return;

        // The block actually broken is the pivot/cut point. Everything below it
        // stays in the world; everything at or above it is the part that falls.
        TreeBlocks fallingTree = TreeDetector.sliceFromCut(fullTree, cutBlock);
        if (fallingTree.logs().isEmpty()) return;

        Block trunkBottom = TreeDetector.findTrunkBottom(cutBlock);
        if (!fullTree.logs().contains(trunkBottom)) {
            trunkBottom = fullTree.logs().stream()
                    .min(java.util.Comparator.comparingInt(Block::getY)
                            .thenComparingInt(Block::getX)
                            .thenComparingInt(Block::getZ))
                    .orElse(cutBlock);
        }

        String treeKey = TreeDetector.getTreeKey(trunkBottom);
        if (!activeTrees.add(treeKey)) return;

        try {
            if (worldGuardPresent && wgHook != null && !canBreakWholeTree(player, fallingTree)) {
                activeTrees.remove(treeKey);
                player.sendMessage(settings.worldGuardErrorMessage);
                return;
            }

            // Cancel vanilla breaking before TreeAnimator changes any blocks.
            event.setCancelled(true);
            event.setDropItems(false);
            cooldowns.put(player.getUniqueId(), now);

            World world = cutBlock.getWorld();
            Location center = cutBlock.getLocation().add(0.5, 0.0, 0.5);
            String season = rsHook != null ? rsHook.getSeasonName(world) : null;
            ItemStack toolSnapshot = player.getInventory().getItemInMainHand().clone();

            TreeDropCalculator.DropResult drops = TreeDropCalculator.calculate(
                    this, fallingTree, season, settings, toolSnapshot);

            int toolSlot = player.getInventory().getHeldItemSlot();
            Vector fallDirection = player.getLocation().getDirection().setY(0);
            if (fallDirection.lengthSquared() < 0.001) fallDirection = new Vector(0, 0, 1);
            else fallDirection.normalize();

            TreeAnimator.play(this, world, center, fallingTree, drops, player,
                    toolSlot, toolSnapshot, treeKey, fallDirection);
        } catch (Throwable throwable) {
            activeTrees.remove(treeKey);
            getLogger().severe("TreeFall failed safely: " + throwable.getClass().getSimpleName()
                    + ": " + throwable.getMessage());
        }
    }

    private TreeBlocks collectTreeWithRetry(Block cutBlock) {
        int firstTryLimit = settings.maxBlocks;
        TreeBlocks tree = TreeDetector.collectTree(cutBlock, firstTryLimit, settings);
        if (tree.truncated()) {
            tree = TreeDetector.collectTree(cutBlock,
                    Math.min(5000, Math.max(firstTryLimit, 2200)), settings);
        }
        if (tree.truncated() && firstTryLimit < 5000) {
            tree = TreeDetector.collectTree(cutBlock, 5000, settings);
        }
        return tree;
    }

    private boolean canBreakWholeTree(Player player, TreeBlocks tree) {
        for (Block block : tree.logs()) if (!wgHook.canBreak(player, block)) return false;
        for (Block block : tree.leaves()) if (!wgHook.canBreak(player, block)) return false;
        return true;
    }

    void releaseTree(String treeKey) { if (treeKey != null) activeTrees.remove(treeKey); }
    int activeTreeCount() { return activeTrees.size(); }

    private void cleanupCooldowns(long now) {
        if (cooldowns.size() < 256) return;
        cooldowns.entrySet().removeIf(entry ->
                now - entry.getValue() > Math.max(settings.cooldownMs * 4L, 5000L));
    }
}
