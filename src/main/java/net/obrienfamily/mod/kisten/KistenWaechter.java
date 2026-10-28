package net.obrienfamily.mod.kisten;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientBlockEntityEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.obrienfamily.mod.Notifier;
import net.obrienfamily.mod.ObrienMod;
import net.obrienfamily.mod.ShopApi;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Bereinigt Registrierungen abgebauter Kisten automatisch – ohne Zugriff auf den Minecraft-Server, nur mit dem, was der Client sicher sieht.
 * Auslöser (nur Hinweise, nie allein ausreichend): eine Kiste verschwindet aus dem Client (Block-Entity entladen), ein Chunk mit registrierter
 * Kiste wird geladen, oder ein Befehl zeigt eine fehlende Kiste an. Gelöscht wird erst, wenn die Kiste bei ZWEI Prüfungen im Abstand von
 * mindestens 2 Sekunden in einem geladenen Chunk fehlt (ein vom Server abgelehnter Abbau wird so nicht als Abbau gewertet).
 * Nicht geladene Chunks gelten immer als „ungeprüft“ und führen nie zu einer Löschung. Es werden keine Chunks geladen oder durchsucht.
 * Schreiben dürfen nur Admin-Schlüssel – Spieler-Clients zeigen fehlende Kisten nur an.
 */
public final class KistenWaechter {
	private KistenWaechter() {}

	/** Registrierter Lagerort (aus /api/mod/chests?mode=positions). */
	public record Reg(long id, String dim, String kind, BlockPos a, BlockPos b) {}

	public enum Zustand { VORHANDEN, FEHLT, UNGEPRUEFT }

	private static final class Verdacht { final Reg reg; long faellig; int fehlt; long erstesFehlen; long sperreBis; Verdacht(Reg r, long f) { reg = r; faellig = f; } }

	// Alles hier wird nur im Spiel-Thread gelesen und geschrieben; Netzwerkergebnisse kommen per mc.execute zurück.
	private static String welt;
	private static Map<Long, List<Reg>> proChunk = new HashMap<>();
	private static long geladenAm, laedtSeit;
	private static boolean neuLaden = true;
	private static final Map<Long, Verdacht> VERDACHT = new HashMap<>();
	private static final Set<Long> LAEUFT = new HashSet<>();
	private static int takt;
	// Verschwundene Kiste, die (noch) nicht in der lokalen Liste steht – evtl. kürzlich von einem anderen Admin registriert.
	private static BlockPos unbekannt;
	private static long unbekanntSeit, letzterAnlass;

	private static final long NEU_LADEN_MS = 10 * 60_000, ABSTAND_MS = 2_000, FEHLER_PAUSE_MS = 30_000;

	public static void init() {
		ClientPlayConnectionEvents.JOIN.register((h, s, mc) -> { welt = null; neuLaden = true; VERDACHT.clear(); });
		// Kiste verschwindet aus dem Client: Abbau – oder nur ein entladener Chunk. Deshalb nur Prüfung vormerken.
		ClientBlockEntityEvents.BLOCK_ENTITY_UNLOAD.register((be, level) -> {
			if (KistenOrt.istLager(be.getBlockState().getBlock())) pruefeSpaeter(level, be.getBlockPos());
		});
		// Neu geladener Chunk (auch nach einem Neustart): registrierte Kisten darin einmal lokal prüfen.
		ClientChunkEvents.CHUNK_LOAD.register((level, chunk) -> {
			List<Reg> l = proChunk.get(chunk.getPos().pack());
			if (l != null) for (Reg r : l) vormerken(r, 1_000);
		});
	}

	/** Gilt die Registrierung laut aktuellem Client-Zustand noch? Nur geladene Chunks der aktuellen Dimension werden bewertet. */
	public static Zustand zustand(String dim, String kind, BlockPos a, BlockPos b) {
		Minecraft mc = Minecraft.getInstance();
		ClientLevel level = mc.level;
		if (level == null || a == null || !dim.equals(KistenOrt.dimension()) || !geladen(level, a) || (b != null && !geladen(level, b))) return Zustand.UNGEPRUEFT;
		Block blk = level.getBlockState(a).getBlock();
		return switch (kind) {
			case "barrel" -> blk == Blocks.BARREL ? Zustand.VORHANDEN : Zustand.FEHLT;
			// Doppelkiste nur gültig, wenn beide registrierten Hälften noch genau miteinander verbunden sind.
			// Eine übrig gebliebene Einzelhälfte ist NICHT mehr die registrierte Doppelkiste.
			case "double" -> {
				KistenOrt.Erkennung e = KistenOrt.erkenne(level, a);
				yield e.ziel() != null && "double".equals(e.ziel().kind()) && b != null && b.equals(e.ziel().b()) ? Zustand.VORHANDEN : Zustand.FEHLT;
			}
			default -> KistenOrt.istKiste(blk) ? Zustand.VORHANDEN : Zustand.FEHLT;
		};
	}

