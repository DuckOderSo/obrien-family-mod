package net.obrienfamily.mod;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Bestellübersicht im Spiel (nur lesen). Ebene 1: kompakte Karten aller offenen Bestellanfragen.
 * Ebene 2: Detailansicht einer Anfrage mit allen Positionen, Mengen, Einzel-, Positions- und Gesamtpreisen.
 * Die Mod verändert keine Bestellungen – ein Klick öffnet nur Details.
 */
public final class OrdersScreen extends Screen {
	private enum Mode { LIST, DETAIL }

	private static final int CARD = 42, GAP = 4, HEADER = 30, FOOTER = 28;
	private static final DateTimeFormatter SHORT = DateTimeFormatter.ofPattern("dd.MM. HH:mm");
	private static final DateTimeFormatter LONG = DateTimeFormatter.ofPattern("dd.MM.yyyy, HH:mm 'Uhr'");

	private Mode mode = Mode.LIST;
	private final List<JsonObject> orders = new ArrayList<>();
	private int openTotal;
	private boolean loading = true;
	private String error;
	private String loadedAt = "";

	private String detailNumber;
	private JsonObject detail;
	private boolean detailLoading;
	private String detailError;

	private double scroll, listScroll;
	private int contentH;
	private int px, py, pw, ph, top, bottom;
	private Button prevBtn, nextBtn;
	private final Map<String, ItemStack> icons = new HashMap<>();

	public OrdersScreen(String openNumber) {
		super(Component.literal("O’Brien – Offene Bestellanfragen"));
		reload();
		if (openNumber != null) openDetail(openNumber);
	}

	/** Öffnet die Übersicht (oder direkt eine Anfrage) – nach dem Schließen des Chats, damit sie nicht sofort wieder zugeht. */
	public static void open(String number) {
		Minecraft mc = Minecraft.getInstance();
		if (!ObrienMod.config.hasToken()) {
			Notifier.warn("Noch nicht verbunden – gib zuerst /obrien key <Schlüssel> ein.");
			return;
		}
		ObrienMod.worker.execute(() -> mc.execute(() -> mc.gui.setScreen(new OrdersScreen(number))));
	}

	// ---------- Daten ----------

	private void reload() {
		loading = true;
		error = null;
		ObrienMod.worker.execute(() -> {
			try {
				JsonObject r = ObrienMod.api.get("/api/mod/orders?limit=50");
				Minecraft.getInstance().execute(() -> {
					orders.clear();
					for (JsonElement e : r.getAsJsonArray("orders")) orders.add(e.getAsJsonObject());
					openTotal = r.get("open").getAsInt();
					loading = false;
					loadedAt = java.time.LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
					updateNav();
				});
			} catch (ShopApi.ApiException e) {
				Minecraft.getInstance().execute(() -> { loading = false; error = e.getMessage(); });
			}
		});
	}

	private void openDetail(String number) {
		if (mode == Mode.LIST) listScroll = scroll;
		mode = Mode.DETAIL;
		detailNumber = number;
		detail = null;
		detailError = null;
		detailLoading = true;
		scroll = 0;
		if (minecraft != null) rebuildWidgets();
		ObrienMod.worker.execute(() -> {
			try {
				JsonObject r = ObrienMod.api.get("/api/mod/orders/" + URLEncoder.encode(number, StandardCharsets.UTF_8));
				Minecraft.getInstance().execute(() -> { if (number.equals(detailNumber)) { detail = r.getAsJsonObject("order"); detailLoading = false; } });
			} catch (ShopApi.ApiException e) {
				Minecraft.getInstance().execute(() -> { if (number.equals(detailNumber)) { detailError = e.getMessage(); detailLoading = false; } });
			}
		});
	}

	private void back() {
		mode = Mode.LIST;
		detail = null;
		detailNumber = null;
		scroll = listScroll;
		rebuildWidgets();
	}

