package net.obrienfamily.mod;

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

/** Einheitliche Ausgaben der Mod: Chat (nur lokal sichtbar), Toasts oben rechts und ein dezenter Hinweiston. */
public final class Notifier {
	private Notifier() {}

	private static final SystemToast.SystemToastId TOAST = new SystemToast.SystemToastId(7000L);

	/** „O’Brien │ “ – kurzes Präfix im Markenverlauf für einzeilige Meldungen. */
	public static MutableComponent prefix() {
		return Component.empty().append(Theme.gradient("O’Brien", Theme.BRAND, true)).append(Theme.t(" │ ", Theme.DIM));
	}

	/** Klickbarer Text, der einen /obrien-Befehl ausführt. */
	public static MutableComponent command(String label, String command, String hover) {
		return Component.literal(label).withStyle(Style.EMPTY.withColor(Theme.EMERALD).withUnderlined(true)
			.withClickEvent(new ClickEvent.RunCommand(command))
			.withHoverEvent(new HoverEvent.ShowText(Theme.t(hover, Theme.TEXT))));
	}

	/** Klickbarer Link (Minecraft fragt vor dem Öffnen im Browser nach). */
	public static MutableComponent link(String label, String url) {
		return Component.literal(label).withStyle(Style.EMPTY.withColor(Theme.INFO).withUnderlined(true)
			.withClickEvent(new ClickEvent.OpenUrl(URI.create(url)))
			.withHoverEvent(new HoverEvent.ShowText(Theme.t(url, Theme.MUTED))));
	}

	public static void chat(Component message) { line(prefix().append(message)); }
	public static void ok(String s) { chat(Theme.t("✔ " + s, Theme.OK)); }
	public static void info(String s) { chat(Theme.t(s, Theme.TEXT)); }
	public static void warn(String s) { chat(Theme.t(s, Theme.WARN)); }
	public static void error(String s) { chat(Theme.t("✖ " + s, Theme.ERR)); }

	/** Mehrzeiliger Block mit Trennlinien und Überschrift. */
	public static void block(String title, Component... lines) {
		line(Theme.rule());
		line(Component.empty().append(Theme.brand()).append(Theme.t("  " + title, Theme.CREAM)));
		for (Component c : lines) line(c);
		line(Theme.rule());
	}

	/** Zeile ohne Präfix (für Blöcke). */
	public static void line(Component message) {
		Minecraft mc = Minecraft.getInstance();
		mc.execute(() -> mc.gui.chatListener().handleSystemMessage(message, false));
	}

	public static void toast(String title, String body) {
		Minecraft mc = Minecraft.getInstance();
		mc.execute(() -> {
			if (ObrienMod.config.toasts)
				SystemToast.add(mc.gui.toastManager(), TOAST, Theme.gradient("☘ " + title, Theme.BRAND, true),
					Theme.t(body.length() > 80 ? body.substring(0, 79) + "…" : body, Theme.CREAM));
			if (ObrienMod.config.sound)
				mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.25F, 0.5F));
		});
	}
}
