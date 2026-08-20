package com.oneworld.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap.Types;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(
   targets = {"net.minecraft.world.entity.monster.Phantom$PhantomAttackStrategyGoal"}
)
public abstract class PhantomAttackStrategyGoalMixin {
   @Redirect(
      method = {"stop"},
      at = @At(
         value = "INVOKE",
         target = "Lnet/minecraft/world/level/Level;getHeightmapPos(Lnet/minecraft/world/level/levelgen/Heightmap$Types;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/core/BlockPos;"
      )
   )
   private BlockPos oneworld$stayAtHighAltitude(Level level, Types type, BlockPos pos) {
      return level.dimension() == Level.OVERWORLD && pos.getY() >= 320 ? pos : level.getHeightmapPos(type, pos);
   }
}
