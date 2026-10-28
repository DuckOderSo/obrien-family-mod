package net.obrienfamily.mod.kisten;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;

import java.util.ArrayList;
import java.util.List;

/**
 * Markiert gefundene Kisten 10 Sekunden lang mit goldenen Partikeln entlang der Kanten.
 * Partikel werden wie normale Spielgrafik von Blöcken verdeckt – es gibt also keine Sicht durch Wände (kein „Röntgen“).
 * Markiert werden nur Kisten in geladenen Chunks, an deren Position der Client gerade wirklich eine Kiste sieht.
 * Hinweis: Steht „Partikel“ in den Videoeinstellungen auf „Minimal“, zeigt Minecraft die Markierung ggf. nicht an.
 */
public final class Hervorhebung {
	private Hervorhebung() {}

	private record Box(BlockPos a, BlockPos b, String dim, long bis) {}
	private static final List<Box> AKTIV = new ArrayList<>();
	private static final DustParticleOptions GOLD = new DustParticleOptions(0xE5BB4F, 0.55F);
	private static int takt;

	public static void markiere(KistenOrt.Ziel z, String dim) {
		synchronized (AKTIV) {
			AKTIV.removeIf(x -> x.a().equals(z.a()) && x.dim().equals(dim));
			AKTIV.add(new Box(z.a(), z.b(), dim, System.currentTimeMillis() + 10_000));
		}
	}

	/** Aufruf einmal pro Client-Tick (ohne Netzwerkzugriff). */
	public static void tick() {
		Minecraft mc = Minecraft.getInstance();
		long now = System.currentTimeMillis();
		synchronized (AKTIV) {
			AKTIV.removeIf(x -> x.bis() < now);
			if (AKTIV.isEmpty() || mc.level == null || mc.player == null || ++takt % 4 != 0) return;
			String dim = KistenOrt.dimension();
			for (Box x : AKTIV) {
				if (!x.dim().equals(dim)) continue;
				// Nur geladene Chunks (es wird nichts nachgeladen) und nur, solange dort noch eine Kiste steht.
				if (!KistenOrt.geladen(mc.level, x.a()) || !KistenOrt.istLager(mc.level.getBlockState(x.a()).getBlock())) continue;
				zeichne(mc, x);
			}
		}
	}

	private static void zeichne(Minecraft mc, Box x) {
		BlockPos b = x.b() == null ? x.a() : x.b();
		double x0 = Math.min(x.a().getX(), b.getX()) + 0.04, z0 = Math.min(x.a().getZ(), b.getZ()) + 0.04, y0 = x.a().getY() + 0.02;
		double x1 = Math.max(x.a().getX(), b.getX()) + 0.96, z1 = Math.max(x.a().getZ(), b.getZ()) + 0.96, y1 = x.a().getY() + 0.94;
		double[][] ecken = {{x0, y0, z0}, {x1, y0, z0}, {x1, y0, z1}, {x0, y0, z1}, {x0, y1, z0}, {x1, y1, z0}, {x1, y1, z1}, {x0, y1, z1}};
		int[][] kanten = {{0, 1}, {1, 2}, {2, 3}, {3, 0}, {4, 5}, {5, 6}, {6, 7}, {7, 4}, {0, 4}, {1, 5}, {2, 6}, {3, 7}};
		for (int[] k : kanten) {
			double[] p = ecken[k[0]], q = ecken[k[1]];
			double len = Math.abs(p[0] - q[0]) + Math.abs(p[1] - q[1]) + Math.abs(p[2] - q[2]);
			int n = Math.max(2, (int) Math.round(len / 0.2));
			for (int i = 0; i <= n; i++) {
				double t = (double) i / n;
				mc.level.addParticle(GOLD, p[0] + (q[0] - p[0]) * t, p[1] + (q[1] - p[1]) * t, p[2] + (q[2] - p[2]) * t, 0, 0, 0);
			}
		}
	}
}
