package com.example.taczmeshloader.render;

import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import java.util.LinkedHashMap;
import java.util.Map;

/** Explicit opt-in source material, with bounded Minecraft lighting adaptation. */
public final class MeleeSurfaceMaterial {
    public final ResourceLocation[] textures = new ResourceLocation[4];
    public final float[] colorB, colorR, colorFlash;
    public final int toneMap;
    public final float global, interval, pulse, roughMultiply, roughPower, metallic, specular;
    public final float ambientMin, ambientMax, directMin, directMax, exposure;
    private MeleeSurfaceMaterial(JsonObject o) {
        String[] names={"texture_d","texture_n","texture_s","texture_em"};
        for(int i=0;i<4;i++) {
            textures[i]=ResourceLocation.tryParse(o.get(names[i]).getAsString());
            if(textures[i]==null) throw new IllegalArgumentException("Invalid surface texture");
        }
        colorB=color(o,"color_b"); colorR=color(o,"color_r"); colorFlash=color(o,"color_flash");
        global=value(o,"global",1,0,200); interval=value(o,"flash_interval",1,0,100);
        pulse=value(o,"flash_pulse",0,0,100);
        roughMultiply=value(o,"roughness_multiply",1,0,4); roughPower=value(o,"roughness_power",1,.01f,8);
        metallic=value(o,"metallic",1,0,4); specular=value(o,"specular",.4f,0,1);
        ambientMin=value(o,"ambient_min",.16f,0,1); ambientMax=value(o,"ambient_max",.30f,ambientMin,1);
        directMin=value(o,"direct_min",.28f,0,2); directMax=value(o,"direct_max",.75f,directMin,2);
        exposure=value(o,"exposure",1,.01f,4);
        String mapping=o.has("tone_map")?o.get("tone_map").getAsString():"max_channel";
        toneMap=switch(mapping) {
            case "max_channel" -> 0;
            case "blue_blade_shoulder" -> 1;
            default -> throw new IllegalArgumentException("Unknown surface tone_map");
        };
    }
    private static float value(JsonObject o,String k,float d,float lo,float hi) {
        float x=o.has(k)?o.get(k).getAsFloat():d;
        if(!Float.isFinite(x)||x<lo||x>hi) throw new IllegalArgumentException("Invalid surface "+k);
        return x;
    }
    private static float[] color(JsonObject o,String k) {
        var a=o.getAsJsonArray(k);
        if(a==null||a.size()<3) throw new IllegalArgumentException("Missing surface "+k);
        float[] c=new float[3];
        for(int i=0;i<3;i++) { c[i]=a.get(i).getAsFloat(); if(!Float.isFinite(c[i])||c[i]<0||c[i]>100) throw new IllegalArgumentException(k); }
        return c;
    }
    public static Map<String,MeleeSurfaceMaterial> parse(JsonObject extras) {
        Map<String,MeleeSurfaceMaterial> out=new LinkedHashMap<>();
        if(!extras.has("surface")) return out;
        JsonObject surface=extras.getAsJsonObject("surface");
        if(!surface.has("skins")) return out;
        for(var e:surface.getAsJsonObject("skins").entrySet()) out.put(e.getKey(),new MeleeSurfaceMaterial(e.getValue().getAsJsonObject()));
        return out;
    }
}
