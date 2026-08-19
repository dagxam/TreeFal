package me.dagxam.treefall;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.util.logging.Logger;

public final class WorldGuardHook {

    private final Logger logger;
    private boolean warned;

    public WorldGuardHook(Logger logger) {
        this.logger = logger;
    }

    /**
     * Checks whether the player may build/break at the location through WorldGuard.
     * On an API failure TreeFall is denied for safety instead of bypassing protection.
     */
    public boolean canBreak(Player player, Block block) {
        try {
            Class<?> wgPluginClass = Class.forName("com.sk89q.worldguard.bukkit.WorldGuardPlugin");
            Object wgPlugin = wgPluginClass.getMethod("inst").invoke(null);
            Object localPlayer = wgPluginClass.getMethod("wrapPlayer", Player.class)
                    .invoke(wgPlugin, player);

            Class<?> wgClass = Class.forName("com.sk89q.worldguard.WorldGuard");
            Object wg = wgClass.getMethod("getInstance").invoke(null);
            Object platform = wg.getClass().getMethod("getPlatform").invoke(wg);
            Object container = platform.getClass().getMethod("getRegionContainer").invoke(platform);

            Class<?> adapter = Class.forName("com.sk89q.worldedit.bukkit.BukkitAdapter");
            Object weWorld = adapter.getMethod("adapt", World.class).invoke(null, block.getWorld());
            Object regionManager = container.getClass()
                    .getMethod("get", Class.forName("com.sk89q.worldedit.world.World"))
                    .invoke(container, weWorld);

            if (regionManager == null) return true;

            Object blockVector = adapter.getMethod("asBlockVector", Location.class)
                    .invoke(null, block.getLocation());

            Object regions = regionManager.getClass()
                    .getMethod("getApplicableRegions",
                            Class.forName("com.sk89q.worldedit.math.BlockVector3"))
                    .invoke(regionManager, blockVector);

            Class<?> flags = Class.forName("com.sk89q.worldguard.protection.flags.Flags");
            Object build = flags.getField("BUILD").get(null);

            return (boolean) regions.getClass()
                    .getMethod("testState",
                            Class.forName("com.sk89q.worldguard.LocalPlayer"),
                            Class.forName("com.sk89q.worldguard.protection.flags.StateFlag"))
                    .invoke(regions, localPlayer, build);
        } catch (Throwable throwable) {
            if (!warned) {
                warned = true;
                logger.warning("WorldGuard API check failed. TreeFall will fail closed for protection safety: "
                        + throwable.getClass().getSimpleName() + ": " + throwable.getMessage());
            }
            return false;
        }
    }
}
