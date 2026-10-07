package com.example.taczmeshloader.render;

import com.example.taczmeshloader.tacz.GunConfig;
import com.tacz.guns.api.client.animation.ObjectAnimationRunner;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import java.util.List;

/** Captured first-person model poses consumed by the shared particle engine. */
public interface FxParticleSource {
    List<GunConfig.FxEmitter> activeFxEmitters();
    boolean isShooterAiming(Minecraft mc);
    float getInspectProgressSeconds(ItemStack stack);
    Vec3 fxBoneWorld(String bone, float x, float y, float z, boolean boneSpace, Minecraft mc);
    boolean fxBoneWorldOffset(String bone, float x, float y, float z, boolean boneSpace,
                              Minecraft mc, float fovScale, Matrix4f scratch, Vector3f result);
    boolean fxBoneWorldPose(String bone, Minecraft mc, float fovScale, Matrix4f result);
    default boolean validLocalOwner(Minecraft mc) { return false; }
    default Object ownershipToken() { return this; }
    default boolean deferEmissionUntilHand() { return false; }
    /** Held appearance may survive F1/chat while transient notification FX retain their old gate. */
    default boolean allowTransientFx(Minecraft mc) { return true; }
    default void sampleAnimations() {}
    default ObjectAnimationRunner animation(GunConfig.FxEmitter emitter) { return null; }
    default String diagnosticDetails(Minecraft mc) { return ""; }
}
