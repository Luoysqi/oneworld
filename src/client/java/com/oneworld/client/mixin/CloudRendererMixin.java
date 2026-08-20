package com.oneworld.client.mixin;

import com.oneworld.OneWorldConfig;
import com.oneworld.client.EndSkyState;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.CloudRenderer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin({CloudRenderer.class})
public abstract class CloudRendererMixin {
   @Inject(
      method = {"render"},
      at = {@At("HEAD")},
      cancellable = true
   )
   private void oneworld$noCloudsInTheNetherScene(
      int cloudDistance, CloudStatus status, float partialTicks, int ticks, Vec3 cameraPos, long seed, float cloudHeight, CallbackInfo ci
   ) {
      if (OneWorldConfig.vanillaNetherLight()) {
         Minecraft client = Minecraft.getInstance();
         if (client != null && client.player != null && client.level != null && client.level.dimension() == Level.OVERWORLD) {
            if (client.player.getY() <= EndSkyState.netherSceneTopY()) {
               ci.cancel();
            }
         }
      }
   }
}
