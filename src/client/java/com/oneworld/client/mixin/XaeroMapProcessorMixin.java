package com.oneworld.client.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xaero.map.MapProcessor;
import xaero.map.world.MapWorld;

@Mixin(
   value = {MapProcessor.class},
   remap = false
)
public abstract class XaeroMapProcessorMixin {
   private static final int ONWORLD_END_SURFACE_LAYER = 62;
   @Shadow
   private int currentCaveLayer;

   @Shadow
   public abstract MapWorld getMapWorld();

   @Inject(
      method = {"updateCaveStart"},
      at = {@At("HEAD")},
      cancellable = true,
      require = 0
   )
   private void oneworld$bandPinnedCaveLayer(CallbackInfo ci) {
      Minecraft client = Minecraft.getInstance();
      if (client != null && client.player != null && client.level != null) {
         if (client.level.dimension() == Level.OVERWORLD) {
            MapWorld mapWorld = this.getMapWorld();
            if (mapWorld != null && mapWorld.getCurrentDimension() != null) {
               double y = client.player.getY();
               if (y >= 1000.0) {
                  mapWorld.getCurrentDimension().getLayeredMapRegions().getLayer(62).setCaveStart(Integer.MAX_VALUE);
                  this.currentCaveLayer = 62;
                  ci.cancel();
               } else if (y >= -1008.0 && y <= -193.0) {
                  mapWorld.getCurrentDimension().getLayeredMapRegions().getLayer(Integer.MIN_VALUE).setCaveStart(Integer.MIN_VALUE);
                  this.currentCaveLayer = Integer.MIN_VALUE;
                  ci.cancel();
               }
            }
         }
      }
   }
}
