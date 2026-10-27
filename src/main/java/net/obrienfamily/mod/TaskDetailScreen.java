package net.obrienfamily.mod;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Detailansicht einer Aufgabe – wie das Detailfenster auf der Website: Status und Zuständigkeit direkt änderbar,
 * Fakten, Tags, Beschreibung, Kommentare und Verlauf; Bearbeiten, Duplizieren, Archivieren und Löschen.
 */
public final class TaskDetailScreen extends BoardUi {
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy, HH:mm");
	private int LABEL = 92; // Breite der Beschriftungsspalte, aus dem längsten Text berechnet

	private final BoardScreen board;
	private final String id;
	private JsonObject data;
	private String error;
	private boolean comments = true;
	private EditBox input;
	private String draft = "";
	private String inputError;
	private boolean busy;
	private Button sendBtn;

	public TaskDetailScreen(BoardScreen board, String id) {
		super(Component.literal("O’Brien – Aufgabe"), board);
		this.board = board;
		this.id = id;
		load();
	}

	private void load() {
		Board.I.detail(id, d -> {
			data = d;
			error = null;
			Board.I.replace(d.getAsJsonObject("task"));
			if (minecraft != null) rebuildWidgets();
		}, e -> error = e);
	}

	private JsonObject task() { return data == null ? null : data.getAsJsonObject("task"); }
	private boolean archived() { return task() != null && task().has("archived_at") && !task().get("archived_at").isJsonNull(); }

	// ---------- Aufbau ----------

	@Override
	protected void init() {
		frame(540);
		LABEL = font.width("Zuletzt aktualisiert") + 10;
		bottom = py + ph - FOOTER - (comments && data != null ? 24 : 0);
		int by = py + ph - 23, x = px + 6;
		addRenderableWidget(button("‹ Board", b -> onClose(), x, by, 54));
		if (data != null) {
			JsonObject t = task();
			boolean narrow = pw < 400;
			int bx = x + 58;
			if (!archived()) { int ew = font.width(Theme.b("Bearbeiten", Theme.GOLD)) + 12; addRenderableWidget(Button.builder(Theme.b("Bearbeiten", Theme.GOLD), b -> edit()).bounds(bx, by, ew, 18).build()); bx += ew + 4; }
			addRenderableWidget(button(narrow ? "Kopie" : "Duplizieren", b -> Board.I.duplicate(t, n -> minecraft.gui.setScreen(new TaskDetailScreen(board, Board.str(n, "id")))), bx, by, narrow ? 40 : 66));
			bx += narrow ? 44 : 70;
			if (archived()) { addRenderableWidget(button("Wiederherstellen", b -> setArchived(false), bx, by, 90)); bx += 94; }
			else { addRenderableWidget(button("Archivieren", b -> setArchived(true), bx, by, 66)); bx += 70; }
			addRenderableWidget(Button.builder(Theme.t("Löschen", Theme.ERR), b -> askDelete()).bounds(bx, by, 50, 18).build());

			// Kommentarfeld (fest über der Fußzeile, wie auf der Website unter den Kommentaren)
			if (comments) {
				int iy = py + ph - FOOTER - 21, sw = 82;
				input = new EditBox(font, px + 8, iy, pw - 16 - sw - 4, 18, Component.literal("Kommentar"));
				input.setMaxLength(Board.I.limit("comment", 4000));
				input.setHint(Theme.t("Kommentar schreiben … (wird als „" + Board.I.selfName() + "“ gespeichert)", Theme.DIM));
				input.setValue(draft);
				input.setResponder(v -> { draft = v; inputError = null; updateSend(); });
				addRenderableWidget(input);
				sendBtn = addRenderableWidget(button(busy ? "Speichert …" : "Kommentieren", b -> sendComment(), px + pw - 8 - sw, iy, sw));
				updateSend();
			}

			// Status, Verantwortlich und Reiter scrollen mit dem Inhalt
			Layout l = layout();
			int fx = px + 10 + LABEL, w = Math.min(150, pw - 20 - LABEL);
			Button st = scrolled(Button.builder(Theme.t(Board.I.statusLabel(Board.str(t, "status")) + " ▾", statusColor(Board.str(t, "status"))), b -> statusMenu(b)).bounds(fx, l.status, w, 18).build());
			Button as = scrolled(Button.builder(Component.literal(Board.I.memberName(Board.str(t, "assignee")) + " ▾"), b -> assigneeMenu(b)).bounds(fx, l.assignee, w, 18).build());
			st.active = as.active = !archived();
			int c = data.getAsJsonArray("comments").size(), a = data.getAsJsonArray("activity").size();
			Button cb = scrolled(button("Kommentare (" + c + ")", b -> { comments = true; rebuildWidgets(); }, px + 10, l.tabs, 96));
			Button ab = scrolled(button("Verlauf (" + a + ")", b -> { comments = false; rebuildWidgets(); }, px + 110, l.tabs, 80));
			cb.active = !comments;
			ab.active = comments;
			placeScrolled();
		}
	}

