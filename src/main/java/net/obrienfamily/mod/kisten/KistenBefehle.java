package net.obrienfamily.mod.kisten;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.world.item.ItemStack;
import net.obrienfamily.mod.Notifier;
import net.obrienfamily.mod.ObrienMod;
import net.obrienfamily.mod.ShopApi;
import net.obrienfamily.mod.Theme;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

/**
 * Lagersystem: /obrien lager (Bereiche verwalten, Bestand) · findkiste · bestand · listkisten · infokiste.
 * Grundsatz: Die Datenbank kennt registrierte Lagerorte. Ob eine Kiste noch steht, prüft die Mod nur dort, wo der Chunk gerade
 * geladen ist; Bestände nur, wenn ein Admin die Kiste selbst geöffnet hat. Alles andere wird als „ungeprüft“ angezeigt.
 */
public final class KistenBefehle {
	private KistenBefehle() {}

	/** Ereignisse des Moduls (Hervorhebung, Bestandszählung). */
	public static void init() {
		ClientTickEvents.END_CLIENT_TICK.register(c -> { Hervorhebung.tick(); KistenWaechter.tick(); });
		Bestand.init();
		KistenWaechter.init();
	}

	/** Hängt die Lagerbefehle an /obrien an. */
	public static LiteralArgumentBuilder<FabricClientCommandSource> attach(LiteralArgumentBuilder<FabricClientCommandSource> root) {
		return root
			.then(literal("findkiste").executes(c -> find(""))
				.then(argument("item", StringArgumentType.greedyString()).suggests((c, b) -> ItemSuche.vorschlagen(b, false))
					.executes(c -> find(StringArgumentType.getString(c, "item")))))
			.then(literal("setkiste").executes(c -> abgeloest()))
			.then(literal("removekiste").executes(c -> abgeloest()).then(argument("x", StringArgumentType.greedyString()).executes(c -> abgeloest())))
			.then(literal("listkisten").executes(c -> list(""))
				.then(argument("filter", StringArgumentType.greedyString()).suggests((c, b) -> ItemSuche.vorschlagen(b, false))
					.executes(c -> list(StringArgumentType.getString(c, "filter")))))
			.then(literal("infokiste").executes(c -> info()))
			.then(bestandBefehl(literal("bestand")))
			.then(bestandBefehl(lagerBefehl()));
	}

	/** /obrien lager: Bereiche verwalten; ohne Unterbefehl bzw. mit Item wie /obrien bestand. */
	private static LiteralArgumentBuilder<FabricClientCommandSource> lagerBefehl() {
		LiteralArgumentBuilder<FabricClientCommandSource> l = literal("lager");
		l.then(literal("erstellen").then(argument("name", StringArgumentType.greedyString()).executes(c -> erstellen(StringArgumentType.getString(c, "name")))));
		for (int w = 1; w <= 2; w++) {
			int welcher = w;
			l.then(literal("punkt" + w).executes(c -> punkt(welcher, null))
				.then(argument("name", StringArgumentType.greedyString()).suggests((c, b) -> namen(b)).executes(c -> punkt(welcher, StringArgumentType.getString(c, "name")))));
		}
		l.then(literal("liste").executes(c -> bereichListe()));
		for (String w : List.of("info", "zeigen"))
			l.then(literal(w).executes(c -> bereichInfo(null))
				.then(argument("name", StringArgumentType.greedyString()).suggests((c, b) -> namen(b)).executes(c -> bereichInfo(StringArgumentType.getString(c, "name")))));
		l.then(literal("umbenennen").then(argument("alt", StringArgumentType.string()).suggests((c, b) -> namen(b))
			.then(argument("neu", StringArgumentType.greedyString()).executes(c -> umbenennen(StringArgumentType.getString(c, "alt"), StringArgumentType.getString(c, "neu"))))));
		for (String w : List.of("löschen", "loeschen"))
			l.then(literal(w).then(argument("name", StringArgumentType.greedyString()).suggests((c, b) -> namen(b)).executes(c -> loeschen(StringArgumentType.getString(c, "name")))));
		return l;
	}

	private static LiteralArgumentBuilder<FabricClientCommandSource> bestandBefehl(LiteralArgumentBuilder<FabricClientCommandSource> b) {
		return b.executes(c -> bestand(""))
			.then(argument("item", StringArgumentType.greedyString()).suggests((c, sb) -> {
				// Erstes Wort: Optionen der Übersicht zusätzlich zu den Items
				String rest = sb.getRemaining().toLowerCase(Locale.ROOT);
				if (!rest.contains(" ")) for (String w : List.of("name", "niedrig", "ungeprüft")) if (w.startsWith(rest)) sb.suggest(w);
				return ItemSuche.vorschlagen(sb, false);
			}).executes(c -> bestand(StringArgumentType.getString(c, "item"))));
	}

	// ---------- Gemeinsames ----------

	private record Ort(String welt, String dim, double x, double y, double z) {}

	/** Welt, Dimension und Position – oder null mit Fehlermeldung. */
	private static Ort ort() {
		Minecraft mc = Minecraft.getInstance();
		if (!ObrienMod.config().hasToken()) { Notifier.warn("Noch nicht verbunden – gib zuerst /obrien key <Schlüssel> ein."); return null; }
		if (mc.player == null || mc.level == null) { Notifier.error("Du bist in keiner Welt."); return null; }
		String welt = KistenOrt.welt();
		if (welt == null) { Notifier.error("Die Server-/Weltzuordnung konnte nicht bestimmt werden. Ohne sie wird nichts gespeichert oder gesucht."); return null; }
		return new Ort(welt, KistenOrt.dimension(), mc.player.getX(), mc.player.getY(), mc.player.getZ());
	}

	private static void async(Runnable r) {
		KistenApi.IO.execute(() -> {
			try { r.run(); } catch (Throwable t) { ObrienMod.LOG.warn("[O'Brien] Kistenfinder", t); Notifier.error("Unerwarteter Fehler (" + t.getClass().getSimpleName() + ")."); }
		});
	}

	private static void main(Runnable r) { Minecraft.getInstance().execute(r); }

	/** Verständliche Meldung für Backend-Fehler (keine falschen Erfolgsmeldungen). */
	private static String fehler(ShopApi.ApiException e, String was) {
		if (e.status == 0) return was + " Keine Verbindung zur Website – bitte versuche es gleich erneut.";
		if (e.status == 403) return "Dafür brauchst du einen Admin-Schlüssel. Mit einem Spieler-Schlüssel kannst du nur suchen.";
		return was + " " + e.getMessage();
	}

