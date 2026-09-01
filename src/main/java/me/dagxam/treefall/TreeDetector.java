package me.dagxam.treefall;

import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Leaves;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

public final class TreeDetector {

    private TreeDetector() {}

    /**
     * Finds the real base of a tree instead of only walking straight down from
     * the block that was hit. This is important for branches and for trees
     * grown from saplings whose lower canopy/branches can make the hit block
     * no longer be part of a simple vertical column.
     */
    public static Block findTrunkBottom(Block start) {
        Block direct = walkDownLogs(start, 128);
        Block best = direct;
        int bestHeight = measureVerticalSupport(direct);

        // A hit may be on a branch. Search a small horizontal area while
        // walking down and prefer the lowest candidate with a real trunk.
        for (int dy = 0; dy <= 8; dy++) {
            Block level = start.getRelative(0, -dy, 0);
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    Block candidate = level.getRelative(dx, 0, dz);
                    if (!Tag.LOGS.isTagged(candidate.getType())) continue;
                    if (!hasGroundOrTrunkSupport(candidate)) continue;

                    int height = measureVerticalSupport(candidate);
                    if (height >= bestHeight && candidate.getY() <= best.getY()) {
                        best = candidate;
                        bestHeight = height;
                    }
                }
            }
        }

        // Explicitly handle a 2x2 trunk: return its lowest corner so all four
        // logs are included when collection starts.
        if (is2x2Trunk(best)) {
            Material material = best.getType();
            Block corner = best;
            for (int dx = -1; dx <= 0; dx++) {
                for (int dz = -1; dz <= 0; dz++) {
                    Block candidate = best.getRelative(dx, 0, dz);
                    if (isExact2x2At(candidate, material)
                            && candidate.getY() <= corner.getY()) {
                        corner = candidate;
                    }
                }
            }
            return corner;
        }
        return best;
    }

    private static Block walkDownLogs(Block start, int maxDepth) {
        Block current = start;
        for (int depth = 0; depth < maxDepth; depth++) {
            Block below = current.getRelative(0, -1, 0);
            if (!Tag.LOGS.isTagged(below.getType())) break;
            current = below;
        }
        return current;
    }

    private static int measureVerticalSupport(Block block) {
        if (!Tag.LOGS.isTagged(block.getType())) return 0;
        int height = 0;
        Block current = block;
        while (height < 128 && Tag.LOGS.isTagged(current.getType())) {
            height++;
            current = current.getRelative(0, 1, 0);
        }
        return height;
    }

    private static boolean hasGroundOrTrunkSupport(Block block) {
        if (Tag.LOGS.isTagged(block.getRelative(0, -1, 0).getType())) return true;
        Material below = block.getRelative(0, -1, 0).getType();
        return below.isSolid() && !Tag.LOGS.isTagged(below) && !Tag.LEAVES.isTagged(below);
    }

    public static int measureTrunkHeight(Block bottom) {
        return measureVerticalSupport(bottom);
    }

    public static boolean hasSideLogsAtBase(Block base) {
        if (is2x2Trunk(base)) return false;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                if (Tag.LOGS.isTagged(base.getRelative(dx, 0, dz).getType())) return true;
            }
        }
        return false;
    }

    public static boolean is2x2Trunk(Block base) {
        Material material = base.getType();
        if (!Tag.LOGS.isTagged(material)) return false;
        for (int ox = -1; ox <= 0; ox++) {
            for (int oz = -1; oz <= 0; oz++) {
                if (isExact2x2At(base.getRelative(ox, 0, oz), material)) return true;
            }
        }
        return false;
    }

    public static String getTreeKey(Block base) {
        int x = base.getX();
        int z = base.getZ();
        if (is2x2Trunk(base)) {
            Material material = base.getType();
            for (int dx = -1; dx <= 0; dx++) {
                for (int dz = -1; dz <= 0; dz++) {
                    Block candidate = base.getRelative(dx, 0, dz);
                    if (isExact2x2At(candidate, material)) {
                        x = Math.min(x, candidate.getX());
                        z = Math.min(z, candidate.getZ());
                    }
                }
            }
        }
        return base.getWorld().getUID() + ":" + x + ":" + base.getY() + ":" + z;
    }

    private static boolean isExact2x2At(Block corner, Material material) {
        for (int dx = 0; dx <= 1; dx++) {
            for (int dz = 0; dz <= 1; dz++) {
                if (corner.getRelative(dx, 0, dz).getType() != material) return false;
            }
        }
        return true;
    }

    public static boolean hasCanopyAbove(Block top, Settings settings) {
        int radius = 3;
        int height = 6;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = 1; dy <= height; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (isLeafLike(top.getRelative(dx, dy, dz), settings)) return true;
                }
            }
        }
        return false;
    }

    public static TreeBlocks collectTree(Block start, int limit, Settings settings) {
        Set<Block> logs = new HashSet<>();
        Set<Block> leaves = new HashSet<>();
        Set<Block> visited = new HashSet<>();
        ArrayDeque<Block> queue = new ArrayDeque<>();
        queue.add(start);

        if (is2x2Trunk(start)) {
            Material material = start.getType();
            for (int dx = -1; dx <= 0; dx++) {
                for (int dz = -1; dz <= 0; dz++) {
                    Block candidate = start.getRelative(dx, 0, dz);
                    if (isExact2x2At(candidate, material)) {
                        for (int ox = 0; ox <= 1; ox++) {
                            for (int oz = 0; oz <= 1; oz++) queue.add(candidate.getRelative(ox, 0, oz));
                        }
                    }
                }
            }
        }

        int maxHorizDist = settings.maxTreeHorizontalDistance;
        int maxVertDist = settings.maxTreeVerticalDistance;
        int startX = start.getX();
        int startY = start.getY();
        int startZ = start.getZ();

        while (!queue.isEmpty() && visited.size() < limit) {
            Block block = queue.poll();
            if (!visited.add(block)) continue;
            if (Math.abs(block.getX() - startX) > maxHorizDist
                    || Math.abs(block.getZ() - startZ) > maxHorizDist
                    || Math.abs(block.getY() - startY) > maxVertDist) continue;

            boolean log = Tag.LOGS.isTagged(block.getType());
            boolean leaf = isLeafLike(block, settings);
            if (log) logs.add(block);
            else if (leaf) leaves.add(block);
            else continue;

            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        if (log && Math.abs(dx) + Math.abs(dy) + Math.abs(dz) != 1) continue;
                        Block next = block.getRelative(dx, dy, dz);
                        if (!visited.contains(next)) queue.add(next);
                    }
                }
            }
        }
        return new TreeBlocks(logs, leaves, !queue.isEmpty());
    }

    static boolean isLeafLike(Block block, Settings settings) {
        BlockData data = block.getBlockData();
        if (data instanceof Leaves leaves) return !settings.ignorePersistentLeaves || !leaves.isPersistent();
        return Tag.LEAVES.isTagged(block.getType());
    }

    public static Material getAnyLeafMaterial(TreeBlocks tree) {
        for (Block block : tree.leaves()) return block.getType();
        return null;
    }

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
