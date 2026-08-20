package com.oneworld.mixin;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin({Mob.class})
public abstract class PhantomSunBurnMixin {
   @Inject(
      method = {"isSunBurnTick"},
      at = {@At("HEAD")},
      cancellable = true
   )
   private void oneworld$noBurnHighUp(CallbackInfoReturnable<Boolean> cir) {
      Mob self = (Mob)(Object)this;
      if (self.getY() >= 560.0 && self.level().dimension() == Level.OVERWORLD) {
         cir.setReturnValue(false);
      }
   }
}
