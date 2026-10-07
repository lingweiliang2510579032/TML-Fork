package com.example.taczmeshloader.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.client.resource.ClientAssetsManager;
import com.tacz.guns.client.resource.pojo.display.gun.GunDisplay;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 枪皮肤目录（客户端）：从枪的 geo JSON 顶层 extras 里读 "skins" 表。
 *
 * <p>解析路径：枪物品 → IGun#getGunDisplayId → 客户端 GunDisplay → model 路径 →
 * {@code assets/<ns>/geo_models/<path>.json} → extras.skins / extras.skin_nbt_key。
 * 结果按 geo 路径缓存；没有 skins 表的枪返回空表（= 该枪不支持换皮）。</p>
 */
@OnlyIn(Dist.CLIENT)
public final class GunSkinCatalog {

    private GunSkinCatalog() {}

    public static final class Info {
        /** 皮肤 id（与 NBT 里存的值一致） */
        public final List<String> ids = new ArrayList<>();
        /** 皮肤显示名（缺省用 id） */
        public final Map<String, String> names = new HashMap<>();
        /** 存选择的 NBT 键名 */
        public String nbtKey = "MeshSkin";
    }

    private static final Map<ResourceLocation, Info> CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    /** 展示 id → geo 路径（由 GunDisplayInstanceMixin 在载入 poly_mesh 时登记）。 */
    private static final Map<ResourceLocation, ResourceLocation> DISPLAY_GEO = new java.util.concurrent.ConcurrentHashMap<>();

    /** 载入 poly_mesh 时登记（客户端读不到 data/ 下的 index，只能这样把展示和 geo 关联起来）。 */
    public static void rememberDisplayGeo(ResourceLocation displayId, ResourceLocation geo) {
        if (displayId != null && geo != null) DISPLAY_GEO.put(displayId, geo);
    }

    /** 诊断用：前 N 次查表打印"走到哪一步、读到几张皮肤"（定位皮肤表读不到的原因）。 */
    private static final java.util.concurrent.atomic.AtomicInteger DBG = new java.util.concurrent.atomic.AtomicInteger(60);

    /** "取不到 geo"最多报几条（多了是噪音，见 forStack） */
    private static final java.util.concurrent.atomic.AtomicInteger GEO_MISS_LOG =
            new java.util.concurrent.atomic.AtomicInteger(3);

    private static void dbg(String msg) {
        if (DBG.getAndDecrement() > 0) {
            org.apache.logging.log4j.LogManager.getLogger("MeshyLoader").info("[SkinDbg] {}", msg);
        }
    }

    private static String dbgOfStack(ItemStack stack) {
        IGun gun = IGun.getIGunOrNull(stack);
        if (gun == null) return "gun=null(不是枪物品)";
        ResourceLocation displayId = gun.getGunDisplayId(stack);
        GunDisplay display = displayId == null ? null : ClientAssetsManager.INSTANCE.getGunDisplay(displayId);
        return "gunId=" + gun.getGunId(stack) + " displayId=" + displayId + " display=" + (display != null)
                + " model=" + (display == null ? "-" : String.valueOf(display.getModelLocation()));
    }

    /**
     * 模型归属判定：某个 mesh 模型（由 {@code modelGeo} 指明它从哪份 geo 载入）是否属于"当前手持这把枪"。
     *
     * <p>认可两种 geo：这把枪的**主模型 geo** 与它的 **LOD geo**（LOD 同样是 poly 模型，第一人称也会被渲染，
     * 只看主 geo 会把 LOD 那条路径误判成"别人的模型"）。任一 geo 解析不出来时**返回 true（不拦）**——
     * 宁可保持原行为，也不要让特效彻底不出现。</p>
     *
     * <p>用途：开火特效的归属校验。只比物品栈挡不住"某个界面拿别的栈渲染了我们的模型"的场合
     * （如 gunsmith 的改装界面会 3D 预览枪模），那会让手持 B 枪却撒出 A 枪的特效。</p>
     */
    public static boolean modelBelongsTo(ResourceLocation modelGeo, ItemStack held) {
        if (modelGeo == null) return true;          // 模型自己不知道出处 ⇒ 无从判断，不拦
        if (held == null || held.isEmpty()) return false;
        ResourceLocation main = geoOfStack(held);
        if (main != null && main.equals(modelGeo)) return true;
        IGun gun = IGun.getIGunOrNull(held);
        if (gun == null) return false;              // 手持根本不是枪 ⇒ 不可能是它的模型
        try {
            ResourceLocation dispId = gun.getGunDisplayId(held);
            GunDisplay pojo = dispId == null ? null : ClientAssetsManager.INSTANCE.getGunDisplay(dispId);
            if (pojo != null && pojo.getGunLod() != null && pojo.getGunLod().getModelLocation() != null) {
                ResourceLocation lm = pojo.getGunLod().getModelLocation();
                ResourceLocation lodGeo = new ResourceLocation(lm.getNamespace(),
                        "geo_models/" + lm.getPath() + ".json");
                return lodGeo.equals(modelGeo);
            }
        } catch (Throwable ignored) {
        }
        // ★ fail-closed：到这里就是"判不出属于手持这把枪" ⇒ 一律拦。
        //   原来这里是 `main == null`（判不出就放行），于是"geo 解析不到"会变成漏网口——
        //   玩家实测就是手持别的资源配置/其它枪时仍然撒出关联特效的电弧（2026-09-14 截图实证）。
        return false;
    }