	public static Zustand zustand(JsonObject loc) {
		return zustand(KistenBefehle.str(loc, "dimension"), KistenBefehle.str(loc, "kind"), KistenBefehle.pos(loc.get("pos")), KistenBefehle.pos(loc.get("pos2")));
	}

	private static boolean geladen(ClientLevel level, BlockPos p) { return KistenOrt.geladen(level, p); }

	/** Ein Befehl hat eine fehlende Kiste gesehen: zur sicheren Bereinigung vormerken (nur Admin-Schlüssel). */
	public static void gesehen(JsonObject loc) {
		if (!ObrienMod.isAdminKey() || !loc.has("id")) return;
		vormerken(new Reg(loc.get("id").getAsLong(), KistenBefehle.str(loc, "dimension"), KistenBefehle.str(loc, "kind"),
			KistenBefehle.pos(loc.get("pos")), KistenBefehle.pos(loc.get("pos2"))), 0);
	}

	/** Nach eigenen Änderungen (registrieren/entfernen) die Positionsliste beim nächsten Takt neu laden. */
	public static void veraltet() { Minecraft.getInstance().execute(() -> neuLaden = true); }

	private static void pruefeSpaeter(ClientLevel level, BlockPos p) {
		boolean bekannt = false;
		List<Reg> l = proChunk.get(ChunkPos.pack(p.getX() >> 4, p.getZ() >> 4));
		if (l != null) for (Reg r : l) if (p.equals(r.a()) || p.equals(r.b())) { vormerken(r, 1_000); bekannt = true; }
		if (!bekannt && ObrienMod.isAdminKey()) { unbekannt = p.immutable(); unbekanntSeit = System.currentTimeMillis(); }
	}

	private static void vormerken(Reg r, long inMs) {
		if (!ObrienMod.isAdminKey() || LAEUFT.contains(r.id())) return;
		VERDACHT.computeIfAbsent(r.id(), k -> new Verdacht(r, System.currentTimeMillis() + inMs));
	}

