package com.oneworld.mixin;

import com.oneworld.HotLavaFrame;
import com.oneworld.OneWorld;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin({FlowingFluid.class})
public abstract class FlowingFluidMixin {
   @Inject(
      method = {"tick"},
      at = {@At("HEAD")},
      cancellable = true
   )
   private void oneworld$evaporateStaleHotZoneWater(ServerLevel level, BlockPos pos, BlockState blockState, FluidState fluidState, CallbackInfo ci) {
      HotLavaFrame.open(fluidState, pos);
      if (fluidState.is(FluidTags.WATER) && OneWorld.inHotZone(pos.getY())) {
         OneWorld.evaporateWater(level, pos);
         ci.cancel();
      }
   }

   @Inject(
      method = {"tick"},
      at = {@At("TAIL")}
   )
   private void oneworld$closeLavaFrameAfterTick(ServerLevel level, BlockPos pos, BlockState blockState, FluidState fluidState, CallbackInfo ci) {
      HotLavaFrame.close(fluidState);
   }
}
