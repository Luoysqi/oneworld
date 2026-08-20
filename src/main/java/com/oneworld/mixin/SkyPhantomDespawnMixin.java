package com.oneworld.mixin;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Phantom;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin({Mob.class})
public abstract class SkyPhantomDespawnMixin {
   @Inject(
      method = {"checkDespawn"},
      at = {@At("HEAD")},
      cancellable = true
   )
   private void oneworld$skyPhantomsStayInPeaceful(CallbackInfo ci) {
      if ((Object)this instanceof Phantom phantom && phantom.entityTags().contains("oneworld_sky")) {
         ci.cancel();
      }
   }
}
