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
    final boolean ignorePersistentLeaves;
    final int maxTreeHorizontalDistance;
    final int maxTreeVerticalDistance;
    final int animBlocksPerTick;
    final long animTickDelay;
    final int maxFallingBlocks;
    final long animationTimeoutTicks;
    final boolean adaptiveAnimation;
    final int busyAnimationThreshold;
    final boolean directionalFall;
    final boolean randomFallDirection;
    final double horizontalVelocity;
    final double upwardVelocity;
    final double randomSpread;
    final boolean particles;
    final int particleInterval;
    final boolean sounds;
    final int soundInterval;
    final int fallDurationTicks;
    final double fallAngleDegrees;
    final double fallDistance;
    final boolean useFortune;
    final boolean useSilkTouch;
    final boolean leavesEnabled;
    final int leavesAmount;
    final boolean sticksEnabled;
    final int sticksMin;
    final int sticksMax;
    final boolean saplingsEnabled;
    final int saplingsMin;
    final int saplingsMax;
    final boolean fruitsEnabled;
    final int fruitsMin;
    final int fruitsMax;
    final double stickChance;
    final double saplingChance;
    final String noPermissionMessage;
    final String reloadMessage;
    final String usageMessage;
    final String worldGuardErrorMessage;

    Settings(JavaPlugin plugin) {
        plugin.reloadConfig();
        var c = plugin.getConfig();
        enabled = c.getBoolean("enabled", true);
        requirePermission = c.getBoolean("authorization.require-permission", c.getBoolean("require-permission", true));
        sneakToDisable = c.getBoolean("sneak-to-disable", false);
        damageTool = c.getBoolean("damage-tool", true);
        requireAxeForBig = c.getBoolean("require-axe-for-big", false);
        minTrunkHeight = clampInt(c.getInt("min-trunk-height", 1), 1, 128);
        maxBlocks = clampInt(c.getInt("max-blocks", 512), 64, 5000);
        cooldownMs = Math.max(0L, c.getLong("cooldown-ms", 500L));
        bypassPermission = c.getString("authorization.bypass-permission", "treefall.bypass");
        worldBlacklist = loadWorldBlacklist(c.getStringList("world-blacklist"));
        ignorePersistentLeaves = c.getBoolean("tree-detection.ignore-persistent-leaves", true);
        maxTreeHorizontalDistance = clampInt(c.getInt("tree-detection.max-horizontal-distance", 12), 4, 32);
        maxTreeVerticalDistance = clampInt(c.getInt("tree-detection.max-vertical-distance", 48), 8, 128);
        animBlocksPerTick = clampInt(c.getInt("animation.blocks-per-tick", 1000), 1, 1000);
        animTickDelay = Math.max(1L, c.getLong("animation.tick-delay", 1L));
        maxFallingBlocks = clampInt(c.getInt("animation.max-falling-blocks", 5000), 10, 5000);
        animationTimeoutTicks = clampLong(c.getLong("animation.timeout-ticks", 140L), 20L, 600L);
        adaptiveAnimation = c.getBoolean("animation.adaptive", false);
        busyAnimationThreshold = clampInt(c.getInt("animation.busy-threshold", 8), 1, 100);
        directionalFall = c.getBoolean("animation.directional-fall", true);
        randomFallDirection = c.getBoolean("animation.random-direction", true);
        horizontalVelocity = clampDouble(c.getDouble("animation.horizontal-velocity", 0.12), 0.0, 1.0);
        upwardVelocity = clampDouble(c.getDouble("animation.upward-velocity", 0.02), 0.0, 0.5);
        randomSpread = clampDouble(c.getDouble("animation.random-spread", 0.025), 0.0, 0.25);
        particles = c.getBoolean("animation.particles", true);
        particleInterval = clampInt(c.getInt("animation.particle-interval", 2), 1, 20);
        sounds = c.getBoolean("animation.sounds", true);
        soundInterval = clampInt(c.getInt("animation.sound-interval", 8), 1, 40);
        fallDurationTicks = clampInt(c.getInt("animation.fall-duration-ticks", 24), 8, 100);
        fallAngleDegrees = clampDouble(c.getDouble("animation.fall-angle-degrees", 88.0), 45.0, 90.0);
        fallDistance = clampDouble(c.getDouble("animation.fall-distance", 0.6), 0.0, 3.0);
        useFortune = c.getBoolean("drop.use-fortune", true);
        useSilkTouch = c.getBoolean("drop.use-silk-touch", true);
        leavesEnabled = c.getBoolean("drop.leaves.enabled", true);
        leavesAmount = clampInt(c.getInt("drop.leaves.amount", 10), 0, 64);
        sticksEnabled = c.getBoolean("drop.sticks.enabled", true);
        sticksMin = clampInt(c.getInt("drop.sticks.min", 4), 0, 64);
        sticksMax = clampInt(c.getInt("drop.sticks.max", 10), sticksMin, 64);
        saplingsEnabled = c.getBoolean("drop.saplings.enabled", true);
        saplingsMin = clampInt(c.getInt("drop.saplings.min", 1), 0, 64);
        saplingsMax = clampInt(c.getInt("drop.saplings.max", 3), saplingsMin, 64);
        fruitsEnabled = c.getBoolean("drop.fruits.enabled", true);
        fruitsMin = clampInt(c.getInt("drop.fruits.min", 1), 0, 64);
        fruitsMax = clampInt(c.getInt("drop.fruits.max", 3), fruitsMin, 64);
        stickChance = clamp01(c.getDouble("drop.chance.stick", 0.02));
        saplingChance = clamp01(c.getDouble("drop.chance.sapling", 0.05));
        noPermissionMessage = c.getString("messages.no-permission", "§cУ вас нет прав.");
        reloadMessage = c.getString("messages.reload", "§aКонфигурация TreeFall перезагружена.");
        usageMessage = c.getString("messages.usage", "§eИспользование: /treefall reload");
        worldGuardErrorMessage = c.getString("messages.worldguard-error", "§cНе удалось проверить защиту WorldGuard. TreeFall отменён.");
    }
    boolean isWorldBlacklisted(String worldName) { return worldBlacklist.contains(worldName.toLowerCase(Locale.ROOT)); }
    private static Set<String> loadWorldBlacklist(List<String> worlds) { if (worlds == null || worlds.isEmpty()) return Collections.emptySet(); Set<String> result = new HashSet<>(); for (String world : worlds) if (world != null && !world.isBlank()) result.add(world.trim().toLowerCase(Locale.ROOT)); return Collections.unmodifiableSet(result); }
    private static int clampInt(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }
    private static long clampLong(long value, long min, long max) { return Math.max(min, Math.min(max, value)); }
    private static double clampDouble(double value, double min, double max) { return Math.max(min, Math.min(max, value)); }
    private static double clamp01(double value) { return clampDouble(value, 0.0, 1.0); }
}
