package com.oneworld.mixin;

import com.oneworld.dragon.DragonLiteHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap.Types;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin({EnderDragon.class})
public abstract class EnderDragonMixin {
   @Redirect(
      method = {"findClosestNode"},
      at = @At(
         value = "INVOKE",
         target = "Lnet/minecraft/world/level/Level;getHeightmapPos(Lnet/minecraft/world/level/levelgen/Heightmap$Types;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/core/BlockPos;"
      )
   )
   private BlockPos oneworld$bandCirclingNodes(Level level, Types type, BlockPos pos) {
      return DragonLiteHandler.bandDragonSurface(level, type, pos);
   }

   @Redirect(
      method = {"getHeadLookVector"},
      at = @At(
         value = "INVOKE",
         target = "Lnet/minecraft/world/level/Level;getHeightmapPos(Lnet/minecraft/world/level/levelgen/Heightmap$Types;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/core/BlockPos;"
      )
   )
   private BlockPos oneworld$bandLandingLook(Level level, Types type, BlockPos pos) {
      return DragonLiteHandler.bandDragonSurface(level, type, pos);
   }
}
