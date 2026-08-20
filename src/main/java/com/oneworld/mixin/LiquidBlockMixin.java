package com.oneworld.mixin;

import com.oneworld.OneWorld;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin({LiquidBlock.class})
public abstract class LiquidBlockMixin {
   @Inject(
      method = {"onPlace"},
      at = {@At("HEAD")},
      cancellable = true
   )
   private void oneworld$evaporateWaterInHotZone(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston, CallbackInfo ci) {
      if (!level.isClientSide() && state.getFluidState().is(FluidTags.WATER) && OneWorld.inHotZone(pos.getY())) {
         OneWorld.evaporateWater(level, pos);
         ci.cancel();
      }
   }
}
