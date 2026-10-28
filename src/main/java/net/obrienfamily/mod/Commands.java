package net.obrienfamily.mod;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.ArrayList;
import java.util.List;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

/** Client-Befehle (/obrien …). Sie laufen nur auf deinem PC und werden nicht an den Server geschickt. */
public final class Commands {
	private Commands() {}

	public static void register() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, ctx) -> dispatcher.register(net.obrienfamily.mod.kisten.KistenBefehle.attach(literal("obrien"))
			.executes(c -> help())
			.then(literal("hilfe").executes(c -> help()))
			.then(literal("key").then(argument("schluessel", StringArgumentType.greedyString()).executes(c -> setKey(StringArgumentType.getString(c, "schluessel")))))
			.then(literal("status").executes(c -> async(Commands::status)))
			.then(literal("bestellungen").executes(c -> { OrdersScreen.open(null); return 1; }))
			.then(literal("bestellung").then(argument("nummer", StringArgumentType.word()).executes(c -> { OrdersScreen.open(StringArgumentType.getString(c, "nummer").toUpperCase(java.util.Locale.ROOT)); return 1; })))
			.then(literal("aufgaben").executes(c -> async(Commands::tasks)))
			.then(literal("board").executes(c -> { BoardScreen.open(); return 1; }))
			.then(literal("update").executes(c -> async(ObrienMod.updater::install)))
			.then(literal("test").executes(c -> { Notifier.toast("Test-Benachrichtigung", "So erscheinen neue Anfragen und Aufgaben."); Notifier.ok("Test-Benachrichtigung gesendet."); return 1; }))
			.then(literal("ton").then(literal("an").executes(c -> toggle("sound", true))).then(literal("aus").executes(c -> toggle("sound", false))))
			.then(literal("toasts").then(literal("an").executes(c -> toggle("toasts", true))).then(literal("aus").executes(c -> toggle("toasts", false))))
		));
	}

	private static int async(Runnable r) {
		ObrienMod.worker.execute(() -> {
			try { r.run(); } catch (Throwable t) { ObrienMod.LOG.warn("[O'Brien] Befehl fehlgeschlagen", t); Notifier.error("Unerwarteter Fehler (" + t.getClass().getSimpleName() + ")."); }
		});
		return 1;
	}

	private static MutableComponent row(String cmd, String what) {
		return Component.empty().append(Theme.t(" ▸ ", Theme.DARK)).append(Theme.t(cmd, Theme.GOLD)).append(Theme.t("  " + what, Theme.MUTED));
	}

	private static int help() {
		Notifier.block("Befehle · v" + ObrienMod.version(),
			Theme.t(" Kistenfinder", Theme.CREAM),
			row("/obrien findkiste [item] [all]", "Lagerorte suchen, z. B. diamond oder Eisenbarren"),
			row("/obrien setkiste", "anvisierte Kiste für Item in der Hand registrieren (Admin)"),
			row("/obrien removekiste [item]", "Zuordnung der anvisierten Kiste entfernen (Admin)"),
			row("/obrien listkisten [item|nether|seite]", "alle Registrierungen, seitenweise"),
			row("/obrien infokiste", "Infos zur anvisierten Kiste"),
			row("/obrien bestand [item]", "Gesamtbestand aller Lagerkisten (Alias: lager)"),
			row("/obrien bestand name|niedrig|ungeprüft", "Übersicht sortieren/filtern"),
			row("/obrien bestand <item> min <zahl|aus>", "Mindestbestand festlegen (Admin)"),
			Theme.t(" Shop & Team", Theme.CREAM),
			row("/obrien bestellungen", "Bestellübersicht (Taste B)"),
			row("/obrien board", "Family Task Board (Taste J)"),
			row("/obrien aufgaben", "deine offenen Aufgaben im Chat"),
			row("/obrien status", "Verbindung und Version"),
			row("/obrien update", "neue Version installieren"),
			row("/obrien key <Schlüssel>", "Mod mit deinem Konto verbinden"),
			row("/obrien ton | toasts an|aus", "Hinweiston / Einblendungen"),
			row("/obrien test", "Test-Benachrichtigung"));
		return 1;
	}

	private static int setKey(String key) {
		String k = key.trim();
		if (!k.matches("obr_[A-Za-z0-9_-]{20,80}")) {
			Notifier.error("Das ist kein Mod-Schlüssel – er beginnt mit „obr_“.");
			return 0;
		}
		ObrienMod.config.token = k;
		ObrienMod.config.save();
		ObrienMod.resetSession();
		return async(() -> {
			try {
				JsonObject r = ObrienMod.api.get("/api/mod/me");
				ObrienMod.refreshScope();
				if ("admin".equals(r.get("scope").getAsString())) Notifier.ok("Verbunden als " + r.get("name").getAsString() + " – Benachrichtigungen sind aktiv.");
				else Notifier.ok("Verbunden als " + r.get("name").getAsString() + " – Spieler-Schlüssel: Kistensuche ist aktiv (/obrien findkiste).");
			} catch (ShopApi.ApiException e) {
				Notifier.error("Schlüssel gespeichert, aber: " + e.getMessage());
			}
		});
	}

	private static int toggle(String what, boolean on) {
		if (what.equals("sound")) ObrienMod.config.sound = on; else ObrienMod.config.toasts = on;
		ObrienMod.config.save();
		Notifier.ok((what.equals("sound") ? "Hinweiston " : "Einblendungen ") + (on ? "eingeschaltet." : "ausgeschaltet."));
		return 1;
	}

	private static MutableComponent kv(String k, Component v) {
		return Component.empty().append(Theme.t(" " + k + "  ", Theme.DIM)).append(v);
	}

	private static void status() {
		List<Component> lines = new ArrayList<>();
		lines.add(kv("Version", Theme.t("v" + ObrienMod.version(), Theme.TEXT)));
		lines.add(kv("Website", Theme.t(ObrienMod.config.base().replaceFirst("^https?://", ""), Theme.TEXT)));
		if (!ObrienMod.config.hasToken()) {
			lines.add(kv("Konto  ", Theme.t("nicht verbunden – /obrien key <Schlüssel>", Theme.WARN)));
		} else {
			try {
				JsonObject r = ObrienMod.api.get("/api/mod/me");
				boolean admin = "admin".equals(r.get("scope").getAsString());
				lines.add(kv("Konto  ", Theme.t("✔ verbunden als " + r.get("name").getAsString() + (admin ? " (Admin)" : " (Spieler – nur Kistensuche)"), Theme.OK)));
				ObrienMod.refreshScope();
			} catch (ShopApi.ApiException e) {
				lines.add(kv("Konto  ", Theme.t("✖ " + e.getMessage(), Theme.ERR)));
			}
		}
		ObrienMod.updater.check(false);
		String staged = ObrienMod.updater.stagedVersion();
		if (!staged.isEmpty()) lines.add(kv("Update ", Theme.t("v" + staged + " wird beim Beenden installiert", Theme.OK)));
		else if (ObrienMod.updater.hasUpdate()) lines.add(kv("Update ", Component.empty().append(Theme.t("v" + ObrienMod.updater.availableVersion() + " verfügbar  ", Theme.WARN)).append(Notifier.command("[Installieren]", "/obrien update", "Neue Version herunterladen und prüfen"))));
		else lines.add(kv("Update ", Theme.t("aktuell", Theme.MUTED)));
		Notifier.block("Status", lines.toArray(new Component[0]));
	}

	private static void tasks() {
		try {
			JsonObject r = ObrienMod.api.get("/api/mod/tasks");
			boolean mine = "mine".equals(r.get("scope").getAsString());
			JsonArray list = r.getAsJsonArray("tasks");
			List<Component> lines = new ArrayList<>();
			if (list.isEmpty()) lines.add(Theme.t(" Nichts offen – stark!", Theme.MUTED));
			for (JsonElement el : list) {
				JsonObject t = el.getAsJsonObject();
				String p = t.get("priority").getAsString();
				int pc = switch (p) { case "critical" -> Theme.ERR; case "high" -> Theme.GOLD; case "normal" -> Theme.INFO; default -> Theme.MUTED; };
				String sym = switch (p) { case "critical" -> " ◆ "; case "high" -> " ▲ "; case "normal" -> " ◇ "; default -> " ▽ "; };
				String status = switch (t.get("status").getAsString()) { case "todo" -> "To Do"; case "doing" -> "In Arbeit"; case "review" -> "Review"; default -> "Backlog"; };
				MutableComponent line = Component.empty().append(Theme.t(sym, pc)).append(Theme.t(t.get("title").getAsString(), Theme.CREAM)).append(Theme.t("  " + status, Theme.DIM));
				if (!t.get("due_date").isJsonNull()) {
					String[] d = t.get("due_date").getAsString().split("-");
					boolean od = !t.get("overdue").isJsonNull() && t.get("overdue").getAsBoolean();
					line.append(Theme.t(od ? "  überfällig seit " + d[2] + "." + d[1] + "." : "  fällig " + d[2] + "." + d[1] + ".", od ? Theme.ERR : Theme.MUTED));
				}
				lines.add(line);
			}
			lines.add(Component.empty().append(Theme.t(" ", Theme.DIM)).append(Notifier.command("[Board öffnen]", "/obrien board", "Family Task Board im Spiel öffnen (Taste J)"))
				.append(Theme.t("  ", Theme.DIM)).append(Notifier.link("[Im Browser]", ObrienMod.config.base() + "/admin")));
			Notifier.block((mine ? "Deine offenen Aufgaben" : "Dringend & überfällig") + " (" + r.get("open").getAsInt() + ")", lines.toArray(new Component[0]));
		} catch (ShopApi.ApiException e) {
			Notifier.error(e.getMessage());
		}
	}
}
