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

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

/**
 * Kistenfinder: /obrien findkiste · setkiste · removekiste · listkisten · infokiste · bestand (Alias: lager).
 * Grundsatz: Die Datenbank kennt registrierte Lagerorte. Ob eine Kiste noch steht, prüft die Mod nur dort, wo der Chunk gerade
 * geladen ist; Bestände nur, wenn ein Admin die Kiste selbst geöffnet hat. Alles andere wird als „ungeprüft“ angezeigt.
 */
public final class KistenBefehle {
	private KistenBefehle() {}

	static final int RADIUS = 32;

	/** Ereignisse des Moduls (Hervorhebung, Bestandszählung). */
	public static void init() {
		ClientTickEvents.END_CLIENT_TICK.register(c -> Hervorhebung.tick());
		Bestand.init();
	}

	/** Hängt die Kistenbefehle an /obrien an. */
	public static LiteralArgumentBuilder<FabricClientCommandSource> attach(LiteralArgumentBuilder<FabricClientCommandSource> root) {
		return root
			.then(literal("findkiste").executes(c -> find(""))
				.then(argument("item", StringArgumentType.greedyString()).suggests((c, b) -> ItemSuche.vorschlagen(b, true))
					.executes(c -> find(StringArgumentType.getString(c, "item")))))
			.then(literal("setkiste").executes(c -> set()))
			.then(literal("removekiste").executes(c -> remove(""))
				.then(argument("item", StringArgumentType.greedyString()).suggests((c, b) -> ItemSuche.vorschlagen(b, false))
					.executes(c -> remove(StringArgumentType.getString(c, "item")))))
			.then(literal("listkisten").executes(c -> list(""))
				.then(argument("filter", StringArgumentType.greedyString()).suggests((c, b) -> ItemSuche.vorschlagen(b, false))
					.executes(c -> list(StringArgumentType.getString(c, "filter")))))
			.then(literal("infokiste").executes(c -> info()))
			.then(bestandBefehl("bestand"))
			.then(bestandBefehl("lager"));
	}

