package com.oneworld.mixin;

import com.oneworld.OneWorld;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin({BucketItem.class})
public abstract class BucketItemMixin {
   @Shadow
   @Final
   private Fluid content;

   @Inject(
      method = {"emptyContents"},
      at = {@At("HEAD")},
      cancellable = true
   )
   private void oneworld$evaporateInHotZone(LivingEntity player, Level level, BlockPos pos, BlockHitResult hit, CallbackInfoReturnable<Boolean> cir) {
      if (OneWorld.inHotZone(pos.getY()) && this.content == Fluids.WATER) {
         RandomSource rand = level.getRandom();
         level.playSound(player, pos, SoundEvents.FIRE_EXTINGUISH, SoundSource.BLOCKS, 0.5F, 2.6F + (rand.nextFloat() - rand.nextFloat()) * 0.8F);
         if (level instanceof ServerLevel serverLevel) {
            serverLevel.sendParticles(ParticleTypes.LARGE_SMOKE, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 8, 0.5, 0.5, 0.5, 0.0);
         } else {
            for (int i = 0; i < 8; i++) {
               level.addParticle(
                  ParticleTypes.LARGE_SMOKE, pos.getX() + rand.nextFloat(), pos.getY() + rand.nextFloat(), pos.getZ() + rand.nextFloat(), 0.0, 0.0, 0.0
               );
            }
         }

         cir.setReturnValue(true);
      }
   }
}
