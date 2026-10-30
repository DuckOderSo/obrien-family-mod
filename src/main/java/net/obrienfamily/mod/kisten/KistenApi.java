package net.obrienfamily.mod.kisten;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.obrienfamily.mod.ObrienMod;
import net.obrienfamily.mod.ShopApi;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Verbindung des Lagersystems zur Website (/api/mod/chests). Alle Daten liegen dauerhaft in der Datenbank der Website;
 * die Mod hält nur einen kurzen Zwischenspeicher für wiederholte Abfragen. Netzwerkzugriffe laufen auf einem eigenen Thread –
 * nie im Spiel- oder Render-Takt.
 */
public final class KistenApi {
	private KistenApi() {}

	static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "obrien-kisten");
		t.setDaemon(true);
		return t;
	});

	private record Eintrag(JsonObject daten, long zeit) {}
	private static final Map<String, Eintrag> CACHE = new ConcurrentHashMap<>();
	private static final long TTL = 20_000;

	private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
	private static ShopApi api() { return ObrienMod.api(); }

	/** Lesen mit 20 s Zwischenspeicher (wird bei jeder eigenen Änderung geleert). */
	private static JsonObject cached(String path) throws ShopApi.ApiException {
		Eintrag c = CACHE.get(path);
		if (c != null && System.currentTimeMillis() - c.zeit() < TTL) return c.daten();
		JsonObject r = api().get(path);
		CACHE.put(path, new Eintrag(r, System.currentTimeMillis()));
		return r;
	}

	private static JsonObject send(JsonObject body) throws ShopApi.ApiException {
		JsonObject r = api().send("POST", "/api/mod/chests", body);
		CACHE.clear();
		return r;
	}

	private static JsonObject body(String action, String welt) {
		JsonObject b = new JsonObject();
		b.addProperty("action", action);
		b.addProperty("world", welt);
		return b;
	}

	static JsonArray pos(BlockPos p) {
		JsonArray a = new JsonArray();
		a.add(p.getX()); a.add(p.getY()); a.add(p.getZ());
		return a;
	}

	private static void ziel(JsonObject b, KistenOrt.Ziel z) {
		b.addProperty("kind", z.kind());
		b.add("pos", pos(z.a()));
		if (z.b() != null) b.add("pos2", pos(z.b()));
	}

	// ---------- Lesen ----------

	/** Alle Kisten in allen Lagerbereichen, die das Item enthalten – gruppiert nach Bereich. */
	public static JsonObject suche(String welt, String item) throws ShopApi.ApiException {
		return cached("/api/mod/chests?mode=search&world=" + enc(welt) + "&item=" + enc(item));
	}

	public static JsonObject liste(String welt, String dim, String item, int seite) throws ShopApi.ApiException {
		return api().get("/api/mod/chests?mode=list&world=" + enc(welt) + "&page=" + seite + (dim != null ? "&dim=" + enc(dim) : "") + (item != null ? "&item=" + enc(item) : ""));
	}

	/** Lagerbestand: mit Item → Summe und Kisten (seitenweise), ohne Item → Übersicht aller Items. Summiert wird per SQL auf der Website. */
	public static JsonObject bestand(String welt, String item, String sort, String filter, int seite) throws ShopApi.ApiException {
		return cached("/api/mod/chests?mode=stock&world=" + enc(welt) + "&page=" + seite + (item != null ? "&item=" + enc(item) : "")
			+ (sort != null ? "&sort=" + sort : "") + (filter != null ? "&filter=" + filter : ""));
	}

	public static JsonObject bereiche(String welt) throws ShopApi.ApiException {
		return cached("/api/mod/chests?mode=areas&world=" + enc(welt));
	}

	public static JsonObject bereich(String welt, String name) throws ShopApi.ApiException {
		return cached("/api/mod/chests?mode=area&world=" + enc(welt) + "&name=" + enc(name));
	}

	/** Alle Kisten und Bereiche dieses Servers (für Erkennung und Abbau-Prüfung; nur Admin-Clients). */
	public static JsonObject positionen(String welt) throws ShopApi.ApiException {
		return api().get("/api/mod/chests?mode=positions&world=" + enc(welt));
	}

	public static JsonObject an(String welt, String dim, BlockPos p) throws ShopApi.ApiException {
		return api().get("/api/mod/chests?mode=at&world=" + enc(welt) + "&dim=" + enc(dim) + "&x=" + p.getX() + "&y=" + p.getY() + "&z=" + p.getZ());
	}

	// ---------- Lagerbereiche (nur Admin-Schlüssel; geprüft wird auf der Website) ----------

	public static JsonObject bereichAnlegen(String welt, String name) throws ShopApi.ApiException {
		JsonObject b = body("area_create", welt);
		b.addProperty("name", name);
		return send(b);
	}

	public static JsonObject bereichPunkt(String welt, String name, int welcher, String dim, BlockPos p) throws ShopApi.ApiException {
		JsonObject b = body("area_point", welt);
		b.addProperty("name", name);
		b.addProperty("which", welcher);
		b.addProperty("dimension", dim);
		b.add("pos", pos(p));
		return send(b);
	}

	public static JsonObject bereichUmbenennen(String welt, String name, String neu) throws ShopApi.ApiException {
		JsonObject b = body("area_rename", welt);
		b.addProperty("name", name);
		b.addProperty("new_name", neu);
		return send(b);
	}

	public static JsonObject bereichLoeschen(String welt, String name) throws ShopApi.ApiException {
		JsonObject b = body("area_delete", welt);
		b.addProperty("name", name);
		return send(b);
	}

	// ---------- Automatische Erfassung ----------

	/** In Lagerbereichen gesehene Kisten melden (die Website legt nur Kisten innerhalb eines Bereichs an). */
	public static JsonObject erkannt(String welt, String dim, List<KistenOrt.Ziel> kisten) throws ShopApi.ApiException {
		JsonObject b = body("detect", welt);
		b.addProperty("dimension", dim);
		JsonArray a = new JsonArray();
		for (KistenOrt.Ziel z : kisten) { JsonObject k = new JsonObject(); ziel(k, z); a.add(k); }
		b.add("chests", a);
		return send(b);
	}

	/** Kompletter Inhalt beim Schließen: alle Items mit Menge (ersetzt den bisherigen Inhalt dieser Kiste). */
	public static JsonObject inhalt(String welt, String dim, KistenOrt.Ziel z, Map<String, Integer> zaehlung) throws ShopApi.ApiException {
		JsonObject b = body("inventory", welt);
		b.addProperty("dimension", dim);
		ziel(b, z);
		JsonObject c = new JsonObject();
		zaehlung.forEach(c::addProperty);
		b.add("counts", c);
		return send(b);
	}

	/** Echte Beobachtung: Kiste im geladenen Chunk vorhanden. */
	public static JsonObject pruefung(String welt, String dim, BlockPos p, boolean vorhanden) throws ShopApi.ApiException {
		JsonObject b = body("check", welt);
		b.addProperty("dimension", dim);
		b.add("pos", pos(p));
		b.addProperty("exists", vorhanden);
		return send(b);
	}

	/** Abbau einer erfassten Kiste melden: löscht den ganzen Lagerort samt aller Items. */
	public static JsonObject abgebaut(String welt, String dim, long id, BlockPos p) throws ShopApi.ApiException {
		JsonObject b = body("gone", welt);
		b.addProperty("dimension", dim);
		b.add("pos", pos(p));
		b.addProperty("id", id);
		return send(b);
	}

	/** Mindestbestand setzen (null = entfernen). */
	public static JsonObject mindest(String welt, String item, Integer min) throws ShopApi.ApiException {
		JsonObject b = body("min", welt);
		b.addProperty("item", item);
		if (min == null) b.add("min", JsonNull.INSTANCE); else b.addProperty("min", min);
		return send(b);
	}
}
