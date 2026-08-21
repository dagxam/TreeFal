package me.dagxam.treefall;

import org.bukkit.Material;
import org.bukkit.block.Block;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

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

    public static DropResult calculate(TreeFallPlugin plugin,
                                       TreeBlocks falling,
                                       String season,
                                       Settings settings) {
        Random random = plugin.random;
        int leafCount = falling.leaves().size();
        boolean bigPart = leafCount >= Settings.BIG_TREE_LEAVES;

        Material leafSample = TreeDetector.getAnyLeafMaterial(falling);
        Material saplingType = TreeDetector.getSaplingForLeaf(leafSample);
        boolean appleTree = leafSample == Material.OAK_LEAVES || leafSample == Material.DARK_OAK_LEAVES;

        int leafDropTarget = computeLeafDropTarget(leafCount);
        boolean winter = season != null && season.equals("WINTER");

        if (season != null && (season.equals("AUTUMN") || season.equals("FALL"))) {
            leafDropTarget = Math.min(leafDropTarget + 6, 32);
        }
        if (winter) {
            leafDropTarget = Math.max(0, leafDropTarget - 8);
        }

        Map<Material, Integer> leafDrops = new HashMap<>();
        List<Block> leafList = new ArrayList<>(falling.leaves());
        Collections.shuffle(leafList, random);
        int take = Math.min(leafDropTarget, leafList.size());
        for (int i = 0; i < take; i++) {
            leafDrops.merge(leafList.get(i).getType(), 1, Integer::sum);
        }

        Map<Material, Integer> logDrops = new HashMap<>();
        for (Block block : falling.logs()) {
            logDrops.merge(block.getType(), 1, Integer::sum);
        }

        // Bigger canopies naturally produce more sticks. This is an aggregate chance,
        // so a small tree gives roughly 1-2 sticks while a large tree can give several.
        int sticks = calculateSizeBasedAmount(random, leafCount, settings.stickChance, 1, 8, 30);

        int saplings = 0;
        if (saplingType != null) {
            saplings = calculateSizeBasedAmount(random, leafCount, settings.saplingChance, 1, 4, 45);
            if (season != null && season.equals("SPRING") && saplings > 0) {
                saplings = Math.min(4, saplings + 1);
            }
        }

        // Fruit is only generated for trees that have fruit in vanilla Minecraft.
        // The amount scales with the amount of leaves rather than being a fixed number.
        int apples = 0;
        if (appleTree && !winter) {
            apples = calculateSizeBasedAmount(random, leafCount, 0.015, 1, 8, 35);
            if (season != null && season.equals("SUMMER") && apples > 0) {
                apples = Math.min(8, apples + 1);
            }
        }

        return new DropResult(leafDrops, logDrops, sticks, saplingType, saplings, apples);
    }

    private static int computeLeafDropTarget(int leafCount) {
        if (leafCount <= 0) return 0;

        int min = 10;
        int max = 24;
        int low = 40;
        int high = 220;

        if (leafCount <= low) return Math.min(min, leafCount);
        if (leafCount >= high) return Math.min(max, leafCount);

        double t = (leafCount - low) / (double) (high - low);
        int target = (int) Math.round(min + t * (max - min));
        target = Math.max(min, Math.min(max, target));
        return Math.min(target, leafCount);
    }

    private static int calculateSizeBasedAmount(Random random,
                                                int leafCount,
                                                double chancePerLeaf,
                                                int min,
                                                int max,
                                                int minimumLeavesForDrop) {
        if (leafCount < minimumLeavesForDrop || chancePerLeaf <= 0) return 0;

        double expected = leafCount * chancePerLeaf;
        int base = (int) Math.floor(expected);
        if (random.nextDouble() < expected - base) base++;

        // A real tree with enough leaves should normally produce at least one item,
        // while the upper bound prevents giant trees from flooding the server.
        base = Math.max(min, base);
        return Math.min(max, base);
    }
}
