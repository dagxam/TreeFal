package me.dagxam.treefall;

import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;

import java.util.Random;

public final class ToolDamageHandler {

    private static final Random random = new Random();

    private ToolDamageHandler() {}

    /**
     * Наносит {@code uses} единиц износа инструменту в руке игрока.
     * <p>
     * Учитывает Unbreaking по ванильной формуле:
     * шанс потери прочности = 1 / (level + 1).
     */
    public static void damageTool(Player player, int uses) {
        ItemStack tool = player.getInventory().getItemInMainHand();
        if (tool == null || tool.getType() == Material.AIR) return;

        // Не все предметы имеют прочность
        if (tool.getType().getMaxDurability() <= 0) return;
        if (!(tool.getItemMeta() instanceof Damageable dmg)) return;

        int unbreaking = tool.getEnchantmentLevel(Enchantment.UNBREAKING);
        int applied = 0;

        for (int i = 0; i < uses; i++) {
            // ★ ПРАВИЛЬНАЯ ванильная формула
            // Unbreaking 0 → 100 % шанс
            // Unbreaking 1 → 50 %
            // Unbreaking 2 → 33 %
            // Unbreaking 3 → 25 %
            if (random.nextInt(unbreaking + 1) == 0) {
                applied++;
            }
        }

        if (applied <= 0) return;

        dmg.setDamage(dmg.getDamage() + applied);
        tool.setItemMeta(dmg);

        // Инструмент сломался
        if (dmg.getDamage() >= tool.getType().getMaxDurability()) {
            player.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
            player.playSound(player.getLocation(), Sound.ENTITY_ITEM_BREAK, 1.0f, 1.0f);
        }
    }
}
