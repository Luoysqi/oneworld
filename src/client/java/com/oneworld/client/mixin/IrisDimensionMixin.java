package com.oneworld.client.mixin;

import com.oneworld.OneWorldConfig;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.shaderpack.DimensionId;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(
   value = {Iris.class},
   remap = false
)
public abstract class IrisDimensionMixin {
   @Inject(
      method = {"getCurrentDimension"},
      at = {@At("RETURN")},
      cancellable = true,
      remap = false
   )
   private static void oneworld$bandDimensionShaders(CallbackInfoReturnable<NamespacedId> cir) {
      Minecraft client = Minecraft.getInstance();
      if (client != null && client.player != null && client.level != null) {
         if (client.level.dimension() == Level.OVERWORLD) {
            if (OneWorldConfig.endNightSky() && client.player.getY() >= 1000.0) {
               cir.setReturnValue(DimensionId.END);
            } else if (OneWorldConfig.vanillaNetherLight() && client.player.getY() <= -193.0) {
               cir.setReturnValue(DimensionId.NETHER);
            }
         }
      }
   }
}
