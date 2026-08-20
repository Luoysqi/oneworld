package com.oneworld.mixin;

import com.oneworld.portal.PortalLogic;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.InsideBlockEffectApplier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.EndPortalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.portal.TeleportTransition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin({EndPortalBlock.class})
public abstract class EndPortalBlockMixin {
   @Inject(
      method = {"getPortalDestination"},
      at = {@At("HEAD")},
      cancellable = true
   )
   private void oneworld$verticalDestination(ServerLevel level, Entity entity, BlockPos pos, CallbackInfoReturnable<TeleportTransition> cir) {
      if (level.dimension().equals(Level.OVERWORLD)) {
         cir.setReturnValue(PortalLogic.endPortal(level, entity, pos));
      }
   }

   @Inject(
      method = {"entityInside"},
      at = {@At("HEAD")},
      cancellable = true
   )
   private void oneworld$endCredits(BlockState state, Level level, BlockPos pos, Entity entity, InsideBlockEffectApplier applier, boolean flag, CallbackInfo ci) {
      if (level instanceof ServerLevel serverLevel
         && serverLevel.dimension() == Level.OVERWORLD
         && pos.getY() >= 1000
         && entity instanceof ServerPlayer player
         && !player.seenCredits) {
         player.showEndCredits();
         ci.cancel();
      }
   }
}
