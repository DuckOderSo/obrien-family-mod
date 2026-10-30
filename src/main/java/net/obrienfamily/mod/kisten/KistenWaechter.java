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
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.obrienfamily.mod.Notifier;
import net.obrienfamily.mod.ObrienMod;
import net.obrienfamily.mod.ShopApi;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Hält das Lager automatisch aktuell – ohne Zugriff auf den Minecraft-Server, nur mit dem, was der Client sicher sieht (nur Admin-Schlüssel).
 * <ul>
 * <li><b>Erkennen:</b> Kisten/Fässer innerhalb eines Lagerbereichs werden automatisch erfasst, sobald der Client sie sieht – beim Laden
 * eines Chunks, beim Platzieren und nach dem Anlegen/Ändern eines Bereichs. Dafür werden nur die Block-Entities bereits geladener
 * Chunks gelesen (kein Block-für-Block-Scan, es wird nichts nachgeladen). Ihr Inhalt ist bis zum ersten Öffnen „unbekannt“.</li>
 * <li><b>Entfernen:</b> Fehlt eine erfasste Kiste bei ZWEI Prüfungen im Abstand von mindestens 2 Sekunden in einem geladenen Chunk,
 * wird sie samt Inhalt gelöscht (ein vom Server abgelehnter Abbau zählt so nicht). Nicht geladene Chunks führen nie zu einer Löschung.</li>
 * </ul>
 */
public final class KistenWaechter {
	private KistenWaechter() {}

	/** Erfasste Kiste (aus /api/mod/chests?mode=positions). */
	public record Reg(long id, String dim, String kind, BlockPos a, BlockPos b) {}

	/** Lagerbereich: Quader min–max (inklusive); unvollständig, solange ein Eckpunkt fehlt. */
	public record Bereich(long id, String name, String dim, BlockPos p1, BlockPos p2, BlockPos min, BlockPos max) {
		public boolean komplett() { return min != null; }
		public boolean enthaelt(String d, BlockPos p) {
			return komplett() && p != null && d.equals(dim) && p.getX() >= min.getX() && p.getX() <= max.getX()
				&& p.getY() >= min.getY() && p.getY() <= max.getY() && p.getZ() >= min.getZ() && p.getZ() <= max.getZ();
		}
	}

	public enum Zustand { VORHANDEN, FEHLT, UNGEPRUEFT }

	private static final class Verdacht { final Reg reg; long faellig; int fehlt; long erstesFehlen; long sperreBis; Verdacht(Reg r, long f) { reg = r; faellig = f; } }

	// Alles hier wird nur im Spiel-Thread gelesen und geschrieben; Netzwerkergebnisse kommen per mc.execute zurück.
	private static String welt;
	private static Map<Long, List<Reg>> proChunk = new HashMap<>();
	private static Set<String> bekannt = new HashSet<>();            // „dim|pos“ jeder Hälfte jeder erfassten Kiste
	private static List<Bereich> bereiche = List.of();
	private static long geladenAm, laedtSeit;
	private static boolean neuLaden = true, sendet;
	private static final Map<Long, Verdacht> VERDACHT = new HashMap<>();
	private static final Set<Long> LAEUFT = new HashSet<>();
	private static final Map<String, Long> ERKENNEN = new LinkedHashMap<>();  // „dim|pos“ → frühester Prüfzeitpunkt
	private static final Set<String> GEMELDET = new HashSet<>();           // bis zum nächsten Neuladen nicht erneut melden
	private static int takt;
	// Verschwundene Kiste, die (noch) nicht in der lokalen Liste steht – evtl. kürzlich von einem anderen Admin erfasst.
	private static BlockPos unbekannt;
	private static long unbekanntSeit, letzterAnlass, erkennenPauseBis;

	private static final long NEU_LADEN_MS = 10 * 60_000, ABSTAND_MS = 2_000, FEHLER_PAUSE_MS = 30_000;
	private static final int MAX_CHUNKS_SCAN = 4096, MAX_BATCH = 256;

