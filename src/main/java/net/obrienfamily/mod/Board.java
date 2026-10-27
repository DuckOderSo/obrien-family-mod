package net.obrienfamily.mod;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Gemeinsamer Zustand des Family Task Boards für alle Board-Fenster der Mod.
 * Dieselben Daten und Regeln wie auf der Website: Alle Änderungen laufen über /api/mod/board, das serverseitig
 * dieselben Funktionen nutzt wie das Board im Admin-Bereich (Prüfung, Versionskontrolle, Verlauf).
 */
public final class Board {
	public static final Board I = new Board();

	/** Eigener Thread, damit Board-Aktionen nicht hinter der Benachrichtigungs-Abfrage warten und in Reihenfolge laufen. */
	private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "obrien-board");
		t.setDaemon(true);
		return t;
	});

	public JsonObject meta;                       // me, members, statuses, priorities, tags, limits
	public final List<JsonObject> tasks = new ArrayList<>();
	public List<JsonObject> archived;             // null = noch nicht geladen
	public String today = "";
	public boolean loading;
	public String error;
	public String loadedAt = "";
	public long lastLoad;

	public record Toast(boolean ok, String text, long until) {}
	public final List<Toast> toasts = new ArrayList<>();

	private Board() {}

	private static void ui(Runnable r) { Minecraft.getInstance().execute(r); }

	public void toast(boolean ok, String text) {
		toasts.add(new Toast(ok, text, System.currentTimeMillis() + (ok ? 3500 : 7000)));
		while (toasts.size() > 4) toasts.remove(0);
	}

	// ---------- Laden ----------

	public void load(boolean quiet) {
		if (loading) return;
		loading = true;
		if (!quiet) error = null;
		io.execute(() -> {
			try {
				JsonObject r = ObrienMod.api.get("/api/mod/board");
				ui(() -> {
					meta = r;
					today = str(r, "today");
					tasks.clear();
					for (JsonElement e : r.getAsJsonArray("tasks")) tasks.add(e.getAsJsonObject());
					loading = false;
					error = null;
					lastLoad = System.currentTimeMillis();
					loadedAt = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
				});
			} catch (ShopApi.ApiException e) {
				ui(() -> { loading = false; error = e.getMessage(); lastLoad = System.currentTimeMillis(); });
			}
		});
	}

	public void loadArchive() {
		io.execute(() -> {
			try {
				JsonObject r = ObrienMod.api.get("/api/mod/board?archived=1");
				ui(() -> {
					List<JsonObject> l = new ArrayList<>();
					for (JsonElement e : r.getAsJsonArray("tasks")) l.add(e.getAsJsonObject());
					archived = l;
				});
			} catch (ShopApi.ApiException e) {
				ui(() -> toast(false, e.getMessage().isEmpty() ? "Das Archiv konnte nicht geladen werden." : e.getMessage()));
			}
		});
	}

	public void detail(String id, Consumer<JsonObject> ok, Consumer<String> fail) {
		io.execute(() -> {
			try {
				JsonObject r = ObrienMod.api.get("/api/mod/board/" + id);
				ui(() -> ok.accept(r));
			} catch (ShopApi.ApiException e) {
				ui(() -> fail.accept(e.getMessage()));
			}
		});
	}

	// ---------- Ändern ----------

	/** Aufgabe im Board ersetzen, einfügen oder (wenn archiviert) entfernen – wie auf der Website. */
	public void replace(JsonObject t) {
		String id = str(t, "id");
		boolean arch = t.has("archived_at") && !t.get("archived_at").isJsonNull();
		int i = indexOf(tasks, id);
		if (arch) { if (i >= 0) tasks.remove(i); }
		else if (i >= 0) tasks.set(i, t);
		else tasks.add(0, t);
		if (archived != null) {
			int j = indexOf(archived, id);
			if (arch) { if (j >= 0) archived.set(j, t); else archived.add(0, t); }
			else if (j >= 0) archived.remove(j);
		}
	}

	private static int indexOf(List<JsonObject> l, String id) {
		for (int i = 0; i < l.size(); i++) if (str(l.get(i), "id").equals(id)) return i;
		return -1;
	}

	public JsonObject find(String id) {
		int i = indexOf(tasks, id);
		if (i >= 0) return tasks.get(i);
		if (archived != null && (i = indexOf(archived, id)) >= 0) return archived.get(i);
		return null;
	}

	/**
	 * Speichert Änderungen mit Versionsprüfung. Im Board sofort sichtbar (optimistisch), bei Fehler zurückgesetzt.
	 * Bei einem Konflikt (jemand anderes hat inzwischen geändert) wird die aktuelle Version übernommen – nichts wird überschrieben.
	 */
	public void patch(JsonObject t, JsonObject changes, String okText, Consumer<JsonObject> done) {
		JsonObject before = t.deepCopy();
		if (!changes.has("archived")) {
			JsonObject opt = t.deepCopy();
			for (String k : changes.keySet()) opt.add(k, changes.get(k));
			int i = indexOf(tasks, str(t, "id"));
			if (i >= 0) tasks.set(i, opt);
		}
		JsonObject body = changes.deepCopy();
		body.addProperty("version", t.get("version").getAsInt());
		io.execute(() -> {
			try {
				JsonObject r = ObrienMod.api.send("PATCH", "/api/mod/board/" + str(t, "id"), body);
				ui(() -> { JsonObject n = r.getAsJsonObject("task"); replace(n); toast(true, okText); if (done != null) done.accept(n); });
			} catch (ShopApi.ApiException e) {
				ui(() -> {
					if (e.status == 409 && e.body.has("task")) replace(e.body.getAsJsonObject("task"));
					else if (indexOf(tasks, str(before, "id")) >= 0) replace(before);
					toast(false, e.getMessage());
					if (e.status == 404) { tasks.removeIf(x -> str(x, "id").equals(str(before, "id"))); load(true); }
					if (done != null) done.accept(null);
				});
			}
		});
	}

	public void moveTo(JsonObject t, String status) {
		if (status.equals(str(t, "status"))) return;
		JsonObject c = new JsonObject();
		c.addProperty("status", status);
		patch(t, c, "„" + str(t, "title") + "“ → " + statusLabel(status), null);
	}

	/** Neue Aufgabe oder vollständige Bearbeitung (Formular). Fehler gehen ungefiltert an das Formular zurück. */
	public void save(JsonObject base, JsonObject body, Consumer<JsonObject> ok, Consumer<ShopApi.ApiException> fail) {
		JsonObject b = body.deepCopy();
		if (base != null) b.addProperty("version", base.get("version").getAsInt());
		io.execute(() -> {
			try {
				JsonObject r = base == null ? ObrienMod.api.send("POST", "/api/mod/board", b) : ObrienMod.api.send("PATCH", "/api/mod/board/" + str(base, "id"), b);
				ui(() -> { JsonObject n = r.getAsJsonObject("task"); replace(n); ok.accept(n); });
			} catch (ShopApi.ApiException e) {
				ui(() -> { if (e.status == 409 && e.body.has("task")) replace(e.body.getAsJsonObject("task")); fail.accept(e); });
			}
		});
	}

	public void duplicate(JsonObject t, Consumer<JsonObject> ok) {
		io.execute(() -> {
			try {
				JsonObject r = ObrienMod.api.send("POST", "/api/mod/board/" + str(t, "id") + "/duplicate", null);
				ui(() -> { JsonObject n = r.getAsJsonObject("task"); replace(n); toast(true, "Aufgabe dupliziert."); ok.accept(n); });
			} catch (ShopApi.ApiException e) {
				ui(() -> toast(false, e.getMessage()));
			}
		});
	}

	public void delete(JsonObject t, Runnable ok) {
		String id = str(t, "id");
		io.execute(() -> {
			try {
				ObrienMod.api.send("DELETE", "/api/mod/board/" + id, null);
				ui(() -> gone(id, ok));
			} catch (ShopApi.ApiException e) {
				ui(() -> { if (e.status == 404) gone(id, ok); else toast(false, e.getMessage()); });
			}
		});
	}

	private void gone(String id, Runnable ok) {
		tasks.removeIf(x -> str(x, "id").equals(id));
		if (archived != null) archived.removeIf(x -> str(x, "id").equals(id));
		toast(true, "Aufgabe gelöscht.");
		ok.run();
	}

	public void comment(String id, String text, Runnable ok, Consumer<String> fail) {
		JsonObject b = new JsonObject();
		b.addProperty("body", text);
		io.execute(() -> {
			try {
				ObrienMod.api.send("POST", "/api/mod/board/" + id + "/comments", b);
				ui(() -> {
					JsonObject t = find(id);
					if (t != null) t.addProperty("comments", t.get("comments").getAsInt() + 1);
					toast(true, "Kommentar gespeichert.");
					ok.run();
				});
			} catch (ShopApi.ApiException e) {
				ui(() -> fail.accept(e.getMessage()));
			}
		});
	}

	// ---------- Definitionen (kommen von der Website, damit Spalten, Namen und Limits überall gleich sind) ----------

	public JsonArray list(String key) { return meta != null && meta.has(key) ? meta.getAsJsonArray(key) : new JsonArray(); }

	private JsonObject byKey(String list, String key) {
		for (JsonElement e : list(list)) if (str(e.getAsJsonObject(), "key").equals(key)) return e.getAsJsonObject();
		return null;
	}

	public JsonObject member(String key) { return key == null || key.isEmpty() ? null : byKey("members", key); }
	public String memberName(String key) { JsonObject m = member(key); return m == null ? "Nicht zugewiesen" : str(m, "name"); }
	public String statusLabel(String key) { JsonObject s = byKey("statuses", key); return s == null ? key : str(s, "label"); }
	public JsonObject priority(String key) { JsonObject p = byKey("priorities", key); return p != null ? p : byKey("priorities", "normal"); }
	public int limit(String key, int fallback) {
		return meta != null && meta.has("limits") && meta.getAsJsonObject("limits").has(key) ? meta.getAsJsonObject("limits").get(key).getAsInt() : fallback;
	}

	/** Eigenes Mitglied (persönlicher Mod-Schlüssel), sonst null – z. B. beim gemeinsamen Admin-Zugang. */
	public String self() {
		if (meta == null || !meta.has("me")) return null;
		JsonObject me = meta.getAsJsonObject("me");
		return me.has("member") && !me.get("member").isJsonNull() ? me.get("member").getAsString() : null;
	}
	public String selfName() { return meta == null ? "" : str(meta.getAsJsonObject("me"), "name"); }

	/** Alle bekannten Tags: Standard-Tags plus alle verwendeten, alphabetisch (wie auf der Website). */
	public List<String> allTags() {
		Set<String> s = new LinkedHashSet<>();
		for (JsonElement e : list("tags")) s.add(e.getAsString());
		for (JsonObject t : tasks) for (JsonElement e : t.getAsJsonArray("tags")) s.add(e.getAsString());
		if (archived != null) for (JsonObject t : archived) for (JsonElement e : t.getAsJsonArray("tags")) s.add(e.getAsString());
		List<String> out = new ArrayList<>(s);
		java.text.Collator c = java.text.Collator.getInstance(Locale.GERMAN);
		out.sort(c);
		return out;
	}

	public static String str(JsonObject o, String key) {
		return o != null && o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
	}
	public static boolean bool(JsonObject o, String key) {
		return o != null && o.has(key) && !o.get(key).isJsonNull() && o.get(key).getAsBoolean();
	}
}
