package net.obrienfamily.mod;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

import java.text.NumberFormat;
import java.util.Locale;

/**
 * O’Brien-Designsystem: Hex-Farben (Minecraft unterstützt beliebige RGB-Farben pro Textabschnitt) und Farbverläufe.
 * Verläufe gibt es in Minecraft nicht als eigene Funktion – sie entstehen, indem jedes Zeichen einzeln eingefärbt wird.
 */
public final class Theme {
	private Theme() {}

	// Markenfarben: Dunkelgrün → Smaragd → Gold
	public static final int DARK = 0x1F6B3A, EMERALD = 0x34C47A, GOLD = 0xE5BB4F;
	// Text
	public static final int CREAM = 0xF2EBD6, TEXT = 0xDCE6DF, MUTED = 0x9DB0A3, DIM = 0x63776A;
	// Status
	public static final int OK = 0x63DB91, ERR = 0xFF6F69, WARN = 0xF4C152, INFO = 0x7FCBDB, VIOLET = 0xC9A2FF;

	public static final int[] BRAND = {DARK, EMERALD, GOLD};
	private static final int[] RULE = {DARK, EMERALD, GOLD, EMERALD, DARK};

	/** Text mit Farbverlauf über mehrere Farbstopps. */
	public static MutableComponent gradient(String s, int[] stops, boolean bold) {
		MutableComponent out = Component.empty();
		int[] cps = s.codePoints().toArray();
		for (int i = 0; i < cps.length; i++) {
			float t = cps.length == 1 ? 0 : (float) i / (cps.length - 1);
			out.append(Component.literal(new String(Character.toChars(cps[i]))).withStyle(Style.EMPTY.withColor(at(stops, t)).withBold(bold)));
		}
		return out;
	}

	static int at(int[] stops, float t) {
		float p = t * (stops.length - 1);
		int i = Math.min(stops.length - 2, (int) p);
		return lerp(stops[i], stops[i + 1], p - i);
	}

	static int lerp(int a, int b, float t) {
		int r = (int) (((a >> 16) & 255) + (((b >> 16) & 255) - ((a >> 16) & 255)) * t);
		int g = (int) (((a >> 8) & 255) + (((b >> 8) & 255) - ((a >> 8) & 255)) * t);
		int bl = (int) ((a & 255) + ((b & 255) - (a & 255)) * t);
		return (r << 16) | (g << 8) | bl;
	}

	public static MutableComponent t(String s, int color) { return Component.literal(s).withStyle(Style.EMPTY.withColor(color)); }
	public static MutableComponent b(String s, int color) { return Component.literal(s).withStyle(Style.EMPTY.withColor(color).withBold(true)); }
	public static MutableComponent i(String s, int color) { return Component.literal(s).withStyle(Style.EMPTY.withColor(color).withItalic(true)); }

	/** Schriftzug „☘ O’BRIEN“ im Markenverlauf. */
	public static MutableComponent brand() { return gradient("☘ O’BRIEN", BRAND, true); }

	/** Trennlinie im Markenverlauf. */
	public static MutableComponent rule() { return gradient("━".repeat(26), RULE, false); }

	public static String num(double v) {
		NumberFormat f = NumberFormat.getNumberInstance(Locale.GERMANY);
		f.setMaximumFractionDigits(2);
		return f.format(v);
	}

	/** Betrag in einer Minecraft-Währung, z. B. „500 × Ton“. */
	public static String money(double amount, String currency) { return num(amount) + " × " + (currency == null ? "?" : currency); }

	/** Farbe für den Bestellstatus aus dem Shop. */
	public static int statusColor(String s) {
		return switch (s == null ? "" : s) {
			case "Neu" -> GOLD;
			case "Wird bearbeitet" -> INFO;
			case "Preisabsprache erforderlich" -> WARN;
			case "Preis vereinbart" -> EMERALD;
			case "Bereit zur Übergabe" -> OK;
			case "Abgeschlossen" -> MUTED;
			case "Storniert" -> ERR;
			default -> TEXT;
		};
	}

	public static int argb(int rgb) { return 0xFF000000 | rgb; }
	public static int argb(int rgb, int alpha) { return (alpha << 24) | (rgb & 0xFFFFFF); }
}
