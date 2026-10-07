package com.example.taczmeshloader.network;

import com.example.taczmeshloader.TacZMeshLoaderMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

/** TML 自己的网络通道（目前只有"设置枪皮肤"）。 */
public final class GunSkinNetwork {

    private GunSkinNetwork() {}

    private static final String VERSION = "1";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(TacZMeshLoaderMod.MOD_ID, "main"),
            () -> VERSION, VERSION::equals, VERSION::equals);

    public static void register() {
        int id = 0;
        CHANNEL.registerMessage(id++, SetGunSkinMessage.class,
                SetGunSkinMessage::encode, SetGunSkinMessage::decode, SetGunSkinMessage::handle);
    }
}
