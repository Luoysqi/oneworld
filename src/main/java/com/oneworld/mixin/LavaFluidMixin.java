package com.oneworld.mixin;

import com.oneworld.HotLavaFrame;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.material.LavaFluid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin({LavaFluid.class})
public abstract class LavaFluidMixin {
   @Inject(
      method = {"getTickDelay"},
      at = {@At("RETURN")},
      cancellable = true
   )
   private void oneworld$fastHotLavaTickDelay(LevelReader level, CallbackInfoReturnable<Integer> cir) {
      if (HotLavaFrame.isHot() && (Integer)cir.getReturnValue() == 30) {
         cir.setReturnValue(10);
      }
   }

   @Inject(
      method = {"getDropOff"},
      at = {@At("RETURN")},
      cancellable = true
   )
   private void oneworld$farHotLavaDropOff(LevelReader level, CallbackInfoReturnable<Integer> cir) {
      if (HotLavaFrame.isHot() && (Integer)cir.getReturnValue() == 2) {
         cir.setReturnValue(1);
      }
   }

   @Inject(
      method = {"getSlopeFindDistance"},
      at = {@At("RETURN")},
      cancellable = true
   )
   private void oneworld$wideHotLavaSlopeSearch(LevelReader level, CallbackInfoReturnable<Integer> cir) {
      if (HotLavaFrame.isHot() && (Integer)cir.getReturnValue() == 2) {
         cir.setReturnValue(4);
      }
   }
}
