package com.oneworld.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.ServerLevelAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin({Monster.class})
public abstract class MonsterSpawnLightMixin {
   @Inject(
      method = {"isDarkEnoughToSpawn"},
      at = {@At("HEAD")},
      cancellable = true
   )
   private static void oneworld$bandSpawnLight(ServerLevelAccessor level, BlockPos pos, RandomSource random, CallbackInfoReturnable<Boolean> cir) {
      if (level.getLevel().dimension() == Level.OVERWORLD) {
         int y = pos.getY();
         if (y >= 1000) {
            cir.setReturnValue(level.getBrightness(LightLayer.BLOCK, pos) <= 0);
         } else {
            if (y <= -193) {
               cir.setReturnValue(level.getBrightness(LightLayer.BLOCK, pos) <= 7);
            }
         }
      }
   }
}
