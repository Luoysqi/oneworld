package com.oneworld.mixin;

import com.oneworld.dragon.DragonLiteHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.boss.enderdragon.phases.DragonLandingPhase;
import net.minecraft.world.level.levelgen.Heightmap.Types;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin({DragonLandingPhase.class})
public abstract class DragonLandingPhaseMixin {
   @Redirect(
      method = {"doServerTick"},
      at = @At(
         value = "INVOKE",
         target = "Lnet/minecraft/server/level/ServerLevel;getHeightmapPos(Lnet/minecraft/world/level/levelgen/Heightmap$Types;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/core/BlockPos;"
      )
   )
   private BlockPos oneworld$bandPerch(ServerLevel level, Types type, BlockPos pos) {
      return DragonLiteHandler.bandDragonSurface(level, type, pos);
   }
}