	/** Nur abweisen, wenn sicher ein Spieler-Schlüssel eingetragen ist – die eigentliche Prüfung macht immer der Server. */
	private static boolean admin() {
		if (ObrienMod.scope() == null) ObrienMod.refreshScope();
		return !"chests".equals(ObrienMod.scope());
	}

	static String str(JsonObject o, String k) { return o != null && o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : ""; }

	static BlockPos pos(JsonElement e) {
		if (e == null || e.isJsonNull()) return null;
		JsonArray a = e.getAsJsonArray();
		return new BlockPos(a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt());
	}

	static String vor(String iso) {
		if (iso.isEmpty()) return "";
		try {
			long s = Duration.between(Instant.parse(iso), Instant.now()).getSeconds();
			if (s < 90) return "gerade eben";
			if (s < 3600) return "vor " + (s / 60) + " Min.";
			if (s < 86400) return "vor " + (s / 3600) + " Std.";
			long d = s / 86400;
			return d == 1 ? "vor 1 Tag" : "vor " + d + " Tagen";
		} catch (Exception e) { return ""; }
	}

	private static String coords(BlockPos p) { return p.getX() + " " + p.getY() + " " + p.getZ(); }

	private static MutableComponent koordinaten(BlockPos p) {
		return Component.literal(coords(p)).withStyle(Style.EMPTY.withColor(Theme.CREAM).withBold(true)
			.withClickEvent(new ClickEvent.CopyToClipboard(coords(p)))
			.withHoverEvent(new HoverEvent.ShowText(Theme.t("Klicken: Koordinaten kopieren", Theme.MUTED))));
	}

	/** Koordinaten eines Lagerorts – bei Doppelkisten beide Hälften, jeweils einzeln kopierbar. */
	private static MutableComponent koordinaten(JsonObject loc) {
		MutableComponent m = Component.empty().append(koordinaten(pos(loc.get("pos"))));
		BlockPos b = pos(loc.get("pos2"));
		return b == null ? m : m.append(Theme.t(" + ", Theme.DIM)).append(koordinaten(b));
	}

	private static String coords(KistenOrt.Ziel z) { return coords(z.a()) + (z.b() == null ? "" : " + " + coords(z.b())); }

	private static MutableComponent zeile() { return Component.empty().append(Theme.t(" ▸ ", Theme.DARK)); }

	/** Registrierungsstatus aus der Datenbank (zuletzt bekannte, echte Prüfung). */
	private static MutableComponent status(JsonObject loc) {
		String checked = str(loc, "checked_at");
		if ("missing".equals(str(loc, "status")))
			return Theme.t("⚠ zuletzt nicht gefunden" + (checked.isEmpty() ? "" : " (" + vor(checked) + ")"), Theme.WARN);
		return Theme.t("erfasst" + (checked.isEmpty() ? "" : " · zuletzt gesehen " + vor(checked)), Theme.MUTED);
	}

	private record Live(boolean geladen, boolean vorhanden) {}

	/** Echte Prüfung nur in geladenen Chunks der aktuellen Dimension (es wird nichts nachgeladen). */
	private static Live live(JsonObject loc, String dim) {
		KistenWaechter.Zustand z = KistenWaechter.zustand(loc);
		if (z == KistenWaechter.Zustand.FEHLT) KistenWaechter.gesehen(loc); // Admin-Client bereinigt nach zweiter Bestätigung
		return new Live(z != KistenWaechter.Zustand.UNGEPRUEFT, z == KistenWaechter.Zustand.VORHANDEN);
	}

	private static MutableComponent liveText(Live l) {
		if (!l.geladen()) return Theme.t("nicht geladen – Zustand ungeprüft", Theme.DIM);
		if (l.vorhanden()) return Theme.t("✔ Kiste vorhanden (gerade geprüft)", Theme.OK);
		return Theme.t("✖ Truhe nicht gefunden" + (ObrienMod.isAdminKey() ? " – wird aus dem Lager entfernt" : " – ein Admin-Client entfernt sie"), Theme.ERR);
	}

	/** Item aus Eingabe oder Haupthand; bei Mehrdeutigkeit klickbare Vorschläge. Muss im Spiel-Thread laufen. */
	private static ItemSuche.Eintrag item(String eingabe, String befehl, String zusatz) {
		if (eingabe.isBlank()) {
			Minecraft mc = Minecraft.getInstance();
			ItemStack st = mc.player == null ? ItemStack.EMPTY : mc.player.getMainHandItem();
			if (st.isEmpty()) { Notifier.error("Halte ein Item in der Haupthand oder gib einen Namen an, z. B. " + befehl + " diamond"); return null; }
			return ItemSuche.vonItem(st.getItem());
		}
		ItemSuche.Ergebnis r = ItemSuche.aufloesen(eingabe);
		if (r.treffer() != null) return r.treffer();
		if (r.vorschlaege().isEmpty()) { Notifier.error("Unbekanntes Item „" + eingabe.trim() + "“. Beispiele: diamond, minecraft:iron_ingot, Eisenbarren."); return null; }
		MutableComponent m = Component.empty().append(Theme.t("„" + eingabe.trim() + "“ ist mehrdeutig – meintest du: ", Theme.WARN));
		for (int i = 0; i < r.vorschlaege().size(); i++) {
			ItemSuche.Eintrag e = r.vorschlaege().get(i);
			if (i > 0) m.append(Theme.t(", ", Theme.DIM));
			m.append(Notifier.command(e.name(), befehl + " " + e.id() + zusatz, e.id()));
		}
		Notifier.chat(m);
		return null;
	}

	// ---------- /obrien findkiste ----------

	/** Sucht ein Item in allen Lagerbereichen: kompakte Übersicht je Bereich, gefundene Kisten werden einige Sekunden markiert. */
	private static int find(String arg) {
		String a = arg.trim(), lower = a.toLowerCase(Locale.ROOT);
		// „all“ aus früheren Versionen wird ignoriert – gesucht wird immer in allen Lagerbereichen.
		if (lower.equals("all") || lower.equals("alle")) a = "";
		else if (lower.endsWith(" all") || lower.endsWith(" alle")) a = a.substring(0, a.lastIndexOf(' ')).trim();
		Ort o = ort(); if (o == null) return 0;
		ItemSuche.Eintrag it = item(a, "/obrien findkiste", ""); if (it == null) return 0;
		async(() -> {
			try {
				JsonObject r = KistenApi.suche(o.welt(), it.id());
				main(() -> zeigeSuche(o, it, r));
			} catch (ShopApi.ApiException e) {
				Notifier.error(fehler(e, "Die Suche ist fehlgeschlagen."));
			}
		});
		return 1;
	}

