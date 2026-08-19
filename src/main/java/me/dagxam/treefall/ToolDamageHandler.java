package me.dagxam.treefall;

import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;

import java.util.Random;

public final class ToolDamageHandler {

    private ToolDamageHandler() {}

    /**
     * Applies durability damage only if the player still has the same tool in the original slot.
     * This prevents a delayed tree animation from damaging a different item after a hotbar swap.
     */
    public static void damageTool(Player player,
                                  int slot,
                                  ItemStack originalTool,
                                  int uses,
                                  Random random) {
        if (uses <= 0 || originalTool == null || originalTool.getType() == Material.AIR) return;
        if (slot < 0 || slot >= player.getInventory().getSize()) return;

        ItemStack current = player.getInventory().getItem(slot);
        if (current == null || current.getType() == Material.AIR) return;
        if (!current.isSimilar(originalTool)) return;
        if (!(current.getItemMeta() instanceof Damageable damageable)) return;

        int unbreaking = current.getEnchantmentLevel(Enchantment.UNBREAKING);
        int applied = 0;

        for (int i = 0; i < uses; i++) {
            if (random.nextInt(unbreaking + 1) == 0) {
                applied++;
            }
        }

        if (applied <= 0) return;

        int newDamage = damageable.getDamage() + applied;
        int maxDurability = current.getType().getMaxDurability();

        if (newDamage >= maxDurability) {
            player.getInventory().setItem(slot, new ItemStack(Material.AIR));
            player.playSound(player.getLocation(), Sound.ENTITY_ITEM_BREAK, 1.0f, 1.0f);
            return;
        }

        damageable.setDamage(newDamage);
        current.setItemMeta(damageable);
    }
}
