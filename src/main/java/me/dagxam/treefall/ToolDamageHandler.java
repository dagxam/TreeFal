// src/main/java/me/dagxam/treefall/ToolDamageHandler.java

package me.dagxam.treefall;

import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;

import java.util.Random;

public final class ToolDamageHandler {

    private ToolDamageHandler() {}

    /**
     * Наносит урон инструменту с правильной логикой Unbreaking.
     * Формула ванили: шанс получить урон = 1 / (unbreaking + 1).
     */
    public static void damageTool(Player p, int uses, Random random) {
        ItemStack tool = p.getInventory().getItemInMainHand();
        if (tool == null || tool.getType() == Material.AIR) return;
        if (!(tool.getItemMeta() instanceof Damageable dmg)) return;

        int unbreaking = tool.getEnchantmentLevel(Enchantment.UNBREAKING);
        int applied = 0;

        for (int i = 0; i < uses; i++) {
            // ★ ПРАВИЛЬНО: с Unbreaking N шанс урона = 1/(N+1)
            // random.nextInt(N+1) == 0 → урон наносится
            if (random.nextInt(unbreaking + 1) == 0) {
                applied++;
            }
        }

        if (applied <= 0) return;

        dmg.setDamage(dmg.getDamage() + applied);
        tool.setItemMeta(dmg);

        if (dmg.getDamage() >= tool.getType().getMaxDurability()) {
            p.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
            p.playSound(p.getLocation(), org.bukkit.Sound.ENTITY_ITEM_BREAK, 1.0f, 1.0f);
        }
    }
}
