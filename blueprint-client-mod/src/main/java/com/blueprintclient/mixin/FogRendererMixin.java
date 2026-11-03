package com.blueprintclient.mixin;

import com.blueprintclient.module.Modules;
import com.blueprintclient.module.ObsidianSky;

import net.minecraft.client.render.Camera;
import net.minecraft.client.render.fog.FogRenderer;
import net.minecraft.client.world.ClientWorld;

import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Obsidian Sky, part two: the fog at the horizon follows the darker sky, or a
 * blue band would sit under a black one. Underwater, lava and powder-snow fog
 * are left alone - those colours tell you where you are.
 */
@Mixin(FogRenderer.class)
public abstract class FogRendererMixin {
	@Inject(method = "getFogColor", at = @At("RETURN"), cancellable = true)
	private void obsidian$darkFog(
			Camera camera, float tickProgress, ClientWorld world, int viewDistance, float skyDarkness,
			CallbackInfoReturnable<Vector4f> cir) {
		if (!Modules.OBSIDIAN_SKY.isEnabled() || ObsidianSky.isSubmerged(camera)) {
			return;
		}
		Vector4f fog = cir.getReturnValue();
		cir.setReturnValue(ObsidianSky.darken(fog));
	}
}