	private static void zeigeSuche(Ort o, ItemSuche.Eintrag it, JsonObject r) {
		if (i(r, "areas") == 0) { keineBereiche(); return; }
		int chests = i(r, "chests"), unknown = i(r, "unknown"), stale = i(r, "stale");
		long total = r.get("total").getAsLong();
		String offen = unknown == 0 ? "" : (unknown == 1 ? "1 Truhe" : unknown + " Truhen") + " im Lager noch nicht erfasst";
		if (chests == 0) {
			Notifier.chat(Component.empty().append(Theme.t("Kein " + it.name() + " in den Lagerbereichen gefunden.", Theme.WARN))
				.append(unknown > 0 ? Theme.t(" (" + offen + " – einmal öffnen, dann ist ihr Inhalt bekannt.)", Theme.DIM) : Component.empty()));
			return;
		}
		// Markieren: nur Kisten in geladenen Chunks der aktuellen Dimension, an denen der Client wirklich eine Kiste sieht.
		Hervorhebung.kistenLeeren();
		int markiert = 0, fern = 0;
		for (JsonElement el : r.getAsJsonArray("results")) {
			JsonObject loc = el.getAsJsonObject();
			Live lv = live(loc, o.dim());
			if (!lv.vorhanden()) { fern++; continue; }
			KistenOrt.Erkennung e = KistenOrt.erkenne(Minecraft.getInstance().level, pos(loc.get("pos")));
			if (e.ziel() != null) { Hervorhebung.markiere(e.ziel(), o.dim()); markiert++; }
		}
		List<Component> lines = new ArrayList<>();
		for (JsonElement el : r.getAsJsonArray("groups")) {
			JsonObject g = el.getAsJsonObject();
			lines.add(zeile().append(Theme.b(str(g, "area"), Theme.CREAM)).append(Theme.t(": " + truhen(i(g, "chests")) + " · " + zahl(g.get("total").getAsLong()) + " Stück", Theme.TEXT)));
		}
		lines.add(Component.empty().append(Theme.t(" Gesamtbestand: ", Theme.TEXT)).append(Theme.b(zahl(total) + " Stück", Theme.GOLD))
			.append(stale > 0 ? Theme.t("  (teils älterer Stand)", Theme.WARN) : Component.empty()));
		if (unknown > 0) lines.add(Theme.t(" ? " + offen + " – Bestand kann höher sein", Theme.DIM));
		MutableComponent m = Component.empty().append(Theme.t(markiert > 0 ? " ✦ " + truhen(markiert) + " " + Hervorhebung.KISTE_MS / 1000 + " s markiert" + (fern > 0 ? " · " + fern + " außer Sichtweite" : "") + "  "
			: " Nicht in Sichtweite – geh näher heran, dann werden die Truhen markiert.  ", Theme.DIM));
		m.append(Notifier.command("[Details & Koordinaten]", "/obrien bestand " + it.id(), "Alle Truhen mit Menge und Koordinaten"));
		lines.add(m);
		Notifier.block(it.name() + " gefunden!", lines.toArray(new Component[0]));
	}

	private static String truhen(int n) { return n == 1 ? "1 Truhe" : n + " Truhen"; }

	private static void keineBereiche() {
		Notifier.chat(Component.empty().append(Theme.t("Es gibt noch keine Lagerbereiche. ", Theme.WARN))
			.append(Theme.t("Lege einen an: /obrien lager erstellen <name>", Theme.GOLD)));
	}

	/** setkiste/removekiste gibt es nicht mehr: Kisten werden in Lagerbereichen automatisch erkannt. */
	private static int abgeloest() {
		Notifier.chat(Component.empty().append(Theme.t("Einzelne Kisten werden nicht mehr registriert – die Mod erkennt alle Truhen in deinen Lagerbereichen automatisch. ", Theme.WARN))
			.append(Notifier.command("[Lagerbereiche]", "/obrien lager liste", "Alle Lagerbereiche anzeigen")));
		return 1;
	}

	// ---------- /obrien lager erstellen | punkt1 | punkt2 | liste | info | zeigen | umbenennen | löschen ----------

	/** Zuletzt benutzter Bereich – punkt1/punkt2/info/zeigen ohne Namen beziehen sich darauf. */
	private static String aktiv;
	private static final List<String> NAMEN = new ArrayList<>();

	private static CompletableFuture<Suggestions> namen(SuggestionsBuilder b) {
		String rest = b.getRemaining().toLowerCase(Locale.ROOT);
		Set<String> alle = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
		for (KistenWaechter.Bereich x : KistenWaechter.bereiche()) alle.add(x.name());
		synchronized (NAMEN) { alle.addAll(NAMEN); }
		for (String n : alle) if (n.toLowerCase(Locale.ROOT).startsWith(rest)) b.suggest(n);
		return b.buildFuture();
	}

	private static void merkeName(String n) { synchronized (NAMEN) { if (NAMEN.stream().noneMatch(n::equalsIgnoreCase)) NAMEN.add(n); } }

	private static String name(String eingabe) {
		String n = eingabe == null ? "" : eingabe.trim();
		if (!n.isEmpty()) return n;
		if (aktiv == null) Notifier.error("Welcher Lagerbereich? Gib den Namen an, z. B. /obrien lager punkt1 Hauptlager");
		return aktiv;
	}

	private static int erstellen(String eingabe) {
		Ort o = ort(); if (o == null) return 0;
		String n = eingabe.trim();
		async(() -> {
			if (!admin()) { Notifier.error("Lagerbereiche anlegen dürfen nur Admins (Admin-Schlüssel)."); return; }
			try {
				JsonObject a = KistenApi.bereichAnlegen(o.welt(), n).getAsJsonObject("area");
				String nm = str(a, "name");
				aktiv = nm; merkeName(nm);
				Notifier.chat(Component.empty().append(Theme.t("✔ Lagerbereich „" + nm + "“ angelegt. ", Theme.OK))
					.append(Theme.t("Schau auf die erste Ecke und tippe ", Theme.TEXT)).append(Theme.b("/obrien lager punkt1", Theme.GOLD))
					.append(Theme.t(", dann auf die gegenüberliegende Ecke: ", Theme.TEXT)).append(Theme.b("/obrien lager punkt2", Theme.GOLD)));
			} catch (ShopApi.ApiException e) {
				Notifier.error(fehler(e, "Der Lagerbereich konnte nicht angelegt werden."));
			}
		});
		return 1;
	}

