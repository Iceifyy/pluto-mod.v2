package com.plutomod.mixin;

import com.plutomod.config.PlutoConfig;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.ThreadLocalRandom;

@Mixin(ParticleManager.class)
public class ParticleManagerMixin {
    @Inject(method = "addParticle(Lnet/minecraft/client/particle/Particle;)V", at = @At("HEAD"), cancellable = true)
    private void plutomod$thinParticles(Particle particle, CallbackInfo ci) {
        int drop = PlutoConfig.get().particleReduction;
        if (drop > 0 && ThreadLocalRandom.current().nextInt(100) < drop) {
            ci.cancel();
        }
    }
}
