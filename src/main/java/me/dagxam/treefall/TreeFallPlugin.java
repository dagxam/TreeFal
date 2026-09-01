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

    @Override
    public void onEnable() {
        saveDefaultConfig();
        settings = new Settings(this);
        getServer().getPluginManager().registerEvents(this, this);
        setupWorldGuard();
        setupRealisticSeasons();
        getLogger().info("TreeFall 1.6.0 enabled.");
    }

    @Override
    public void onDisable() {
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
        } else {
            getLogger().info("RealisticSeasons detected. Seasonal logic enabled.");
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
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

        // IMPORTANT: build the tree from the block actually hit, not only from
        // the guessed trunk bottom. A branch hit must still discover the complete
        // sapling-grown tree. The bottom is used only for the tree identity and
        // animation center after the structure has been successfully detected.
        TreeBlocks fullTree;
        Block trunkBottom;
        try {
            fullTree = collectTreeWithRetry(cutBlock);
            if (fullTree.truncated() || fullTree.logs().isEmpty()) return;

            // A valid TreeFall target needs more than the single block the player
            // hit. Leaves are enough to identify a small natural tree, while a
            // multi-log trunk/branch is also valid without a perfect canopy.
            if (fullTree.logs().size() < 2 && fullTree.leaves().isEmpty()) return;

            trunkBottom = TreeDetector.findTrunkBottom(cutBlock);
            if (!fullTree.logs().contains(trunkBottom)) {
                // The broad base search can find an unrelated nearby tree. In that
                // case use the lowest log belonging to this exact detected tree.
                trunkBottom = fullTree.logs().stream()
                        .min(java.util.Comparator.comparingInt(Block::getY))
                        .orElse(cutBlock);
            }
        } catch (Throwable throwable) {
            getLogger().warning("TreeFall detector rejected a log safely: "
                    + throwable.getClass().getSimpleName() + ": " + throwable.getMessage());
            return;
        }

        int trunkHeight = TreeDetector.measureTrunkHeight(trunkBottom);
        boolean hasAxe = player.getInventory().getItemInMainHand().getType().name().endsWith("_AXE");
        if (settings.requireAxeForBig && !hasAxe && trunkHeight > 6) return;

        String treeKey = TreeDetector.getTreeKey(trunkBottom);
        if (!activeTrees.add(treeKey)) {
            event.setCancelled(true);
            return;
        }

        try {
            if (worldGuardPresent && wgHook != null && !canBreakWholeTree(player, fullTree)) {
                activeTrees.remove(treeKey);
                player.sendMessage(settings.worldGuardErrorMessage);
                return;
            }

            // From this point TreeFall owns the block break. Vanilla must never
            // get a chance to remove only the originally hit log.
            event.setCancelled(true);
            cooldowns.put(player.getUniqueId(), now);

            World world = cutBlock.getWorld();
            Location center = trunkBottom.getLocation();
            String season = rsHook != null ? rsHook.getSeasonName(world) : null;
            ItemStack toolSnapshot = player.getInventory().getItemInMainHand().clone();
            TreeDropCalculator.DropResult drops = TreeDropCalculator.calculate(
                    this, fullTree, season, settings, toolSnapshot);

            int toolSlot = player.getInventory().getHeldItemSlot();
            Vector fallDirection = player.getLocation().getDirection().setY(0);
            if (fallDirection.lengthSquared() < 0.001) fallDirection = new Vector(0, 0, 1);
            else fallDirection.normalize();

            TreeAnimator.play(this, world, center, fullTree, drops, player,
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
        for (Block block : tree.logs()) {
            if (!wgHook.canBreak(player, block)) return false;
        }
        for (Block block : tree.leaves()) {
            if (!wgHook.canBreak(player, block)) return false;
        }
        return true;
    }

    void releaseTree(String treeKey) {
        if (treeKey != null) activeTrees.remove(treeKey);
    }

    int activeTreeCount() {
        return activeTrees.size();
    }

    private void cleanupCooldowns(long now) {
        if (cooldowns.size() < 256) return;
        cooldowns.entrySet().removeIf(entry ->
                now - entry.getValue() > Math.max(settings.cooldownMs * 4L, 5000L));
    }
}
