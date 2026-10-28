package net.obrienfamily.mod.kisten;

import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * Löst Suchbegriffe in Minecraft-Items auf – unabhängig vom Inventar: „minecraft:diamond“, „diamond“ oder der Name in der
 * eingestellten Spielsprache („Diamant“, „Eisenbarren“). Mehrdeutige Begriffe liefern Vorschläge statt eines Treffers.
 */
public final class ItemSuche {
	private ItemSuche() {}

	public record Eintrag(String id, String pfad, String name, String nameKlein) {}
	public record Ergebnis(Eintrag treffer, List<Eintrag> vorschlaege) {}

	private static List<Eintrag> index;
	private static String indexSprache;

	/** Verzeichnis aller Items mit Namen in der aktuellen Sprache (wird bei Sprachwechsel neu aufgebaut). */
	static synchronized List<Eintrag> alle() {
		String lang = net.minecraft.client.Minecraft.getInstance().options.languageCode;
		if (index == null || !lang.equals(indexSprache)) {
			List<Eintrag> l = new ArrayList<>();
			for (Identifier id : BuiltInRegistries.ITEM.keySet()) {
				Item it = BuiltInRegistries.ITEM.getValue(id);
				if (it == Items.AIR) continue;
				String name = new ItemStack(it).getHoverName().getString();
				l.add(new Eintrag(id.toString(), id.getPath(), name, name.toLowerCase(Locale.ROOT)));
			}
			l.sort(Comparator.comparing(Eintrag::id));
			index = l;
			indexSprache = lang;
		}
		return index;
	}

	public static Eintrag vonItem(Item it) {
		String id = BuiltInRegistries.ITEM.getKey(it).toString();
		for (Eintrag e : alle()) if (e.id().equals(id)) return e;
		return null;
	}

	public static String name(String id) {
		for (Eintrag e : alle()) if (e.id().equals(id)) return e.name();
		return id;
	}

	public static Ergebnis aufloesen(String eingabe) {
		String q = eingabe.trim().toLowerCase(Locale.ROOT);
		if (q.isEmpty()) return new Ergebnis(null, List.of());
		List<Eintrag> all = alle();
		// 1. Exakte ID (mit oder ohne „minecraft:“)
		String voll = q.contains(":") ? q : "minecraft:" + q;
		for (Eintrag e : all) if (e.id().equals(voll)) return new Ergebnis(e, List.of());
		// 2. Exakter Name in der Spielsprache, auch Mehrzahl („Diamanten“ → „Diamant“)
		for (String v : new String[]{q, strip(q, "en"), strip(q, "n"), strip(q, "e"), strip(q, "s")}) {
			if (v == null) continue;
			List<Eintrag> exact = all.stream().filter(e -> e.nameKlein().equals(v)).toList();
			if (exact.size() == 1) return new Ergebnis(exact.get(0), List.of());
			if (exact.size() > 1) return new Ergebnis(null, exact);
		}
		// 3. Teiltreffer in Name oder ID
		String norm = q.replace(' ', '_');
		List<Eintrag> part = all.stream().filter(e -> e.nameKlein().contains(q) || e.pfad().contains(norm))
			.sorted(Comparator.comparingInt((Eintrag e) -> e.name().length())).toList();
		if (part.size() == 1) return new Ergebnis(part.get(0), List.of());
		return new Ergebnis(null, part.size() > 8 ? part.subList(0, 8) : part);
	}

	private static String strip(String s, String suffix) {
		return s.length() > suffix.length() + 2 && s.endsWith(suffix) ? s.substring(0, s.length() - suffix.length()) : null;
	}

	/** Vervollständigung im Chat: Item-IDs und Namen passend zur bisherigen Eingabe, danach optional „all“. */
	public static CompletableFuture<Suggestions> vorschlagen(SuggestionsBuilder b, boolean mitAll) {
		String rest = b.getRemaining().toLowerCase(Locale.ROOT);
		if (mitAll && rest.contains(" ")) {
			String vor = rest.substring(0, rest.lastIndexOf(' ') + 1);
			if (!rest.endsWith(" all") && aufloesen(vor.trim()).treffer() != null) {
				SuggestionsBuilder s = b.createOffset(b.getStart() + vor.length());
				s.suggest("all");
				return s.buildFuture();
			}
		}
		int n = 0;
		for (Eintrag e : alle()) {
			if (n >= 40) break;
			if (e.pfad().startsWith(rest) || e.id().startsWith(rest)) { b.suggest(rest.contains(":") ? e.id() : e.pfad()); n++; }
		}
		if (rest.length() >= 2) for (Eintrag e : alle()) {
			if (n >= 50) break;
			if (e.nameKlein().startsWith(rest) && !e.pfad().startsWith(rest)) { b.suggest(e.name()); n++; }
		}
		return b.buildFuture();
	}
}
