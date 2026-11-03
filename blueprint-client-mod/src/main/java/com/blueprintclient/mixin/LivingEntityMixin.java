package com.blueprintclient.mixin;

import com.blueprintclient.module.Modules;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.LivingEntity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Slow Swing: stretches how long your own arm takes to swing.
 *
 * <p>Only the arm animation reads this on the client. Attack timing is the
 * separate cooldown the server tracks, so nothing about how hits land changes.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {
	@Inject(method = "getHandSwingDuration", at = @At("RETURN"), cancellable = true)
	private void obsidian$slowSwing(CallbackInfoReturnable<Integer> cir) {
		if (Modules.SLOW_SWING.isEnabled() && (Object) this == MinecraftClient.getInstance().player) {
			cir.setReturnValue(cir.getReturnValueI() * 2);
		}
	}
}
