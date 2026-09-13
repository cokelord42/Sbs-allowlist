package com.cokelord.skyblocksimplified.mixin;

import com.cokelord.skyblocksimplified.feature.FeatureRegistry;
import com.cokelord.skyblocksimplified.feature.impl.CameraFeature;
import net.minecraft.client.Camera;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** {@code Camera.alignWithEntity(float)} (only called while detached, i.e. third person) computes a
 *  baseline distance — vanilla's own {@code DEFAULT_CAMERA_DISTANCE} (4 blocks), or the real {@code
 *  minecraft:camera_distance} attribute's value when the mounted/riding entity has one — then passes it
 *  into {@code getMaxZoom(float)}, which raycasts backward from the eye and clips to whatever it actually
 *  hits. Rewriting that one argument here (rather than the attribute itself, which is server-syncable and
 *  would just get overwritten by the next attribute-update packet from Hypixel) only widens the requested
 *  distance — getMaxZoom's own raycast/clip still runs unchanged, so this can never let the camera clip
 *  through a wall, only pull back further in open space. {@code Math.max} with the vanilla-computed value
 *  means this never SHRINKS the distance below whatever vanilla would have used. */
@Mixin(Camera.class)
public class CameraMixin {
	@ModifyArg(method = "alignWithEntity", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;getMaxZoom(F)F"))
	private float skyblocksimplified$extendMaxDistance(float cameraDistance) {
		if (FeatureRegistry.get("camera") instanceof CameraFeature feature && feature.isEnabled()) {
			Minecraft mc = Minecraft.getInstance();
			if (mc.options.getCameraType() == CameraType.THIRD_PERSON_BACK) {
				return Math.max(cameraDistance, feature.getMaxDistance());
			}
		}
		return cameraDistance;
	}
}
