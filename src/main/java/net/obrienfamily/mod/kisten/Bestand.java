package net.obrienfamily.mod.kisten;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.obrienfamily.mod.Notifier;
import net.obrienfamily.mod.ObrienMod;
import net.obrienfamily.mod.ShopApi;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bestandszählung – die einzige zuverlässige Quelle für Kisteninhalte auf einem fremden Server: Öffnet ein Admin eine
 * registrierte Kiste selbst, schickt der Server ihm den Inhalt. Beim Schließen zählt die Mod die registrierten Items und
 * speichert die Zahl mit Zeitpunkt („beim Öffnen gezählt“). Ohne Öffnen gibt es keine Zahl – es wird nichts geschätzt.
 * Der Kisteninhalt wird dabei nur gelesen, nie verändert.
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
				String welt = KistenOrt.welt();
				letzter = e != null && e.ziel() != null && welt != null ? new Klick(welt, KistenOrt.dimension(), e.ziel(), System.currentTimeMillis()) : null;
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

	private static void zaehlen(Klick k, ChestMenu menu, int rows) {
		Map<String, Integer> inhalt = new LinkedHashMap<>();
		for (int i = 0; i < rows * 9 && i < menu.slots.size(); i++) {
			ItemStack st = menu.getSlot(i).getItem();
			if (st.isEmpty()) continue;
			inhalt.merge(BuiltInRegistries.ITEM.getKey(st.getItem()).toString(), st.getCount(), Integer::sum);
		}
		KistenApi.IO.execute(() -> {
			try {
				JsonObject at = KistenApi.an(k.welt(), k.dim(), k.ziel().a()).getAsJsonObject("location");
				if (at == null || at.isJsonNull()) return; // nicht registriert – nichts speichern
				Map<String, Integer> zaehlung = new LinkedHashMap<>();
				for (JsonElement e : at.getAsJsonArray("items")) {
					String id = e.getAsJsonObject().get("item").getAsString();
					zaehlung.put(id, inhalt.getOrDefault(id, 0)); // registriert, aber nicht in der Kiste = gezählt 0
				}
				KistenApi.pruefung(k.welt(), k.dim(), k.ziel().a(), true, zaehlung);
				StringBuilder sb = new StringBuilder();
				zaehlung.forEach((id, n) -> sb.append(sb.length() > 0 ? " · " : "").append(ItemSuche.name(id)).append(" ").append(n));
				Notifier.info("Bestand gezählt: " + sb);
			} catch (ShopApi.ApiException e) {
				ObrienMod.LOG.info("[O'Brien] Bestand konnte nicht gespeichert werden: {}", e.getMessage());
			}
		});
	}
}
