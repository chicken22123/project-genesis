package com.blueprintclient.mixin;

import com.blueprintclient.module.Modules;
import com.blueprintclient.module.ObsidianSky;

import net.minecraft.client.render.Camera;
import net.minecraft.client.render.SkyRendering;
import net.minecraft.client.render.state.SkyRenderState;
import net.minecraft.client.world.ClientWorld;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Obsidian Sky: once vanilla has worked out this frame's sky, darken it toward
 * near-black and bring the stars out, day or night.
 */
@Mixin(SkyRendering.class)
public abstract class SkyRenderingMixin {
	@Inject(method = "updateRenderState", at = @At("TAIL"))
	private void obsidian$darkSky(
			ClientWorld world, float tickProgress, Camera camera, SkyRenderState state, CallbackInfo ci) {
		if (Modules.OBSIDIAN_SKY.isEnabled()) {
			state.skyColor = ObsidianSky.darken(state.skyColor);
			state.starBrightness = Math.max(state.starBrightness, ObsidianSky.STAR_BRIGHTNESS);
		}
	}
}