	private void updateSend() {
		if (sendBtn != null) sendBtn.active = !busy && !draft.isBlank();
	}

	/** Positionen der mitscrollenden Felder (relativ zum Inhalt, ohne Bildlauf). */
	private record Layout(int status, int assignee, int tabs) {}

	private Layout layout() {
		JsonObject t = task();
		int w = pw - 20, y = top + 6;
		y += 14; // Etiketten
		y += lines(Theme.b(Board.str(t, "title"), Theme.CREAM), w, 99) * 10 + 6;
		int status = y;
		y += 21;
		int assignee = y;
		y += 21;
		y += 11 * facts(t).size();
		if (!t.getAsJsonArray("tags").isEmpty()) y += 14;
		y += 6;
		String d = Board.str(t, "description");
		y += 12 + (d.isEmpty() ? 10 : descLines(d, w).size() * 10) + 8;
		return new Layout(status, assignee, y);
	}

	private List<String[]> facts(JsonObject t) {
		List<String[]> f = new ArrayList<>();
		String due = Board.str(t, "due_date");
		boolean od = Board.bool(t, "overdue");
		f.add(new String[]{"Fällig", due.isEmpty() ? "Kein Datum" : fmtDate(due) + (od ? " · " + Board.str(t, "due_text") : ""), od ? "bad" : ""});
		f.add(new String[]{"Erstellt", time(Board.str(t, "created_at")) + " · von " + Board.str(t, "created_by_name"), ""});
		f.add(new String[]{"Zuletzt aktualisiert", time(Board.str(t, "updated_at")), ""});
		if (!Board.str(t, "completed_at").isEmpty()) f.add(new String[]{"Erledigt am", time(Board.str(t, "completed_at")), "ok"});
		return f;
	}

	private List<net.minecraft.util.FormattedCharSequence> descLines(String d, int w) {
		List<net.minecraft.util.FormattedCharSequence> out = new ArrayList<>();
		for (String para : d.split("\n", -1)) {
			if (para.isEmpty()) { out.add(net.minecraft.util.FormattedCharSequence.EMPTY); continue; }
			out.addAll(font.split(Theme.t(para, Theme.TEXT), w));
		}
		return out;
	}

	static String fmtDate(String iso) {
		String[] p = iso.split("-");
		return p.length == 3 ? p[2] + "." + p[1] + "." + p[0] : iso;
	}

	static String time(String iso) {
		try { return OffsetDateTime.parse(iso).atZoneSameInstant(ZoneId.of("Europe/Berlin")).format(TIME); } catch (Exception e) { return ""; }
	}

	// ---------- Aktionen ----------

	private void statusMenu(Button b) {
		JsonObject t = task();
		List<Option> o = new ArrayList<>();
		for (JsonElement e : Board.I.list("statuses")) {
			String k = Board.str(e.getAsJsonObject(), "key");
			o.add(new Option(Theme.t(Board.str(e.getAsJsonObject(), "label"), statusColor(k)), () -> change("status", k, "Status: " + Board.I.statusLabel(k)), k.equals(Board.str(t, "status"))));
		}
		openMenu(b, o);
	}

	private void assigneeMenu(Button b) {
		JsonObject t = task();
		List<Option> o = new ArrayList<>();
		o.add(new Option(Theme.t("Nicht zugewiesen", Theme.TEXT), () -> change("assignee", null, "Zuständig: Nicht zugewiesen"), Board.str(t, "assignee").isEmpty()));
		for (JsonElement e : Board.I.list("members")) {
			String k = Board.str(e.getAsJsonObject(), "key");
			o.add(new Option(Theme.t(Board.I.memberName(k), hex(Board.str(e.getAsJsonObject(), "color"), Theme.TEXT)), () -> change("assignee", k, "Zuständig: " + Board.I.memberName(k)), k.equals(Board.str(t, "assignee"))));
		}
		openMenu(b, o);
	}

