package net.obrienfamily.mod.kisten;

import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.obrienfamily.mod.ObrienMod;
import net.obrienfamily.mod.ShopApi;

import java.util.Map;
import java.util.TreeMap;

/**
 * Bestandserfassung – die einzige zuverlässige Quelle für Kisteninhalte auf einem fremden Server: Öffnet ein Admin eine Kiste
 * innerhalb eines Lagerbereichs, schickt der Server ihm den Inhalt. Beim Schließen liest die Mod ALLE Slots, fasst gleiche Items
 * zusammen und speichert den kompletten Inhalt als Schnappschuss (ersetzt den vorherigen). Ohne Öffnen gibt es keine Zahlen –
 * es wird nichts geschätzt. Der Kisteninhalt wird nur gelesen, nie verändert. Im Chat erscheint dabei nichts.
 */
public final class Bestand {
	private Bestand() {}

	private record Klick(String welt, String dim, KistenOrt.Ziel ziel, long zeit) {}
	private static volatile Klick letzter;

	public static void init() {
		// Rechtsklick auf eine Kiste merken (Client-Seite, ändert nichts am Ablauf des Spiels).
		UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
			if (level.isClientSide() && ObrienMod.isAdminKey()) {
				Minecraft mc = Minecraft.getInstance();
				KistenOrt.Erkennung e = mc.level == null ? null : KistenOrt.erkenne(mc.level, hit.getBlockPos());
				String welt = KistenOrt.welt(), dim = KistenOrt.dimension();
				// Nur Kisten innerhalb eines Lagerbereichs werden erfasst (die Website prüft das zusätzlich).
				letzter = e != null && e.ziel() != null && welt != null && KistenWaechter.imLager(dim, e.ziel())
					? new Klick(welt, dim, e.ziel(), System.currentTimeMillis()) : null;
			}
			return InteractionResult.PASS;
		});
		ScreenEvents.AFTER_INIT.register((mc, screen, w, h) -> {
			Klick k = letzter;
			if (k == null || System.currentTimeMillis() - k.zeit() > 2500) return;
			if (!(screen instanceof AbstractContainerScreen<?> acs) || !(acs.getMenu() instanceof ChestMenu menu)) return;
			// Nur echte Kistenfenster: Doppelkiste = 6 Reihen, Einzelkiste/Fass = 3 Reihen (Plugin-Menüs werden so meist ausgeschlossen).
			int rows = "double".equals(k.ziel().kind()) ? 6 : 3;
			if (menu.getRowCount() != rows) return;
			letzter = null;
			ScreenEvents.remove(screen).register(s -> zaehlen(k, menu, rows));
		});
	}

	/** Alle Item-Typen der Kiste getrennt zählen; mehrere Slots desselben Items werden addiert, leere Slots ignoriert. */
	static Map<String, Integer> inhalt(ChestMenu menu, int rows) {
		Map<String, Integer> inhalt = new TreeMap<>();
		for (int i = 0; i < rows * 9 && i < menu.slots.size(); i++) {
			ItemStack st = menu.getSlot(i).getItem();
			if (st.isEmpty()) continue;
			inhalt.merge(BuiltInRegistries.ITEM.getKey(st.getItem()).toString(), st.getCount(), Integer::sum);
		}
		return inhalt;
	}

	private static void zaehlen(Klick k, ChestMenu menu, int rows) {
		Map<String, Integer> inhalt = inhalt(menu, rows);
		KistenApi.IO.execute(() -> {
			try {
				JsonObject r = KistenApi.inhalt(k.welt(), k.dim(), k.ziel(), inhalt);
				if (r.has("location") && !r.get("location").isJsonNull()) KistenWaechter.merke(r.getAsJsonObject("location"));
				ObrienMod.LOG.debug("[O'Brien] Kisteninhalt erfasst: {} Item-Typen", inhalt.size());
			} catch (ShopApi.ApiException e) {
				ObrienMod.LOG.info("[O'Brien] Kisteninhalt konnte nicht gespeichert werden: {}", e.getMessage());
			}
		});
	}
}
