package com.oneworld;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.material.FluidState;

public final class HotLavaFrame {
   private static final ThreadLocal<Integer> DEPTH = new ThreadLocal<>();
   private static final ThreadLocal<Boolean> HOT = ThreadLocal.withInitial(() -> Boolean.FALSE);

   private HotLavaFrame() {
   }

   public static void open(FluidState fluidState, BlockPos pos) {
      if (fluidState.is(FluidTags.LAVA)) {
         Integer depth = DEPTH.get();
         if (depth == null) {
            DEPTH.set(1);
            HOT.set(OneWorld.inHotZone(pos.getY()));
         } else {
            DEPTH.set(depth + 1);
         }
      }
   }

   public static void close(FluidState fluidState) {
      if (fluidState.is(FluidTags.LAVA)) {
         Integer depth = DEPTH.get();
         if (depth != null) {
            if (depth <= 1) {
               DEPTH.remove();
               HOT.set(false);
            } else {
               DEPTH.set(depth - 1);
            }
         }
      }
   }

   public static boolean isHot() {
      return HOT.get();
   }
}