	private static LiteralArgumentBuilder<FabricClientCommandSource> bestandBefehl(String name) {
		return literal(name).executes(c -> bestand(""))
			.then(argument("item", StringArgumentType.greedyString()).suggests((c, b) -> {
				// Erstes Wort: Optionen der Übersicht zusätzlich zu den Items
				String rest = b.getRemaining().toLowerCase(Locale.ROOT);
				if (!rest.contains(" ")) for (String w : List.of("name", "niedrig", "ungeprüft")) if (w.startsWith(rest)) b.suggest(w);
				return ItemSuche.vorschlagen(b, false);
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
		return Theme.t("registriert" + (checked.isEmpty() ? "" : " · zuletzt gesehen " + vor(checked)), Theme.MUTED);
	}

	/** Bestand nur mit echter Zählung, sonst ausdrücklich „ungeprüft“. */
	private static MutableComponent bestand(JsonObject item) {
		if (item == null || !item.has("count") || item.get("count").isJsonNull()) return Theme.t("Bestand ungeprüft", Theme.DIM);
		int n = item.get("count").getAsInt();
		return Theme.t("Bestand " + n + " (beim Öffnen gezählt, " + vor(str(item, "counted_at")) + ")", n > 0 ? Theme.OK : Theme.WARN);
	}

	private record Live(boolean geladen, boolean vorhanden) {}

	/** Echte Prüfung nur in geladenen Chunks der aktuellen Dimension (es wird nichts nachgeladen). */
	private static Live live(JsonObject loc, String dim) {
		Minecraft mc = Minecraft.getInstance();
		BlockPos p = pos(loc.get("pos"));
		if (mc.level == null || p == null || !dim.equals(str(loc, "dimension")) || !mc.level.hasChunk(p.getX() >> 4, p.getZ() >> 4)) return new Live(false, false);
		KistenOrt.Erkennung e = KistenOrt.erkenne(mc.level, p);
		return new Live(true, e.ziel() != null);
	}

	private static MutableComponent liveText(Live l) {
		if (!l.geladen()) return Theme.t("nicht geladen – Zustand ungeprüft", Theme.DIM);
		return l.vorhanden() ? Theme.t("✔ Kiste vorhanden (gerade geprüft)", Theme.OK) : Theme.t("⚠ keine Kiste mehr an dieser Stelle (gerade geprüft)", Theme.ERR);
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

	private static int find(String arg) {
		String a = arg.trim();
		boolean all = false;
		String lower = a.toLowerCase(Locale.ROOT);
		if (lower.equals("all") || lower.equals("alle")) { all = true; a = ""; }
		else if (lower.endsWith(" all") || lower.endsWith(" alle")) { all = true; a = a.substring(0, a.lastIndexOf(' ')).trim(); }
		Ort o = ort(); if (o == null) return 0;
		ItemSuche.Eintrag it = item(a, "/obrien findkiste", all ? " all" : ""); if (it == null) return 0;
		boolean alle = all;
		async(() -> {
			try {
				JsonObject r = KistenApi.suche(o.welt(), o.dim(), it.id(), o.x(), o.y(), o.z(), alle, RADIUS);
				main(() -> zeigeSuche(o, it, r, alle));
			} catch (ShopApi.ApiException e) {
				Notifier.error(fehler(e, "Die Suche ist fehlgeschlagen."));
			}
		});
		return 1;
	}

	private static void zeigeSuche(Ort o, ItemSuche.Eintrag it, JsonObject r, boolean all) {
		JsonArray res = r.getAsJsonArray("results");
		int total = r.get("total").getAsInt();
		if (res.isEmpty()) {
			MutableComponent m = Component.empty().append(Theme.t("Keine registrierten Lagerorte für " + it.name() + (all ? " auf diesem Server." : " im Umkreis von " + RADIUS + " Blöcken."), Theme.WARN));
			if (!all) m.append(Theme.t("  ", Theme.DIM)).append(Notifier.command("[Alle anzeigen]", "/obrien findkiste " + it.id() + " all", "Alle registrierten Lagerorte dieses Servers, unabhängig von der Entfernung"));
			Notifier.chat(m);
			return;
		}
		List<Component> lines = new ArrayList<>();
		lines.add(Theme.t(" " + total + (total == 1 ? " registrierter Lagerort" : " registrierte Lagerorte") + (all ? " · alle Dimensionen" : " im Umkreis von " + RADIUS + " Blöcken"), Theme.TEXT));
		int markiert = 0, shown = 0, fehlt = 0;
		boolean meldenErlaubt = ObrienMod.isAdminKey();
		for (JsonElement el : res) {
			JsonObject loc = el.getAsJsonObject();
			if (shown++ >= 10) break;
			BlockPos p = pos(loc.get("pos"));
			String dim = str(loc, "dimension");
			MutableComponent l1 = zeile().append(koordinaten(loc)).append(Theme.t("  " + KistenOrt.dimName(dim), dim.equals(o.dim()) ? Theme.MUTED : Theme.INFO));
			if (loc.has("distance") && !loc.get("distance").isJsonNull()) l1.append(Theme.t(" · " + Math.round(loc.get("distance").getAsDouble()) + " Blöcke", Theme.GOLD));
			l1.append(Theme.t(" · " + KistenOrt.typName(str(loc, "kind")), Theme.MUTED));
			lines.add(l1);
			Live lv = live(loc, o.dim());
			JsonObject item = loc.getAsJsonArray("items").get(0).getAsJsonObject();
			MutableComponent l2 = Component.empty().append(Theme.t("     ", Theme.DIM)).append(lv.geladen() ? liveText(lv) : status(loc)).append(Theme.t(" · ", Theme.DIM)).append(bestand(item));
			// Zerstörte Kiste (gerade gesehen): Admins können die veraltete Registrierung direkt entfernen.
			if (lv.geladen() && !lv.vorhanden()) {
				fehlt++;
				if (meldenErlaubt) l2.append(Theme.t("  ", Theme.DIM)).append(Notifier.command("[Registrierung entfernen]", "/obrien removekiste " + it.id() + " @" + p.getX() + "," + p.getY() + "," + p.getZ(),
					"Entfernt nur diese veraltete Zuordnung"));
			}
			lines.add(l2);
			// Hervorhebung nur für tatsächlich gesehene Kisten in der Nähe.
			if (lv.vorhanden() && (!loc.has("distance") || loc.get("distance").isJsonNull() || loc.get("distance").getAsDouble() <= 64)) {
				KistenOrt.Erkennung e = KistenOrt.erkenne(Minecraft.getInstance().level, p);
				if (e.ziel() != null) { Hervorhebung.markiere(e.ziel(), o.dim()); markiert++; }
			}
			// Echte Beobachtung speichern (nur Admin-Schlüssel), damit veraltete Registrierungen erkennbar werden.
			if (lv.geladen() && meldenErlaubt && (lv.vorhanden() != "ok".equals(str(loc, "status")) || vor(str(loc, "checked_at")).matches("vor \\d+ (Std\\.|Tag|Tagen)")))
				melde(o, p, lv.vorhanden());
		}
		if (total > 10) lines.add(Component.empty().append(Theme.t(" + " + (total - 10) + " weitere  ", Theme.DIM))
			.append(Notifier.command("[Liste]", "/obrien listkisten " + it.id(), "Alle Lagerorte für dieses Item seitenweise")));
		if (markiert > 0) lines.add(Theme.t(" ✦ " + markiert + (markiert == 1 ? " Kiste" : " Kisten") + " 10 Sekunden lang golden markiert.", Theme.DIM));
		else if (fehlt > 0) lines.add(Theme.t(" Nichts markiert – an der registrierten Stelle steht keine Kiste mehr.", Theme.DIM));
		else lines.add(Theme.t(" Nichts markiert – die Kisten sind nicht geladen. Entfernte Lagerorte werden nur als Koordinaten angezeigt.", Theme.DIM));
		Notifier.block(it.name() + ": " + total + (total == 1 ? " Lagerort" : " Lagerorte"), lines.toArray(new Component[0]));
	}

	private static void melde(Ort o, BlockPos p, boolean vorhanden) {
		async(() -> {
			try { KistenApi.pruefung(o.welt(), o.dim(), p, vorhanden, null); } catch (ShopApi.ApiException e) { ObrienMod.LOG.info("[O'Brien] Prüfung nicht gespeichert: {}", e.getMessage()); }
		});
	}

	// ---------- /obrien setkiste ----------

	private static int set() {
		Ort o = ort(); if (o == null) return 0;
		Minecraft mc = Minecraft.getInstance();
		ItemStack st = mc.player.getMainHandItem();
		if (st.isEmpty()) { Notifier.error("Halte das Item, das in diese Kiste gehört, in der Haupthand."); return 0; }
		ItemSuche.Eintrag it = ItemSuche.vonItem(st.getItem());
		KistenOrt.Erkennung e = KistenOrt.anvisiert();
		if (e.ziel() == null) { Notifier.error(e.fehler()); return 0; }
		KistenOrt.Ziel z = e.ziel();
		async(() -> {
			if (!admin()) { Notifier.error("Kisten registrieren dürfen nur Admins (Admin-Schlüssel)."); return; }
			try {
				JsonObject r = KistenApi.registrieren(o.welt(), o.dim(), z, it.id());
				JsonObject loc = r.getAsJsonObject("location");
				main(() -> Hervorhebung.markiere(z, o.dim()));
				List<String> andere = new ArrayList<>();
				for (JsonElement x : loc.getAsJsonArray("items")) { String id = str(x.getAsJsonObject(), "item"); if (!id.equals(it.id())) andere.add(ItemSuche.name(id)); }
				// Gespeichert wird der anvisierte Block (bei Doppelkisten beide Hälften) – nie die Spielerposition.
				String wo = " (" + z.typ() + " · " + coords(z) + ")";
				if (r.get("created").getAsBoolean()) Notifier.ok("Kiste erfolgreich als Lager für " + it.name() + " registriert" + wo + ".");
				else Notifier.warn("Diese Kiste ist bereits für " + it.name() + " registriert" + wo + ".");
				if (r.has("merged") && r.get("merged").getAsBoolean()) Notifier.info("Frühere Einzelkisten-Registrierungen wurden zu dieser Doppelkiste zusammengeführt.");
				if (!andere.isEmpty()) Notifier.info("Ebenfalls hier registriert: " + String.join(", ", andere));
			} catch (ShopApi.ApiException ex) {
				Notifier.error(fehler(ex, "Die Registrierung konnte nicht gespeichert werden."));
			}
		});
		return 1;
	}

	// ---------- /obrien removekiste ----------

	private static int remove(String arg) {
		Ort o = ort(); if (o == null) return 0;
		KistenOrt.Erkennung e = KistenOrt.anvisiert();
		// Klickbare Auswahl enthält „@x,y,z“: So wird nie versehentlich eine andere Kiste geändert.
		String a = arg.trim();
		int at = a.lastIndexOf('@');
		BlockPos soll = null;
		if (at >= 0) {
			String[] c = a.substring(at + 1).split(",");
			try { soll = new BlockPos(Integer.parseInt(c[0]), Integer.parseInt(c[1]), Integer.parseInt(c[2])); }
			catch (Exception ex) { Notifier.error("Ungültige Auswahl."); return 0; }
			a = a.substring(0, at).trim();
		}
		KistenOrt.Ziel z;
		if (e.ziel() != null) {
			z = e.ziel();
			if (soll != null && !soll.equals(z.a()) && !soll.equals(z.b())) { Notifier.error("Du schaust inzwischen auf eine andere Kiste. Bitte schau wieder auf die Kiste " + coords(soll) + "."); return 0; }
		} else if (soll != null) {
			// Veraltete Registrierung einer zerstörten Kiste: nur erlaubt, wenn die Mod an dieser Stelle gerade sicher keine Kiste sieht.
			Minecraft lv = Minecraft.getInstance();
			if (!lv.level.hasChunk(soll.getX() >> 4, soll.getZ() >> 4)) { Notifier.error("Diese Stelle ist nicht geladen – geh näher heran, damit die Mod prüfen kann, ob dort noch eine Kiste steht."); return 0; }
			if (KistenOrt.erkenne(lv.level, soll).ziel() != null) { Notifier.error("Dort steht noch eine Kiste. Schau sie an, um die Zuordnung zu entfernen."); return 0; }
			z = new KistenOrt.Ziel("single", soll, null);
		} else { Notifier.error(e.fehler()); return 0; }
		String lower = a.toLowerCase(Locale.ROOT);
		ItemSuche.Eintrag gewaehlt = null;
		Minecraft mc = Minecraft.getInstance();
		ItemStack hand = mc.player.getMainHandItem();
		boolean alle = lower.equals("alle") || lower.equals("alle bestätigen");
		if (!a.isEmpty() && !alle) { gewaehlt = item(a, "/obrien removekiste", ""); if (gewaehlt == null) return 0; }
		ItemSuche.Eintrag handItem = hand.isEmpty() ? null : ItemSuche.vonItem(hand.getItem());
		ItemSuche.Eintrag ziel = gewaehlt;
		String tag = " @" + z.a().getX() + "," + z.a().getY() + "," + z.a().getZ();
		async(() -> {
			if (!admin()) { Notifier.error("Registrierungen entfernen dürfen nur Admins (Admin-Schlüssel)."); return; }
			try {
				JsonObject loc = KistenApi.an(o.welt(), o.dim(), z.a()).getAsJsonObject("location");
				if (loc == null) { Notifier.warn("Diese Kiste ist nicht registriert."); return; }
				List<String> ids = new ArrayList<>();
				for (JsonElement x : loc.getAsJsonArray("items")) ids.add(str(x.getAsJsonObject(), "item"));
				if (lower.equals("alle bestätigen")) {
					JsonObject r = KistenApi.entfernen(o.welt(), o.dim(), z.a(), null);
					Notifier.ok("Alle " + r.get("removed").getAsInt() + " Zuordnungen dieser Kiste wurden entfernt. Der Kisteninhalt bleibt unverändert.");
					return;
				}
				if (lower.equals("alle")) {
					Notifier.chat(Component.empty().append(Theme.t("Wirklich alle " + ids.size() + " Zuordnungen dieser Kiste entfernen? ", Theme.WARN))
						.append(Notifier.command("[Ja, alle entfernen]", "/obrien removekiste alle bestätigen" + tag, "Entfernt nur die Registrierungen, nicht die Items in der Kiste")));
					return;
				}
				String entfernen = ziel != null ? ziel.id() : handItem != null && ids.contains(handItem.id()) ? handItem.id() : ids.size() == 1 ? ids.get(0) : null;
				if (entfernen == null) {
					// Mehrere Zuordnungen: nichts automatisch löschen, sondern gezielt auswählen lassen.
					List<Component> lines = new ArrayList<>();
					lines.add(Theme.t(" Diese Kiste ist für " + ids.size() + " Items registriert. Welche Zuordnung soll entfernt werden?", Theme.TEXT));
					for (String id : ids) lines.add(zeile().append(Theme.t(ItemSuche.name(id) + "  ", Theme.CREAM)).append(Theme.t(id + "  ", Theme.DIM))
						.append(Notifier.command("[Entfernen]", "/obrien removekiste " + id + tag, "Nur diese Zuordnung entfernen")));
					lines.add(zeile().append(Notifier.command("[Alle entfernen …]", "/obrien removekiste alle" + tag, "Fragt noch einmal nach")));
					Notifier.block("Zuordnung entfernen", lines.toArray(new Component[0]));
					return;
				}
				if (!ids.contains(entfernen)) { Notifier.warn("Diese Kiste ist nicht für " + ItemSuche.name(entfernen) + " registriert."); return; }
				JsonObject r = KistenApi.entfernen(o.welt(), o.dim(), z.a(), entfernen);
				if (r.get("removed").getAsInt() == 0) { Notifier.warn("Die Zuordnung war bereits entfernt."); return; }
				JsonObject rest = r.has("location") && !r.get("location").isJsonNull() ? r.getAsJsonObject("location") : null;
				Notifier.ok("Zuordnung „" + ItemSuche.name(entfernen) + "“ entfernt. Der Kisteninhalt bleibt unverändert.");
				if (rest != null) {
					List<String> names = new ArrayList<>();
					for (JsonElement x : rest.getAsJsonArray("items")) names.add(ItemSuche.name(str(x.getAsJsonObject(), "item")));
					Notifier.info("Weiterhin registriert: " + String.join(", ", names));
				} else Notifier.info("Die Kiste ist jetzt nicht mehr registriert.");
			} catch (ShopApi.ApiException ex) {
				Notifier.error(fehler(ex, "Die Zuordnung konnte nicht entfernt werden."));
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
		int total = r.get("total").getAsInt(), page = r.get("page").getAsInt(), pages = r.get("pages").getAsInt();
		String filter = (item != null ? item + " " : "") + (dim != null ? KistenOrt.dimName(dim).toLowerCase(Locale.ROOT) + " " : "");
		String titel = "Registrierte Lagerorte" + (item != null ? " · " + ItemSuche.name(item) : "") + (dim != null ? " · " + KistenOrt.dimName(dim) : "");
		if (total == 0) { Notifier.warn(item != null || dim != null ? "Keine Registrierungen für diesen Filter." : "Auf diesem Server sind noch keine Kisten registriert."); return; }
		List<Component> lines = new ArrayList<>();
		lines.add(Theme.t(" " + total + (total == 1 ? " Registrierung" : " Registrierungen") + " · Seite " + page + " von " + pages, Theme.MUTED));
		for (JsonElement el : r.getAsJsonArray("entries")) {
			JsonObject loc = el.getAsJsonObject();
			JsonObject it = loc.getAsJsonArray("items").get(0).getAsJsonObject();
			String id = str(it, "item");
			lines.add(zeile().append(Theme.b(ItemSuche.name(id), Theme.GOLD)).append(Theme.t("  " + id, Theme.DIM)));
			lines.add(Component.empty().append(Theme.t("     ", Theme.DIM)).append(koordinaten(loc))
				.append(Theme.t("  " + KistenOrt.dimName(str(loc, "dimension")) + " · " + KistenOrt.typName(str(loc, "kind")) + " · ", Theme.MUTED)).append(status(loc)));
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
			MutableComponent m = Component.empty().append(Theme.t("Diese " + z.typ() + " (" + coords(z) + ") ist nicht registriert.", Theme.WARN));
			if (ObrienMod.isAdminKey()) m.append(Theme.t("  ", Theme.DIM)).append(Notifier.command("[Für Item in der Hand registrieren]", "/obrien setkiste", "Registriert diese Kiste für das Item in deiner Haupthand"));
			Notifier.chat(m);
			return;
		}
		List<Component> lines = new ArrayList<>();
		lines.add(zeile().append(Theme.t("Position  ", Theme.DIM)).append(koordinaten(loc)));
		lines.add(zeile().append(Theme.t("Typ        ", Theme.DIM)).append(Theme.t(KistenOrt.typName(str(loc, "kind")) + " · " + KistenOrt.dimName(str(loc, "dimension")), Theme.TEXT)));
		if (!str(loc, "kind").equals(z.kind())) lines.add(Theme.t("     ⚠ Registriert als " + KistenOrt.typName(str(loc, "kind")) + ", jetzt " + z.typ() + ". Mit /obrien setkiste aktualisieren.", Theme.WARN));
		lines.add(zeile().append(Theme.t("Status     ", Theme.DIM)).append(Theme.t("✔ Kiste vorhanden (gerade geprüft)", Theme.OK)));
		lines.add(zeile().append(Theme.t("Zugeordnete Items:", Theme.DIM)));
		for (JsonElement x : loc.getAsJsonArray("items")) {
			JsonObject it = x.getAsJsonObject();
			lines.add(Component.empty().append(Theme.t("     • ", Theme.DARK)).append(Theme.b(ItemSuche.name(str(it, "item")), Theme.GOLD))
				.append(Theme.t("  registriert " + vor(str(it, "registered_at")) + " · ", Theme.DIM)).append(bestand(it)));
		}
		lines.add(Theme.t(" Bestände werden nur beim Öffnen der Kiste durch einen Admin gezählt.", Theme.DIM));
		Notifier.block("Kisteninfo", lines.toArray(new Component[0]));
		if (ObrienMod.isAdminKey() && !"ok".equals(str(loc, "status"))) melde(o, z.a(), true);
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
		if (s == null) {
			Notifier.chat(Component.empty().append(Theme.t("Für " + it.name() + " ist kein Lagerort registriert – kein Bestand bekannt.  ", Theme.WARN))
				.append(Notifier.command("[Übersicht]", "/obrien bestand", "Alle Items mit registrierten Lagerorten")));
			return;
		}
		int h = i(r, "stale_hours");
		int fresh = i(s, "fresh"), stale = i(s, "stale"), unknown = i(s, "unknown"), missing = i(s, "missing"), empty = i(s, "empty");
		long fs = s.get("fresh_sum").getAsLong(), ss = s.get("stale_sum").getAsLong();
		List<Component> lines = new ArrayList<>();
		if (fresh + stale == 0) {
			// Nie gezählt: ausdrücklich unbekannt, nicht 0.
			lines.add(Theme.b(" Bestand unbekannt", Theme.WARN));
			lines.add(Theme.t(unknown + missing == 1 ? " Die registrierte Kiste wurde bisher von keinem Admin geöffnet und gezählt."
				: " Keine der " + (unknown + missing) + " Kisten wurde bisher von einem Admin geöffnet und gezählt.", Theme.MUTED));
		} else {
			lines.add(Component.empty().append(Theme.t(" Bekannter Gesamtbestand: ", Theme.TEXT)).append(Theme.b(zahl(fs + ss), Theme.GOLD))
				.append(Theme.t(" in " + kisten(fresh + stale), Theme.MUTED)));
			if (fresh > 0) lines.add(Theme.t(" ✔ " + zahl(fs) + " bestätigt aus " + kisten(fresh) + " (gezählt in den letzten " + h + " Std.)", Theme.OK));
			if (stale > 0) lines.add(Theme.t(" ◷ " + zahl(ss) + " aus " + kisten(stale) + " mit älterer Zählung (letzter bekannter Stand)", Theme.WARN));
		}
		if (unknown > 0 && fresh + stale > 0) lines.add(Theme.t(" ? Zusätzlich " + (unknown == 1 ? "1 Lagerkiste" : unknown + " Lagerkisten") + " mit unbekanntem Bestand – tatsächlicher Bestand kann höher sein.", Theme.WARN));
		if (missing > 0) lines.add(Theme.t(" ⚠ " + (missing == 1 ? "1 Lagerort" : missing + " Lagerorte") + " zuletzt nicht gefunden – nicht mitgezählt.", Theme.ERR));
		String last = str(s, "last_counted_at");
		if (empty > 0 || !last.isEmpty()) lines.add(Theme.t(" " + (empty > 0 ? (empty == 1 ? "1 Kiste leer" : empty + " Kisten leer") + (last.isEmpty() ? "" : " · ") : "")
			+ (last.isEmpty() ? "" : "Letzte Zählung " + vor(last)), Theme.DIM));
		if (s.has("min") && !s.get("min").isJsonNull()) {
			int m = s.get("min").getAsInt();
			lines.add(s.get("low").getAsBoolean() ? Theme.t(" ⚠ Mindestbestand " + zahl(m) + " unterschritten" + (unknown > 0 ? " (soweit bekannt)" : ""), Theme.ERR)
				: Theme.t(" Mindestbestand " + zahl(m) + " erreicht", Theme.MUTED));
		}
		int page = i(r, "page"), pages = i(r, "pages");
		lines.add(Theme.t(" Lagerorte" + (pages > 1 ? " · Seite " + page + " von " + pages : "") + ":", Theme.MUTED));
		for (JsonElement el : r.getAsJsonArray("entries")) {
			JsonObject e = el.getAsJsonObject();
			String st = str(e, "state");
			String n = e.get("count").isJsonNull() ? "?" : zahl(e.get("count").getAsLong());
			MutableComponent l = zeile().append(switch (st) {
				case "fresh" -> Theme.b(n, Theme.OK);
				case "stale" -> Theme.b(n, Theme.WARN);
				case "missing" -> Theme.t("–", Theme.ERR);
				default -> Theme.b("?", Theme.DIM);
			}).append(Theme.t("  ", Theme.DIM)).append(koordinaten(e))
				.append(Theme.t("  " + KistenOrt.dimName(str(e, "dimension")) + " · " + KistenOrt.typName(str(e, "kind")) + " · ", Theme.MUTED));
			String at = str(e, "counted_at");
			l.append(switch (st) {
				case "fresh" -> Theme.t("gezählt " + vor(at), Theme.MUTED);
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
		lines.add(Theme.t(" Gezählt wird beim Schließen einer registrierten Kiste durch einen Admin.", Theme.DIM));
		Notifier.block("Lagerbestand · " + it.name(), lines.toArray(new Component[0]));
	}

	private static void zeigeUebersicht(JsonObject r) {
		String sort = str(r, "sort"), filter = str(r, "filter");
		int total = i(r, "total_items"), low = i(r, "low_items"), unsure = i(r, "unsure_items"), page = i(r, "page"), pages = i(r, "pages");
		String opt = ("name".equals(sort) ? "name " : "") + ("low".equals(filter) ? "niedrig " : "unsure".equals(filter) ? "ungeprüft " : "");
		if (total == 0) {
			Notifier.warn(filter.isEmpty() ? "Auf diesem Server sind noch keine Kisten registriert." : "low".equals(filter) ? "Kein Item liegt unter seinem Mindestbestand." : "Alle Bestände sind aktuell gezählt.");
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
			l.append(Theme.t("  " + kisten(i(x, "locations")), Theme.DIM));
			if (stale > 0) l.append(Theme.t(" · ◷ " + stale + " älter", Theme.WARN));
			if (unknown > 0) l.append(Theme.t(" · ? " + unknown + " ungeprüft", Theme.DIM));
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
		lines.add(Theme.t(" Gelb = ältere Zählung · „+“ = weitere Kisten ungeprüft, Bestand kann höher sein.", Theme.DIM));
		Notifier.block("Lagerbestand · Übersicht", lines.toArray(new Component[0]));
	}
}
