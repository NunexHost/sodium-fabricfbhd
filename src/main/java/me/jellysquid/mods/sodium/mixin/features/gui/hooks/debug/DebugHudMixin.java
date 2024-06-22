package me.jellysquid.mods.sodium.mixin.features.gui.hooks.debug;

import com.google.common.collect.Lists;
import me.jellysquid.mods.sodium.client.SodiumClientMod;
import me.jellysquid.mods.sodium.client.render.SodiumWorldRenderer;
import me.jellysquid.mods.sodium.client.util.MathUtil;
import me.jellysquid.mods.sodium.client.util.NativeBuffer;
import net.minecraft.client.gui.hud.DebugHud;
import net.minecraft.util.Formatting;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;

@Mixin(DebugHud.class)
public abstract class DebugHudMixin {

    @Redirect(method = "getRightText", at = @At(value = "INVOKE", target = "Lcom/google/common/collect/Lists;newArrayList([Ljava/lang/Object;)Ljava/util/ArrayList;", remap = false))
    private ArrayList<String> redirectRightTextEarly(Object[] elements) {
        ArrayList<String> strings = Lists.newArrayList((String[]) elements);
        strings.add("");
        strings.add(String.format("%sSodium Renderer (%s)", getVersionColor(), SodiumClientMod.getVersion()));

        var renderer = SodiumWorldRenderer.instanceNullable();

        if (renderer != null) {
            strings.addAll(renderer.getDebugStrings());
        }

        for (int i = 0; i < strings.size(); i++) {
            String str = strings.get(i);

            if (str.startsWith("Allocated:")) {
                strings.add(i + 1, getNativeMemoryString());
                break;
            }
        }

        return strings;
    }

    @Unique
    private static final Formatting versionColor = calculateVersionColor();

    private static Formatting calculateVersionColor() {
        String version = SodiumClientMod.getVersion();
        if (version.contains("-local")) {
            return Formatting.RED;
        } else if (version.contains("-snapshot")) {
            return Formatting.LIGHT_PURPLE;
        } else {
            return Formatting.GREEN;
        }
    }

    @Unique
    private static String getNativeMemoryString() {
        return String.format("Off-Heap: +%dMB", MathUtil.toMib(getNativeMemoryUsage()));
    }

    @Unique
    private static long getNativeMemoryUsage() {
        return ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage().getUsed() + NativeBuffer.getTotalAllocated();
    }
}
