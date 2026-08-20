package com.oneworld.mixin;

import com.oneworld.portal.PortalLogic;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.portal.TeleportTransition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin({NetherPortalBlock.class})
public abstract class NetherPortalBlockMixin {
   @Inject(
      method = {"getPortalDestination"},
      at = {@At("HEAD")},
      cancellable = true
   )
   private void oneworld$verticalDestination(ServerLevel level, Entity entity, BlockPos pos, CallbackInfoReturnable<TeleportTransition> cir) {
      if (level.dimension() == Level.OVERWORLD) {
         cir.setReturnValue(PortalLogic.netherPortal(level, entity, pos));
      }
   }

   @Inject(
      method = {"getPortalTransitionTime"},
      at = {@At("HEAD")},
      cancellable = true
   )
   private void oneworld$fastTransition(ServerLevel level, Entity entity, CallbackInfoReturnable<Integer> cir) {
      if (level.dimension() == Level.OVERWORLD) {
         if (entity instanceof ServerPlayer player && player.isCreative()) {
            cir.setReturnValue(0);
         } else {
            cir.setReturnValue(10);
         }
      }
   }
}
