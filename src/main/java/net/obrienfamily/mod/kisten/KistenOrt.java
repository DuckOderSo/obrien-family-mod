package net.obrienfamily.mod.kisten;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.Locale;

/**
 * Erkennt Lagerorte ausschließlich aus Daten, die der Client sicher kennt: dem anvisierten Block und den Blockzuständen
 * geladener Chunks. Doppelkisten werden über die Blockeigenschaft „type“ (links/rechts) und die verbundene Hälfte erkannt.
 * Es werden nie Chunks geladen und keine Daten an den Minecraft-Server gesendet.
 */
public final class KistenOrt {
	private KistenOrt() {}

	/** Ein Lagerort: Einzelkiste, Doppelkiste (a + b) oder Fass. */
	public record Ziel(String kind, BlockPos a, BlockPos b) {
		public String typ() { return typName(kind); }
	}

	/** Ergebnis der Zielerkennung: entweder ein Ziel oder eine verständliche Fehlermeldung. */
	public record Erkennung(Ziel ziel, String fehler) {}

	public static String typName(String kind) {
		return switch (kind) { case "double" -> "Doppelkiste"; case "barrel" -> "Fass"; default -> "Einzelkiste"; };
	}

	static boolean istKiste(Block b) { return b == Blocks.CHEST || b == Blocks.TRAPPED_CHEST; }
	static boolean istLager(Block b) { return istKiste(b) || b == Blocks.BARREL; }

	/** Die Kiste unter dem Fadenkreuz (Reichweite wie beim Öffnen). */
	public static Erkennung anvisiert() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || mc.player == null) return new Erkennung(null, "Du bist in keiner Welt.");
		HitResult hr = mc.hitResult;
		if (!(hr instanceof BlockHitResult bhr) || hr.getType() != HitResult.Type.BLOCK)
			return new Erkennung(null, "Schau eine Kiste an – das Fadenkreuz muss direkt auf der Kiste liegen.");
		return erkenne(mc.level, bhr.getBlockPos());
	}

	/** Lagerort an einer Position bestimmen (nur in geladenen Chunks aufrufen). */
	public static Erkennung erkenne(ClientLevel level, BlockPos pos) {
		BlockState st = level.getBlockState(pos);
		Block b = st.getBlock();
		if (b == Blocks.BARREL) return new Erkennung(new Ziel("barrel", pos.immutable(), null), null);
		if (!istKiste(b)) return new Erkennung(null, "Das ist keine Kiste. Unterstützt werden Kisten, Redstone-Kisten und Fässer.");
		ChestType type = st.getValue(ChestBlock.TYPE);
		if (type == ChestType.SINGLE) return new Erkennung(new Ziel("single", pos.immutable(), null), null);
		// Doppelkiste: Die andere Hälfte muss dieselbe Kistenart sein und zurück auf diese Hälfte zeigen.
		BlockPos other = ChestBlock.getConnectedBlockPos(pos, st);
		BlockState os = level.getBlockState(other);
		if (os.getBlock() != b || os.getValue(ChestBlock.TYPE) == ChestType.SINGLE || !ChestBlock.getConnectedBlockPos(other, os).equals(pos))
			return new Erkennung(null, "Die zweite Hälfte dieser Doppelkiste konnte nicht eindeutig erkannt werden. Es wurde nichts gespeichert.");
		return new Erkennung(new Ziel("double", pos.immutable(), other.immutable()), null);
	}

	/**
	 * Server-/Weltzuordnung: Adresse des Servers (ohne Standardport) bzw. „local:Weltname“ im Einzelspieler.
	 * Grenze: Läuft ein Netzwerk mit mehreren Unterservern unter einer Adresse, kann der Client diese nicht unterscheiden.
	 */
	public static String welt() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.getSingleplayerServer() != null) return clean("local:" + mc.getSingleplayerServer().getWorldData().getLevelName());
		ServerData sd = mc.getCurrentServer();
		if (sd != null && sd.ip != null && !sd.ip.isBlank()) {
			String ip = sd.ip.trim().toLowerCase(Locale.ROOT);
			if (ip.endsWith(":25565")) ip = ip.substring(0, ip.length() - 6);
			return clean(ip);
		}
		return null;
	}

	private static String clean(String s) {
		String c = s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._:-]", "-");
		return c.length() > 100 ? c.substring(0, 100) : c;
	}

	public static String dimension() {
		Minecraft mc = Minecraft.getInstance();
		return mc.level == null ? null : mc.level.dimension().identifier().toString();
	}

	public static String dimName(String dim) {
		return switch (dim) {
			case "minecraft:overworld" -> "Oberwelt";
			case "minecraft:the_nether" -> "Nether";
			case "minecraft:the_end" -> "Ende";
			default -> dim;
		};
	}
}
