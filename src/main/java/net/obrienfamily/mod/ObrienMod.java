package net.obrienfamily.mod;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * O’Brien Family Shop – Client-Mod. Fragt im Hintergrund (nur während du in einer Welt bist) die Website nach Neuigkeiten
 * und zeigt sie als Toast und Chatnachricht. Es werden keine Daten an den Minecraft-Server gesendet.
 */
public final class ObrienMod implements ClientModInitializer {
	public static final String ID = "obrien";
	public static final Logger LOG = LoggerFactory.getLogger("obrien");

	static ModConfig config;
	static ShopApi api;
	static Updater updater;
	static ScheduledExecutorService worker;
	private static KeyMapping ordersKey;

	private static volatile String cursor;
	private static volatile boolean welcomed;
	private static volatile boolean authFailed;
	private static final Set<String> SEEN = new LinkedHashSet<>();

	@Override
	public void onInitializeClient() {
		config = ModConfig.load();
		api = new ShopApi(config);
		updater = new Updater();
		worker = Executors.newSingleThreadScheduledExecutor(r -> {
			Thread t = new Thread(r, "obrien-mod");
			t.setDaemon(true);
			return t;
		});
		Commands.register();
		// Taste B (in den Steuerungs-Einstellungen änderbar): Bestellübersicht öffnen. O ist in 26.3 die Freundesliste.
		ordersKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.obrien.orders", InputConstants.Type.KEYBOARD, InputConstants.KEY_B,
			KeyMapping.Category.register(Identifier.fromNamespaceAndPath(ID, "main"))));
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (ordersKey.consumeClick()) if (client.gui.screen() == null) OrdersScreen.open(null);
		});
		ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
			worker.execute(updater::cleanup);
			worker.schedule(() -> updater.check(false), 10, TimeUnit.SECONDS); // Update-Prüfung braucht keinen Mod-Schlüssel
			worker.scheduleWithFixedDelay(() -> updater.check(true), 6, 6, TimeUnit.HOURS);
			worker.scheduleWithFixedDelay(ObrienMod::safeTick, 20, config.interval(), TimeUnit.SECONDS);
		});
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> worker.shutdownNow());
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> worker.schedule(ObrienMod::onJoin, 4, TimeUnit.SECONDS));
	}

	public static String version() {
		return FabricLoader.getInstance().getModContainer(ID).map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("0.0.0");
	}

	/** Neuer Schlüssel: Zustand zurücksetzen, damit die Begrüßung erneut erscheint. */
	static void resetSession() {
		cursor = null;
		welcomed = false;
		authFailed = false;
		synchronized (SEEN) { SEEN.clear(); }
	}

	private static void onJoin() {
		if (!config.hasToken()) {
			if (!welcomed) {
				welcomed = true;
				Notifier.chat(Component.empty().append(Theme.t("Noch nicht verbunden. Schlüssel im Admin-Bereich unter ", Theme.WARN))
					.append(Theme.t("Konto → Minecraft-Mod", Theme.CREAM)).append(Theme.t(" erstellen, dann ", Theme.WARN))
					.append(Theme.t("/obrien key <Schlüssel>", Theme.GOLD)).append(Theme.t(" eingeben.", Theme.WARN)));
			}
			return;
		}
		safeTick();
		updater.check(true);
	}

	private static void safeTick() {
		try { tick(); } catch (Throwable t) { LOG.warn("[O'Brien] Abruf fehlgeschlagen", t); }
	}

	/** Ein Abruf: beim ersten Mal Zusammenfassung, danach nur neue Ereignisse. */
	private static void tick() {
		if (!config.hasToken() || authFailed || Minecraft.getInstance().player == null) return;
		try {
			if (cursor == null) {
				JsonObject r = api.get("/api/mod/feed");
				cursor = r.get("cursor").getAsString();
				if (!welcomed) { welcomed = true; welcome(r); }
				return;
			}
			JsonObject r = api.get("/api/mod/feed?since=" + java.net.URLEncoder.encode(cursor, java.nio.charset.StandardCharsets.UTF_8));
			cursor = r.get("cursor").getAsString();
			int shown = 0;
			for (JsonElement e : r.getAsJsonArray("events")) {
				JsonObject ev = e.getAsJsonObject();
				String id = ev.get("id").getAsString();
				synchronized (SEEN) {
					if (!SEEN.add(id)) continue;
					if (SEEN.size() > 500) { Iterator<String> it = SEEN.iterator(); it.next(); it.remove(); }
				}
				show(ev, ++shown <= 3);
			}
			if (shown > 3) Notifier.toast("O’Brien Family Shop", shown + " Neuigkeiten – siehe Chat");
		} catch (ShopApi.ApiException e) {
			if (e.status == 401) {
				authFailed = true;
				Notifier.error(e.getMessage());
			} else {
				LOG.info("[O'Brien] Abruf fehlgeschlagen: {}", e.getMessage());
			}
		}
	}

	private static void welcome(JsonObject r) {
		JsonObject s = r.getAsJsonObject("summary");
		int orders = s.get("new_orders").getAsInt(), reqs = s.get("new_requests").getAsInt(), mine = s.get("my_open").getAsInt(), overdue = s.get("my_overdue").getAsInt();
		List<Component> lines = new ArrayList<>();
		if (orders > 0) lines.add(Component.empty().append(Theme.t(" ▸ ", Theme.DARK)).append(Theme.b(String.valueOf(orders), Theme.GOLD))
			.append(Theme.t(orders == 1 ? " neue Bestellanfrage  " : " neue Bestellanfragen  ", Theme.TEXT)).append(Notifier.command("[Öffnen]", "/obrien bestellungen", "Bestellübersicht öffnen (Taste B)")));
		if (reqs > 0) lines.add(Component.empty().append(Theme.t(" ▸ ", Theme.DARK)).append(Theme.b(String.valueOf(reqs), Theme.INFO))
			.append(Theme.t(reqs == 1 ? " neue Dienstleistungsanfrage" : " neue Dienstleistungsanfragen", Theme.TEXT)));
		if (r.get("member").getAsBoolean() && mine > 0) lines.add(Component.empty().append(Theme.t(" ▸ ", Theme.DARK)).append(Theme.b(String.valueOf(mine), Theme.VIOLET))
			.append(Theme.t(mine == 1 ? " offene Aufgabe" : " offene Aufgaben", Theme.TEXT))
			.append(overdue > 0 ? Theme.t(" · " + overdue + " überfällig", Theme.ERR) : Component.empty()).append(Theme.t("  ", Theme.TEXT))
			.append(Notifier.command("[Anzeigen]", "/obrien aufgaben", "Deine Aufgaben anzeigen")));
		if (lines.isEmpty()) lines.add(Theme.t(" Alles erledigt – keine neuen Anfragen.", Theme.MUTED));
		Notifier.block("Willkommen zurück, " + r.get("name").getAsString(), lines.toArray(new Component[0]));
	}

	private static void show(JsonObject ev, boolean toast) {
		String type = ev.get("type").getAsString(), title = ev.get("title").getAsString(), text = ev.get("text").getAsString();
		String ref = ev.has("ref") && !ev.get("ref").isJsonNull() ? ev.get("ref").getAsString() : null;
		int color = switch (type) { case "order" -> Theme.GOLD; case "request" -> Theme.INFO; default -> Theme.VIOLET; };
		MutableComponent m = Component.empty().append(Theme.b(title, color)).append(Theme.t("  " + text + "  ", Theme.TEXT));
		switch (type) {
			case "order" -> m.append(Notifier.command("[Öffnen]", ref != null ? "/obrien bestellung " + ref : "/obrien bestellungen", "Details der Bestellanfrage öffnen"));
			case "task", "comment" -> m.append(Notifier.command("[Anzeigen]", "/obrien aufgaben", "Deine Aufgaben anzeigen"));
			default -> m.append(Notifier.link("[Im Browser]", config.base() + "/admin"));
		}
		Notifier.chat(m);
		if (toast) Notifier.toast(title, text);
	}
}
