package me.dagxam.treefall;

import org.bukkit.plugin.java.JavaPlugin;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class Settings {

    static final int BIG_TREE_LEAVES = 160;

    final boolean enabled;
    final boolean requirePermission;
    final boolean sneakToDisable;
    final boolean damageTool;
    final boolean requireAxeForBig;
    final int minTrunkHeight;
    final int maxBlocks;
    final long cooldownMs;
    final String bypassPermission;
    final Set<String> worldBlacklist;

    final int animBlocksPerTick;
    final long animTickDelay;
    final int maxFallingBlocks;
    final long animationTimeoutTicks;

    final double stickChance;
    final double saplingChance;

    final String noPermissionMessage;
    final String reloadMessage;
    final String usageMessage;

    Settings(JavaPlugin plugin) {
        plugin.reloadConfig();
        var c = plugin.getConfig();

        enabled = c.getBoolean("enabled", true);
        requirePermission = c.getBoolean("authorization.require-permission",
                c.getBoolean("require-permission", true));
        sneakToDisable = c.getBoolean("sneak-to-disable", true);
        damageTool = c.getBoolean("damage-tool", true);
        requireAxeForBig = c.getBoolean("require-axe-for-big", false);

        minTrunkHeight = clampInt(c.getInt("min-trunk-height", 4), 3, 128);
        maxBlocks = clampInt(c.getInt("max-blocks", 512), 64, 5000);
        cooldownMs = Math.max(0L, c.getLong("cooldown-ms", 500L));
        bypassPermission = c.getString("authorization.bypass-permission", "treefall.bypass");

        worldBlacklist = loadWorldBlacklist(c.getStringList("world-blacklist"));

        animBlocksPerTick = clampInt(c.getInt("animation.blocks-per-tick", 18), 1, 100);
        animTickDelay = Math.max(1L, c.getLong("animation.tick-delay", 1L));
        maxFallingBlocks = clampInt(c.getInt("animation.max-falling-blocks", 80), 10, 300);
        animationTimeoutTicks = clampLong(c.getLong("animation.timeout-ticks", 100L), 20L, 600L);

        stickChance = clamp01(c.getDouble("drop.chance.stick", 0.02));
        saplingChance = clamp01(c.getDouble("drop.chance.sapling", 0.05));

        noPermissionMessage = c.getString("messages.no-permission", "§cУ вас нет прав.");
        reloadMessage = c.getString("messages.reload", "§aКонфигурация TreeFall перезагружена.");
        usageMessage = c.getString("messages.usage", "§eИспользование: /treefall reload");
    }

    boolean isWorldBlacklisted(String worldName) {
        return worldBlacklist.contains(worldName.toLowerCase(Locale.ROOT));
    }

    private static Set<String> loadWorldBlacklist(List<String> worlds) {
        if (worlds == null || worlds.isEmpty()) return Collections.emptySet();
        Set<String> result = new HashSet<>();
        for (String world : worlds) {
            if (world != null && !world.isBlank()) {
                result.add(world.trim().toLowerCase(Locale.ROOT));
            }
        }
        return Collections.unmodifiableSet(result);
    }

    private static int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static long clampLong(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}
