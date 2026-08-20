package com.oneworld.client.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import xaero.common.minimap.write.MinimapWriter;

@Mixin(
   value = {MinimapWriter.class},
   remap = false
)
public abstract class XaeroMinimapWriterMixin {
   @Inject(
      method = {"getCaving"},
      at = {@At("RETURN")},
      cancellable = true,
      require = 0
   )
   private void oneworld$bandsNeverEnterCaveMode(CallbackInfoReturnable<Integer> cir) {
      if (cir.getReturnValueI() != Integer.MAX_VALUE) {
         Minecraft client = Minecraft.getInstance();
         if (client != null && client.player != null && client.level != null) {
            if (client.level.dimension() == Level.OVERWORLD) {
               double y = client.player.getY();
               if (y >= 1000.0 || y >= -1008.0 && y <= -193.0) {
                  cir.setReturnValue(Integer.MAX_VALUE);
               }
            }
         }
      }
   }
}
