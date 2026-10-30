package net.obrienfamily.mod.kisten;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;

import java.util.ArrayList;
import java.util.List;

/**
 * Markierungen aus Partikeln (nur Client, wie normale Spielgrafik von Blöcken verdeckt – kein „Röntgen“):
 * <ul>
 * <li>Gefundene Kisten: kräftiger goldener Umriss plus Lichtsäule nach oben, 30 Sekunden – auch aus Entfernung gut sichtbar.</li>
 * <li>Lagerbereiche: türkiser Rahmen um den ganzen Quader (z. B. nach dem Setzen eines Eckpunkts).</li>
 * </ul>
 * Gezeichnet wird nur in geladenen Chunks der aktuellen Dimension und nur, solange dort wirklich eine Kiste steht. Die Anzahl der
 * Partikel ist begrenzt (Abstand der Punkte wächst mit der Größe, ab vielen Kisten nur noch Lichtsäulen).
 */
public final class Hervorhebung {
	private Hervorhebung() {}

	private record Box(BlockPos a, BlockPos b, String dim, long bis, boolean bereich) {}
	private static final List<Box> AKTIV = new ArrayList<>();
	private static final DustParticleOptions GOLD = new DustParticleOptions(0xFFC83D, 1.25F);
	private static final DustParticleOptions SAEULE = new DustParticleOptions(0xFFE58A, 2.0F);
	private static final DustParticleOptions TUERKIS = new DustParticleOptions(0x36C5FF, 1.5F);
	static final int KISTE_MS = 30_000;
	private static final int VOLL_MAX = 16, SAEULE_HOEHE = 18, BEREICH_MAX_PARTIKEL = 420;
	private static int takt;

	public static void markiere(KistenOrt.Ziel z, String dim) {
		synchronized (AKTIV) {
			AKTIV.removeIf(x -> !x.bereich() && x.a().equals(z.a()) && x.dim().equals(dim));
			AKTIV.add(new Box(z.a(), z.b(), dim, System.currentTimeMillis() + KISTE_MS, false));
		}
	}

	/** Alte Kisten-Markierungen entfernen (z. B. vor einer neuen Suche). */
	public static void kistenLeeren() { synchronized (AKTIV) { AKTIV.removeIf(x -> !x.bereich()); } }

	public static void markiereBereich(BlockPos min, BlockPos max, String dim, int sekunden) {
		synchronized (AKTIV) {
			AKTIV.removeIf(Box::bereich);
			AKTIV.add(new Box(min, max, dim, System.currentTimeMillis() + sekunden * 1000L, true));
		}
	}

	/** Aufruf einmal pro Client-Tick (ohne Netzwerkzugriff). Gezeichnet wird alle 8 Ticks. */
	public static void tick() {
		Minecraft mc = Minecraft.getInstance();
		long now = System.currentTimeMillis();
		synchronized (AKTIV) {
			AKTIV.removeIf(x -> x.bis() < now);
			if (AKTIV.isEmpty() || mc.level == null || mc.player == null || ++takt % 8 != 0) return;
			String dim = KistenOrt.dimension();
			int kisten = 0;
			for (Box x : AKTIV) {
				if (!x.dim().equals(dim)) continue;
				if (x.bereich()) { bereich(mc, x); continue; }
				// Nur geladene Chunks (es wird nichts nachgeladen) und nur, solange dort noch eine Kiste steht.
				if (!KistenOrt.geladen(mc.level, x.a()) || !KistenOrt.istLager(mc.level.getBlockState(x.a()).getBlock())) continue;
				kiste(mc, x, ++kisten <= VOLL_MAX);
			}
		}
	}

	private static void kiste(Minecraft mc, Box x, boolean umriss) {
		BlockPos b = x.b() == null ? x.a() : x.b();
		double x0 = Math.min(x.a().getX(), b.getX()) - 0.02, z0 = Math.min(x.a().getZ(), b.getZ()) - 0.02, y0 = x.a().getY() - 0.02;
		double x1 = Math.max(x.a().getX(), b.getX()) + 1.02, z1 = Math.max(x.a().getZ(), b.getZ()) + 1.02, y1 = x.a().getY() + 0.92;
		if (umriss) kanten(mc, GOLD, x0, y0, z0, x1, y1, z1, 0.3);
		// Lichtsäule aus der Mitte der Kiste nach oben
		double cx = (x0 + x1) / 2, cz = (z0 + z1) / 2;
		for (double y = y1 + 0.3; y < y1 + SAEULE_HOEHE; y += 0.6) punkt(mc, SAEULE, cx, y, cz);
	}

	private static void bereich(Minecraft mc, Box x) {
		double x0 = x.a().getX(), y0 = x.a().getY(), z0 = x.a().getZ(), x1 = x.b().getX() + 1, y1 = x.b().getY() + 1, z1 = x.b().getZ() + 1;
		double umfang = 4 * ((x1 - x0) + (y1 - y0) + (z1 - z0));
		kanten(mc, TUERKIS, x0, y0, z0, x1, y1, z1, Math.max(0.5, umfang / BEREICH_MAX_PARTIKEL));
	}

	private static void kanten(Minecraft mc, DustParticleOptions o, double x0, double y0, double z0, double x1, double y1, double z1, double schritt) {
		double[][] e = {{x0, y0, z0}, {x1, y0, z0}, {x1, y0, z1}, {x0, y0, z1}, {x0, y1, z0}, {x1, y1, z0}, {x1, y1, z1}, {x0, y1, z1}};
		int[][] k = {{0, 1}, {1, 2}, {2, 3}, {3, 0}, {4, 5}, {5, 6}, {6, 7}, {7, 4}, {0, 4}, {1, 5}, {2, 6}, {3, 7}};
		for (int[] kk : k) {
			double[] p = e[kk[0]], q = e[kk[1]];
			double len = Math.abs(p[0] - q[0]) + Math.abs(p[1] - q[1]) + Math.abs(p[2] - q[2]);
			int n = Math.max(2, (int) Math.round(len / schritt));
			for (int i = 0; i <= n; i++) {
				double t = (double) i / n;
				punkt(mc, o, p[0] + (q[0] - p[0]) * t, p[1] + (q[1] - p[1]) * t, p[2] + (q[2] - p[2]) * t);
			}
		}
	}

	/** „Immer sichtbar“: auch auf größere Entfernung und bei reduzierter Partikel-Einstellung. */
	private static void punkt(Minecraft mc, DustParticleOptions o, double x, double y, double z) {
		mc.level.addAlwaysVisibleParticle(o, true, x, y, z, 0, 0, 0);
	}
}
