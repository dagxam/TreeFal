// src/main/java/me/dagxam/treefall/Settings.java

package me.dagxam.treefall;

import org.bukkit.plugin.java.JavaPlugin;

public class Settings {

    static final int BIG_TREE_LEAVES = 160;

    final boolean enabled;
    final boolean requirePermission;
    final boolean sneakToDisable;
    final boolean damageTool;
    final boolean requireAxeForBig;
    final int minTrunkHeight;
    final int maxBlocks;
    final long cooldownMs;

    final int animBlocksPerTick;
    final long animTickDelay;
    final int maxFallingBlocks;

    final double stickChance;
    final double saplingChance;

    Settings(JavaPlugin plugin) {
        plugin.reloadConfig();
        var c = plugin.getConfig();

        enabled = c.getBoolean("enabled", true);
        requirePermission = c.getBoolean("require-permission", true);
        sneakToDisable = c.getBoolean("sneak-to-disable", true);
        damageTool = c.getBoolean("damage-tool", true);
        requireAxeForBig = c.getBoolean("require-axe-for-big", false);
        minTrunkHeight = Math.max(3, c.getInt("min-trunk-height", 4));
        maxBlocks = Math.max(64, c.getInt("max-blocks", 512));
        cooldownMs = c.getLong("cooldown-ms", 500);

        animBlocksPerTick = Math.max(8, c.getInt("animation.blocks-per-tick", 18));
        animTickDelay = Math.max(1, c.getLong("animation.tick-delay", 1));
        maxFallingBlocks = Math.max(10, c.getInt("animation.max-falling-blocks", 80));

        stickChance = clamp01(c.getDouble("drop.chance.stick", 0.02));
        saplingChance = clamp01(c.getDouble("drop.chance.sapling", 0.05));
    }

    private static double clamp01(double v) {
        return Math.max(0, Math.min(1, v));
    }
}
