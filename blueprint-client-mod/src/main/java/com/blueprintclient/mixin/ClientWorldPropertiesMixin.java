package com.blueprintclient.mixin;

import com.blueprintclient.module.Modules;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Always Day / Always Night: the time of day this client believes it is.
 *
 * <p>This is the client's own copy of the world clock, so only the sky and the
 * light on your screen change; the server's time, and everyone else's, does
 * not. The day number is kept, so the moon still goes through its phases.
 */
@Mixin(targets = "net.minecraft.client.world.ClientWorld$Properties")
public abstract class ClientWorldPropertiesMixin {
	private static final long DAY_LENGTH = 24000L;
	private static final long NOON = 6000L;
	private static final long MIDNIGHT = 18000L;

	@Inject(method = "getTimeOfDay", at = @At("RETURN"), cancellable = true)
	private void obsidian$fixedTime(CallbackInfoReturnable<Long> cir) {
		long fixed;
		if (Modules.ALWAYS_NIGHT.isEnabled()) {
			fixed = MIDNIGHT;
		} else if (Modules.ALWAYS_DAY.isEnabled()) {
			fixed = NOON;
		} else {
			return;
		}
		long day = Math.floorDiv(cir.getReturnValueJ(), DAY_LENGTH);
		cir.setReturnValue(day * DAY_LENGTH + fixed);
	}
}
