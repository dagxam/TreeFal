package me.dagxam.treefall;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;

import java.util.Collections;
import java.util.HashMap;
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
            Material fruitType,
            int fruits
    ) {}

    public static DropResult calculate(TreeFallPlugin plugin,
                                       TreeBlocks falling,
                                       String season,
                                       Settings settings,
                                       ItemStack tool) {
        Material leafSample = TreeDetector.getAnyLeafMaterial(falling);
        Material saplingType = TreeDetector.getSaplingForLeaf(leafSample);
        Material fruitType = getFruitForLeaf(leafSample);
        boolean silkTouch = settings.useSilkTouch && tool != null
                && tool.containsEnchantment(Enchantment.SILK_TOUCH);
        int fortune = settings.useFortune && tool != null
                ? tool.getEnchantmentLevel(Enchantment.FORTUNE) : 0;

        Map<Material, Integer> logs = new HashMap<>();
        for (Block block : falling.logs()) logs.merge(block.getType(), 1, Integer::sum);

        Map<Material, Integer> leaves = new HashMap<>();
        if (silkTouch) {
            for (Block block : falling.leaves()) leaves.merge(block.getType(), 1, Integer::sum);
        } else if (settings.leavesEnabled && settings.leavesAmount > 0 && leafSample != null) {
            int amount = applyFortune(settings.leavesAmount, fortune);
            leaves.put(leafSample, Math.min(64, amount));
        }

        int sticks = settings.sticksEnabled ? settings.sticksAmount : 0;
        int saplings = settings.saplingsEnabled && saplingType != null ? settings.saplingsAmount : 0;
        int fruits = settings.fruitsEnabled && fruitType != null ? settings.fruitsAmount : 0;

        // Silk Touch gives the actual leaves and suppresses bonus drops.
        if (silkTouch) {
            sticks = 0;
            saplings = 0;
            fruits = 0;
        } else if (fortune > 0) {
            sticks = applyFortune(sticks, fortune);
            saplings = applyFortune(saplings, fortune);
            fruits = applyFortune(fruits, fortune);
        }

        return new DropResult(leaves, logs, sticks, saplingType, saplings, fruitType, fruits);
    }

    private static int applyFortune(int amount, int fortune) {
        if (amount <= 0) return 0;
        return Math.min(64, amount + fortune);
    }

    private static Material getFruitForLeaf(Material leaf) {
        if (leaf == Material.OAK_LEAVES || leaf == Material.DARK_OAK_LEAVES) return Material.APPLE;
        return null;
    }
}
