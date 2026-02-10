// src/main/java/me/dagxam/treefall/TreeDetector.java

package me.dagxam.treefall;

import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Leaves;

import java.util.*;

public final class TreeDetector {

    private TreeDetector() {}

    /**
     * Ищем нижний блок ствола (с ограничением глубины).
     */
    public static Block findTrunkBottom(Block start) {
        Block c = start;
        int maxDepth = 64;
        int depth = 0;
        while (Tag.LOGS.isTagged(c.getRelative(0, -1, 0).getType()) && depth++ < maxDepth) {
            c = c.getRelative(0, -1, 0);
        }
        return c;
    }

    /**
     * Высота ствола вверх от нижнего блока.
     */
    public static int measureTrunkHeight(Block bottom) {
        int h = 0;
        Block c = bottom;
        while (Tag.LOGS.isTagged(c.getType())) {
            h++;
            c = c.getRelative(0, 1, 0);
        }
        return h;
    }

    /**
     * Проверяет, есть ли боковые логи у основания ствола.
     * Учитывает 2×2 стволы (dark oak, spruce, jungle).
     */
    public static boolean hasSideLogsAtBase(Block base) {
        Material baseMat = base.getType();

        // Для деревьев которые бывают 2×2 — проверяем паттерн
        if (baseMat == Material.DARK_OAK_LOG
                || baseMat == Material.SPRUCE_LOG
                || baseMat == Material.JUNGLE_LOG) {
            if (is2x2Trunk(base)) return false; // это нормальный 2×2 ствол
        }

        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                if (Tag.LOGS.isTagged(base.getRelative(dx, 0, dz).getType())) return true;
            }
        }
        return false;
    }

    /**
     * Проверяет, является ли блок частью 2×2 ствола.
     * Проверяем все 4 возможные позиции «левого-нижнего угла».
     */
    private static boolean is2x2Trunk(Block base) {
        Material m = base.getType();
        int[][] corners = {{0, 0}, {-1, 0}, {0, -1}, {-1, -1}};
        for (int[] c : corners) {
            boolean match = true;
            for (int dx = 0; dx <= 1 && match; dx++) {
                for (int dz = 0; dz <= 1 && match; dz++) {
                    if (base.getRelative(c[0] + dx, 0, c[1] + dz).getType() != m) {
                        match = false;
                    }
                }
            }
            if (match) return true;
        }
        return false;
    }

    /**
     * Есть ли крона (листья) выше верхушки.
     */
    public static boolean hasCanopyAbove(Block top) {
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = 1; dy <= 5; dy++) {
                for (int dz = -2; dz <= 2; dz++) {
                    if (isLeafLike(top.getRelative(dx, dy, dz))) return true;
                }
            }
        }
        return false;
    }

    /**
     * BFS-сбор дерева с ограничением расстояния.
     */
    public static TreeBlocks collectTree(Block start, int limit) {
        Set<Block> logs = new HashSet<>();
        Set<Block> leaves = new HashSet<>();
        Set<Block> visited = new HashSet<>();
        ArrayDeque<Block> q = new ArrayDeque<>();
        q.add(start);

        final int maxHorizDist = 12;
        final int maxVertDist = 48;
        int startX = start.getX(), startZ = start.getZ(), startY = start.getY();

        while (!q.isEmpty() && visited.size() < limit) {
            Block b = q.poll();
            if (!visited.add(b)) continue;

            // Ограничение расстояния
            if (Math.abs(b.getX() - startX) > maxHorizDist
                    || Math.abs(b.getZ() - startZ) > maxHorizDist
                    || Math.abs(b.getY() - startY) > maxVertDist) continue;

            if (Tag.LOGS.isTagged(b.getType())) {
                logs.add(b);
            } else if (isLeafLike(b)) {
                leaves.add(b);
            } else {
                continue;
            }

            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        Block next = b.getRelative(dx, dy, dz);
                        if (!visited.contains(next)) {
                            q.add(next);
                        }
                    }
                }
            }
        }
        return new TreeBlocks(logs, leaves);
    }

    /**
     * Проверяет что блок — «листья» (ванильные или моддед).
     * Игроком поставленные (persistent=true) — пропускаются.
     */
    static boolean isLeafLike(Block b) {
        Material t = b.getType();
        BlockData data = b.getBlockData();

        if (data instanceof Leaves leafData) {
            // persistent=true означает поставлено игроком → не часть дерева
            return !leafData.isPersistent();
        }

        if (Tag.LEAVES.isTagged(t)) return true;

        String n = t.name();
        return n.contains("LEAVES") || n.contains("FOLIAGE")
                || n.contains("NEEDLES") || n.contains("CANOPY");
    }

    /**
     * Возвращает любой лист из дерева (для определения типа саженца).
     */
    public static Material getAnyLeafMaterial(TreeBlocks tree) {
        for (Block b : tree.leaves()) return b.getType();
        return null;
    }

    /**
     * Определяет саженец по листу.
     */
    public static Material getSaplingForLeaf(Material leaf) {
        if (leaf == null) return null;
        return switch (leaf) {
            case OAK_LEAVES -> Material.OAK_SAPLING;
            case BIRCH_LEAVES -> Material.BIRCH_SAPLING;
            case SPRUCE_LEAVES -> Material.SPRUCE_SAPLING;
            case JUNGLE_LEAVES -> Material.JUNGLE_SAPLING;
            case ACACIA_LEAVES -> Material.ACACIA_SAPLING;
            case DARK_OAK_LEAVES -> Material.DARK_OAK_SAPLING;
            case MANGROVE_LEAVES -> Material.MANGROVE_PROPAGULE;
            case CHERRY_LEAVES -> Material.CHERRY_SAPLING;
            default -> null;
        };
    }
}
