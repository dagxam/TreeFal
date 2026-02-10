package me.dagxam.treefall;

import org.bukkit.configuration.file.FileConfiguration;

public class Settings {

    // General
    boolean enabled = true;
    boolean requirePermission = true;
    boolean sneakToDisable = true;
    boolean damageTool = true;
    boolean requireAxeForBig = false;
    boolean dropXp = false;

    // Tree detection
    int minTrunkHeight = 4;
    int maxBlocks = 512;
    int maxHorizDist = 12;
    int maxVertDist = 48;

    // Animation
    int maxFallingBlocks = 80;
    int blocksPerTick = 18;
    long tickDelay = 1L;
    int fallingBlockLifetimeTicks = 100;

    // Drops
    double stickChance = 0.02;
    double saplingChance = 0.05;

    // Anti-spam
    long cooldownMs = 500;

    public void load(FileConfiguration config) {
        enabled = config.getBoolean("enabled", true);
        requirePermission = config.getBoolean("require-permission", true);
        sneakToDisable = config.getBoolean("sneak-to-disable", true);
        damageTool = config.getBoolean("damage-tool", true);
        requireAxeForBig = config.getBoolean("require-axe-for-big", false);
        dropXp = config.getBoolean("drop-xp", false);

        minTrunkHeight = Math.max(3, config.getInt("min-trunk-height", 4));
        maxBlocks = Math.max(64, config.getInt("max-blocks", 512));
        maxHorizDist = config.getInt("max-horizontal-distance", 12);
        maxVertDist = config.getInt("max-vertical-distance", 48);

        maxFallingBlocks = config.getInt("animation.max-falling-blocks", 80);
        blocksPerTick = Math.max(8, config.getInt("animation.blocks-per-tick", 18));
        tickDelay = Math.max(1L, config.getLong("animation.tick-delay", 1L));
        fallingBlockLifetimeTicks = config.getInt("animation.falling-block-lifetime-ticks", 100);

        stickChance = clamp01(config.getDouble("drop.chance.stick", 0.02));
        saplingChance = clamp01(config.getDouble("drop.chance.sapling", 0.05));

        cooldownMs = config.getLong("cooldown-ms", 500);
    }

    private static double clamp01(double v) {
        return Math.max(0, Math.min(1, v));
    }
}