	public static void init() {
		ClientPlayConnectionEvents.JOIN.register((h, s, mc) -> { welt = null; neuLaden = true; VERDACHT.clear(); ERKENNEN.clear(); GEMELDET.clear(); });
		// Kiste verschwindet aus dem Client: Abbau – oder nur ein entladener Chunk. Deshalb nur Prüfung vormerken.
		ClientBlockEntityEvents.BLOCK_ENTITY_UNLOAD.register((be, level) -> {
			if (KistenOrt.istLager(be.getBlockState().getBlock())) pruefeSpaeter(level, be.getBlockPos());
		});
		// Kiste taucht auf (Chunk geladen oder neu platziert): liegt sie in einem Lagerbereich, nach kurzer Wartezeit erfassen
		// (bis dahin ist auch die zweite Hälfte einer Doppelkiste geladen).
		ClientBlockEntityEvents.BLOCK_ENTITY_LOAD.register((be, level) -> vormerkenErkennen(level, be, 1_000));
		// Neu geladener Chunk (auch nach einem Neustart): erfasste Kisten darin einmal lokal prüfen.
		ClientChunkEvents.CHUNK_LOAD.register((level, chunk) -> {
			List<Reg> l = proChunk.get(chunk.getPos().pack());
			if (l != null) for (Reg r : l) vormerken(r, 1_000);
		});
	}

	// ---------- Abfragen für Befehle und Bestand ----------

	private static String key(String dim, BlockPos p) { return dim + "|" + p.asLong(); }

	public static List<Bereich> bereiche() { return bereiche; }

	/** Erster (ältester) Bereich, der diese Position enthält – oder null. */
	public static Bereich bereichAn(String dim, BlockPos p) {
		for (Bereich b : bereiche) if (b.enthaelt(dim, p)) return b;
		return null;
	}

	/** Gehört diese Kiste (eine ihrer Hälften) zum Lager? */
	public static boolean imLager(String dim, KistenOrt.Ziel z) { return bereichAn(dim, z.a()) != null || (z.b() != null && bereichAn(dim, z.b()) != null); }

	/** Gilt die Erfassung laut aktuellem Client-Zustand noch? Nur geladene Chunks der aktuellen Dimension werden bewertet. */
	public static Zustand zustand(String dim, String kind, BlockPos a, BlockPos b) {
		Minecraft mc = Minecraft.getInstance();
		ClientLevel level = mc.level;
		if (level == null || a == null || !dim.equals(KistenOrt.dimension()) || !geladen(level, a) || (b != null && !geladen(level, b))) return Zustand.UNGEPRUEFT;
		Block blk = level.getBlockState(a).getBlock();
		return switch (kind) {
			case "barrel" -> blk == Blocks.BARREL ? Zustand.VORHANDEN : Zustand.FEHLT;
			// Doppelkiste nur gültig, wenn beide erfassten Hälften noch genau miteinander verbunden sind.
			// Eine übrig gebliebene Einzelhälfte ist NICHT mehr die erfasste Doppelkiste.
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
		vormerken(reg(loc), 0);
	}

	/** Nach eigenen Änderungen (Bereiche) die Liste beim nächsten Takt neu laden – danach werden geladene Bereiche neu durchsucht. */
	public static void veraltet() { Minecraft.getInstance().execute(() -> neuLaden = true); }

	/** Kiste, die gerade gespeichert wurde (z. B. beim Schließen), sofort in die lokale Liste übernehmen. */
	public static void merke(JsonObject loc) {
		Minecraft.getInstance().execute(() -> {
			Reg r = reg(loc);
			for (List<Reg> l : proChunk.values()) l.removeIf(x -> x.id() == r.id());
			eintragen(proChunk, bekannt, r);
		});
	}

	private static Reg reg(JsonObject o) {
		return new Reg(o.get("id").getAsLong(), KistenBefehle.str(o, "dimension"), KistenBefehle.str(o, "kind"), KistenBefehle.pos(o.get("pos")), KistenBefehle.pos(o.get("pos2")));
	}

	private static void eintragen(Map<Long, List<Reg>> m, Set<String> k, Reg reg) {
		m.computeIfAbsent(ChunkPos.pack(reg.a().getX() >> 4, reg.a().getZ() >> 4), x -> new ArrayList<>()).add(reg);
		k.add(key(reg.dim(), reg.a()));
		if (reg.b() != null) {
			k.add(key(reg.dim(), reg.b()));
			if (reg.b().getX() >> 4 != reg.a().getX() >> 4 || reg.b().getZ() >> 4 != reg.a().getZ() >> 4)
				m.computeIfAbsent(ChunkPos.pack(reg.b().getX() >> 4, reg.b().getZ() >> 4), x -> new ArrayList<>()).add(reg);
		}
	}

	// ---------- Abbau ----------

	private static void pruefeSpaeter(ClientLevel level, BlockPos p) {
		boolean da = false;
		List<Reg> l = proChunk.get(ChunkPos.pack(p.getX() >> 4, p.getZ() >> 4));
		if (l != null) for (Reg r : l) if (p.equals(r.a()) || p.equals(r.b())) { vormerken(r, 1_000); da = true; }
		if (!da && ObrienMod.isAdminKey()) { unbekannt = p.immutable(); unbekanntSeit = System.currentTimeMillis(); }
	}

	private static void vormerken(Reg r, long inMs) {
		if (!ObrienMod.isAdminKey() || LAEUFT.contains(r.id())) return;
		VERDACHT.computeIfAbsent(r.id(), k -> new Verdacht(r, System.currentTimeMillis() + inMs));
	}

	// ---------- Erkennen ----------

	private static void vormerkenErkennen(ClientLevel level, BlockEntity be, long inMs) {
		if (!ObrienMod.isAdminKey() || bereiche.isEmpty() || !KistenOrt.istLager(be.getBlockState().getBlock())) return;
		String dim = level.dimension().identifier().toString();
		BlockPos p = be.getBlockPos();
		if (bereichAn(dim, p) == null || bekannt.contains(key(dim, p))) return;
		ERKENNEN.putIfAbsent(key(dim, p.immutable()), System.currentTimeMillis() + inMs);
	}

	/** Bereits geladene Chunks der Lagerbereiche (aktuelle Dimension) einmal durchgehen – nur deren Block-Entities, nichts wird geladen. */
	private static void durchsuche() {
		ClientLevel level = Minecraft.getInstance().level;
		String dim = KistenOrt.dimension();
		if (level == null || dim == null) return;
		for (Bereich b : bereiche) {
			if (!b.komplett() || !dim.equals(b.dim())) continue;
			int n = 0;
			for (int cx = b.min().getX() >> 4; cx <= b.max().getX() >> 4; cx++)
				for (int cz = b.min().getZ() >> 4; cz <= b.max().getZ() >> 4; cz++) {
					if (++n > MAX_CHUNKS_SCAN) return;
					if (!(level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false) instanceof LevelChunk c)) continue;
					for (BlockEntity be : c.getBlockEntities().values()) vormerkenErkennen(level, be, 0);
				}
		}
	}