	private int indexOfDetail() {
		for (int i = 0; i < orders.size(); i++) if (str(orders.get(i), "number").equals(detailNumber)) return i;
		return -1;
	}

	private void step(int dir) {
		int i = indexOfDetail() + dir;
		if (indexOfDetail() >= 0 && i >= 0 && i < orders.size()) openDetail(str(orders.get(i), "number"));
	}

	private void updateNav() {
		int i = indexOfDetail();
		if (prevBtn != null) prevBtn.active = i > 0;
		if (nextBtn != null) nextBtn.active = i >= 0 && i < orders.size() - 1;
	}

	// ---------- Aufbau ----------

	@Override
	protected void init() {
		pw = Math.min(460, width - 12);
		ph = height - 12;
		px = (width - pw) / 2;
		py = 6;
		top = py + HEADER + 1;
		bottom = py + ph - FOOTER;
		int by = py + ph - 23, right = px + pw - 6;
		String admin = ObrienMod.config.base() + "/admin";
		boolean narrow = pw < 380;
		if (mode == Mode.LIST) {
			int w1 = 78, w2 = 92, w3 = 64;
			addRenderableWidget(Button.builder(Component.literal("Schließen"), b -> onClose()).bounds(right - w3, by, w3, 18).build());
			addRenderableWidget(Button.builder(Component.literal("Admin-Bereich"), ConfirmLinkScreen.confirmLink(this, URI.create(admin))).bounds(right - w3 - w2 - 4, by, w2, 18).build());
			addRenderableWidget(Button.builder(Component.literal("Aktualisieren"), b -> reload()).bounds(right - w3 - w2 - w1 - 8, by, w1, 18).build());
		} else {
			addRenderableWidget(Button.builder(Component.literal("‹ Übersicht"), b -> back()).bounds(px + 6, by, 74, 18).build());
			prevBtn = addRenderableWidget(Button.builder(Component.literal(narrow ? "‹" : "‹ Vorherige"), b -> step(-1)).bounds(px + 84, by, narrow ? 22 : 76, 18).build());
			nextBtn = addRenderableWidget(Button.builder(Component.literal(narrow ? "›" : "Nächste ›"), b -> step(1)).bounds(px + (narrow ? 110 : 164), by, narrow ? 22 : 70, 18).build());
			addRenderableWidget(Button.builder(Component.literal("Im Browser"), ConfirmLinkScreen.confirmLink(this, URI.create(admin))).bounds(right - 74, by, 74, 18).build());
			updateNav();
		}
	}

	@Override
	public boolean isPauseScreen() { return false; }

	// ---------- Eingaben ----------

	@Override
	public boolean keyPressed(KeyEvent e) {
		if (mode == Mode.DETAIL) {
			// Seit 26.x nutzt Minecraft SDL-Tastencodes – daher die Konstanten aus InputConstants statt fester Zahlen.
			switch (e.key()) {
				case InputConstants.KEY_ESCAPE, InputConstants.KEY_BACKSPACE -> { back(); return true; } // zurück zur Übersicht
				case InputConstants.KEY_LEFT -> { step(-1); return true; }
				case InputConstants.KEY_RIGHT -> { step(1); return true; }
				default -> {}
			}
		}
		if (e.key() == InputConstants.KEY_PAGEDOWN || e.key() == InputConstants.KEY_PAGEUP) {
			scroll = Math.max(0, Math.min(maxScroll(), scroll + (e.key() == InputConstants.KEY_PAGEDOWN ? 1 : -1) * (bottom - top - 20)));
			return true;
		}
		if (e.key() == InputConstants.KEY_F5) { reload(); if (mode == Mode.DETAIL && detailNumber != null) openDetail(detailNumber); return true; } // F5
		return super.keyPressed(e);
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double sx, double sy) {
		scroll = Math.max(0, Math.min(maxScroll(), scroll - sy * 22));
		return true;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
		if (super.mouseClicked(e, doubleClick)) return true;
		if (mode == Mode.LIST && e.button() == InputConstants.MOUSE_BUTTON_LEFT && e.y() >= top && e.y() < bottom && e.x() >= px + 6 && e.x() < px + pw - 6) {
			int i = (int) ((e.y() - top - GAP + scroll) / (CARD + GAP));
			double inCard = (e.y() - top - GAP + scroll) - i * (CARD + GAP);
			if (i >= 0 && i < orders.size() && inCard < CARD) { openDetail(str(orders.get(i), "number")); return true; }
		}
		return false;
	}

