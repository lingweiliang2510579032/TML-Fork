package com.example.taczmeshloader.client;

import com.github.mcmodderanchor.simplebedrockmodel.v1.client.handler.FirstPersonRenderHandler;
import net.minecraft.world.item.ItemStack;

/** Uses the same item as the first-person renderer, including its outgoing put-away animation. */
public final class RenderedHand {
    private RenderedHand() {}

    public static ItemStack stack() {
        var animation = FirstPersonRenderHandler.getActiveAnimationInstance();
        return animation == null ? ItemStack.EMPTY : animation.currentItem();
    }
}