	private void change(String key, String value, String ok) {
		JsonObject c = new JsonObject();
		c.addProperty(key, value);
		Board.I.patch(task(), c, ok, n -> load());
	}

	private void setArchived(boolean a) {
		JsonObject c = new JsonObject();
		c.addProperty("archived", a);
		Board.I.patch(task(), c, a ? "Aufgabe archiviert." : "Aufgabe wiederhergestellt.", n -> { if (n != null && a) onClose(); else load(); });
	}

	private void edit() { minecraft.gui.setScreen(new TaskFormScreen(this, task(), null, true)); }

	/** Nach dem Speichern im Formular: Details neu laden. */
	void reloadAfterEdit() { load(); }

	private void askDelete() {
		JsonObject t = task();
		minecraft.gui.setScreen(new ConfirmScreen(yes -> {
			if (yes) Board.I.delete(t, () -> minecraft.gui.setScreen(board));
			else minecraft.gui.setScreen(this);
		}, Theme.b("Aufgabe endgültig löschen?", Theme.ERR),
			Theme.t("„" + Board.str(t, "title") + "“ wird mit allen Kommentaren und dem Verlauf dauerhaft gelöscht. Das kann nicht rückgängig gemacht werden. Tipp: Archivieren behält alles.", Theme.TEXT),
			Theme.b("Endgültig löschen", Theme.ERR), Component.literal("Abbrechen")));
	}

	private void sendComment() {
		String text = draft.trim();
		if (text.isEmpty()) { inputError = "Der Kommentar ist leer."; return; }
		busy = true;
		updateSend();
		Board.I.comment(id, text, () -> { busy = false; draft = ""; inputError = null; comments = true; load(); rebuildWidgets(); },
			e -> { busy = false; inputError = e; updateSend(); });
	}

	@Override
	public void onClose() { if (minecraft != null) minecraft.gui.setScreen(board); }

	@Override
	public boolean keyPressed(KeyEvent e) {
		if (!menuOpen() && input != null && input.isFocused() && (e.key() == InputConstants.KEY_RETURN || e.key() == InputConstants.KEY_NUMPADENTER)) { sendComment(); return true; }
		if (!menuOpen() && (input == null || !input.isFocused())) {
			if (e.key() == InputConstants.KEY_PAGEDOWN || e.key() == InputConstants.KEY_PAGEUP) {
				scroll = Math.max(0, Math.min(maxScroll(), scroll + (e.key() == InputConstants.KEY_PAGEDOWN ? 1 : -1) * (bottom - top - 20)));
				placeScrolled();
				return true;
			}
			if (e.key() == InputConstants.KEY_F5) { load(); return true; }
		}
		return super.keyPressed(e);
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double sx, double sy) {
		if (super.mouseScrolled(mx, my, sx, sy)) return true;
		scroll = Math.max(0, Math.min(maxScroll(), scroll - sy * 22));
		placeScrolled();
		return true;
	}

