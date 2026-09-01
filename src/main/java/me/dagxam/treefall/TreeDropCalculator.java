package me.dagxam.treefall;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;

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
                                       Settings settings,
                                       ItemStack tool) {
        Random random = plugin.random;
        int leafCount = falling.leaves().size();
        boolean bigPart = leafCount >= Settings.BIG_TREE_LEAVES;

        Material leafSample = TreeDetector.getAnyLeafMaterial(falling);
        Material saplingType = TreeDetector.getSaplingForLeaf(leafSample);
        boolean appleTree = leafSample == Material.OAK_LEAVES || leafSample == Material.DARK_OAK_LEAVES;

        int fortune = settings.useFortune && tool != null
                ? tool.getEnchantmentLevel(Enchantment.FORTUNE) : 0;
        boolean silkTouch = settings.useSilkTouch && tool != null
                && tool.containsEnchantment(Enchantment.SILK_TOUCH);

        Map<Material, Integer> leafDrops = new HashMap<>();
        if (silkTouch) {
            for (Block block : falling.leaves()) {
                leafDrops.merge(block.getType(), 1, Integer::sum);
            }
        } else {
            int leafDropTarget = computeLeafDropTarget(leafCount);
            boolean winter = season != null && season.equals("WINTER");
            if (season != null && (season.equals("AUTUMN") || season.equals("FALL"))) {
                leafDropTarget = Math.min(leafDropTarget + 6, 26);
            }
            if (winter) leafDropTarget = Math.max(0, leafDropTarget - 8);

            List<Block> leafList = new ArrayList<>(falling.leaves());
            Collections.shuffle(leafList, random);
            int take = Math.min(leafDropTarget, leafList.size());
            for (int i = 0; i < take; i++) {
                leafDrops.merge(leafList.get(i).getType(), 1, Integer::sum);
            }
        }

        Map<Material, Integer> logDrops = new HashMap<>();
        for (Block block : falling.logs()) logDrops.merge(block.getType(), 1, Integer::sum);

        int sticks = 0;
        int saplings = 0;
        int apples = 0;
        if (!silkTouch) {
            double fortuneMultiplier = 1.0 + (fortune * 0.5);
            sticks = calculateAggregatedAmount(random, leafCount,
                    settings.stickChance * fortuneMultiplier, 1, 3 + Math.min(3, fortune));
            if (saplingType != null) {
                saplings = calculateAggregatedAmount(random, leafCount,
                        settings.saplingChance * fortuneMultiplier, 1, 3 + Math.min(3, fortune));
                if (season != null && season.equals("SPRING") && saplings > 0) saplings++;
                saplings = Math.min(6, saplings);
            }
            if (appleTree && season != null && !season.equals("WINTER")) {
                apples = bigPart ? 5 : 3;
                if (season.equals("SUMMER")) apples = Math.min(6, apples + 1);
                apples = Math.min(8, (int) Math.ceil(apples * fortuneMultiplier));
            } else if (appleTree && season == null) {
                apples = bigPart ? 5 : 3;
                apples = Math.min(8, (int) Math.ceil(apples * fortuneMultiplier));
            }
        }

        return new DropResult(leafDrops, logDrops, sticks, saplingType, saplings, apples);
    }

    private static int computeLeafDropTarget(int leafCount) {
        if (leafCount <= 0) return 0;
        int min = 10;
        int max = 20;
        int low = 40;
        int high = 160;
        if (leafCount <= low) return Math.min(min, leafCount);
        if (leafCount >= high) return Math.min(max, leafCount);
        double t = (leafCount - low) / (double) (high - low);
        int target = (int) Math.round(min + t * (max - min));
        return Math.min(max, Math.max(min, target));
    }

    private static int calculateAggregatedAmount(Random random, int leafCount,
                                                 double chancePerLeaf, int min, int max) {
        if (leafCount <= 0 || chancePerLeaf <= 0) return 0;
        double expected = leafCount * chancePerLeaf;
        int base = (int) Math.floor(expected);
        if (random.nextDouble() < expected - base) base++;
        return Math.max(min, Math.min(max, base));
    }
}
