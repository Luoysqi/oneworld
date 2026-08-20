package com.oneworld.defense;

import com.oneworld.OneWorld;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

public final class AdvancementHelper {
   private AdvancementHelper() {
   }

   public static void award(ServerPlayer player, String path) {
      MinecraftServer server = player.level().getServer();
      if (server != null) {
         AdvancementHolder holder = server.getAdvancements().get(OneWorld.id(path));
         if (holder != null && !player.getAdvancements().getOrStartProgress(holder).isDone()) {
            player.getAdvancements().award(holder, "granted");
         }
      }
   }

   public static void greyMessage(ServerPlayer player, Component message) {
      player.sendOverlayMessage(message);
   }
}