	// ---------- Zeichnen ----------

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float pt) {
		JsonObject t = task();
		drawFrame(g, Theme.t("Aufgabe", Theme.CREAM), "Esc zurück zum Board · Änderungen werden als „" + Board.I.selfName() + "“ protokolliert", null);
		g.enableScissor(px + 1, top, px + pw - 1, bottom);
		if (t == null) {
			center(g, error != null ? Theme.t("✖ " + error, Theme.ERR) : Theme.t("Wird geladen …", Theme.MUTED), top + 24);
			contentH = 0;
		} else drawBody(g, t, mx, my);
		g.disableScissor();
		scrollbar(g);
		placeScrolled();
		if (comments && t != null && inputError != null) g.text(font, Theme.t(inputError, Theme.ERR), px + 8, bottom - 10, -1, false);
		super.extractRenderState(g, mx, my, pt);
		drawToasts(g);
		drawMenu(g, mx, my);
	}

	private void drawBody(GuiGraphicsExtractor g, JsonObject t, int mx, int my) {
		int x0 = px + 10, x1 = px + pw - 10, w = x1 - x0, y = top + 6 - (int) scroll, startY = y;
		String p = Board.str(t, "priority");
		JsonObject pr = Board.I.priority(p);
		int px2 = x0 + pill(g, Board.str(pr, "icon") + " " + Board.str(pr, "label"), prioColor(p), x0, y, false) + 4;
		if (Board.bool(t, "overdue")) px2 += pill(g, "Überfällig", 0xB33A32, px2, y, true) + 4;
		if (archived()) pill(g, "Archiviert", 0x3A4A3F, px2, y, true);
		y += 14;
		y = wrap(g, Theme.b(Board.str(t, "title"), Theme.CREAM), x0, y, w, 99) + 6;
		g.text(font, Theme.t("Status", Theme.DIM), x0, y + 5, -1, false);
		y += 21;
		g.text(font, Theme.t("Verantwortlich", Theme.DIM), x0, y + 5, -1, false);
		avatar(g, Board.str(t, "assignee"), x0 + LABEL - 6 - avatarW(Board.str(t, "assignee"), 12), y + 3, 12);
		y += 21;
		for (String[] f : facts(t)) {
			g.text(font, Theme.t(f[0], Theme.DIM), x0, y, -1, false);
			fit(g, Theme.t(f[1], f[2].equals("bad") ? Theme.ERR : f[2].equals("ok") ? Theme.OK : Theme.TEXT), x0 + LABEL, y, w - LABEL);
			y += 11;
		}
		JsonArray tags = t.getAsJsonArray("tags");
		if (!tags.isEmpty()) {
			int tx = x0;
			for (JsonElement e : tags) {
				String tag = e.getAsString();
				int tw = font.width(tag) + 8;
				g.fill(tx, y + 1, tx + tw, y + 12, Theme.argb(0x1F3326));
				g.text(font, Theme.t(tag, 0xA8C7B2), tx + 4, y + 3, -1, false);
				tx += tw + 4;
			}
			y += 14;
		}
		y += 6;
		g.text(font, Theme.gradient("BESCHREIBUNG", Theme.BRAND, true), x0, y, -1, true);
		y += 12;
		String d = Board.str(t, "description");
		if (d.isEmpty()) { g.text(font, Theme.i("Keine Beschreibung.", Theme.DIM), x0, y, -1, false); y += 10; }
		else for (var line : descLines(d, w)) { g.text(font, line, x0, y, -1, false); y += 10; }
		y += 8;
		y += 22; // Reiter (Knöpfe)
		g.fill(x0, y - 3, x1, y - 2, Theme.argb(0x24382B));
		if (comments) {
			JsonArray cs = data.getAsJsonArray("comments");
			if (cs.isEmpty()) { g.text(font, Theme.i("Noch keine Kommentare.", Theme.DIM), x0, y + 2, -1, false); y += 14; }
			for (JsonElement e : cs) {
				JsonObject c = e.getAsJsonObject();
				String author = Board.str(c, "author");
				int ax = x0;
				if (Board.I.member(author) != null) { avatar(g, author, x0, y, 10); ax += avatarW(author, 10) + 3; }
				g.text(font, Theme.b(Board.str(c, "author_name"), Theme.CREAM), ax, y + 1, -1, false);
				String when = time(Board.str(c, "created_at"));
				g.text(font, Theme.t(when, Theme.DIM), x1 - font.width(when), y + 1, -1, false);
				y += 12;
				for (String para : Board.str(c, "body").split("\n", -1)) y = wrap(g, Theme.t(para, Theme.TEXT), x0 + 4, y, w - 4, 999);
				y += 6;
			}
		} else {
			JsonArray as = data.getAsJsonArray("activity");
			for (JsonElement e : as) {
				JsonObject a = e.getAsJsonObject();
				MutableComponent c = Theme.b(Board.str(a, "actor_name"), Theme.CREAM).append(Theme.t(" " + Board.str(a, "text"), Theme.TEXT));
				y = wrap(g, c, x0, y, w, 999);
				g.text(font, Theme.t(time(Board.str(a, "created_at")), Theme.DIM), x0, y, -1, false);
				y += 14;
			}
		}
		contentH = y - startY + 4;
	}
}
