package com.oneworld.mixin;

import com.oneworld.OneWorld;
import com.oneworld.dragon.EndBandGateways;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.TheEndGatewayBlockEntity;
import net.minecraft.world.level.levelgen.feature.configurations.EndGatewayConfiguration;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin({TheEndGatewayBlockEntity.class})
public abstract class TheEndGatewayBlockEntityMixin {
   @Shadow
   @Nullable
   private BlockPos exitPortal;
   @Shadow
   private boolean exactTeleport;

   @Inject(
      method = {"getPortalPosition"},
      at = {@At("HEAD")},
      cancellable = true
   )
   private void oneworld$bandGatewayDestination(ServerLevel level, BlockPos portalEntryPos, CallbackInfoReturnable<Vec3> cir) {
      if (level.dimension() == Level.OVERWORLD && portalEntryPos.getY() >= 1000) {
         if (this.exitPortal == null) {
            BlockPos exit = EndBandGateways.findOrCreateValidTeleportPos(level, portalEntryPos);
            exit = exit.above(10);
            EndBandGateways.spawnGatewayPortal(level, exit, EndGatewayConfiguration.knownExit(portalEntryPos, false));
            ((TheEndGatewayBlockEntity)(Object)this).setExitPosition(exit, this.exactTeleport);
            OneWorld.LOGGER.info("[OneWorld] end gateway at {} paired with return gateway at {}", portalEntryPos, exit);
         }

         if (this.exitPortal != null) {
            BlockPos pos = this.exactTeleport ? this.exitPortal : EndBandGateways.findExitPosition(level, this.exitPortal);
            cir.setReturnValue(Vec3.atBottomCenterOf(pos));
         } else {
            cir.setReturnValue(null);
         }
      }
   }
}
