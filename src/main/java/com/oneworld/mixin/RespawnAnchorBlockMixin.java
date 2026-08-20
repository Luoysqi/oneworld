package com.oneworld.mixin;

import com.oneworld.OneWorld;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.Level.ExplosionInteraction;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin({RespawnAnchorBlock.class})
public abstract class RespawnAnchorBlockMixin {
   @Inject(
      method = {"canSetSpawn"},
      at = {@At("HEAD")},
      cancellable = true
   )
   private static void oneworld$anchorWorksInNetherBand(ServerLevel level, BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
      if (level.dimension() == Level.OVERWORLD && OneWorld.inNetherZone(pos.getY())) {
         cir.setReturnValue(true);
      }
   }

   @Inject(
      method = {"useWithoutItem"},
      at = {@At("HEAD")},
      cancellable = true
   )
   private void oneworld$explodeOutsideNetherZone(
      BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit, CallbackInfoReturnable<InteractionResult> cir
   ) {
      if (level.dimension() == Level.OVERWORLD && !OneWorld.inNetherZone(pos.getY())) {
         if (!level.isClientSide()) {
            level.removeBlock(pos, false);
            Vec3 center = Vec3.atCenterOf(pos);
            level.explode(null, level.damageSources().badRespawnPointExplosion(center), null, center, 5.0F, true, ExplosionInteraction.BLOCK);
         }

         cir.setReturnValue(InteractionResult.SUCCESS);
      }
   }
}
