package com.example.orepatch.mixin;

import com.example.orepatch.OrePatchMod;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Intercepts block-update packets so OrePatch can re-evaluate positions
 * after the client level has been updated by the server.
 *
 * remap=false is required for 26.1+ because Minecraft is no longer obfuscated;
 * method names are real Mojang names at both compile-time and runtime.
 */
@Mixin(value = ClientPacketListener.class, remap = false)
public class BlockUpdateMixin {

    /** Single block update (e.g. player places/breaks a block). */
    @Inject(method = "handleBlockUpdate", at = @At("TAIL"), remap = false)
    private void orepatch_onBlockUpdate(ClientboundBlockUpdatePacket packet, CallbackInfo ci) {
        OrePatchMod mod = OrePatchMod.INSTANCE;
        if (mod == null) return;
        ClientPacketListener self = (ClientPacketListener)(Object)this;
        var level = self.getLevel();
        if (level == null) return;

        BlockPos pos = packet.getPos();
        mod.onBlockUpdate(level, pos.getX(), pos.getY(), pos.getZ());
    }

    /** Multi-block update within a single chunk section. */
    @Inject(method = "handleChunkBlocksUpdate", at = @At("TAIL"), remap = false)
    private void orepatch_onSectionBlocksUpdate(ClientboundSectionBlocksUpdatePacket packet, CallbackInfo ci) {
        OrePatchMod mod = OrePatchMod.INSTANCE;
        if (mod == null) return;
        ClientPacketListener self = (ClientPacketListener)(Object)this;
        var level = self.getLevel();
        if (level == null) return;

        packet.runUpdates((pos, state) ->
                mod.onBlockUpdate(level, pos.getX(), pos.getY(), pos.getZ()));
    }
}
