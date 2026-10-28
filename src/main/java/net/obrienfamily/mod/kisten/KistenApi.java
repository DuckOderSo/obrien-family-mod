package net.obrienfamily.mod.kisten;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.obrienfamily.mod.ObrienMod;
import net.obrienfamily.mod.ShopApi;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Verbindung des Kistenfinders zur Website (/api/mod/chests). Alle Daten liegen dauerhaft in der Datenbank der Website;
 * die Mod hält nur einen kurzen Zwischenspeicher für wiederholte Suchen. Netzwerkzugriffe laufen auf einem eigenen Thread –
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

	/** Suche nach registrierten Lagerorten eines Items (Umkreis in der aktuellen Dimension oder „all“ = alle Dimensionen). */
	public static JsonObject suche(String welt, String dim, String item, double px, double py, double pz, boolean all, int radius) throws ShopApi.ApiException {
		// Zwischenspeicher: gleiche Suche an (fast) gleicher Stelle innerhalb von 20 s nicht erneut abfragen.
		String key = welt + "|" + dim + "|" + item + "|" + all + "|" + (all ? "" : Math.floorDiv((int) px, 4) + "," + Math.floorDiv((int) py, 4) + "," + Math.floorDiv((int) pz, 4));
		Eintrag c = CACHE.get(key);
		if (c != null && System.currentTimeMillis() - c.zeit() < TTL) return c.daten();
		JsonObject r = api().get("/api/mod/chests?mode=search&world=" + enc(welt) + "&dim=" + enc(dim) + "&item=" + enc(item)
			+ "&px=" + px + "&py=" + py + "&pz=" + pz + (all ? "&all=1" : "&radius=" + radius));
		CACHE.put(key, new Eintrag(r, System.currentTimeMillis()));
		return r;
	}

	public static JsonObject liste(String welt, String dim, String item, int seite) throws ShopApi.ApiException {
		return api().get("/api/mod/chests?mode=list&world=" + enc(welt) + "&page=" + seite + (dim != null ? "&dim=" + enc(dim) : "") + (item != null ? "&item=" + enc(item) : ""));
	}

	/**
	 * Lagerbestand: mit Item → Summe und Lagerorte (seitenweise), ohne Item → Übersicht aller Items.
	 * Die Website summiert per SQL; hier nur 20 s Zwischenspeicher, der bei jeder eigenen Änderung/Zählung geleert wird.
	 */
	public static JsonObject bestand(String welt, String item, String sort, String filter, int seite) throws ShopApi.ApiException {
		String key = "stock|" + welt + "|" + item + "|" + sort + "|" + filter + "|" + seite;
		Eintrag c = CACHE.get(key);
		if (c != null && System.currentTimeMillis() - c.zeit() < TTL) return c.daten();
		JsonObject r = api().get("/api/mod/chests?mode=stock&world=" + enc(welt) + "&page=" + seite + (item != null ? "&item=" + enc(item) : "")
			+ (sort != null ? "&sort=" + sort : "") + (filter != null ? "&filter=" + filter : ""));
		CACHE.put(key, new Eintrag(r, System.currentTimeMillis()));
		return r;
	}

	/** Mindestbestand setzen (null = entfernen). Nur Admin-Schlüssel – geprüft wird auf der Website. */
	public static JsonObject mindest(String welt, String item, Integer min) throws ShopApi.ApiException {
		JsonObject b = new JsonObject();
		b.addProperty("action", "min");
		b.addProperty("world", welt);
		b.addProperty("item", item);
		if (min == null) b.add("min", com.google.gson.JsonNull.INSTANCE); else b.addProperty("min", min);
		JsonObject r = api().send("POST", "/api/mod/chests", b);
		CACHE.clear();
		return r;
	}

	public static JsonObject an(String welt, String dim, BlockPos p) throws ShopApi.ApiException {
		return api().get("/api/mod/chests?mode=at&world=" + enc(welt) + "&dim=" + enc(dim) + "&x=" + p.getX() + "&y=" + p.getY() + "&z=" + p.getZ());
	}

	private static JsonArray pos(BlockPos p) {
		JsonArray a = new JsonArray();
		a.add(p.getX()); a.add(p.getY()); a.add(p.getZ());
		return a;
	}

	private static JsonObject basis(String action, String welt, String dim, BlockPos p) {
		JsonObject b = new JsonObject();
		b.addProperty("action", action);
		b.addProperty("world", welt);
		b.addProperty("dimension", dim);
		b.add("pos", pos(p));
		return b;
	}

	public static JsonObject registrieren(String welt, String dim, KistenOrt.Ziel z, String item) throws ShopApi.ApiException {
		JsonObject b = basis("add", welt, dim, z.a());
		b.addProperty("kind", z.kind());
		if (z.b() != null) b.add("pos2", pos(z.b()));
		b.addProperty("item", item);
		JsonObject r = api().send("POST", "/api/mod/chests", b);
		CACHE.clear();
		return r;
	}

	public static JsonObject entfernen(String welt, String dim, BlockPos p, String item) throws ShopApi.ApiException {
		JsonObject b = basis(item == null ? "remove_all" : "remove", welt, dim, p);
		if (item == null) b.addProperty("confirm", true); else b.addProperty("item", item);
		JsonObject r = api().send("POST", "/api/mod/chests", b);
		CACHE.clear();
		return r;
	}

	/** Ergebnis einer echten Prüfung melden: Kiste vorhanden/fehlt und optional beim Öffnen gezählte Bestände. */
	public static JsonObject pruefung(String welt, String dim, BlockPos p, boolean vorhanden, Map<String, Integer> zaehlung) throws ShopApi.ApiException {
		JsonObject b = basis("check", welt, dim, p);
		b.addProperty("exists", vorhanden);
		if (zaehlung != null && !zaehlung.isEmpty()) {
			JsonObject c = new JsonObject();
			zaehlung.forEach(c::addProperty);
			b.add("counts", c);
		}
		JsonObject r = api().send("POST", "/api/mod/chests", b);
		CACHE.clear();
		return r;
	}
}
