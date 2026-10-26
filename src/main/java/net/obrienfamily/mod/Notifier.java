package net.obrienfamily.mod;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.sounds.SoundEvents;

import java.net.URI;

/** Ausgaben im Spiel: Chatzeilen (nur lokal sichtbar), Toasts oben rechts und ein dezenter Hinweiston. */
public final class Notifier {
	private Notifier() {}

	private static final SystemToast.SystemToastId TOAST = new SystemToast.SystemToastId(7000L);

	public static MutableComponent prefix() {
		return Component.literal("[O’Brien] ").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD);
	}

	public static MutableComponent text(String s, ChatFormatting... f) {
		return Component.literal(s).withStyle(f);
	}

	/** Klickbarer Text, der einen /obrien-Befehl ausführt. */
	public static MutableComponent command(String label, String command, String hover) {
		return Component.literal(label).withStyle(Style.EMPTY.withColor(ChatFormatting.GREEN).withUnderlined(true)
			.withClickEvent(new ClickEvent.RunCommand(command))
			.withHoverEvent(new HoverEvent.ShowText(Component.literal(hover))));
	}

	/** Klickbarer Link (Minecraft fragt vor dem Öffnen nach). */
	public static MutableComponent link(String label, String url) {
		return Component.literal(label).withStyle(Style.EMPTY.withColor(ChatFormatting.AQUA).withUnderlined(true)
			.withClickEvent(new ClickEvent.OpenUrl(URI.create(url)))
			.withHoverEvent(new HoverEvent.ShowText(Component.literal(url))));
	}

	/** Chatnachricht mit Präfix – nur für dich sichtbar, wird nicht an den Server gesendet. */
	public static void chat(Component message) {
		Minecraft mc = Minecraft.getInstance();
		mc.execute(() -> mc.gui.chatListener().handleSystemMessage(prefix().append(message), false));
	}

	public static void chatLine(Component message) {
		Minecraft mc = Minecraft.getInstance();
		mc.execute(() -> mc.gui.chatListener().handleSystemMessage(message, false));
	}

	public static void toast(String title, String body) {
		Minecraft mc = Minecraft.getInstance();
		mc.execute(() -> {
			if (ObrienMod.config.toasts)
				SystemToast.add(mc.gui.toastManager(), TOAST, Component.literal(title), Component.literal(body.length() > 80 ? body.substring(0, 79) + "…" : body));
			if (ObrienMod.config.sound)
				mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.25F, 0.5F));
		});
	}
}