	private static void erkennen(String w, long now) {
		if (sendet || ERKENNEN.isEmpty() || now < erkennenPauseBis) return;
		ClientLevel level = Minecraft.getInstance().level;
		String dim = KistenOrt.dimension();
		List<KistenOrt.Ziel> batch = new ArrayList<>();
		Set<String> dabei = new HashSet<>();
		for (var it = ERKENNEN.entrySet().iterator(); it.hasNext() && batch.size() < MAX_BATCH; ) {
			var e = it.next();
			if (e.getValue() > now) continue;
			it.remove();
			String[] k = e.getKey().split("\\|");
			if (!k[0].equals(dim)) continue; // andere Dimension: wird beim nächsten Laden erneut gefunden
			BlockPos p = BlockPos.of(Long.parseLong(k[1]));
			if (!geladen(level, p) || bekannt.contains(e.getKey()) || GEMELDET.contains(e.getKey()) || dabei.contains(e.getKey())) continue;
			KistenOrt.Erkennung er = KistenOrt.erkenne(level, p);
			if (er.ziel() == null || !imLager(dim, er.ziel())) continue;
			KistenOrt.Ziel z = er.ziel();
			dabei.add(key(dim, z.a()));
			if (z.b() != null) dabei.add(key(dim, z.b()));
			batch.add(z);
		}
		if (batch.isEmpty()) return;
		sendet = true;
		GEMELDET.addAll(dabei);
		KistenApi.IO.execute(() -> {
			try {
				JsonObject r = KistenApi.erkannt(w, dim, batch);
				ObrienMod.LOG.debug("[O'Brien] Lager: {} Kisten neu erfasst", r.has("added") ? r.get("added").getAsInt() : 0);
				Minecraft.getInstance().execute(() -> { sendet = false; neuLaden = true; });
			} catch (ShopApi.ApiException e) {
				// Website nicht erreichbar: später erneut versuchen (es wird nichts doppelt angelegt – die Website prüft die Position).
				Minecraft.getInstance().execute(() -> {
					sendet = false;
					GEMELDET.removeAll(dabei);
					erkennenPauseBis = System.currentTimeMillis() + FEHLER_PAUSE_MS;
					for (KistenOrt.Ziel z : batch) ERKENNEN.putIfAbsent(key(dim, z.a()), 0L);
				});
				ObrienMod.LOG.info("[O'Brien] Lager: Kisten konnten nicht erfasst werden: {}", e.getMessage());
			}
		});
	}