	/** Eckpunkt = der anvisierte Block (bis 64 Blöcke weit, wie bei WorldEdit) – nie die Spielerposition. */
	private static int punkt(int welcher, String eingabe) {
		Ort o = ort(); if (o == null) return 0;
		String n = name(eingabe); if (n == null) return 0;
		Minecraft mc = Minecraft.getInstance();
		HitResult hr = mc.player.pick(64, 1.0F, false);
		if (!(hr instanceof BlockHitResult bhr) || hr.getType() != HitResult.Type.BLOCK) { Notifier.error("Schau auf einen Block (höchstens 64 Blöcke entfernt) – er wird zur Ecke des Bereichs."); return 0; }
		BlockPos p = bhr.getBlockPos().immutable();
		async(() -> {
			if (!admin()) { Notifier.error("Lagerbereiche ändern dürfen nur Admins (Admin-Schlüssel)."); return; }
			try {
				JsonObject r = KistenApi.bereichPunkt(o.welt(), n, welcher, o.dim(), p);
				KistenWaechter.Bereich b = KistenWaechter.bereich(r.getAsJsonObject("area"));
				aktiv = b.name(); merkeName(b.name());
				KistenWaechter.veraltet(); // Liste neu laden → Truhen im (neuen) Bereich werden erkannt
				main(() -> {
					if (b.komplett()) Hervorhebung.markiereBereich(b.min(), b.max(), o.dim(), 20);
					else Hervorhebung.markiereBereich(p, p, o.dim(), 10);
					MutableComponent m = Component.empty().append(Theme.t("✔ Punkt " + welcher + " von „" + b.name() + "“: ", Theme.OK)).append(koordinaten(p));
					if (b.komplett()) {
						BlockPos g = b.max().subtract(b.min()).offset(1, 1, 1);
						m.append(Theme.t("  · Bereich " + g.getX() + " × " + g.getY() + " × " + g.getZ() + " Blöcke – Truhen darin werden automatisch erkannt.", Theme.TEXT));
					} else m.append(Theme.t("  · Jetzt den " + (welcher == 1 ? "zweiten" : "ersten") + " Punkt setzen: /obrien lager punkt" + (welcher == 1 ? 2 : 1), Theme.TEXT));
					Notifier.chat(m);
					int weg = i(r, "removed_chests");
					if (weg > 0) Notifier.info(weg == 1 ? "1 Truhe liegt jetzt außerhalb aller Lagerbereiche und wurde aus dem Lager entfernt."
						: weg + " Truhen liegen jetzt außerhalb aller Lagerbereiche und wurden aus dem Lager entfernt.");
				});
			} catch (ShopApi.ApiException e) {
				Notifier.error(fehler(e, "Der Punkt konnte nicht gespeichert werden."));
			}
		});
		return 1;
	}

	private static String groesse(JsonObject a) {
		BlockPos p1 = pos(a.get("p1")), p2 = pos(a.get("p2"));
		if (p1 == null || p2 == null) return p1 == null && p2 == null ? "keine Eckpunkte" : "Punkt " + (p1 == null ? 1 : 2) + " fehlt";
		return (Math.abs(p1.getX() - p2.getX()) + 1) + " × " + (Math.abs(p1.getY() - p2.getY()) + 1) + " × " + (Math.abs(p1.getZ() - p2.getZ()) + 1);
	}

	private static int bereichListe() {
		Ort o = ort(); if (o == null) return 0;
		async(() -> {
			try {
				JsonObject r = KistenApi.bereiche(o.welt());
				main(() -> {
					JsonArray arr = r.getAsJsonArray("areas");
					if (arr.isEmpty()) { keineBereiche(); return; }
					List<Component> lines = new ArrayList<>();
					for (JsonElement el : arr) {
						JsonObject a = el.getAsJsonObject();
						merkeName(str(a, "name"));
						boolean ok = a.get("complete").getAsBoolean();
						lines.add(zeile().append(Notifier.command(str(a, "name"), "/obrien lager info " + str(a, "name"), "Details und Rahmen anzeigen"))
							.append(Theme.t("  " + groesse(a) + (ok ? " · " + KistenOrt.dimName(str(a, "dimension")) : ""), ok ? Theme.MUTED : Theme.WARN))
							.append(ok ? Theme.t(" · " + truhen(i(a, "chests")) + (i(a, "unknown") > 0 ? " (" + i(a, "unknown") + " noch nicht erfasst)" : ""), Theme.TEXT) : Component.empty()));
					}
					lines.add(Theme.t(" Neu: /obrien lager erstellen <name> · Ecken: punkt1 / punkt2", Theme.DIM));
					Notifier.block("Lagerbereiche (" + arr.size() + ")", lines.toArray(new Component[0]));
				});
			} catch (ShopApi.ApiException e) {
				Notifier.error(fehler(e, "Die Lagerbereiche konnten nicht geladen werden."));
			}
		});
		return 1;
	}

	private static int bereichInfo(String eingabe) {
		Ort o = ort(); if (o == null) return 0;
		String n = name(eingabe); if (n == null) return 0;
		async(() -> {
			try {
				JsonObject r = KistenApi.bereich(o.welt(), n);
				main(() -> {
					if (r.get("area").isJsonNull()) { Notifier.warn("Es gibt keinen Lagerbereich „" + n + "“."); return; }
					JsonObject a = r.getAsJsonObject("area");
					String nm = str(a, "name");
					aktiv = nm; merkeName(nm);
					KistenWaechter.Bereich b = KistenWaechter.bereich(a);
					List<Component> lines = new ArrayList<>();
					BlockPos p1 = pos(a.get("p1")), p2 = pos(a.get("p2"));
					lines.add(zeile().append(Theme.t("Punkt 1   ", Theme.DIM)).append(p1 == null ? Theme.t("fehlt", Theme.WARN) : koordinaten(p1)));
					lines.add(zeile().append(Theme.t("Punkt 2   ", Theme.DIM)).append(p2 == null ? Theme.t("fehlt", Theme.WARN) : koordinaten(p2)));
					lines.add(zeile().append(Theme.t("Größe      ", Theme.DIM)).append(Theme.t(groesse(a) + (b.komplett() ? " Blöcke · " + KistenOrt.dimName(str(a, "dimension")) : ""), Theme.TEXT)));
					if (b.komplett()) {
						lines.add(zeile().append(Theme.t("Truhen     ", Theme.DIM)).append(Theme.t(truhen(i(a, "chests")) + " erkannt · " + i(a, "counted") + " Inhalt erfasst", Theme.TEXT))
							.append(i(a, "unknown") > 0 ? Theme.t(" · " + i(a, "unknown") + " noch nicht geöffnet", Theme.WARN) : Component.empty()));
						String last = str(a, "last_inventory");
						if (!last.isEmpty()) lines.add(zeile().append(Theme.t("Erfasst    ", Theme.DIM)).append(Theme.t("zuletzt " + vor(last), Theme.TEXT)));
						JsonArray top = r.getAsJsonArray("top");
						if (!top.isEmpty()) {
							MutableComponent t = zeile().append(Theme.t("Meiste     ", Theme.DIM));
							for (int k = 0; k < top.size(); k++) {
								JsonObject x = top.get(k).getAsJsonObject();
								if (k > 0) t.append(Theme.t(" · ", Theme.DIM));
								t.append(Notifier.command(ItemSuche.name(str(x, "item")) + " " + zahl(x.get("count").getAsLong()), "/obrien bestand " + str(x, "item"), "Bestand anzeigen"));
							}
							lines.add(t);
						}
						if (b.dim().equals(o.dim())) { Hervorhebung.markiereBereich(b.min(), b.max(), o.dim(), 20); lines.add(Theme.t(" ✦ Rahmen 20 s türkis angezeigt.", Theme.DIM)); }
					} else lines.add(Theme.t(" Bereich unvollständig: /obrien lager punkt" + (p1 == null ? 1 : 2) + " " + nm, Theme.WARN));
					lines.add(Component.empty().append(Theme.t(" ", Theme.DIM))
						.append(Notifier.command("[Löschen …]", "/obrien lager löschen " + nm, "Fragt noch einmal nach")));
					Notifier.block("Lagerbereich · " + nm, lines.toArray(new Component[0]));
				});
			} catch (ShopApi.ApiException e) {
				Notifier.error(fehler(e, "Der Lagerbereich konnte nicht geladen werden."));
			}
		});
		return 1;
	}

