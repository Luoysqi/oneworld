package com.oneworld.client;

import net.fabricmc.loader.api.FabricLoader;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.shaderpack.DimensionId;

public final class IrisBandSwitcher {
   private static IrisBandSwitcher.ProgramBand band = IrisBandSwitcher.ProgramBand.OVERWORLD;
   private static boolean irisChecked;
   private static boolean irisPresent;

   private IrisBandSwitcher() {
   }

   public static void tick(IrisBandSwitcher.ProgramBand newBand) {
      if (newBand != band) {
         band = newBand;
         if (!irisChecked) {
            irisChecked = true;
            irisPresent = FabricLoader.getInstance().isModLoaded("iris");
         }

         if (irisPresent) {
            IrisBandSwitcher.Hook.apply(newBand);
         }
      }
   }

   private static final class Hook {
      static void apply(IrisBandSwitcher.ProgramBand band) {
         Iris.getPipelineManager().preparePipeline(switch (band) {
            case OVERWORLD -> DimensionId.OVERWORLD;
            case NETHER -> DimensionId.NETHER;
            case END -> DimensionId.END;
         });
      }
   }

   public static enum ProgramBand {
      OVERWORLD,
      NETHER,
      END;
   }
}
