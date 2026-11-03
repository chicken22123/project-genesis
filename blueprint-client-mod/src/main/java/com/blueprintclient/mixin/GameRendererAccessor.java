package com.blueprintclient.mixin;

import net.minecraft.client.render.GameRenderer;
import net.minecraft.util.Identifier;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * The game renderer's post-effect slot - the one vanilla uses for the creeper
 * and spider views when spectating - which is how motion blur and colour
 * grading get drawn over the world.
 *
 * <p>Invokers rather than direct calls, because these are not all public.
 */
@Mixin(GameRenderer.class)
public interface GameRendererAccessor {
	@Invoker("setPostProcessor")
	void obsidian$setPostProcessor(Identifier id);

	@Invoker("getPostProcessorId")
	Identifier obsidian$getPostProcessorId();

	@Invoker("clearPostProcessor")
	void obsidian$clearPostProcessor();
}
