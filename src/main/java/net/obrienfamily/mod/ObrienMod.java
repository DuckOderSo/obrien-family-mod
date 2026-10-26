package net.obrienfamily.mod;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Iterator;
import java.util.LinkedHashSet;
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
				Notifier.chat(Component.empty().append(Notifier.text("Noch nicht verbunden. Erstelle im Admin-Bereich unter „Konto → Minecraft-Mod“ einen Schlüssel und gib ", ChatFormatting.YELLOW))
					.append(Notifier.text("/obrien key <Schlüssel>", ChatFormatting.WHITE)).append(Notifier.text(" ein.", ChatFormatting.YELLOW)));
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
				Notifier.chat(Notifier.text(e.getMessage(), ChatFormatting.RED));
			} else {
				LOG.info("[O'Brien] Abruf fehlgeschlagen: {}", e.getMessage());
			}
		}
	}

	private static void welcome(JsonObject r) {
		JsonObject s = r.getAsJsonObject("summary");
		int orders = s.get("new_orders").getAsInt(), reqs = s.get("new_requests").getAsInt(), mine = s.get("my_open").getAsInt(), overdue = s.get("my_overdue").getAsInt();
		MutableComponent m = Component.empty().append(Notifier.text("Hallo " + r.get("name").getAsString() + "! ", ChatFormatting.WHITE));
		m.append(Notifier.command(orders + (orders == 1 ? " neue Bestellanfrage" : " neue Bestellanfragen"), "/obrien bestellungen", "Offene Bestellanfragen anzeigen"));
		m.append(Notifier.text(" · " + reqs + " neue Dienstleistungsanfrage" + (reqs == 1 ? "" : "n"), ChatFormatting.GRAY));
		if (r.get("member").getAsBoolean()) {
			m.append(Notifier.text(" · ", ChatFormatting.GRAY));
			m.append(Notifier.command(mine + (mine == 1 ? " offene Aufgabe" : " offene Aufgaben") + (overdue > 0 ? " (" + overdue + " überfällig)" : ""), "/obrien aufgaben", "Deine Aufgaben anzeigen"));
		}
		Notifier.chat(m);
	}

	private static void show(JsonObject ev, boolean toast) {
		String type = ev.get("type").getAsString(), title = ev.get("title").getAsString(), text = ev.get("text").getAsString();
		String cmd = type.equals("task") || type.equals("comment") ? "/obrien aufgaben" : type.equals("order") ? "/obrien bestellungen" : null;
		ChatFormatting color = type.equals("order") ? ChatFormatting.GREEN : type.equals("request") ? ChatFormatting.AQUA : ChatFormatting.LIGHT_PURPLE;
		MutableComponent m = Component.empty().append(Notifier.text(title, color, ChatFormatting.BOLD)).append(Notifier.text(" – " + text + " ", ChatFormatting.WHITE));
		if (cmd != null) m.append(Notifier.command("[anzeigen]", cmd, "Details im Chat anzeigen"));
		else m.append(Notifier.link("[im Browser öffnen]", config.base() + "/admin"));
		Notifier.chat(m);
		if (toast) Notifier.toast(title, text);
	}
}