	private static int umbenennen(String alt, String neu) {
		Ort o = ort(); if (o == null) return 0;
		async(() -> {
			if (!admin()) { Notifier.error("Lagerbereiche ändern dürfen nur Admins (Admin-Schlüssel)."); return; }
			try {
				JsonObject a = KistenApi.bereichUmbenennen(o.welt(), alt.trim(), neu.trim()).getAsJsonObject("area");
				aktiv = str(a, "name"); merkeName(aktiv);
				synchronized (NAMEN) { NAMEN.removeIf(x -> x.equalsIgnoreCase(alt.trim())); }
				KistenWaechter.veraltet();
				Notifier.ok("Lagerbereich „" + alt.trim() + "“ heißt jetzt „" + aktiv + "“.");
			} catch (ShopApi.ApiException e) {
				Notifier.error(fehler(e, "Der Lagerbereich konnte nicht umbenannt werden."));
			}
		});
		return 1;
	}

	private static int loeschen(String eingabe) {
		Ort o = ort(); if (o == null) return 0;
		String n = eingabe.trim();
		String nl = n.toLowerCase(Locale.ROOT);
		boolean ja = nl.endsWith(" bestätigen") || nl.endsWith(" bestaetigen");
		if (ja) n = n.substring(0, n.lastIndexOf(' ')).trim();
		if (n.isEmpty()) { Notifier.error("Welchen Lagerbereich? /obrien lager löschen <name>"); return 0; }
		if (!ja) {
			Notifier.chat(Component.empty().append(Theme.t("Lagerbereich „" + n + "“ wirklich löschen? Die erkannten Truhen darin werden aus dem Lager entfernt (die Truhen und ihr Inhalt im Spiel bleiben natürlich). ", Theme.WARN))
				.append(Notifier.command("[Ja, löschen]", "/obrien lager löschen " + n + " bestätigen", "Endgültig löschen")));
			return 1;
		}
		String fn = n;
		async(() -> {
			if (!admin()) { Notifier.error("Lagerbereiche löschen dürfen nur Admins (Admin-Schlüssel)."); return; }
			try {
				JsonObject r = KistenApi.bereichLoeschen(o.welt(), fn);
				synchronized (NAMEN) { NAMEN.removeIf(x -> x.equalsIgnoreCase(fn)); }
				if (fn.equalsIgnoreCase(aktiv)) aktiv = null;
				KistenWaechter.veraltet();
				int weg = i(r, "removed_chests");
				Notifier.ok("Lagerbereich „" + str(r, "deleted") + "“ gelöscht" + (weg > 0 ? " – " + truhen(weg) + " aus dem Lager entfernt." : "."));
			} catch (ShopApi.ApiException e) {
				Notifier.error(fehler(e, "Der Lagerbereich konnte nicht gelöscht werden."));
			}
		});
		return 1;
	}

	// ---------- /obrien listkisten ----------

	private static int list(String arg) {
		Ort o = ort(); if (o == null) return 0;
		int seite = 1;
		String dim = null;
		List<String> rest = new ArrayList<>();
		for (String t : arg.trim().split("\\s+")) {
			if (t.isEmpty()) continue;
			String l = t.toLowerCase(Locale.ROOT);
			if (l.matches("\\d{1,5}")) seite = Math.max(1, Integer.parseInt(l));
			else if (l.equals("oberwelt") || l.equals("overworld")) dim = "minecraft:overworld";
			else if (l.equals("nether")) dim = "minecraft:the_nether";
			else if (l.equals("ende") || l.equals("end")) dim = "minecraft:the_end";
			else rest.add(t);
		}
		ItemSuche.Eintrag it = null;
		if (!rest.isEmpty()) { it = item(String.join(" ", rest), "/obrien listkisten", ""); if (it == null) return 0; }
		String fDim = dim, fItem = it == null ? null : it.id();
		int fSeite = seite;
		async(() -> {
			try {
				JsonObject r = KistenApi.liste(o.welt(), fDim, fItem, fSeite);
				main(() -> zeigeListe(r, fDim, fItem));
			} catch (ShopApi.ApiException e) {
				Notifier.error(fehler(e, "Die Liste konnte nicht geladen werden."));
			}
		});
		return 1;
	}

