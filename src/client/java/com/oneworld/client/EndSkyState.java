package com.oneworld.client;

import com.oneworld.OneWorldConfig;
import net.minecraft.util.Mth;

public final class EndSkyState {
   public static final float FADE_MIN_Y = 560.0F;
   public static final float FADE_MAX_Y = 980.0F;
   public static volatile float endFactor;
   public static volatile float netherFactor;

   private EndSkyState() {
   }

   public static float factorFor(double cameraY) {
      if (!OneWorldConfig.endNightSky()) {
         return 0.0F;
      } else if (cameraY <= 560.0) {
         return 0.0F;
      } else if (cameraY >= 980.0) {
         return 1.0F;
      } else {
         float t = (float)((cameraY - 560.0) / 420.0);
         return t * t * (3.0F - 2.0F * t);
      }
   }

   public static float clamp01(float v) {
      return Mth.clamp(v, 0.0F, 1.0F);
   }

   public static float netherFactorFor(double cameraY) {
      if (!OneWorldConfig.vanillaNetherLight()) {
         return 0.0F;
      } else {
         double start = netherSceneTopY();
         double end = OneWorldConfig.restrictionsEnabled() ? -192.0 : -193.0;
         if (cameraY >= start) {
            return 0.0F;
         } else {
            return cameraY <= end ? 1.0F : (float)((start - cameraY) / (start - end));
         }
      }
   }

   public static double netherSceneTopY() {
      return OneWorldConfig.restrictionsEnabled() ? -65.0 : -256.0;
   }
}
