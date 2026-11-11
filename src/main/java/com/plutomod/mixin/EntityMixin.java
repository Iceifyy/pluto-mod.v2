package com.plutomod.mixin;

import com.plutomod.config.PlutoConfig;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Entity.class)
public class EntityMixin {
    // Skips rendering entities beyond the mod's own distance cap (the argument is squared distance).
    @Inject(method = "shouldRender(D)Z", at = @At("HEAD"), cancellable = true)
    private void plutomod$entityDistance(double squaredDistance, CallbackInfoReturnable<Boolean> cir) {
        int cap = PlutoConfig.get().entityDistanceCap;
        if (cap < 128 && squaredDistance > (double) cap * cap) {
            cir.setReturnValue(false);
        }
    }
}
