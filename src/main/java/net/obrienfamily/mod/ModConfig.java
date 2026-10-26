package net.obrienfamily.mod;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Einstellungen in config/obrien-mod.json. Der Mod-Schlüssel wird nur hier lokal gespeichert. */
public final class ModConfig {
	public String baseUrl = "https://obrien-familie-shop.vercel.app";
	public String token = "";
	public int pollSeconds = 60;
	public boolean sound = true;
	public boolean toasts = true;

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("obrien-mod.json");

	public static ModConfig load() {
		try {
			if (Files.exists(FILE)) {
				ModConfig c = GSON.fromJson(Files.readString(FILE, StandardCharsets.UTF_8), ModConfig.class);
				if (c != null) return c;
			}
		} catch (Exception e) {
			ObrienMod.LOG.warn("[O'Brien] Konfiguration konnte nicht gelesen werden, Standardwerte werden verwendet.", e);
		}
		ModConfig c = new ModConfig();
		c.save();
		return c;
	}

	public synchronized void save() {
		try {
			Files.createDirectories(FILE.getParent());
			Path tmp = FILE.resolveSibling("obrien-mod.json.tmp");
			Files.writeString(tmp, GSON.toJson(this), StandardCharsets.UTF_8);
			Files.move(tmp, FILE, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			ObrienMod.LOG.warn("[O'Brien] Konfiguration konnte nicht gespeichert werden.", e);
		}
	}

	/** Basisadresse ohne Schrägstrich am Ende. Nur HTTPS (oder localhost zum Testen) ist erlaubt. */
	public String base() {
		String b = baseUrl == null ? "" : baseUrl.trim().replaceAll("/+$", "");
		if (b.startsWith("https://") || b.startsWith("http://localhost") || b.startsWith("http://127.0.0.1")) return b;
		return "https://obrien-familie-shop.vercel.app";
	}

	public int interval() { return Math.max(30, Math.min(600, pollSeconds)); }

	public boolean hasToken() { return token != null && token.startsWith("obr_"); }
}