	/** Einmal pro Client-Tick aufgerufen; arbeitet nur alle 10 Ticks und nur lokal (Netzwerk nur beim Neuladen bzw. Löschen). */
	public static void tick() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || mc.player == null || ++takt % 10 != 0) return;
		if (!ObrienMod.isAdminKey() || !ObrienMod.config().hasToken()) { VERDACHT.clear(); return; }
		String w = KistenOrt.welt();
		if (w == null) return;
		long now = System.currentTimeMillis();
		if (!w.equals(welt)) { welt = w; proChunk = new HashMap<>(); VERDACHT.clear(); neuLaden = true; }
		// Unbekannte Kiste ist nach 2 s im geladenen Chunk immer noch weg (also kein Entladen): Liste einmal auffrischen, max. 1×/Minute.
		if (unbekannt != null && now - unbekanntSeit >= ABSTAND_MS) {
			if (KistenOrt.geladen(mc.level, unbekannt) && !KistenOrt.istLager(mc.level.getBlockState(unbekannt).getBlock()) && now - letzterAnlass > 60_000) {
				letzterAnlass = now;
				neuLaden = true;
			}
			unbekannt = null;
		}
		if ((neuLaden || now - geladenAm > NEU_LADEN_MS) && now - laedtSeit > 15_000) laden(w);
		for (var it = VERDACHT.values().iterator(); it.hasNext(); ) {
			Verdacht v = it.next();
			if (v.faellig > now || v.sperreBis > now || LAEUFT.contains(v.reg.id())) continue;
			Zustand z = zustand(v.reg.dim(), v.reg.kind(), v.reg.a(), v.reg.b());
			if (z != Zustand.FEHLT) { it.remove(); continue; } // vorhanden oder nicht prüfbar → nichts löschen
			if (v.fehlt++ == 0) { v.erstesFehlen = now; v.faellig = now + ABSTAND_MS; continue; }
			if (now - v.erstesFehlen < ABSTAND_MS) { v.faellig = v.erstesFehlen + ABSTAND_MS; continue; }
			loeschen(w, v);
		}
	}

	private static void laden(String w) {
		laedtSeit = System.currentTimeMillis();
		neuLaden = false;
		KistenApi.IO.execute(() -> {
			try {
				JsonObject r = KistenApi.positionen(w);
				Map<Long, List<Reg>> m = new HashMap<>();
				for (JsonElement e : r.getAsJsonArray("locations")) {
					JsonObject o = e.getAsJsonObject();
					Reg reg = new Reg(o.get("id").getAsLong(), KistenBefehle.str(o, "dimension"), KistenBefehle.str(o, "kind"), KistenBefehle.pos(o.get("pos")), KistenBefehle.pos(o.get("pos2")));
					m.computeIfAbsent(ChunkPos.pack(reg.a().getX() >> 4, reg.a().getZ() >> 4), k -> new ArrayList<>()).add(reg);
					if (reg.b() != null && (reg.b().getX() >> 4 != reg.a().getX() >> 4 || reg.b().getZ() >> 4 != reg.a().getZ() >> 4))
						m.computeIfAbsent(ChunkPos.pack(reg.b().getX() >> 4, reg.b().getZ() >> 4), k -> new ArrayList<>()).add(reg);
				}
				Minecraft.getInstance().execute(() -> {
					if (!w.equals(welt)) return;
					proChunk = m;
					geladenAm = System.currentTimeMillis();
					// Bereits geladene Chunks einmal prüfen (z. B. direkt nach dem Betreten der Welt).
					ClientLevel level = Minecraft.getInstance().level;
					if (level != null) for (List<Reg> l : m.values()) for (Reg reg : l) if (geladen(level, reg.a())) vormerken(reg, 1_000);
				});
			} catch (ShopApi.ApiException e) {
				// Website nicht erreichbar: alte Liste behalten, später erneut versuchen. Es wird nichts gelöscht.
				Minecraft.getInstance().execute(() -> neuLaden = true);
			}
		});
	}

	private static void loeschen(String w, Verdacht v) {
		Reg r = v.reg;
		LAEUFT.add(r.id());
		// Gemeldet wird eine Hälfte, die wirklich fehlt (bei Doppelkisten ggf. die zweite).
		ClientLevel level = Minecraft.getInstance().level;
		BlockPos p = r.b() != null && level != null && KistenOrt.istKiste(level.getBlockState(r.a()).getBlock()) ? r.b() : r.a();
		KistenApi.IO.execute(() -> {
			try {
				JsonObject res = KistenApi.abgebaut(w, r.dim(), r.id(), p);
				Minecraft.getInstance().execute(() -> {
					LAEUFT.remove(r.id());
					VERDACHT.remove(r.id());
					for (List<Reg> l : proChunk.values()) l.removeIf(x -> x.id() == r.id());
					if (!res.get("removed").getAsBoolean()) return; // bereits entfernt oder inzwischen geändert
					List<String> namen = new ArrayList<>();
					for (JsonElement e : res.getAsJsonArray("items")) namen.add(ItemSuche.name(e.getAsString()));
					Notifier.info("Abgebaute " + KistenOrt.typName(r.kind()) + " bei " + r.a().getX() + " " + r.a().getY() + " " + r.a().getZ()
						+ (r.b() != null ? " + " + r.b().getX() + " " + r.b().getY() + " " + r.b().getZ() : "")
						+ ": Registrierung entfernt" + (namen.isEmpty() ? "." : " (" + String.join(", ", namen) + ")."));
				});
			} catch (ShopApi.ApiException e) {
				// Nicht erreichbar oder abgelehnt: Verdacht behalten, nach einer Pause neu prüfen (dann wieder zwei Beobachtungen).
				Minecraft.getInstance().execute(() -> {
					LAEUFT.remove(r.id());
					v.fehlt = 0;
					v.sperreBis = System.currentTimeMillis() + FEHLER_PAUSE_MS;
					if (e.status == 403) VERDACHT.remove(r.id());
				});
				ObrienMod.LOG.info("[O'Brien] Abgebaute Kiste konnte nicht gemeldet werden: {}", e.getMessage());
			}
		});
	}
}
