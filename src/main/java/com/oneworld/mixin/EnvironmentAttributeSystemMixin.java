package com.oneworld.mixin;

import com.oneworld.OneWorld;
import net.minecraft.world.attribute.BedRule;
import net.minecraft.world.attribute.EnvironmentAttribute;
import net.minecraft.world.attribute.EnvironmentAttributeSystem;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.attribute.SpatialAttributeInterpolator;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin({EnvironmentAttributeSystem.class})
public abstract class EnvironmentAttributeSystemMixin {
   @Inject(
      method = {"getValue"},
      at = {@At("RETURN")},
      cancellable = true
   )
   private void oneworld$bandEnvironmentOverrides(
      EnvironmentAttribute<?> attribute, Vec3 pos, @Nullable SpatialAttributeInterpolator biomeInterpolator, CallbackInfoReturnable<Object> cir
   ) {
      if (pos != null) {
         Object value = cir.getReturnValue();
         if (OneWorld.inHotZone(pos.y)
            && (attribute == EnvironmentAttributes.WATER_EVAPORATES || attribute == EnvironmentAttributes.SNOW_GOLEM_MELTS)
            && Boolean.FALSE.equals(value)) {
            cir.setReturnValue(Boolean.TRUE);
         } else {
            if (OneWorld.inNetherZone(pos.y)) {
               if (attribute == EnvironmentAttributes.PIGLINS_ZOMBIFY && Boolean.TRUE.equals(value)) {
                  cir.setReturnValue(Boolean.FALSE);
               } else if (attribute == EnvironmentAttributes.CAN_START_RAID && Boolean.TRUE.equals(value)) {
                  cir.setReturnValue(Boolean.FALSE);
               } else if (attribute == EnvironmentAttributes.NETHER_PORTAL_SPAWNS_PIGLINS && Boolean.TRUE.equals(value)) {
                  cir.setReturnValue(Boolean.FALSE);
               } else if (attribute == EnvironmentAttributes.RESPAWN_ANCHOR_WORKS && Boolean.FALSE.equals(value)) {
                  cir.setReturnValue(Boolean.TRUE);
               } else if (attribute == EnvironmentAttributes.BED_RULE && value instanceof BedRule rule && !rule.explodes()) {
                  cir.setReturnValue(BedRule.EXPLODES);
               }
            } else if (OneWorld.inEndZone(pos.y) && attribute == EnvironmentAttributes.BED_RULE && value instanceof BedRule rule && !rule.explodes()) {
               cir.setReturnValue(BedRule.EXPLODES);
            }
         }
      }
   }
}