	private static void zeigeListe(JsonObject r, String dim, String item) {
		if (i(r, "areas") == 0) { keineBereiche(); return; }
		int total = r.get("total").getAsInt(), page = r.get("page").getAsInt(), pages = r.get("pages").getAsInt();
		String filter = (item != null ? item + " " : "") + (dim != null ? KistenOrt.dimName(dim).toLowerCase(Locale.ROOT) + " " : "");
		String titel = "Lagerkisten" + (item != null ? " · " + ItemSuche.name(item) : "") + (dim != null ? " · " + KistenOrt.dimName(dim) : "");
		if (total == 0) { Notifier.warn(item != null || dim != null ? "Keine Truhen für diesen Filter." : "In den Lagerbereichen wurden noch keine Truhen erkannt."); return; }
		List<Component> lines = new ArrayList<>();
		lines.add(Theme.t(" " + truhen(total) + " · Seite " + page + " von " + pages, Theme.MUTED));
		for (JsonElement el : r.getAsJsonArray("entries")) {
			JsonObject loc = el.getAsJsonObject();
			boolean erfasst = !str(loc, "inventory_at").isEmpty();
			lines.add(zeile().append(Theme.b(str(loc, "area"), Theme.GOLD)).append(Theme.t("  " + (erfasst
				? i(loc, "item_kinds") + (i(loc, "item_kinds") == 1 ? " Itemsorte · " : " Itemsorten · ") + zahl(loc.get("item_total").getAsLong()) + " Items · erfasst " + vor(str(loc, "inventory_at"))
				: "Inhalt noch nicht erfasst (einmal öffnen)"), erfasst ? Theme.TEXT : Theme.DIM)));
			// Geladene Kisten jetzt prüfen: Eine abgebaute Kiste erscheint nie kommentarlos als gültig.
			Live lv = live(loc, KistenOrt.dimension());
			lines.add(Component.empty().append(Theme.t("     ", Theme.DIM)).append(koordinaten(loc))
				.append(Theme.t("  " + KistenOrt.dimName(str(loc, "dimension")) + " · " + KistenOrt.typName(str(loc, "kind")) + " · ", Theme.MUTED)).append(lv.geladen() ? liveText(lv) : status(loc)));
		}
		MutableComponent nav = Component.empty().append(Theme.t(" ", Theme.DIM));
		if (page > 1) nav.append(Notifier.command("[◀ Zurück]", "/obrien listkisten " + filter + (page - 1), "Vorherige Seite")).append(Theme.t("  ", Theme.DIM));
		if (page < pages) nav.append(Notifier.command("[Weiter ▶]", "/obrien listkisten " + filter + (page + 1), "Nächste Seite"));
		if (pages > 1) lines.add(nav);
		lines.add(Theme.t(" Filter: /obrien listkisten <item> | oberwelt | nether | ende | <seite>", Theme.DIM));
		Notifier.block(titel, lines.toArray(new Component[0]));
	}

	// ---------- /obrien infokiste ----------

	private static int info() {
		Ort o = ort(); if (o == null) return 0;
		KistenOrt.Erkennung e = KistenOrt.anvisiert();
		if (e.ziel() == null) { Notifier.error(e.fehler()); return 0; }
		KistenOrt.Ziel z = e.ziel();
		async(() -> {
			try {
				JsonObject loc = KistenApi.an(o.welt(), o.dim(), z.a()).getAsJsonObject("location");
				main(() -> zeigeInfo(o, z, loc));
			} catch (ShopApi.ApiException ex) {
				Notifier.error(fehler(ex, "Die Informationen konnten nicht geladen werden."));
			}
		});
		return 1;
	}

	private static void zeigeInfo(Ort o, KistenOrt.Ziel z, JsonObject loc) {
		if (loc == null) {
			Notifier.chat(Theme.t("Diese " + z.typ() + " (" + coords(z) + ") ist nicht im Lager erfasst. Truhen innerhalb eines Lagerbereichs werden automatisch erkannt (/obrien lager liste).", Theme.WARN));
			return;
		}
		List<Component> lines = new ArrayList<>();
		String area = str(loc, "area");
		lines.add(zeile().append(Theme.t("Bereich    ", Theme.DIM)).append(area.isEmpty() ? Theme.t("außerhalb aller Lagerbereiche – zählt nicht zum Lager", Theme.WARN) : Theme.b(area, Theme.GOLD)));
		lines.add(zeile().append(Theme.t("Position  ", Theme.DIM)).append(koordinaten(loc)));
		lines.add(zeile().append(Theme.t("Typ        ", Theme.DIM)).append(Theme.t(KistenOrt.typName(str(loc, "kind")) + " · " + KistenOrt.dimName(str(loc, "dimension")), Theme.TEXT)));
		String inv = str(loc, "inventory_at");
		lines.add(zeile().append(Theme.t("Inhalt     ", Theme.DIM)).append(inv.isEmpty() ? Theme.t("noch nicht erfasst – einmal öffnen und schließen (Admin)", Theme.WARN)
			: Theme.t("erfasst " + vor(inv) + " beim Schließen", Theme.TEXT)));
		JsonArray items = loc.getAsJsonArray("items");
		int k = 0;
		for (JsonElement x : items) {
			if (k++ >= 12) { lines.add(Theme.t("     … und " + (items.size() - 12) + " weitere", Theme.DIM)); break; }
			JsonObject it = x.getAsJsonObject();
			lines.add(Component.empty().append(Theme.t("     • ", Theme.DARK)).append(Theme.b(zahl(it.get("count").getAsLong()), Theme.CREAM)).append(Theme.t("  " + ItemSuche.name(str(it, "item")), Theme.TEXT)));
		}
		if (!inv.isEmpty() && items.isEmpty()) lines.add(Theme.t("     leer", Theme.DIM));
		Notifier.block("Kisteninfo", lines.toArray(new Component[0]));
		if (ObrienMod.isAdminKey() && !"ok".equals(str(loc, "status"))) melde(o, z.a(), true);
	}

	private static void melde(Ort o, BlockPos p, boolean vorhanden) {
		async(() -> {
			try { KistenApi.pruefung(o.welt(), o.dim(), p, vorhanden); } catch (ShopApi.ApiException e) { ObrienMod.LOG.info("[O'Brien] Prüfung nicht gespeichert: {}", e.getMessage()); }
		});
	}

	// ---------- /obrien bestand (Alias: lager) ----------

	private static String zahl(long n) { return String.format(Locale.GERMANY, "%,d", n); }
	private static int i(JsonObject o, String k) { return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsInt() : 0; }
	private static String kisten(int n) { return n == 1 ? "1 Kiste" : n + " Kisten"; }