	// ---------- Takt ----------

	/** Einmal pro Client-Tick aufgerufen; arbeitet nur alle 10 Ticks und nur lokal (Netzwerk nur beim Neuladen, Erfassen, Löschen). */
	public static void tick() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || mc.player == null || ++takt % 10 != 0) return;
		if (!ObrienMod.isAdminKey() || !ObrienMod.config().hasToken()) { VERDACHT.clear(); ERKENNEN.clear(); return; }
		String w = KistenOrt.welt();
		if (w == null) return;
		long now = System.currentTimeMillis();
		if (!w.equals(welt)) { welt = w; proChunk = new HashMap<>(); bekannt = new HashSet<>(); bereiche = List.of(); VERDACHT.clear(); ERKENNEN.clear(); neuLaden = true; }
		// Unbekannte Kiste ist nach 2 s im geladenen Chunk immer noch weg (also kein Entladen): Liste einmal auffrischen, max. 1×/Minute.
		if (unbekannt != null && now - unbekanntSeit >= ABSTAND_MS) {
			if (KistenOrt.geladen(mc.level, unbekannt) && !KistenOrt.istLager(mc.level.getBlockState(unbekannt).getBlock()) && now - letzterAnlass > 60_000) {
				letzterAnlass = now;
				neuLaden = true;
			}
			unbekannt = null;
		}
		if ((neuLaden || now - geladenAm > NEU_LADEN_MS) && now - laedtSeit > (neuLaden ? 1_000 : 15_000)) laden(w);
		erkennen(w, now);
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
				Set<String> k = new HashSet<>();
				for (JsonElement e : r.getAsJsonArray("locations")) eintragen(m, k, reg(e.getAsJsonObject()));
				List<Bereich> b = new ArrayList<>();
				if (r.has("areas")) for (JsonElement e : r.getAsJsonArray("areas")) b.add(bereich(e.getAsJsonObject()));
				Minecraft.getInstance().execute(() -> {
					if (!w.equals(welt)) return;
					proChunk = m; bekannt = k; bereiche = List.copyOf(b);
					geladenAm = System.currentTimeMillis();
					GEMELDET.clear();
					// Bereits geladene Chunks einmal prüfen (z. B. direkt nach dem Betreten der Welt oder nach einer Bereichsänderung).
					ClientLevel level = Minecraft.getInstance().level;
					if (level != null) for (List<Reg> l : m.values()) for (Reg reg : l) if (geladen(level, reg.a())) vormerken(reg, 1_000);
					durchsuche();
				});
			} catch (ShopApi.ApiException e) {
				// Website nicht erreichbar: alte Liste behalten, später erneut versuchen. Es wird nichts gelöscht.
				Minecraft.getInstance().execute(() -> neuLaden = true);
			}
		});
	}

	static Bereich bereich(JsonObject o) {
		BlockPos p1 = KistenBefehle.pos(o.get("p1")), p2 = KistenBefehle.pos(o.get("p2"));
		BlockPos min = p1 == null || p2 == null ? null : new BlockPos(Math.min(p1.getX(), p2.getX()), Math.min(p1.getY(), p2.getY()), Math.min(p1.getZ(), p2.getZ()));
		BlockPos max = p1 == null || p2 == null ? null : new BlockPos(Math.max(p1.getX(), p2.getX()), Math.max(p1.getY(), p2.getY()), Math.max(p1.getZ(), p2.getZ()));
		return new Bereich(o.get("id").getAsLong(), KistenBefehle.str(o, "name"), KistenBefehle.str(o, "dimension"), p1, p2, min, max);
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
					bekannt.remove(key(r.dim(), r.a()));
					if (r.b() != null) bekannt.remove(key(r.dim(), r.b()));
					// Übrig gebliebene Hälfte einer Doppelkiste ist jetzt eine eigene Einzelkiste → neu erkennen.
					neuLaden = true;
					if (!res.get("removed").getAsBoolean()) return; // bereits entfernt oder inzwischen geändert
					Bereich b = bereichAn(r.dim(), r.a());
					Notifier.info(KistenOrt.typName(r.kind()) + " abgebaut" + (b != null ? " (" + b.name() + ")" : "") + " – aus dem Lager entfernt.");
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
