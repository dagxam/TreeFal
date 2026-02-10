// src/main/java/me/dagxam/treefall/TreeDropCalculator.java

package me.dagxam.treefall;

import org.bukkit.Material;
import org.bukkit.block.Block;

import java.util.*;

public final class TreeDropCalculator {

    private TreeDropCalculator() {}

    public record DropResult(
            Map<Material, Integer> leafDrops,
            Map<Material, Integer> logDrops,
            int sticks,
            Material saplingType,
            int saplings,
            int apples
    ) {}

    public static DropResult calculate(TreeFallPlugin plugin, TreeBlocks falling,
                                       String season, Settings settings) {
        Random random = plugin.random;
        int leafCount = falling.leaves().size();
        boolean bigPart = leafCount >= Settings.BIG_TREE_LEAVES;

        Material leafSample = TreeDetector.getAnyLeafMaterial(falling);
        Material saplingType = TreeDetector.getSaplingForLeaf(leafSample);
        boolean appleTree = (leafSample == Material.OAK_LEAVES || leafSample == Material.DARK_OAK_LEAVES);

        int leafDropTarget = computeLeafDropTarget(leafCount);

        boolean winter = season != null && season.equals("WINTER");

        if (season != null && (season.equals("AUTUMN") || season.equals("FALL"))) {
            leafDropTarget = Math.min(leafDropTarget + 6, 26);
        }
        if (winter) {
            leafDropTarget = Math.max(0, leafDropTarget - 8);
        }

        // Дроп листьев
        Map<Material, Integer> leafDrops = new HashMap<>();
        {
            List<Block> leafList = new ArrayList<>(falling.leaves());
            Collections.shuffle(leafList, random);
            int take = Math.min(leafDropTarget, leafList.size());
            for (int i = 0; i < take; i++) {
                leafDrops.merge(leafList.get(i).getType(), 1, Integer::sum);
            }
        }

        // Дроп логов 1:1
        Map<Material, Integer> logDrops = new HashMap<>();
        for (Block b : falling.logs()) {
            logDrops.merge(b.getType(), 1, Integer::sum);
        }

        // Палки
        int sticks = calculateAggregatedAmount(random, leafCount, settings.stickChance, 1, 3);

        // Саженцы
        int saplings = 0;
        if (saplingType != null) {
            saplings = calculateAggregatedAmount(random, leafCount, settings.saplingChance, 1, 3);
            if (season != null && season.equals("SPRING")) saplings = Math.min(3, saplings + 1);
        }

        // Яблоки
        int apples = 0;
        if (appleTree && !winter) {
            apples = bigPart ? 5 : 3;
            if (season != null && season.equals("SUMMER")) apples = Math.min(6, apples + 1);
        }

        return new DropResult(leafDrops, logDrops, sticks, saplingType, saplings, apples);
    }

    private static int computeLeafDropTarget(int leafCount) {
        if (leafCount <= 0) return 0;
        int min = 10, max = 20;
        int low = 40, high = 160;
        if (leafCount <= low) return Math.min(min, leafCount);
        if (leafCount >= high) return Math.min(max, leafCount);
        double t = (leafCount - low) / (double) (high - low);
        int target = (int) Math.round(min + t * (max - min));
        target = Math.max(min, Math.min(max, target));
        return Math.min(target, leafCount);
    }

    private static int calculateAggregatedAmount(Random random, int leafCount,
                                                 double chancePerLeaf, int min, int max) {
        if (leafCount <= 0 || chancePerLeaf <= 0) return min;
        double expected = leafCount * chancePerLeaf;
        int base = (int) Math.floor(expected);
        if (random.nextDouble() < (expected - base)) base++;
        return Math.max(min, Math.min(max, base));
    }
}