	/**
	 * /obrien bestand [item] [seite] · [name] [niedrig|ungeprüft] [seite] · <item> min <zahl|aus>.
	 * Liest nur die gespeicherten Zählungen der Website – unabhängig vom eigenen Inventar und ohne Chunks zu laden.
	 */
	private static int bestand(String arg) {
		Ort o = ort(); if (o == null) return 0;
		int seite = 1;
		String sort = null, filter = null, min = null;
		boolean hand = false;
		List<String> rest = new ArrayList<>();
		String[] t = arg.trim().split("\\s+");
		for (int k = 0; k < t.length; k++) {
			String l = t[k].toLowerCase(Locale.ROOT);
			if (l.isEmpty()) continue;
			if (l.equals("min") || l.equals("mindest")) { if (k + 1 >= t.length) { Notifier.error("Nutze: /obrien bestand <item> min <Zahl|aus>"); return 0; } min = t[++k].toLowerCase(Locale.ROOT); }
			else if (l.matches("\\d{1,5}")) seite = Math.max(1, Integer.parseInt(l));
			else if (l.equals("name")) sort = "name";
			else if (l.equals("menge")) sort = "total";
			else if (l.equals("niedrig")) filter = "low";
			else if (l.equals("ungeprüft") || l.equals("ungeprueft") || l.equals("unsicher")) filter = "unsure";
			else if (l.equals("hand")) hand = true;
			else rest.add(t[k]);
		}
		ItemSuche.Eintrag it = null;
		if (!rest.isEmpty() || hand) { it = item(hand ? "" : String.join(" ", rest), "/obrien bestand", min != null ? " min " + min : ""); if (it == null) return 0; }
		if (min != null) {
			if (it == null) { Notifier.error("Gib das Item an, z. B. /obrien bestand diamond min 128"); return 0; }
			Integer n = null;
			if (!min.equals("aus") && !min.equals("off")) {
				try { n = Integer.parseInt(min.replace(".", "")); } catch (NumberFormatException e) { n = -1; }
				if (n < 1 || n > 10_000_000) { Notifier.error("Der Mindestbestand muss eine Zahl von 1 bis 10.000.000 sein (oder „aus“)."); return 0; }
			}
			return mindest(o, it, n);
		}
		ItemSuche.Eintrag fIt = it;
		String fSort = sort, fFilter = filter;
		int fSeite = seite;
		async(() -> {
			try {
				JsonObject r = KistenApi.bestand(o.welt(), fIt == null ? null : fIt.id(), fSort, fFilter, fSeite);
				main(() -> { if (fIt != null) zeigeBestand(fIt, r); else zeigeUebersicht(r); });
			} catch (ShopApi.ApiException e) {
				Notifier.error(fehler(e, "Der Lagerbestand konnte nicht geladen werden."));
			}
		});
		return 1;
	}

	private static int mindest(Ort o, ItemSuche.Eintrag it, Integer n) {
		async(() -> {
			if (!admin()) { Notifier.error("Den Mindestbestand dürfen nur Admins festlegen (Admin-Schlüssel)."); return; }
			try {
				KistenApi.mindest(o.welt(), it.id(), n);
				Notifier.ok(n == null ? "Mindestbestand für " + it.name() + " entfernt." : "Mindestbestand für " + it.name() + " auf " + zahl(n) + " gesetzt.");
			} catch (ShopApi.ApiException e) {
				Notifier.error(fehler(e, "Der Mindestbestand konnte nicht gespeichert werden."));
			}
		});
		return 1;
	}

	private static void zeigeBestand(ItemSuche.Eintrag it, JsonObject r) {
		JsonObject s = r.has("summary") && !r.get("summary").isJsonNull() ? r.getAsJsonObject("summary") : null;
		if (s == null) { keineBereiche(); return; }
		int h = i(r, "stale_hours");
		int fresh = i(s, "fresh"), stale = i(s, "stale"), unknown = i(s, "unknown"), missing = i(s, "missing");
		long fs = s.get("fresh_sum").getAsLong(), ss = s.get("stale_sum").getAsLong();
		List<Component> lines = new ArrayList<>();
		if (fresh + stale == 0 && unknown == 0) {
			Notifier.chat(Theme.t("Kein " + it.name() + " im Lager – keine der erfassten Truhen enthält es.", Theme.WARN));
			return;
		}
		if (fresh + stale == 0) {
			// Noch nicht geöffnet: ausdrücklich unbekannt, nicht 0.
			lines.add(Theme.b(" Bestand unbekannt", Theme.WARN));
			lines.add(Theme.t(" In den erfassten Truhen ist keins – aber " + truhen(unknown) + " im Lager wurden noch nicht geöffnet.", Theme.MUTED));
		} else {
			lines.add(Component.empty().append(Theme.t(" Bekannter Gesamtbestand: ", Theme.TEXT)).append(Theme.b(zahl(fs + ss), Theme.GOLD))
				.append(Theme.t(" in " + truhen(fresh + stale), Theme.MUTED)));
			if (fresh > 0) lines.add(Theme.t(" ✔ " + zahl(fs) + " bestätigt aus " + truhen(fresh) + " (erfasst in den letzten " + h + " Std.)", Theme.OK));
			if (stale > 0) lines.add(Theme.t(" ◷ " + zahl(ss) + " aus " + truhen(stale) + " mit älterem Stand (letzter bekannter Stand)", Theme.WARN));
		}
		if (unknown > 0 && fresh + stale > 0) lines.add(Theme.t(" ? Zusätzlich " + truhen(unknown) + " im Lager noch nicht erfasst – tatsächlicher Bestand kann höher sein.", Theme.WARN));
		if (missing > 0) lines.add(Theme.t(" ⚠ " + truhen(missing) + " zuletzt nicht gefunden – nicht mitgezählt.", Theme.ERR));
		String last = str(s, "last_counted_at");
		if (!last.isEmpty()) lines.add(Theme.t(" Letzte Erfassung " + vor(last), Theme.DIM));
		if (s.has("min") && !s.get("min").isJsonNull()) {
			int m = s.get("min").getAsInt();
			lines.add(s.get("low").getAsBoolean() ? Theme.t(" ⚠ Mindestbestand " + zahl(m) + " unterschritten" + (unknown > 0 ? " (soweit bekannt)" : ""), Theme.ERR)
				: Theme.t(" Mindestbestand " + zahl(m) + " erreicht", Theme.MUTED));
		}
		int page = i(r, "page"), pages = i(r, "pages");
		if (fresh + stale + missing > 0) lines.add(Theme.t(" Truhen" + (pages > 1 ? " · Seite " + page + " von " + pages : "") + ":", Theme.MUTED));
		for (JsonElement el : r.getAsJsonArray("entries")) {
			JsonObject e = el.getAsJsonObject();
			String st = str(e, "state");
			String n = e.get("count").isJsonNull() ? "?" : zahl(e.get("count").getAsLong());
			MutableComponent l = zeile().append(switch (st) {
				case "fresh" -> Theme.b(n, Theme.OK);
				case "stale" -> Theme.b(n, Theme.WARN);
				case "missing" -> Theme.t("–", Theme.ERR);
				default -> Theme.b("?", Theme.DIM);
			}).append(Theme.t("  " + str(e, "area") + "  ", Theme.GOLD)).append(koordinaten(e))
				.append(Theme.t("  " + KistenOrt.dimName(str(e, "dimension")) + " · " + KistenOrt.typName(str(e, "kind")) + " · ", Theme.MUTED));
			String at = str(e, "counted_at");
			Live lv = live(e, KistenOrt.dimension());
			if (lv.geladen() && !lv.vorhanden()) { lines.add(l.append(Theme.t("✖ Truhe nicht gefunden", Theme.ERR))); continue; }
			l.append(switch (st) {
				case "fresh" -> Theme.t("erfasst " + vor(at), Theme.MUTED);
				case "stale" -> Theme.t("Stand " + vor(at), Theme.WARN);
				case "missing" -> Theme.t("zuletzt nicht gefunden" + (e.get("count").isJsonNull() ? "" : " (vorher " + n + ")"), Theme.ERR);
				default -> Theme.t("ungeprüft", Theme.DIM);
			});
			lines.add(l);
		}
		MutableComponent nav = Component.empty().append(Theme.t(" ", Theme.DIM));
		if (page > 1) nav.append(Notifier.command("[◀ Zurück]", "/obrien bestand " + it.id() + " " + (page - 1), "Vorherige Seite")).append(Theme.t("  ", Theme.DIM));
		if (page < pages) nav.append(Notifier.command("[Weiter ▶]", "/obrien bestand " + it.id() + " " + (page + 1), "Nächste Seite")).append(Theme.t("  ", Theme.DIM));
		nav.append(Notifier.command("[Übersicht]", "/obrien bestand", "Alle Items"));
		lines.add(nav);
		lines.add(Theme.t(" Erfasst wird beim Schließen einer Lagertruhe durch einen Admin.", Theme.DIM));
		Notifier.block("Lagerbestand · " + it.name(), lines.toArray(new Component[0]));
	}

