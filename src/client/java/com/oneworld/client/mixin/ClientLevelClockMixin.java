package com.oneworld.client.mixin;

import com.oneworld.client.EndSkyState;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin({Level.class})
public abstract class ClientLevelClockMixin {
   @Shadow
   @Final
   public boolean isClientSide;

   @Inject(
      method = {"getOverworldClockTime"},
      at = {@At("RETURN")},
      cancellable = true
   )
   private void oneworld$blendClockOverworld(CallbackInfoReturnable<Long> cir) {
      this.oneworld$blend(cir);
   }

   @Inject(
      method = {"getDefaultClockTime"},
      at = {@At("RETURN")},
      cancellable = true
   )
   private void oneworld$blendClockDefault(CallbackInfoReturnable<Long> cir) {
      this.oneworld$blend(cir);
   }

   private void oneworld$blend(CallbackInfoReturnable<Long> cir) {
      if (this.isClientSide && ((Level)(Object)this).dimension() == Level.OVERWORLD) {
         Minecraft client = Minecraft.getInstance();
         if (client != null && client.player != null) {
            float factor = EndSkyState.factorFor(client.player.getY());
            if (!(factor <= 0.0F)) {
               long real = (Long)cir.getReturnValue();
               long delta = distanceToMidnight(real);
               if (factor >= 1.0F) {
                  cir.setReturnValue(real + delta);
               } else {
                  cir.setReturnValue(real + Math.round((float)delta * factor));
               }
            }
         }
      }
   }

   private static long distanceToMidnight(long time) {
      long dayTime = (time % 24000L + 24000L) % 24000L;
      long delta = 18000L - dayTime;
      if (delta > 12000L) {
         delta -= 24000L;
      }

      if (delta < -12000L) {
         delta += 24000L;
      }

      return delta;
   }
}
