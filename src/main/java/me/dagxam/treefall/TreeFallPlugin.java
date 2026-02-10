// src/main/java/me/dagxam/treefall/TreeFallPlugin.java

package me.dagxam.treefall;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;

public class TreeFallPlugin extends JavaPlugin implements Listener {

    private static final String PERMISSION_USE = "treefall.use";
    static final String FALLING_TAG = "treefall_falling";

    final Random random = new Random();
    private boolean worldGuardPresent;

    // Кешированные настройки
    Settings settings;

    RealisticSeasonsHook rsHook;
    private WorldGuardHook wgHook;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        settings = new Settings(this);
        getServer().getPluginManager().registerEvents(this, this);

        Plugin wg = getServer().getPluginManager().getPlugin("WorldGuard");
        worldGuardPresent = wg != null && wg.isEnabled();
        if (worldGuardPresent) {
            wgHook = new WorldGuardHook();
        }

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

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (cmd.getName().equalsIgnoreCase("treefall")) {
            if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
                if (!sender.hasPermission("treefall.admin")) {
                    sender.sendMessage("§cNo permission.");
                    return true;
                }
                settings = new Settings(this);
                sender.sendMessage("§aTreeFall config reloaded.");
                return true;
            }
            sender.sendMessage("§eUsage: /treefall reload");
            return true;
        }
        return false;
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

    // Cooldowns
    private final Map<UUID, Long> cooldowns = new HashMap<>();

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onLogBreak(BlockBreakEvent event) {

        if (!settings.enabled) return;

        Block cutBlock = event.getBlock();
        if (!Tag.LOGS.isTagged(cutBlock.getType())) return;

        Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.CREATIVE) return;

        // Shift = обычное поведение
        if (settings.sneakToDisable && player.isSneaking()) return;

        if (settings.requirePermission && !player.hasPermission(PERMISSION_USE)) return;

        // Cooldown
        long now = System.currentTimeMillis();
        Long last = cooldowns.get(player.getUniqueId());
        if (last != null && now - last < settings.cooldownMs) return;

        // Требование топора для больших деревьев (опционально)
        boolean hasAxe = player.getInventory().getItemInMainHand().getType().name().endsWith("_AXE");

        if (worldGuardPresent && wgHook != null && !wgHook.canBreak(player, cutBlock)) return;

        final int cutY = cutBlock.getY();

        Block trunkBottom = TreeDetector.findTrunkBottom(cutBlock);

        int trunkHeight = TreeDetector.measureTrunkHeight(trunkBottom);
        if (trunkHeight < settings.minTrunkHeight) return;

        // Требовать топор для больших деревьев
        if (settings.requireAxeForBig && !hasAxe && trunkHeight > 6) return;

        // Анти-дом: если у основания есть боковые логи (с учётом 2×2)
        if (TreeDetector.hasSideLogsAtBase(trunkBottom)) return;

        // Должно быть "похоже на дерево": крона выше
        Block top = trunkBottom.getRelative(0, trunkHeight - 1, 0);
        if (!TreeDetector.hasCanopyAbove(top)) return;

        // Сбор дерева (для больших — расширяем)
        int firstTryLimit = Math.max(settings.maxBlocks, 700);
        TreeBlocks fullTree = TreeDetector.collectTree(trunkBottom, firstTryLimit);

        if (fullTree.logs().size() + fullTree.leaves().size() >= firstTryLimit - 10
                || fullTree.leaves().size() >= Settings.BIG_TREE_LEAVES) {
            fullTree = TreeDetector.collectTree(trunkBottom, Math.max(firstTryLimit, 2200));
        }

        if (fullTree.logs().isEmpty()) return;

        // Разделяем: что падает, что остаётся
        TreeBlocks falling = sliceAboveY(fullTree, cutY);
        if (falling.logs().isEmpty()) return;

        // Ставим cooldown
        cooldowns.put(player.getUniqueId(), now);

        // Отменяем обычную ломку
        event.setCancelled(true);

        World world = cutBlock.getWorld();
        Location center = cutBlock.getLocation();

        // Рассчитываем дроп
        String season = (rsHook != null) ? rsHook.getSeasonName(world) : null;
        TreeDropCalculator.DropResult drops = TreeDropCalculator.calculate(
                this, falling, season, settings);

        // Анимация
        TreeAnimator.play(this, world, center, falling, drops, player);
    }

    private TreeBlocks sliceAboveY(TreeBlocks tree, int cutY) {
        Set<Block> logs = new HashSet<>();
        Set<Block> leaves = new HashSet<>();
        for (Block b : tree.logs()) {
            if (b.getY() >= cutY) logs.add(b);
        }
        for (Block b : tree.leaves()) {
            if (b.getY() >= cutY) leaves.add(b);
        }
        return new TreeBlocks(logs, leaves);
    }
}
