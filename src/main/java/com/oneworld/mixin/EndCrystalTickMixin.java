package com.oneworld.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseFireBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin({EndCrystal.class})
public abstract class EndCrystalTickMixin {
   // 原版末地水晶每 tick 在脚下为空气时补一团火（EndCrystal.tick，前提是维度存在 EnderDragonFight），
   // 这就是原版里玩家自己放到基岩上的水晶下面也会有火、且火焰始终不消失的原因。
   // 合并世界的主维度没有 fight 对象，这条路径永不生效，导致所有水晶下方无火。
   // 这里在末地带（y >= 1000）复刻原版行为；配合 infiniburn_end（含基岩）火焰与原版一样常驻。
   @Inject(
      method = {"tick"},
      at = {@At("HEAD")}
   )
   private void oneworld$crystalFire(CallbackInfo ci) {
      EndCrystal self = (EndCrystal)(Object)this;
      if (self.level() instanceof ServerLevel && self.level().dimension() == Level.OVERWORLD && self.getY() >= 1000.0) {
         BlockPos pos = self.blockPosition();
         if (self.level().getBlockState(pos).isAir()) {
            self.level().setBlockAndUpdate(pos, BaseFireBlock.getState(self.level(), pos));
         }
      }
   }
}
