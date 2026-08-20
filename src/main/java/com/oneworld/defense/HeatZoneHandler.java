package com.oneworld.defense;

import com.oneworld.OneWorldConfig;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Items;

public final class HeatZoneHandler {
   private static int tickCounter = 0;

   private HeatZoneHandler() {
   }

   public static void tickServer(MinecraftServer server) {
      tickCounter++;
      if (tickCounter % 20 == 0 && OneWorldConfig.restrictionsEnabled()) {
         ServerLevel level = server.overworld();
         if (level != null) {
            for (ServerPlayer player : level.players()) {
               double y = player.getY();
               if (y <= -64.0 && !OneWorldAttachments.getBool(player, OneWorldAttachments.HEAT_ADV)) {
                  player.setAttached(OneWorldAttachments.HEAT_ADV, true);
                  AdvancementHelper.award(player, "its_getting_hot_in_here");
               }

               boolean inZone = y >= -192.0 && y <= -66.0;
               if (!inZone) {
                  if (OneWorldAttachments.getBool(player, OneWorldAttachments.HEAT_IN_ZONE)) {
                     player.setAttached(OneWorldAttachments.HEAT_IN_ZONE, false);
                     player.setAttached(OneWorldAttachments.HEAT_BURNED, false);
                  }
               } else {
                  player.setAttached(OneWorldAttachments.HEAT_IN_ZONE, true);
                  if (!player.isCreative() && !OneWorldAttachments.getBool(player, OneWorldAttachments.HEAT_BURNED) && !fireExempt(player)) {
                     player.setAttached(OneWorldAttachments.HEAT_BURNED, true);
                     player.igniteForSeconds(8.0F);
                  }
               }
            }
         }
      }
   }

   private static boolean fireExempt(ServerPlayer player) {
      boolean anyNetherite = player.getItemBySlot(EquipmentSlot.HEAD).is(Items.NETHERITE_HELMET)
         || player.getItemBySlot(EquipmentSlot.CHEST).is(Items.NETHERITE_CHESTPLATE)
         || player.getItemBySlot(EquipmentSlot.LEGS).is(Items.NETHERITE_LEGGINGS)
         || player.getItemBySlot(EquipmentSlot.FEET).is(Items.NETHERITE_BOOTS);
      boolean allDiamond = player.getItemBySlot(EquipmentSlot.HEAD).is(Items.DIAMOND_HELMET)
         && player.getItemBySlot(EquipmentSlot.CHEST).is(Items.DIAMOND_CHESTPLATE)
         && player.getItemBySlot(EquipmentSlot.LEGS).is(Items.DIAMOND_LEGGINGS)
         && player.getItemBySlot(EquipmentSlot.FEET).is(Items.DIAMOND_BOOTS);
      return anyNetherite || allDiamond;
   }
}