	private int maxScroll() { return Math.max(0, contentH - (bottom - top)); }

	// ---------- Zeichnen ----------

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float pt) {
		// Rahmen, Kopf und Fuß
		g.fill(px, py, px + pw, py + ph, Theme.argb(0x0A110D, 238));
		g.outline(px, py, pw, ph, Theme.argb(0x2C4A36));
		g.fillGradient(px + 1, py + 1, px + pw - 1, py + HEADER, Theme.argb(0x1B3D26), Theme.argb(0x0E1D14));
		for (int x = px + 1; x < px + pw - 1; x++) g.fill(x, py + HEADER, x + 1, py + HEADER + 1, Theme.argb(Theme.at(new int[]{Theme.DARK, Theme.EMERALD, Theme.GOLD, Theme.EMERALD, Theme.DARK}, (float) (x - px) / pw)));
		g.fill(px + 1, bottom, px + pw - 1, bottom + 1, Theme.argb(0x24382B));
		g.text(font, Theme.brand(), px + 8, py + 6, -1, true);
		int bx = px + 8 + font.width(Theme.brand()) + 6;
		if (mode == Mode.LIST) {
			g.text(font, Theme.t("Offene Bestellanfragen", Theme.CREAM), bx, py + 6, -1, true);
			String c = loading && orders.isEmpty() ? "" : openTotal + (openTotal == 1 ? " Anfrage" : " Anfragen");
			g.text(font, Theme.t(c, Theme.MUTED), px + pw - 8 - font.width(c), py + 6, -1, false);
			g.text(font, Theme.t("Wähle eine Anfrage aus, um alle Details zu sehen.", Theme.DIM), px + 8, py + 18, -1, false);
		} else {
			g.text(font, Theme.t("Bestellanfrage", Theme.CREAM), bx, py + 6, -1, true);
			int i = indexOfDetail();
			String c = i >= 0 ? (i + 1) + " von " + orders.size() : "";
			g.text(font, Theme.t(c, Theme.MUTED), px + pw - 8 - font.width(c), py + 6, -1, false);
			g.text(font, Theme.t("Esc zurück · ←/→ blättern · nur Ansicht", Theme.DIM), px + 8, py + 18, -1, false);
		}

		g.enableScissor(px + 1, top, px + pw - 1, bottom);
		if (mode == Mode.LIST) drawList(g, mx, my); else drawDetail(g, mx, my);
		g.disableScissor();

		// Bildlaufleiste
		int bodyH = bottom - top;
		if (contentH > bodyH) {
			int barH = Math.max(16, bodyH * bodyH / contentH), barY = top + (int) ((bodyH - barH) * (scroll / Math.max(1, maxScroll())));
			g.fill(px + pw - 4, top + 1, px + pw - 2, bottom - 1, Theme.argb(0x16241B));
			g.fill(px + pw - 4, barY, px + pw - 2, barY + barH, Theme.argb(0x3C6B4A));
		}
		scroll = Math.max(0, Math.min(maxScroll(), scroll));
		// Fußzeile links: Stand der Daten oder Verbindungsfehler (auch wenn ältere Daten noch angezeigt werden)
		if (mode == Mode.LIST) {
			int fy = py + ph - 18, maxW = (px + pw - 6 - 78 - 92 - 64 - 12) - (px + 8);
			if (loading && !orders.isEmpty()) fit(g, Theme.t("Aktualisiere …", Theme.MUTED), px + 8, fy, maxW);
			else if (error != null && !orders.isEmpty()) {
				fit(g, Theme.t("✖ " + error, Theme.ERR), px + 8, fy, maxW);
				// Gekürzte Fehlermeldung vollständig als Tooltip
				if (mx >= px + 8 && mx < px + 8 + maxW && my >= fy - 2 && my < fy + 10) g.setTooltipForNextFrame(Theme.t(error, Theme.ERR), mx, my);
			}
			else if (!loadedAt.isEmpty()) fit(g, Theme.t("Stand " + loadedAt, Theme.DIM), px + 8, fy, maxW);
		}
		super.extractRenderState(g, mx, my, pt);
	}

	private void center(GuiGraphicsExtractor g, Component c, int y) { g.text(font, c, px + (pw - font.width(c)) / 2, y, -1, false); }

	private void drawList(GuiGraphicsExtractor g, int mx, int my) {
		if (loading && orders.isEmpty()) { center(g, Theme.t("Lade Bestellanfragen …", Theme.MUTED), top + 24); contentH = 0; return; }
		if (error != null && orders.isEmpty()) {
			center(g, Theme.t("✖ " + error, Theme.ERR), top + 22);
			center(g, Theme.t("Prüfe die Verbindung mit /obrien status und klicke auf „Aktualisieren“.", Theme.DIM), top + 36);
			contentH = 0; return;
		}
		if (orders.isEmpty()) {
			center(g, Theme.t("Keine offenen Bestellanfragen.", Theme.CREAM), top + 22);
			center(g, Theme.t("Neue Anfragen meldet die Mod automatisch.", Theme.DIM), top + 36);
			contentH = 0; return;
		}
		int x0 = px + 6, x1 = px + pw - 8;
		for (int i = 0; i < orders.size(); i++) {
			int y = top + GAP + i * (CARD + GAP) - (int) scroll;
			if (y + CARD < top || y > bottom) continue;
			JsonObject o = orders.get(i);
			boolean hover = mx >= x0 && mx < x1 && my >= Math.max(y, top) && my < Math.min(y + CARD, bottom);
			String status = str(o, "status");
			g.fill(x0, y, x1, y + CARD, Theme.argb(hover ? 0x1D3125 : 0x132019));
			g.fill(x0, y, x0 + 3, y + CARD, Theme.argb(Theme.statusColor(status)));
			if (hover) g.outline(x0, y, x1 - x0, CARD, Theme.argb(0x3F7A52));
			// Item-Symbol der ersten Position
			JsonArray ic = o.has("icons") ? o.getAsJsonArray("icons") : new JsonArray();
			g.fill(x0 + 8, y + 12, x0 + 28, y + 32, Theme.argb(0x0B120E));
			if (!ic.isEmpty()) g.item(icon(ic.get(0).getAsString()), x0 + 10, y + 14);
			int tx = x0 + 34;
			// Zeile 1: Nummer · Spieler · Status
			int pillW = font.width(status) + 10, pillX = x1 - pillW - 6;
			g.fill(pillX, y + 4, pillX + pillW, y + 15, Theme.argb(Theme.statusColor(status), 45));
			g.outline(pillX, y + 4, pillW, 11, Theme.argb(Theme.statusColor(status), 160));
			g.text(font, Theme.t(status, Theme.statusColor(status)), pillX + 5, y + 6, -1, false);
			MutableComponent l1 = Component.empty().append(Theme.b("#" + str(o, "number"), Theme.GOLD)).append(Theme.t("  ·  ", Theme.DIM)).append(Theme.t(str(o, "mc_name"), Theme.CREAM));
			fit(g, l1, tx, y + 5, pillX - tx - 6);
			// Zeile 2: Artikel und Menge
			int pos = o.has("positions") ? o.get("positions").getAsInt() : o.getAsJsonArray("lines").size();
			String qty = o.has("quantity") ? Theme.num(o.get("quantity").getAsDouble()) + (o.get("quantity").getAsDouble() == 1 ? " Item" : " Items") : "";
			MutableComponent l2 = Theme.t(pos + " Artikel" + (qty.isEmpty() ? "" : " · " + qty), Theme.MUTED);
			String when = date(o, SHORT);
			g.text(font, Theme.t(when, Theme.DIM), x1 - 6 - font.width(when), y + 17, -1, false);
			fit(g, l2, tx, y + 17, x1 - tx - font.width(when) - 14);
			// Zeile 3: Gesamtwert je Währung
			fit(g, totalsLine(o), tx, y + 29, x1 - tx - 8);
		}
		contentH = GAP + orders.size() * (CARD + GAP);
		if (openTotal > orders.size()) {
			int y = top + contentH - (int) scroll;
			center(g, Theme.t("+ " + (openTotal - orders.size()) + " ältere Anfragen im Admin-Bereich", Theme.DIM), y + 2);
			contentH += 14;
		}
	}

	/** „Gesamtwert: 500 × Ton · 50 × Diamant + 1 nach Absprache“ – Währungen werden nie zusammengerechnet. */
	private MutableComponent totalsLine(JsonObject o) {
		JsonArray totals = o.has("totals") && o.get("totals").isJsonArray() ? o.getAsJsonArray("totals") : new JsonArray();
		int req = o.has("request_count") ? o.get("request_count").getAsInt() : 0;
		MutableComponent c = Component.empty().append(Theme.t("Gesamtwert ", Theme.DIM));
		if (totals.isEmpty()) return c.append(Theme.i("nach Absprache", Theme.MUTED));
		for (int t = 0; t < totals.size(); t++) {
			JsonObject tt = totals.get(t).getAsJsonObject();
			if (t > 0) c.append(Theme.t(" · ", Theme.DIM));
			c.append(Theme.b(Theme.money(tt.get("amount").getAsDouble(), str(tt, "name")), Theme.GOLD));
		}
		if (req > 0) c.append(Theme.i("  + " + req + " nach Absprache", Theme.MUTED));
		return c;
	}

	private void drawDetail(GuiGraphicsExtractor g, int mx, int my) {
		int x0 = px + 10, x1 = px + pw - 10, y = top + 6 - (int) scroll, startY = y;
		if (detailLoading) { center(g, Theme.t("Lade Bestellanfrage " + detailNumber + " …", Theme.MUTED), top + 24); contentH = 0; return; }
		if (detailError != null) {
			center(g, Theme.t("✖ " + detailError, Theme.ERR), top + 22);
			center(g, Theme.t("Mit „‹ Übersicht“ zurück oder F5 zum erneuten Laden.", Theme.DIM), top + 36);
			contentH = 0; return;
		}
		JsonObject o = detail;
		if (o == null) { contentH = 0; return; }
		String status = str(o, "status");
		// Kopf
		g.text(font, Component.empty().append(Theme.b("#" + str(o, "number"), Theme.GOLD)), x0, y, -1, true);
		int pillW = font.width(status) + 10;
		g.fill(x1 - pillW, y - 2, x1, y + 9, Theme.argb(Theme.statusColor(status), 45));
		g.outline(x1 - pillW, y - 2, pillW, 11, Theme.argb(Theme.statusColor(status), 160));
		g.text(font, Theme.t(status, Theme.statusColor(status)), x1 - pillW + 5, y, -1, false);
		y += 15;
		// Angaben zur Anfrage
		y = field(g, "Kunde", str(o, "mc_name"), x0, y, x1);
		y = field(g, "Discord", str(o, "discord_name"), x0, y, x1);
		y = field(g, "Eingang", date(o, LONG), x0, y, x1);
		y = field(g, "Übergabe", str(o, "handover"), x0, y, x1);
		String msg = str(o, "message");
		if (!msg.isEmpty()) {
			g.text(font, Theme.t("Nachricht", Theme.DIM), x0, y, -1, false);
			for (FormattedCharSequence line : font.split(Theme.i(msg, Theme.TEXT), x1 - x0 - 70)) { g.text(font, line, x0 + 70, y, -1, false); y += 10; }
			y += 1;
		}
		y += 6;
		// Positionen
		JsonArray lines = o.getAsJsonArray("lines");
		g.text(font, Component.empty().append(Theme.gradient("BESTELLDETAILS", Theme.BRAND, true)).append(Theme.t("  " + lines.size() + " Artikel", Theme.DIM)), x0, y, -1, true);
		g.text(font, Theme.t("Positionspreis", Theme.DIM), x1 - font.width("Positionspreis"), y, -1, false);
		y += 11;
		g.fill(x0, y, x1, y + 1, Theme.argb(0x24382B));
		y += 5;
		int requestCount = 0;
		for (JsonElement el : lines) {
			JsonObject l = el.getAsJsonObject();
			boolean fixed = "fixed".equals(str(l, "type")) && l.has("price") && !l.get("price").isJsonNull();
			if (!fixed) requestCount++;
			g.fill(x0, y - 1, x0 + 20, y + 19, Theme.argb(0x0B120E));
			g.item(icon(str(l, "item_id")), x0 + 2, y + 1);
			int tx = x0 + 26;
			// Zeile 1: Artikelname (volle Breite)
			String name = str(l, "name");
			boolean cut = fit(g, Theme.b(name, Theme.CREAM), tx, y, x1 - tx);
			if (cut && mx >= tx && mx < x1 && my >= y && my < y + 9 && my >= top && my < bottom) g.setTooltipForNextFrame(Theme.t(name, Theme.CREAM), mx, my);
			// Zeile 2: Menge · Einzelpreis … Positionspreis (rechts, Gold)
			Component total = fixed ? Theme.b(Theme.money(l.get("total").getAsDouble(), str(l, "cur")), Theme.GOLD) : Theme.i("nach Absprache", Theme.MUTED);
			int tw = font.width(total);
			g.text(font, total, x1 - tw, y + 11, -1, false);
			String qty = Theme.num(l.get("qty").getAsDouble());
			Component unit = fixed ? Theme.t(Theme.money(l.get("price").getAsDouble(), str(l, "cur")), Theme.GOLD) : Theme.i("nach Absprache", Theme.MUTED);
			int room = x1 - tw - tx - 10;
			// Einzelpreis nie abschneiden: erst kompakt, sonst in eigener Zeile
			MutableComponent l2 = Component.empty().append(Theme.t("Menge ", Theme.DIM)).append(Theme.t(qty + " Stück", Theme.TEXT))
				.append(Theme.t("   Einzelpreis ", Theme.DIM)).append(unit);
			if (font.width(l2) > room)
				l2 = Component.empty().append(Theme.t(qty + " Stk.", Theme.TEXT)).append(Theme.t(" · je ", Theme.DIM)).append(unit);
			boolean own = font.width(l2) > room;
			if (own) l2 = Component.empty().append(Theme.t("Menge ", Theme.DIM)).append(Theme.t(qty + " Stück", Theme.TEXT));
			fit(g, l2, tx, y + 11, room);
			y += 22;
			if (own) {
				fit(g, Component.empty().append(Theme.t("Einzelpreis ", Theme.DIM)).append(unit), tx, y - 1, x1 - tx);
				y += 10;
			}
			JsonArray ench = l.has("ench") ? l.getAsJsonArray("ench") : new JsonArray();
			if (!ench.isEmpty()) {
				List<String> names = new ArrayList<>();
				for (JsonElement e : ench) names.add(e.getAsString());
				fit(g, Theme.i("Verzaubert: " + String.join(", ", names), Theme.VIOLET), tx, y, x1 - tx);
				y += 10;
			}
			g.fill(tx, y + 1, x1, y + 2, Theme.argb(0x182A1F));
			y += 6;
		}
		// Gesamtwert je Währung
		y += 2;
		for (int x = x0; x < x1; x++) g.fill(x, y, x + 1, y + 1, Theme.argb(Theme.at(new int[]{Theme.DARK, Theme.GOLD, Theme.DARK}, (float) (x - x0) / (x1 - x0))));
		y += 6;
		g.text(font, Component.empty().append(Theme.b("GESAMTWERT", Theme.CREAM)).append(Theme.t("  Richtwert, je Währung getrennt", Theme.DIM)), x0, y, -1, true);
		JsonArray totals = o.has("totals") && o.get("totals").isJsonArray() ? o.getAsJsonArray("totals") : new JsonArray();
		if (totals.isEmpty()) {
			y += 12;
			g.text(font, Theme.i("Kein fester Gesamtwert – alle Preise nach Absprache.", Theme.MUTED), x0, y, -1, false);
		}
		for (JsonElement el : totals) {
			JsonObject t = el.getAsJsonObject();
			y += 12;
			Component c = Theme.b(Theme.money(t.get("amount").getAsDouble(), str(t, "name")), Theme.GOLD);
			g.text(font, c, x1 - font.width(c), y, -1, true);
		}
		y += 13;
		if (requestCount > 0 && !totals.isEmpty()) {
			g.text(font, Theme.t("+ " + requestCount + (requestCount == 1 ? " Position" : " Positionen") + " nach Absprache – nicht im Gesamtwert enthalten.", Theme.WARN), x0, y, -1, false);
			y += 11;
		}
		g.text(font, Theme.t("Unverbindliche Anfrage – weder Kauf noch Zahlung bestätigt.", Theme.DIM), x0, y, -1, false);
		y += 14;
		contentH = y - startY + 6;
	}

	private int field(GuiGraphicsExtractor g, String label, String value, int x0, int y, int x1) {
		if (value.isEmpty()) return y;
		g.text(font, Theme.t(label, Theme.DIM), x0, y, -1, false);
		fit(g, Theme.t(value, Theme.TEXT), x0 + 70, y, x1 - x0 - 70);
		return y + 11;
	}

	/** Zeichnet eine Zeile; ist sie zu lang, wird sie mit „…“ gekürzt. Rückgabe: true, wenn gekürzt wurde. */
	private boolean fit(GuiGraphicsExtractor g, Component c, int x, int y, int maxW) {
		if (maxW <= 8) return true;
		if (font.width(c) <= maxW) { g.text(font, c, x, y, -1, false); return false; }
		List<FormattedCharSequence> parts = font.split(c, maxW - font.width("…"));
		if (!parts.isEmpty()) g.text(font, parts.get(0), x, y, -1, false);
		int w = parts.isEmpty() ? 0 : font.width(parts.get(0));
		g.text(font, Theme.t("…", Theme.DIM), x + w, y, -1, false);
		return true;
	}

	private ItemStack icon(String id) {
		return icons.computeIfAbsent(id, k -> {
			Identifier rl = Identifier.tryParse(k.contains(":") ? k : "minecraft:" + k);
			Item item = rl == null ? Items.PAPER : BuiltInRegistries.ITEM.getValue(rl);
			return new ItemStack(item == Items.AIR ? Items.PAPER : item);
		});
	}

	private static String str(JsonObject o, String key) {
		return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
	}

	private static String date(JsonObject o, DateTimeFormatter f) {
		try { return OffsetDateTime.parse(str(o, "created_at")).atZoneSameInstant(ZoneId.systemDefault()).format(f); } catch (Exception e) { return ""; }
	}
}
