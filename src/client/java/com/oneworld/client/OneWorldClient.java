package com.oneworld.client;

import com.oneworld.OneWorld;
import com.oneworld.OneWorldConfig;
import com.oneworld.worldgen.BandHeights;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.level.Level;

public final class OneWorldClient implements ClientModInitializer {
   private static int configReloadCounter;

   public void onInitializeClient() {
      BandHeights.setClientRenderThread(Thread.currentThread());
      ClientTickEvents.END_CLIENT_TICK.register(OneWorldClient::updateClientBand);
      ClientTickEvents.END_CLIENT_TICK.register(OneWorldClient::tickConfigReload);
      OneWorld.LOGGER.info("[OneWorld] client sky ready: gradual end night above y {}", 560);
   }

   private static void updateClientBand(Minecraft client) {
      LocalPlayer player = client.player;
      if (player != null && player.level().dimension() == Level.OVERWORLD) {
         int[] band = BandHeights.bandFor(player.getY());
         BandHeights.setClientBand(band[0], band[1]);
         if (OneWorldConfig.endNightSky() && player.getY() >= 1000.0) {
            IrisBandSwitcher.tick(IrisBandSwitcher.ProgramBand.END);
         } else if (OneWorldConfig.vanillaNetherLight() && player.getY() <= -193.0) {
            IrisBandSwitcher.tick(IrisBandSwitcher.ProgramBand.NETHER);
         } else {
            IrisBandSwitcher.tick(IrisBandSwitcher.ProgramBand.OVERWORLD);
         }
      } else {
         BandHeights.setClientBand(-64, 319);
         IrisBandSwitcher.tick(IrisBandSwitcher.ProgramBand.OVERWORLD);
      }
   }

   private static void tickConfigReload(Minecraft client) {
      if (++configReloadCounter % 20 == 0) {
         OneWorldConfig.maybeReload();
      }
   }
}
