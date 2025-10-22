package me.dagxam.treefall;

import org.bukkit.plugin.java.JavaPlugin;

public class TreeFallPlugin extends JavaPlugin {

    private static TreeFallPlugin instance;
    public static TreeFallPlugin getInstance() { return instance; }

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig(); // создаст config.yml при первом запуске
        getServer().getPluginManager().registerEvents(new TreeBreakListener(), this);
        getLogger().info("TreeFall запущен ✅");
    }

    @Override
    public void onDisable() {
        getLogger().info("TreeFall выключен.");
    }
}
