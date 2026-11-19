package com.replaymod.replay.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.replaymod.recording.mixin.NetworkManagerAccessor;
import com.replaymod.replay.ReplayHandler;
import com.replaymod.replay.ReplayModReplay;
import com.replaymod.replay.ReplaySender;
import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.ClientConnection;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.Team;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Ignores only a stale team member removal on the current local replay connection.
 */
@Mixin(ClientPlayNetworkHandler.class)
public abstract class Mixin_IgnoreMismatchedReplayTeamRemoval {
    @WrapOperation(
            method = "onTeam(Lnet/minecraft/network/packet/s2c/play/TeamS2CPacket;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/scoreboard/Scoreboard;removeScoreHolderFromTeam(Ljava/lang/String;Lnet/minecraft/scoreboard/Team;)V"
            ),
            remap = true,
            require = 1,
            expect = 1,
            allow = 1
    )
    private void replaymod$ignoreMismatchedReplayTeamRemoval(
            Scoreboard scoreboard, String member, Team targetTeam, Operation<Void> original) {
        if (replaymod$isCurrentReplayConnection() && scoreboard.getScoreHolderTeam(member) != targetTeam) {
            // Keep a member's current other team (or absence) untouched; the packet loop continues.
            return;
        }
        original.call(scoreboard, member, targetTeam);
    }

    @Unique
    private boolean replaymod$isCurrentReplayConnection() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || !mc.isOnThread()) {
            return false;
        }

        ClientPlayNetworkHandler self = (ClientPlayNetworkHandler) (Object) this;
        if (mc.getNetworkHandler() != self) {
            return false;
        }
        ClientConnection connection = self.getConnection();
        if (connection == null || connection.getPacketListener() != self
                || !(connection instanceof NetworkManagerAccessor accessor)) {
            return false;
        }

        Channel channel = accessor.getChannel();
        if (!(channel instanceof EmbeddedChannel) || !channel.isOpen() || !channel.isActive()) {
            return false;
        }

        ReplayModReplay replayMod = ReplayModReplay.instance;
        ReplayHandler replayHandler = replayMod == null ? null : replayMod.getReplayHandler();
        if (replayHandler == null) {
            return false;
        }
        ReplaySender sender = replayHandler.getReplaySender();
        ChannelPipeline pipeline = channel.pipeline();
        return sender != null
                && pipeline.get("packet_handler") == connection
                && pipeline.get(ReplayHandler.PACKET_HANDLER_NAME) == sender;
    }
}