    /** 枪物品 → geo 路径（对外）。取不到返回 null。开火特效"归属校验"用它比对模型身份。 */
    public static ResourceLocation geoOfStack(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        IGun gun = IGun.getIGunOrNull(stack);
        if (gun != null) return geoOf(stack, gun);
        if (com.example.taczmeshloader.item.SkinWeapon.isMelee(stack)) {
            var display = me.xjqsh.lrtactical.api.LrTacticalAPI.getMeleeDisplay(stack).orElse(null);
            if (display != null && display.getModel() instanceof com.example.taczmeshloader.lrtactical.LrPolyMeshModel model)
                return model.geoLocation();
            // LRT models may not be constructed until their first render. The skin screen and
            // creative/JEI card lists need the catalog before that happens.
            ResourceLocation id = com.example.taczmeshloader.item.SkinWeapon.id(stack);
            if (id != null) {
                String model = readStringField(new ResourceLocation(id.getNamespace(),
                        "display/melee/" + id.getPath() + ".json"), "model");
                ResourceLocation modelId = model == null ? null : ResourceLocation.tryParse(model);
                if (modelId != null)
                    return new ResourceLocation(modelId.getNamespace(), "geo_models/" + modelId.getPath() + ".json");
            }
        }
        return null;
    }

    public static Info forStack(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return new Info();
        IGun gun = IGun.getIGunOrNull(stack);
        ResourceLocation geo = geoOfStack(stack);
        if (geo == null) {
            // 只报前几条：别的资源配置的枪本来就不在 TML 管辖内（一个整合包里几十把），逐把报纯属噪音
            if (gun != null && GEO_MISS_LOG.getAndDecrement() > 0) {
                dbg("取不到 geo: gunId=" + gun.getGunId(stack) + " displayId=" + gun.getGunDisplayId(stack)
                        + " 已登记展示数=" + DISPLAY_GEO.size());
            }
            return new Info();
        }
        Info info = load(geo);
        dbg("weaponId=" + com.example.taczmeshloader.item.SkinWeapon.id(stack) + " geo=" + geo + " skins=" + info.ids.size() + " ids=" + info.ids);
        return info;
    }

