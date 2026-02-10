package me.dagxam.treefall;

import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Leaves;

import java.util.*;

public class TreeDetector {

    /**
     * Содержит множества логов и листьев, принадлежащих одному дереву.
     */
    public record TreeBlocks(Set<Block> logs, Set<Block> leaves) {

        public boolean isEmpty() {
            return logs.isEmpty();
        }

        public int totalSize() {
            return logs.size() + leaves.size();
        }
    }

    // ============================
    //  Ствол: поиск низа и высоты
    // ============================

    /**
     * Спускаемся вниз по вертикальной колонне логов (с ограничением глубины).
     */
    public Block findTrunkBottom(Block start, int maxDepth) {
        Block c = start;
        int depth = 0;
        while (Tag.LOGS.isTagged(c.getRelative(0, -1, 0).getType()) && depth++ < maxDepth) {
            c = c.getRelative(0, -1, 0);
        }
        return c;
    }

    /**
     * Считаем высоту сплошной колонны логов от {@code bottom} вверх.
     */
    public int measureTrunkHeight(Block bottom) {
        int h = 0;
        Block c = bottom;
        while (Tag.LOGS.isTagged(c.getType())) {
            h++;
            c = c.getRelative(0, 1, 0);
        }
        return h;
    }

    // ============================
    //  Анти-дом: боковые логи
    // ============================

    /**
     * Проверяет, есть ли логи сбоку от основания ствола,
     * которые НЕ являются частью 2×2-ствола (dark oak, spruce, jungle).
     */
    public boolean hasSideLogsAtBase(Block base) {
        Set<Block> footprint = getTrunkFootprint(base);

        for (Block trunk : footprint) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    Block neighbor = trunk.getRelative(dx, 0, dz);
                    if (footprint.contains(neighbor)) continue;
                    if (Tag.LOGS.isTagged(neighbor.getType())) return true;
                }
            }
        }
        return false;
    }

    /**
     * Определяет «отпечаток» ствола: 1 блок для обычных деревьев,
     * до 4 блоков для 2×2 стволов (dark oak, spruce, jungle).
     */
    private Set<Block> getTrunkFootprint(Block base) {
        Set<Block> footprint = new HashSet<>();
        footprint.add(base);

        Material m = base.getType();
        if (m != Material.DARK_OAK_LOG && m != Material.SPRUCE_LOG && m != Material.JUNGLE_LOG) {
            return footprint;
        }

        // Пробуем все 4 варианта расположения base внутри 2×2 сетки
        int[][] corners = {{0, 0}, {-1, 0}, {0, -1}, {-1, -1}};
        for (int[] corner : corners) {
            boolean valid = true;
            Set<Block> candidate = new HashSet<>();
            for (int dx = 0; dx <= 1 && valid; dx++) {
                for (int dz = 0; dz <= 1 && valid; dz++) {
                    Block check = base.getRelative(corner[0] + dx, 0, corner[1] + dz);
                    if (check.getType() != m) {
                        valid = false;
                    } else {
                        candidate.add(check);
                    }
                }
            }
            if (valid) {
                footprint.addAll(candidate);
                break;
            }
        }
        return footprint;
    }

    // ============================
    //  Крона
    // ============================

    /**
     * Проверяет, есть ли натуральная крона над верхушкой ствола.
     */
    public boolean hasCanopyAbove(Block top) {
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = 1; dy <= 5; dy++) {
                for (int dz = -2; dz <= 2; dz++) {
                    if (isNaturalLeaf(top.getRelative(dx, dy, dz))) return true;
                }
            }
        }
        return false;
    }

    // ============================
    //  Листья: натуральные vs поставленные
    // ============================

    /**
     * Натуральный лист: НЕ persistent (т.е. не поставлен игроком).
     * Для моддед-листьев (без интерфейса Leaves) — проверяем по имени.
     */
    public boolean isNaturalLeaf(Block b) {
        BlockData data = b.getBlockData();
        if (data instanceof Leaves leaves) {
            return !leaves.isPersistent();
        }
        // Моддед-листья без стандартного BlockData
        String n = b.getType().name();
        return n.contains("LEAVES") || n.contains("FOLIAGE")
                || n.contains("NEEDLES") || n.contains("CANOPY");
    }

    // ============================
    //  BFS-сбор дерева
    // ============================

    /**
     * Собирает все блоки дерева (логи + натуральные листья) BFS-ом
     * с ограничением по расстоянию и общему количеству.
     */
    public TreeBlocks collectTree(Block start, int limit, int maxHorizDist, int maxVertDist) {
        Set<Block> logs = new HashSet<>();
        Set<Block> leaves = new HashSet<>();
        Set<Block> visited = new HashSet<>();
        ArrayDeque<Block> queue = new ArrayDeque<>();
        queue.add(start);

        final int startX = start.getX();
        final int startY = start.getY();
        final int startZ = start.getZ();

        while (!queue.isEmpty() && visited.size() < limit) {
            Block b = queue.poll();
            if (!visited.add(b)) continue;

            // Ограничение расстояния
            if (Math.abs(b.getX() - startX) > maxHorizDist
                    || Math.abs(b.getZ() - startZ) > maxHorizDist
                    || Math.abs(b.getY() - startY) > maxVertDist) {
                continue;
            }

            if (Tag.LOGS.isTagged(b.getType())) {
                logs.add(b);
            } else if (isNaturalLeaf(b)) {
                leaves.add(b);
            } else {
                continue;
            }

            // 26 соседей
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        Block next = b.getRelative(dx, dy, dz);
                        if (!visited.contains(next)) {
                            queue.add(next);
                        }
                    }
                }
            }
        }
        return new TreeBlocks(logs, leaves);
    }

    /**
     * Возвращает только блоки с Y >= cutY (то, что «падает»).
     */
    public TreeBlocks sliceAboveY(TreeBlocks tree, int cutY) {
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

    // ============================
    //  Утилиты для типов
    // ============================

    public Material getAnyLeafMaterial(TreeBlocks tree) {
        for (Block b : tree.leaves()) return b.getType();
        return null;
    }

    public Material getSaplingForLeaf(Material leaf) {
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
