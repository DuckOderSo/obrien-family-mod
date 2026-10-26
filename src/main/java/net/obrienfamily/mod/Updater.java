package net.obrienfamily.mod;

import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Auto-Update mit Nachfrage: prüft die öffentlichen GitHub-Releases dieses Projekts auf eine neuere Version, lädt sie erst
 * nach /obrien update herunter, prüft die Ed25519-Signatur (geheimer Schlüssel liegt nur als GitHub-Secret vor) und
 * installiert sie beim Beenden von Minecraft. Ohne gültige Signatur wird nichts installiert.
 */
public final class Updater {
	/** Öffentliches Repository der Mod – Quellcode, Builds und Releases sind dort für alle einsehbar. */
	public static final String REPO = "DuckOderSo/obrien-family-mod";
	/** Öffentlicher Schlüssel der Familie. Nur damit signierte Dateien werden installiert. */
	private static final String PUBLIC_KEY = "MCowBQYDK2VwAyEAYm9x6cH5Ks7JZ5G9/Bz4DHX3uzlY93bURFbp2828zTo=";
	private static final long MAX_SIZE = 20L * 1024 * 1024;

	private volatile JsonObject available;
	private volatile String announced = "";
	private volatile String staged = "";
	private volatile boolean hookInstalled;

	/** Pfad der laufenden Mod-Datei (null in der Entwicklungsumgebung). */
	public static Path ownJar() {
		Optional<ModContainer> c = FabricLoader.getInstance().getModContainer(ObrienMod.ID);
		if (c.isEmpty()) return null;
		List<Path> paths = c.get().getOrigin().getKind() == net.fabricmc.loader.api.metadata.ModOrigin.Kind.PATH ? c.get().getOrigin().getPaths() : List.of();
		for (Path p : paths) if (Files.isRegularFile(p) && p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")) return p;
		return null;
	}

	/** Beim Start: übrig gebliebene Update-Dateien alter oder gleicher Versionen entfernen. */
	public void cleanup() {
		Path jar = ownJar();
		if (jar == null) return;
		try (Stream<Path> s = Files.list(jar.getParent())) {
			s.filter(p -> {
				String n = p.getFileName().toString();
				return n.startsWith("obrien-mod-") && (n.endsWith(".jar.pending") || n.endsWith(".jar.part"));
			}).forEach(p -> {
				String v = p.getFileName().toString().replaceFirst("^obrien-mod-", "").replaceFirst("\\.jar\\.(pending|part)$", "");
				if (compare(v, ObrienMod.version()) <= 0) {
					try { Files.deleteIfExists(p); } catch (Exception ignored) {}
				}
			});
		} catch (Exception e) {
			ObrienMod.LOG.debug("[O'Brien] Aufräumen fehlgeschlagen", e);
		}
	}

	/** Fragt die neueste Version ab. announce=true: im Chat Bescheid geben (nur einmal pro Version). */
	public void check(boolean announce) {
		try {
			JsonObject rel = ObrienMod.api.getPublic("https://api.github.com/repos/" + REPO + "/releases/latest");
			String v = rel.get("tag_name").getAsString().replaceFirst("^v", "");
			if (!v.matches("\\d+\\.\\d+\\.\\d+")) return;
			JsonObject r = new JsonObject();
			r.addProperty("version", v);
			for (var el : rel.getAsJsonArray("assets")) {
				JsonObject as = el.getAsJsonObject();
				String n = as.get("name").getAsString(), url = as.get("browser_download_url").getAsString();
				if (!url.startsWith("https://github.com/" + REPO + "/releases/download/")) continue;
				if (n.equals("obrien-mod-" + v + ".jar")) { r.addProperty("jar", url); r.addProperty("size", as.get("size").getAsLong()); }
				if (n.equals("obrien-mod-" + v + ".jar.sig")) r.addProperty("sig", url);
			}
			if (!r.has("jar") || !r.has("sig")) return;
			if (!v.matches("\\d+\\.\\d+\\.\\d+") || compare(v, ObrienMod.version()) <= 0) { available = null; return; }
			available = r;
			if (announce && !v.equals(announced) && !v.equals(staged) && Minecraft.getInstance().player != null) {
				announced = v;
				Notifier.chat(Component.empty()
					.append(Notifier.text("Update v" + v + " verfügbar (du hast v" + ObrienMod.version() + "). ", ChatFormatting.YELLOW))
					.append(Notifier.command("[Jetzt herunterladen]", "/obrien update", "Lädt v" + v + " herunter, prüft die Signatur und installiert sie beim Beenden von Minecraft.")));
			}
		} catch (ShopApi.ApiException e) {
			ObrienMod.LOG.debug("[O'Brien] Update-Prüfung fehlgeschlagen: {}", e.getMessage());
		}
	}

	public boolean hasUpdate() { return available != null; }
	public String availableVersion() { return available == null ? null : available.get("version").getAsString(); }
	public String stagedVersion() { return staged; }

	/** /obrien update: herunterladen, prüfen, bereitlegen. Wird auf dem Hintergrund-Thread ausgeführt. */
	public void install() {
		check(false);
		JsonObject r = available;
		if (r == null) { Notifier.chat(Notifier.text("Du hast bereits die neueste Version (v" + ObrienMod.version() + ").", ChatFormatting.GREEN)); return; }
		String v = r.get("version").getAsString();
		if (v.equals(staged)) { Notifier.chat(Notifier.text("v" + v + " ist bereits heruntergeladen und wird beim Beenden von Minecraft installiert.", ChatFormatting.GREEN)); return; }
		Path jar = ownJar();
		if (jar == null) { Notifier.chat(Notifier.text("Automatische Installation ist nur mit installierter Mod-Datei möglich.", ChatFormatting.RED)); return; }
		Notifier.chat(Notifier.text("Lade v" + v + " herunter …", ChatFormatting.GRAY));
		try {
			byte[] data = ObrienMod.api.downloadPublic(r.get("jar").getAsString(), MAX_SIZE);
			byte[] sig = ObrienMod.api.downloadPublic(r.get("sig").getAsString(), 4096);
			if (data.length != r.get("size").getAsLong())
				throw new SecurityException("Dateigröße stimmt nicht – Update verworfen.");
			if (!verifySignature(data, sig))
				throw new SecurityException("Die Signatur ist ungültig – Update verworfen. Bitte melde das im Team.");
			Path dir = jar.getParent();
			Path part = dir.resolve("obrien-mod-" + v + ".jar.part"), pending = dir.resolve("obrien-mod-" + v + ".jar.pending");
			Files.write(part, data);
			Files.move(part, pending, StandardCopyOption.REPLACE_EXISTING);
			staged = v;
			installHook(jar, pending, dir.resolve("obrien-mod-" + v + ".jar"));
			Notifier.chat(Notifier.text("v" + v + " heruntergeladen und Signatur geprüft ✔ Das Update wird installiert, sobald du Minecraft schließt.", ChatFormatting.GREEN));
		} catch (SecurityException e) {
			Notifier.chat(Notifier.text(e.getMessage(), ChatFormatting.RED));
		} catch (ShopApi.ApiException e) {
			Notifier.chat(Notifier.text(e.getMessage(), ChatFormatting.RED));
		} catch (Exception e) {
			ObrienMod.LOG.warn("[O'Brien] Update fehlgeschlagen", e);
			Notifier.chat(Notifier.text("Update fehlgeschlagen: " + e.getClass().getSimpleName(), ChatFormatting.RED));
		}
	}

	static boolean verifySignature(byte[] data, byte[] signature) {
		try {
			PublicKey key = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(PUBLIC_KEY)));
			Signature s = Signature.getInstance("Ed25519");
			s.initVerify(key);
			s.update(data);
			return s.verify(signature);
		} catch (Exception e) {
			return false;
		}
	}

	/** Startet beim Beenden von Minecraft den Helfer, der die Mod-Datei austauscht. */
	private synchronized void installHook(Path oldJar, Path pending, Path target) {
		if (hookInstalled) return;
		hookInstalled = true;
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			try {
				String s = staged;
				Path p = oldJar.getParent().resolve("obrien-mod-" + s + ".jar.pending"), t = oldJar.getParent().resolve("obrien-mod-" + s + ".jar");
				Path log = FabricLoader.getInstance().getGameDir().resolve("logs").resolve("obrien-update.log");
				new ProcessBuilder(javaBinary(), "-cp", p.toString(), UpdateSwap.class.getName(),
					String.valueOf(ProcessHandle.current().pid()), oldJar.toString(), p.toString(), t.toString(), log.toString())
					.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
			} catch (Exception e) {
				ObrienMod.LOG.warn("[O'Brien] Update-Helfer konnte nicht gestartet werden", e);
			}
		}, "obrien-update"));
	}

	private static String javaBinary() {
		Path bin = Path.of(System.getProperty("java.home"), "bin");
		boolean win = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
		for (String n : win ? new String[]{"javaw.exe", "java.exe"} : new String[]{"java"})
			if (Files.isRegularFile(bin.resolve(n))) return bin.resolve(n).toString();
		return "java";
	}

	/** Vergleicht Versionen der Form 1.2.3. */
	static int compare(String a, String b) {
		String[] x = a.split("\\."), y = b.split("\\.");
		for (int i = 0; i < 3; i++) {
			int p = i < x.length ? parse(x[i]) : 0, q = i < y.length ? parse(y[i]) : 0;
			if (p != q) return Integer.compare(p, q);
		}
		return 0;
	}

	private static int parse(String s) {
		try { return Integer.parseInt(s.replaceAll("[^0-9].*$", "")); } catch (NumberFormatException e) { return 0; }
	}
}
