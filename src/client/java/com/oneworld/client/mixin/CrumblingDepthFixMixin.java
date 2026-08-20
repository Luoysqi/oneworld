package com.oneworld.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.RenderPipelines;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(
   targets = {"com.mojang.blaze3d.opengl.GlCommandEncoder"}
)
public abstract class CrumblingDepthFixMixin {
   @Unique
   private static boolean oneworld$fixCrumblingDepth;

   @Inject(
      method = {"applyPipelineState"},
      at = {@At("HEAD")}
   )
   private void oneworld$beginCrumblingFix(RenderPipeline pipeline, CallbackInfo ci) {
      oneworld$fixCrumblingDepth = pipeline == RenderPipelines.CRUMBLING && !RenderSystem.getDevice().getDeviceInfo().isZZeroToOne();
   }

   @WrapOperation(
      method = {"applyPipelineState"},
      at = {@At(
         value = "INVOKE",
         target = "Lcom/mojang/blaze3d/opengl/GlConst;toGl(Lcom/mojang/blaze3d/platform/CompareOp;)I"
      )}
   )
   private int oneworld$crumblingDepthFunc(CompareOp compareOp, Operation<Integer> original) {
      return oneworld$fixCrumblingDepth && compareOp == CompareOp.GREATER_THAN_OR_EQUAL
         ? (Integer)original.call(new Object[]{CompareOp.LESS_THAN_OR_EQUAL})
         : (Integer)original.call(new Object[]{compareOp});
   }

   @WrapOperation(
      method = {"applyPipelineState"},
      at = {@At(
         value = "INVOKE",
         target = "Lcom/mojang/blaze3d/opengl/GlStateManager;_polygonOffset(FF)V"
      )}
   )
   private void oneworld$crumblingNoOffset(float factor, float units, Operation<Void> original) {
      if (oneworld$fixCrumblingDepth) {
         factor = 0.0F;
         units = 0.0F;
      }

      original.call(new Object[]{factor, units});
   }

   @Inject(
      method = {"applyPipelineState"},
      at = {@At("RETURN")}
   )
   private void oneworld$endCrumblingFix(RenderPipeline pipeline, CallbackInfo ci) {
      oneworld$fixCrumblingDepth = false;
   }
}
