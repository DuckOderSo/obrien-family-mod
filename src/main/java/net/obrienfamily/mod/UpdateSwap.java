package net.obrienfamily.mod;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

/**
 * Kleiner Helfer, der NACH dem Beenden von Minecraft die Mod-Datei austauscht (solange das Spiel läuft, ist sie gesperrt).
 * Läuft als eigener Java-Prozess aus der neuen, bereits geprüften Mod-Datei und nutzt keine Minecraft-Klassen.
 * Argumente: <pid> <alteMod.jar> <neueMod.jar.pending> <ziel.jar> <logdatei>
 */
public final class UpdateSwap {
	public static void main(String[] a) {
		if (a.length < 5) return;
		Path old = Path.of(a[1]), pending = Path.of(a[2]), target = Path.of(a[3]), log = Path.of(a[4]);
		try {
			long pid = Long.parseLong(a[0]);
			ProcessHandle.of(pid).ifPresent(h -> {
				try { h.onExit().get(10, TimeUnit.MINUTES); } catch (Exception ignored) {}
			});
		} catch (NumberFormatException ignored) {}
		for (int i = 0; i < 90; i++) {
			try {
				// Kopieren statt Verschieben: Die .pending-Datei ist von diesem Helfer selbst geöffnet; die Mod räumt sie beim nächsten Start weg.
				Path tmp = target.resolveSibling(target.getFileName() + ".part");
				Files.copy(pending, tmp, StandardCopyOption.REPLACE_EXISTING);
				if (!old.equals(target)) Files.deleteIfExists(old);
				Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
				write(log, "Update installiert: " + target.getFileName());
				return;
			} catch (IOException e) {
				try { Thread.sleep(1000); } catch (InterruptedException ie) { return; }
			}
		}
		write(log, "Update konnte nicht installiert werden (Datei gesperrt?): " + old + " -> " + target);
	}

	private static void write(Path log, String line) {
		try {
			Files.createDirectories(log.getParent());
			Files.writeString(log, LocalDateTime.now() + " " + line + System.lineSeparator(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		} catch (IOException ignored) {}
	}
}
