package com.oneworld.client.mixin;

import com.oneworld.client.EndSkyState;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SkyRenderer;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType.Skybox;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin({SkyRenderer.class})
public abstract class SkyRendererMixin {
   @Inject(
      method = {"extractRenderState"},
      at = {@At("TAIL")}
   )
   private void oneworld$endSky(ClientLevel level, float partialTicks, Camera camera, SkyRenderState state, CallbackInfo ci) {
      if (state.skybox == Skybox.OVERWORLD && level.dimension() == Level.OVERWORLD) {
         float factor = EndSkyState.factorFor(camera.position().y);
         EndSkyState.endFactor = factor;
         EndSkyState.netherFactor = EndSkyState.netherFactorFor(camera.position().y);
         if (factor >= 0.98F) {
            state.shouldRenderDarkDisc = true;
         }
      } else {
         EndSkyState.endFactor = 0.0F;
         EndSkyState.netherFactor = 0.0F;
      }
   }

   @ModifyVariable(
      method = {"renderSun"},
      at = @At("HEAD"),
      argsOnly = true
   )
   private float oneworld$fadeSun(float rainBrightness) {
      float nether = EndSkyState.netherFactor;
      if (nether > 0.0F) {
         rainBrightness *= EndSkyState.clamp01(1.0F - nether / 0.5F);
      }

      float factor = EndSkyState.endFactor;
      if (factor <= 0.0F) {
         return rainBrightness;
      } else {
         float fade = EndSkyState.clamp01(1.0F - factor / 0.55F);
         return rainBrightness * fade;
      }
   }

   @ModifyVariable(
      method = {"renderMoon"},
      at = @At("HEAD"),
      argsOnly = true
   )
   private float oneworld$fadeMoon(float rainBrightness) {
      float nether = EndSkyState.netherFactor;
      if (nether > 0.0F) {
         rainBrightness *= EndSkyState.clamp01(1.0F - nether / 0.5F);
      }

      float factor = EndSkyState.endFactor;
      if (factor <= 0.25F) {
         return rainBrightness;
      } else {
         float fade = EndSkyState.clamp01(1.0F - (factor - 0.25F) / 0.6F);
         return rainBrightness * fade;
      }
   }
}
