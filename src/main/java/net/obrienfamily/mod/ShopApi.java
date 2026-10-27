package net.obrienfamily.mod;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** Verbindung zur Website (/api/mod/...). Authentifizierung nur über den persönlichen Mod-Schlüssel. */
public final class ShopApi {
	public static final class ApiException extends Exception {
		public final int status;
		/** Antwort der Website bei Fehlern (z. B. die aktuelle Aufgabe bei einem Versionskonflikt 409). */
		public final JsonObject body;
		ApiException(int status, String message) { this(status, message, new JsonObject()); }
		ApiException(int status, String message, JsonObject body) { super(message); this.status = status; this.body = body; }
	}

	private final ModConfig config;
	private final HttpClient http = HttpClient.newBuilder()
		.version(HttpClient.Version.HTTP_1_1) // kein h2c-Upgrade-Versuch (manche Server brechen die Verbindung sonst ab)
		.connectTimeout(Duration.ofSeconds(10))
		.followRedirects(HttpClient.Redirect.NORMAL)
		.build();

	public ShopApi(ModConfig config) { this.config = config; }

	private HttpRequest.Builder request(String path, Duration timeout) throws ApiException {
		if (!config.hasToken()) throw new ApiException(401, "Noch kein Mod-Schlüssel eingegeben. Erstelle einen im Admin-Bereich unter „Konto“ und gib /obrien key <Schlüssel> ein.");
		return HttpRequest.newBuilder(URI.create(config.base() + path))
			.timeout(timeout)
			.header("Authorization", "Bearer " + config.token)
			.header("User-Agent", "ObrienMod/" + ObrienMod.version())
			.header("Accept", "application/json");
	}

	public JsonObject get(String path) throws ApiException { return send("GET", path, null); }

	/** Anfrage an die Mod-Schnittstelle (GET, POST, PATCH, DELETE) mit optionalem JSON-Inhalt. */
	public JsonObject send(String method, String path, JsonObject payload) throws ApiException {
		try {
			HttpRequest.Builder b = request(path, Duration.ofSeconds(15));
			if (payload == null) b.method(method, HttpRequest.BodyPublishers.noBody());
			else b.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8));
			HttpResponse<byte[]> r = http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
			String body = new String(r.body(), StandardCharsets.UTF_8);
			JsonObject json;
			try {
				JsonElement el = JsonParser.parseString(body);
				json = el.isJsonObject() ? el.getAsJsonObject() : new JsonObject();
			} catch (Exception e) {
				json = new JsonObject();
			}
			if (r.statusCode() >= 400) {
				String msg = json.has("error") ? json.get("error").getAsString() : "Die Website hat mit Fehler " + r.statusCode() + " geantwortet.";
				throw new ApiException(r.statusCode(), msg, json);
			}
			return json;
		} catch (ApiException e) {
			throw e;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new ApiException(0, "Abgebrochen.");
		} catch (Exception e) {
			throw new ApiException(0, "Keine Verbindung zur Website (" + config.base() + ").");
		}
	}

	/** Öffentliche Abfrage ohne Mod-Schlüssel (GitHub-Releases für Updates). */
	public JsonObject getPublic(String url) throws ApiException {
		try {
			HttpResponse<byte[]> r = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15))
				.header("User-Agent", "ObrienMod/" + ObrienMod.version()).header("Accept", "application/vnd.github+json").GET().build(), HttpResponse.BodyHandlers.ofByteArray());
			if (r.statusCode() >= 400) throw new ApiException(r.statusCode(), "GitHub hat mit Fehler " + r.statusCode() + " geantwortet.");
			return JsonParser.parseString(new String(r.body(), StandardCharsets.UTF_8)).getAsJsonObject();
		} catch (ApiException e) {
			throw e;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new ApiException(0, "Abgebrochen.");
		} catch (Exception e) {
			throw new ApiException(0, "Keine Verbindung zu GitHub.");
		}
	}

	/** Öffentlicher Download (GitHub-Release-Dateien). Die Echtheit wird danach über die Signatur geprüft. */
	public byte[] downloadPublic(String url, long maxBytes) throws ApiException {
		try {
			HttpResponse<byte[]> r = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60))
				.header("User-Agent", "ObrienMod/" + ObrienMod.version()).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
			if (r.statusCode() >= 400) throw new ApiException(r.statusCode(), "Download fehlgeschlagen (Fehler " + r.statusCode() + ").");
			if (r.body().length > maxBytes) throw new ApiException(0, "Die heruntergeladene Datei ist unerwartet groß.");
			return r.body();
		} catch (ApiException e) {
			throw e;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new ApiException(0, "Abgebrochen.");
		} catch (Exception e) {
			throw new ApiException(0, "Keine Verbindung zu GitHub.");
		}
	}


}