    /**
     * 枪物品 → geo 路径。用 TaCZ 自己的入口 {@code TimelessAPI.getGunDisplay(stack)} 拿展示实例
     * （它内部会处理 NBT 里 {@code tacz:default} 的回落），再用实例的 displayId 查载入时登记的
     * 展示→geo 映射。绕开两件事：①枪物品 NBT 的 displayId 常是 tacz:default；
     * ②客户端资源管理器读不到 {@code data/} 下的 index 文件。
     */
    private static ResourceLocation geoOf(ItemStack stack, IGun gun) {
        try {
            var instance = com.tacz.guns.api.TimelessAPI.getGunDisplay(stack).orElse(null);
            if (instance instanceof GunDisplayIdAccess access) {
                ResourceLocation resolvedDisplayId = access.taczmeshloader$displayId();
                ResourceLocation geo = DISPLAY_GEO.get(resolvedDisplayId);
                if (geo != null) return geo;
                // Gun models load lazily. Skin cards must also work before the gun is rendered.
                if (resolvedDisplayId != null) {
                    String model = readStringField(new ResourceLocation(resolvedDisplayId.getNamespace(),
                            "display/guns/" + resolvedDisplayId.getPath() + ".json"), "model");
                    ResourceLocation modelId = model == null ? null : ResourceLocation.tryParse(model);
                    if (modelId != null) {
                        geo = new ResourceLocation(modelId.getNamespace(), "geo_models/" + modelId.getPath() + ".json");
                        DISPLAY_GEO.put(resolvedDisplayId, geo);
                        return geo;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        // 退化路径：物品 NBT 里若真有显示 id，就直接按它推 geo（读 assets/ 下的展示文件）
        try {
            ResourceLocation displayId = gun.getGunDisplayId(stack);
            if (displayId != null && !"tacz:default".equals(displayId.toString())) {
                String model = readStringField(new ResourceLocation(displayId.getNamespace(),
                        "display/guns/" + displayId.getPath() + ".json"), "model");
                if (model != null) {
                    ResourceLocation loc = ResourceLocation.tryParse(model);
                    if (loc != null) {
                        return new ResourceLocation(loc.getNamespace(), "geo_models/" + loc.getPath() + ".json");
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 读一个 JSON（容忍 // 与 /* *\/ 注释）里的字符串字段，取不到返回 null。 */
    private static String readStringField(ResourceLocation json, String field) {
        try {
            var res = Minecraft.getInstance().getResourceManager().getResource(json);
            if (res.isEmpty()) return null;
            String text;
            try (var reader = new InputStreamReader(res.get().open(), java.nio.charset.StandardCharsets.UTF_8)) {
                StringBuilder sb = new StringBuilder();
                char[] buf = new char[4096];
                int n;
                while ((n = reader.read(buf)) > 0) sb.append(buf, 0, n);
                text = sb.toString();
            }
            JsonObject root = JsonParser.parseString(stripComments(text)).getAsJsonObject();
            return root.has(field) && root.get(field).isJsonPrimitive() ? root.get(field).getAsString() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 去掉 JSONC 注释（TaCZ 的展示文件里带 // 注释，Gson 严格解析会炸）。 */
    private static String stripComments(String text) {
        StringBuilder out = new StringBuilder(text.length());
        boolean inStr = false, inLine = false, inBlock = false;
        final int LF = 10, BACKSLASH = 92, QUOTE = 34, SLASH = 47, STAR = 42;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            char next = i + 1 < text.length() ? text.charAt(i + 1) : (char) 0;
            if (inLine) {
                if (c == LF) { inLine = false; out.append(c); }
                continue;
            }
            if (inBlock) {
                if (c == STAR && next == SLASH) { inBlock = false; i++; }
                continue;
            }
            if (inStr) {
                out.append(c);
                if (c == BACKSLASH) {
                    if (i + 1 < text.length()) out.append(text.charAt(++i));
                } else if (c == QUOTE) {
                    inStr = false;
                }
                continue;
            }
            if (c == QUOTE) { inStr = true; out.append(c); continue; }
            if (c == SLASH && next == SLASH) { inLine = true; i++; continue; }
            if (c == SLASH && next == STAR) { inBlock = true; i++; continue; }
            out.append(c);
        }
        return out.toString();
    }

    private static Info load(ResourceLocation geo) {
        Info cached = CACHE.get(geo);
        if (cached != null) return cached;
        Info info = new Info();
        try {
            var res = Minecraft.getInstance().getResourceManager().getResource(geo);
            if (!res.isPresent()) dbg("资源不存在: " + geo);
            if (res.isPresent()) {
                try (var reader = new InputStreamReader(res.get().open(), java.nio.charset.StandardCharsets.UTF_8)) {
                    readSkinInfo(reader, info);
                }
            }
        } catch (Throwable ignored) {
        }
        CACHE.put(geo, info);
        return info;
    }

    /** Name lookups must not build another object tree for the embedded FX meshes. */
    static void readSkinInfo(java.io.Reader input, Info info) throws java.io.IOException {
        var reader = new com.google.gson.stream.JsonReader(input);
        reader.setLenient(true); // Same JSONC tolerance as JsonParser.parseReader(Reader).
        JsonObject extras = null;
        reader.beginObject();
        while (reader.hasNext()) {
            String field = reader.nextName();
            if (!"extras".equals(field)) { reader.skipValue(); continue; }
            extras = null;
            if (reader.peek() != com.google.gson.stream.JsonToken.BEGIN_OBJECT) { reader.skipValue(); continue; }
            extras = new JsonObject();
            reader.beginObject();
            while (reader.hasNext()) {
                String key = reader.nextName();
                if ("skins".equals(key) || "skin_nbt_key".equals(key)) extras.add(key, JsonParser.parseReader(reader));
                else reader.skipValue();
            }
            reader.endObject();
        }
        reader.endObject();
        if (reader.peek() != com.google.gson.stream.JsonToken.END_DOCUMENT)
            throw new com.google.gson.JsonSyntaxException("Trailing geo content");
        fillSkinInfo(extras, info);
    }

    /** The melee FX loader already has this JSON. Retain only the tiny catalog, never the mesh tree. */
    public static void rememberGeoSkins(ResourceLocation geo, JsonObject extras) {
        if (geo == null) return;
        Info info = new Info();
        try { fillSkinInfo(extras, info); } catch (RuntimeException ignored) { }
        CACHE.put(geo, info);
    }

    private static void fillSkinInfo(JsonObject extras, Info info) {
        if (extras != null) {
            if (extras.has("skin_nbt_key") && extras.get("skin_nbt_key").isJsonPrimitive()) {
                info.nbtKey = extras.get("skin_nbt_key").getAsString();
            }
            if (extras.has("skins") && extras.get("skins").isJsonArray()) {
                JsonArray arr = extras.getAsJsonArray("skins");
                for (JsonElement el : arr) {
                    if (!el.isJsonObject()) continue;
                    JsonObject o = el.getAsJsonObject();
                    if (!o.has("id")) continue;
                    String id = o.get("id").getAsString();
                    info.ids.add(id);
                    info.names.put(id, o.has("name") ? o.get("name").getAsString() : id);
                }
            }
        }
    }

    /**
     * 改装界面用：这把枪能换皮肤才返回皮肤表，否则 null（界面据此画 TaCZ 的"斜杠"槽）。
     * "能换"= 皮肤表里至少 2 套（第 1 套是默认皮肤/原皮，没有别的可换就等于不支持）。
     */
    public static Info refittable(ItemStack gunStack) {
        if (gunStack == null || gunStack.isEmpty() || com.example.taczmeshloader.item.SkinWeapon.id(gunStack) == null) return null;
        try {
            Info info = forStack(gunStack);
            return info.ids.size() >= 2 ? info : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 资源包重载后清缓存。 */
    public static void invalidate() {
        CACHE.clear();
        DISPLAY_GEO.clear();
    }

    /** 枪 id → 皮肤表（拿这把枪的物品栈去查，命中缓存）。 */
    public static Info ofGun(ResourceLocation gunId) {
        if (gunId == null) return new Info();
        try {
            if (net.minecraftforge.fml.ModList.get().isLoaded("lrtactical")) {
                for (var index : me.xjqsh.lrtactical.api.LrTacticalAPI.getMeleeIndexes()) {
                    if (gunId.equals(index.getId())) return forStack(index.createItemStack());
                }
            }
            ItemStack stack = com.tacz.guns.api.item.builder.GunItemBuilder.create().setId(gunId).build();
            if (stack.isEmpty()) return new Info();
            return forStack(stack);
        } catch (Throwable t) {
            return new Info();
        }
    }

    /** 皮肤的显示名（查不到就回落成 id 本身）。 */
    public static String skinName(ResourceLocation gunId, String skinId) {
        if (skinId == null || skinId.isEmpty()) return "";
        Info info = ofGun(gunId);
        return info.names.getOrDefault(skinId, skinId);
    }

    /** 枪的显示名（读资源配置 index 里的 name 本地化键）。 */
    public static String gunName(ResourceLocation gunId) {
        if (gunId == null) return "";
        try {
            if (net.minecraftforge.fml.ModList.get().isLoaded("lrtactical")) {
                for (var index : me.xjqsh.lrtactical.api.LrTacticalAPI.getMeleeIndexes()) {
                    if (gunId.equals(index.getId()))
                        return net.minecraft.network.chat.Component.translatable(index.getName()).getString();
                }
            }
            return com.tacz.guns.api.TimelessAPI.getClientGunIndex(gunId).map(index -> {
                String key = index.getName();
                if (key == null || key.isEmpty()) return gunId.toString();
                String localized = net.minecraft.network.chat.Component.translatable(key).getString();
                return localized.isEmpty() || localized.equals(key) ? gunId.toString() : localized;
            }).orElse(gunId.toString());
        } catch (Throwable t) {
            return gunId.toString();
        }
    }

    /** 该枪存皮肤选择用的 NBT 键（拿物品本体查，比按 id 造栈更稳）。 */
    public static String nbtKeyOfGun(ItemStack gunStack) {
        if (gunStack == null || gunStack.isEmpty()) return "MeshSkin";
        try {
            return forStack(gunStack).nbtKey;
        } catch (Throwable t) {
            return "MeshSkin";
        }
    }

    /** 该枪存皮肤选择用的 NBT 键（geo extras 可覆盖，默认 MeshSkin）。 */
    public static String nbtKey(ResourceLocation gunId) {
        return ofGun(gunId).nbtKey;
    }
}