	private static void zeigeUebersicht(JsonObject r) {
		String sort = str(r, "sort"), filter = str(r, "filter");
		int total = i(r, "total_items"), low = i(r, "low_items"), unsure = i(r, "unsure_items"), page = i(r, "page"), pages = i(r, "pages");
		String opt = ("name".equals(sort) ? "name " : "") + ("low".equals(filter) ? "niedrig " : "unsure".equals(filter) ? "ungeprüft " : "");
		if (i(r, "areas") == 0) { keineBereiche(); return; }
		if (total == 0) {
			Notifier.warn(!filter.isEmpty() ? ("low".equals(filter) ? "Kein Item liegt unter seinem Mindestbestand." : "Alle Bestände sind aktuell erfasst.")
				: i(r, "unknown_chests") > 0 ? "Noch keine Inhalte erfasst – öffne die Truhen in deinen Lagerbereichen einmal (" + truhen(i(r, "unknown_chests")) + " warten darauf)."
				: "Das Lager ist leer.");
			return;
		}
		List<Component> lines = new ArrayList<>();
		lines.add(Theme.t(" " + total + (total == 1 ? " Item" : " Items") + " · sortiert nach " + ("name".equals(sort) ? "Name" : "Menge")
			+ ("low".equals(filter) ? " · nur unter Mindestbestand" : "unsure".equals(filter) ? " · nur unsichere Bestände" : "") + (pages > 1 ? " · Seite " + page + "/" + pages : ""), Theme.MUTED));
		for (JsonElement el : r.getAsJsonArray("items")) {
			JsonObject x = el.getAsJsonObject();
			String id = str(x, "item");
			int fresh = i(x, "fresh"), stale = i(x, "stale"), unknown = i(x, "unknown"), missing = i(x, "missing");
			long sum = x.get("fresh_sum").getAsLong() + x.get("stale_sum").getAsLong();
			MutableComponent l = zeile().append(Notifier.command(ItemSuche.name(id), "/obrien bestand " + id, "Bestand und Lagerorte anzeigen")).append(Theme.t("  ", Theme.DIM));
			l.append(fresh + stale == 0 ? Theme.t("unbekannt", Theme.DIM) : Theme.b(zahl(sum) + (unknown > 0 ? "+" : ""), stale > 0 ? Theme.WARN : Theme.CREAM));
			l.append(Theme.t("  " + truhen(i(x, "fresh") + i(x, "stale")), Theme.DIM));
			if (stale > 0) l.append(Theme.t(" · ◷ " + stale + " älter", Theme.WARN));

			if (missing > 0) l.append(Theme.t(" · ⚠ " + missing + " fehlt", Theme.ERR));
			if (x.get("low").getAsBoolean()) l.append(Theme.t(" · ⚠ unter Mindestbestand " + zahl(x.get("min").getAsLong()), Theme.ERR));
			lines.add(l);
		}
		MutableComponent nav = Component.empty().append(Theme.t(" ", Theme.DIM));
		if (page > 1) nav.append(Notifier.command("[◀]", "/obrien bestand " + opt + (page - 1), "Vorherige Seite")).append(Theme.t(" ", Theme.DIM));
		if (page < pages) nav.append(Notifier.command("[▶]", "/obrien bestand " + opt + (page + 1), "Nächste Seite")).append(Theme.t(" ", Theme.DIM));
		String f = "low".equals(filter) ? "niedrig " : "unsure".equals(filter) ? "ungeprüft " : "";
		nav.append("name".equals(sort) ? Notifier.command("[Nach Menge]", "/obrien bestand " + f, "Größte Bestände zuerst") : Notifier.command("[Nach Name]", "/obrien bestand name " + f, "Alphabetisch nach Item-ID"));
		if (filter.isEmpty()) {
			if (low > 0) nav.append(Theme.t(" ", Theme.DIM)).append(Notifier.command("[⚠ " + low + " niedrig]", "/obrien bestand niedrig", "Nur Items unter Mindestbestand"));
			if (unsure > 0) nav.append(Theme.t(" ", Theme.DIM)).append(Notifier.command("[? " + unsure + " unsicher]", "/obrien bestand ungeprüft", "Items mit älteren, ungeprüften oder fehlenden Lagerorten"));
		} else nav.append(Theme.t(" ", Theme.DIM)).append(Notifier.command("[Alle]", "/obrien bestand " + ("name".equals(sort) ? "name" : ""), "Filter aufheben"));
		lines.add(nav);
		if (i(r, "unknown_chests") > 0) lines.add(Theme.t(" ? " + truhen(i(r, "unknown_chests")) + " im Lager noch nicht erfasst – „+“: Bestand kann höher sein.", Theme.WARN));
		lines.add(Theme.t(" Gelb = älterer Stand (über 24 Std.) · Details: Item anklicken", Theme.DIM));
		Notifier.block("Lagerbestand · Übersicht", lines.toArray(new Component[0]));
	}
}
