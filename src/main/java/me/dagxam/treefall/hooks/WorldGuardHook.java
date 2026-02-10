package me.dagxam.treefall.hooks;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

public final class WorldGuardHook {

    private WorldGuardHook() {}

    /**
     * Проверяет через reflection, разрешает ли WorldGuard данному игроку
     * ломать блок в данной позиции.
     
