package net.obrienfamily.mod;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

/** Client-Befehle (/obrien …). Sie laufen nur auf deinem PC und werden nicht an den Server geschickt. */
public final class Commands {
	private Commands() {}
	private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("dd.MM. HH:mm");

	public static void register() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, ctx) -> dispatcher.register(literal("obrien")
			.executes(c -> help())
			.then(literal("hilfe").executes(c -> help()))
			.then(literal("key").then(argument("schluessel", StringArgumentType.greedyString()).executes(c -> setKey(StringArgumentType.getString(c, "schluessel")))))
			.then(literal("status").executes(c -> async(Commands::status)))
			.then(literal("bestellungen").executes(c -> async(Commands::orders)))
			.then(literal("aufgaben").executes(c -> async(Commands::tasks)))
			.then(literal("update").executes(c -> async(ObrienMod.updater::install)))
			.then(literal("test").executes(c -> { Notifier.toast("O’Brien Family Shop", "So sehen Benachrichtigungen aus."); Notifier.chat(Notifier.text("Test-Benachrichtigung ✔", ChatFormatting.GREEN)); return 1; }))
			.then(literal("ton").then(literal("an").executes(c -> toggle("sound", true))).then(literal("aus").executes(c -> toggle("sound", false))))
			.then(literal("toasts").then(literal("an").executes(c -> toggle("toasts", true))).then(literal("aus").executes(c -> toggle("toasts", false))))
		));
	}

	private static int async(Runnable r) {
		ObrienMod.worker.execute(() -> {
			try { r.run(); } catch (Throwable t) { ObrienMod.LOG.warn("[O'Brien] Befehl fehlgeschlagen", t); Notifier.chat(Notifier.text("Fehler: " + t.getClass().getSimpleName(), ChatFormatting.RED)); }
		});
		return 1;
	}

	private static int help() {
		Notifier.chat(Notifier.text("O’Brien Family Shop v" + ObrienMod.version(), ChatFormatting.WHITE));
		String[][] rows = {
			{"/obrien bestellungen", "offene Bestellanfragen"}, {"/obrien aufgaben", "deine offenen Aufgaben"},
			{"/obrien status", "Verbindung und Version"}, {"/obrien update", "neueste Version herunterladen"},
			{"/obrien key <Schlüssel>", "Mod-Schlüssel eingeben"}, {"/obrien ton an|aus", "Hinweiston"},
			{"/obrien toasts an|aus", "Einblendungen oben rechts"}, {"/obrien test", "Test-Benachrichtigung"}};
		for (String[] r : rows) Notifier.chatLine(Component.empty().append(Notifier.text("  " + r[0], ChatFormatting.YELLOW)).append(Notifier.text(" – " + r[1], ChatFormatting.GRAY)));
		return 1;
	}

	private static int setKey(String key) {
		String k = key.trim();
		if (!k.matches("obr_[A-Za-z0-9_-]{20,80}")) {
			Notifier.chat(Notifier.text("Das sieht nicht wie ein Mod-Schlüssel aus (er beginnt mit „obr_“).", ChatFormatting.RED));
			return 0;
		}
		ObrienMod.config.token = k;
		ObrienMod.config.save();
		ObrienMod.resetSession();
		return async(() -> {
			try {
				JsonObject r = ObrienMod.api.get("/api/mod/feed");
				Notifier.chat(Notifier.text("Verbunden als " + r.get("name").getAsString() + " ✔ Neuigkeiten erscheinen ab jetzt automatisch.", ChatFormatting.GREEN));
			} catch (ShopApi.ApiException e) {
				Notifier.chat(Notifier.text("Schlüssel gespeichert, aber: " + e.getMessage(), ChatFormatting.RED));
			}
		});
	}

	private static int toggle(String what, boolean on) {
		if (what.equals("sound")) ObrienMod.config.sound = on; else ObrienMod.config.toasts = on;
		ObrienMod.config.save();
		Notifier.chat(Notifier.text((what.equals("sound") ? "Hinweiston " : "Einblendungen ") + (on ? "an" : "aus") + ".", ChatFormatting.GREEN));
		return 1;
	}

	private static void status() {
		Notifier.chat(Notifier.text("Version v" + ObrienMod.version() + " · Website " + ObrienMod.config.base() + " · Abruf alle " + ObrienMod.config.interval() + " s", ChatFormatting.WHITE));
		if (!ObrienMod.config.hasToken()) { Notifier.chatLine(Notifier.text("  Kein Mod-Schlüssel eingegeben (/obrien key <Schlüssel>).", ChatFormatting.YELLOW)); return; }
		try {
			JsonObject r = ObrienMod.api.get("/api/mod/feed");
			Notifier.chatLine(Notifier.text("  Verbunden als " + r.get("name").getAsString() + " ✔", ChatFormatting.GREEN));
		} catch (ShopApi.ApiException e) {
			Notifier.chatLine(Notifier.text("  " + e.getMessage(), ChatFormatting.RED));
		}
		ObrienMod.updater.check(false);
		String staged = ObrienMod.updater.stagedVersion();
		if (!staged.isEmpty()) Notifier.chatLine(Notifier.text("  Update v" + staged + " wird beim Beenden installiert.", ChatFormatting.GREEN));
		else if (ObrienMod.updater.hasUpdate()) Notifier.chatLine(Component.empty().append(Notifier.text("  Update v" + ObrienMod.updater.availableVersion() + " verfügbar ", ChatFormatting.YELLOW)).append(Notifier.command("[herunterladen]", "/obrien update", "Update herunterladen")));
		else Notifier.chatLine(Notifier.text("  Mod ist aktuell.", ChatFormatting.GRAY));
	}

	private static String when(JsonObject o, String key) {
		try { return OffsetDateTime.parse(o.get(key).getAsString()).atZoneSameInstant(ZoneId.systemDefault()).format(WHEN); } catch (Exception e) { return ""; }
	}

	private static String num(JsonElement e) {
		double d = e.getAsDouble();
		return String.format(java.util.Locale.GERMANY, d == Math.rint(d) ? "%,.0f" : "%,.2f", d);
	}

	private static void orders() {
		try {
			JsonObject r = ObrienMod.api.get("/api/mod/orders");
			JsonArray list = r.getAsJsonArray("orders");
			Notifier.chat(Component.empty().append(Notifier.text("Offene Bestellanfragen: " + r.get("open").getAsInt(), ChatFormatting.WHITE, ChatFormatting.BOLD))
				.append(Notifier.text(list.size() < r.get("open").getAsInt() ? " (die neuesten " + list.size() + ") " : " ", ChatFormatting.GRAY))
				.append(Notifier.link("[Admin-Bereich]", ObrienMod.config.base() + "/admin")));
			if (list.isEmpty()) Notifier.chatLine(Notifier.text("  Keine offenen Bestellanfragen.", ChatFormatting.GRAY));
			for (JsonElement el : list) {
				JsonObject o = el.getAsJsonObject();
				ChatFormatting sc = o.get("status").getAsString().equals("Neu") ? ChatFormatting.GOLD : ChatFormatting.GRAY;
				Notifier.chatLine(Component.empty().append(Notifier.text(" " + o.get("number").getAsString(), ChatFormatting.YELLOW, ChatFormatting.BOLD))
					.append(Notifier.text(" · " + o.get("mc_name").getAsString() + " · ", ChatFormatting.WHITE))
					.append(Notifier.text(o.get("status").getAsString(), sc)).append(Notifier.text(" · " + when(o, "created_at"), ChatFormatting.GRAY)));
				JsonArray lines = o.getAsJsonArray("lines");
				int i = 0;
				for (JsonElement le : lines) {
					if (++i > 4) { Notifier.chatLine(Notifier.text("    … und " + (lines.size() - 4) + " weitere Position(en)", ChatFormatting.GRAY)); break; }
					JsonObject l = le.getAsJsonObject();
					boolean fixed = "fixed".equals(l.get("type").getAsString()) && !l.get("price").isJsonNull();
					MutableComponent line = Component.empty().append(Notifier.text("    " + num(l.get("qty")) + "× " + l.get("name").getAsString(), ChatFormatting.WHITE));
					line.append(fixed ? Notifier.text(" – " + num(l.get("total")) + " × " + l.get("cur").getAsString(), ChatFormatting.GREEN)
						: Notifier.text(" – Preis nach Absprache", ChatFormatting.GRAY, ChatFormatting.ITALIC));
					Notifier.chatLine(line);
				}
				JsonArray totals = o.getAsJsonArray("totals");
				if (!totals.isEmpty()) {
					StringBuilder sb = new StringBuilder("    Richtwert: ");
					for (int t = 0; t < totals.size(); t++) {
						JsonObject tt = totals.get(t).getAsJsonObject();
						if (t > 0) sb.append(" · ");
						sb.append(num(tt.get("amount"))).append(" × ").append(tt.get("name").getAsString());
					}
					Notifier.chatLine(Notifier.text(sb.toString(), ChatFormatting.DARK_GREEN));
				}
			}
		} catch (ShopApi.ApiException e) {
			Notifier.chat(Notifier.text(e.getMessage(), ChatFormatting.RED));
		}
	}

	private static void tasks() {
		try {
			JsonObject r = ObrienMod.api.get("/api/mod/tasks");
			boolean mine = "mine".equals(r.get("scope").getAsString());
			Notifier.chat(Component.empty().append(Notifier.text((mine ? "Deine offenen Aufgaben: " : "Dringende und überfällige Aufgaben: ") + r.get("open").getAsInt() + " ", ChatFormatting.WHITE, ChatFormatting.BOLD))
				.append(Notifier.link("[Aufgabenboard]", ObrienMod.config.base() + "/admin")));
			JsonArray list = r.getAsJsonArray("tasks");
			if (list.isEmpty()) Notifier.chatLine(Notifier.text("  Nichts offen – stark!", ChatFormatting.GRAY));
			for (JsonElement el : list) {
				JsonObject t = el.getAsJsonObject();
				String p = t.get("priority").getAsString();
				ChatFormatting pc = switch (p) { case "critical" -> ChatFormatting.RED; case "high" -> ChatFormatting.GOLD; case "normal" -> ChatFormatting.AQUA; default -> ChatFormatting.GRAY; };
				String pl = switch (p) { case "critical" -> "◆ Kritisch"; case "high" -> "▲ Hoch"; case "normal" -> "◇ Normal"; default -> "▽ Niedrig"; };
				MutableComponent line = Component.empty().append(Notifier.text("  " + pl + " ", pc)).append(Notifier.text(t.get("title").getAsString(), ChatFormatting.WHITE));
				String status = switch (t.get("status").getAsString()) { case "todo" -> "To Do"; case "doing" -> "In Arbeit"; case "review" -> "Review"; default -> "Backlog"; };
				line.append(Notifier.text(" · " + status, ChatFormatting.GRAY));
				if (!t.get("due_date").isJsonNull()) {
					String[] d = t.get("due_date").getAsString().split("-");
					boolean od = !t.get("overdue").isJsonNull() && t.get("overdue").getAsBoolean();
					line.append(Notifier.text(" · fällig " + d[2] + "." + d[1] + "." + (od ? " (überfällig!)" : ""), od ? ChatFormatting.RED : ChatFormatting.GRAY));
				}
				Notifier.chatLine(line);
			}
		} catch (ShopApi.ApiException e) {
			Notifier.chat(Notifier.text(e.getMessage(), ChatFormatting.RED));
		}
	}
}
