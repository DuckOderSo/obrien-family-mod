package net.obrienfamily.mod;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Neue Aufgabe / Aufgabe bearbeiten – dieselben Felder, Prüfungen und Meldungen wie das Formular auf der Website.
 * Die verbindliche Prüfung macht die Website; Fehler werden hier am jeweiligen Feld angezeigt.
 */
public final class TaskFormScreen extends BoardUi {
	private static final int LABEL = 84;

	private final boolean fromDetail;
	private JsonObject base;          // null = neue Aufgabe
	private String title, description, assignee, status, priority, due;
	private final List<String> tags = new ArrayList<>();
	private String newTag = "";
	private final Map<String, String> errs = new HashMap<>();
	private String msg;
	private boolean busy;

	private EditBox titleBox, dueBox, tagBox;
	private int yAssign, yDue, yPrio, yTags, yEnd; // Zeilenpositionen ohne Bildlauf
	private MultiLineEditBox descBox;

	public TaskFormScreen(Screen parent, JsonObject task, String status, boolean fromDetail) {
		super(Component.literal(task == null ? "Neue Aufgabe" : "Aufgabe bearbeiten"), parent);
		this.fromDetail = fromDetail;
		this.base = task;
		this.title = task == null ? "" : Board.str(task, "title");
		this.description = task == null ? "" : Board.str(task, "description");
		this.assignee = task == null ? "" : Board.str(task, "assignee");
		this.status = task != null ? Board.str(task, "status") : status != null ? status : "backlog";
		this.priority = task == null ? "normal" : Board.str(task, "priority");
		String d = task == null ? "" : Board.str(task, "due_date");
		this.due = d.isEmpty() ? "" : TaskDetailScreen.fmtDate(d);
		if (task != null) for (JsonElement e : task.getAsJsonArray("tags")) tags.add(e.getAsString());
	}

	// ---------- Aufbau ----------

	@Override
	protected void init() {
		frame(500);
		int x = px + 10 + LABEL, w = pw - 20 - LABEL, y = top + 6;

		titleBox = scrolled(new EditBox(font, x, y, w, 18, Component.literal("Titel")));
		titleBox.setMaxLength(Board.I.limit("title", 160));
		titleBox.setHint(Theme.t("z. B. Startseite: neues Banner einbauen", Theme.DIM));
		titleBox.setValue(title);
		titleBox.setResponder(v -> { title = v; errs.remove("title"); });
		y += 32;

		descBox = scrolled(MultiLineEditBox.builder().setX(x).setY(y)
			.setPlaceholder(Theme.t("Details, Schritte, Links … (Zeilenumbrüche bleiben erhalten)", Theme.DIM))
			.build(font, w, 58, Component.literal("Beschreibung")));
		descBox.setCharacterLimit(Board.I.limit("description", 8000));
		descBox.setValue(description);
		descBox.setValueListener(v -> { description = v; errs.remove("description"); });
		y += 72;

		yAssign = y;
		int half = (w - 4) / 2;
		scrolled(Button.builder(Component.literal(Board.I.memberName(assignee) + " ▾"), this::assigneeMenu).bounds(x, y, half, 18).build());
		scrolled(Button.builder(Theme.t("Status: " + Board.I.statusLabel(status) + " ▾", statusColor(status)), this::statusMenu).bounds(x + half + 4, y, w - half - 4, 18).build());
		y += 24;

		yDue = y;
		dueBox = scrolled(new EditBox(font, x, y, 74, 18, Component.literal("Fällig am")));
		dueBox.setMaxLength(10);
		dueBox.setHint(Theme.t("TT.MM.JJJJ", Theme.DIM));
		dueBox.setValue(due);
		dueBox.setResponder(v -> { due = v; errs.remove("due_date"); });
		int bx = x + 78;
		scrolled(button("Heute", b -> setDue(0), bx, y, 38)); bx += 41;
		scrolled(button("+1 T", b -> shiftDue(1), bx, y, 32)); bx += 35;
		scrolled(button("+7 T", b -> shiftDue(7), bx, y, 32)); bx += 35;
		scrolled(Button.builder(Theme.t("✕", Theme.MUTED), b -> { due = ""; dueBox.setValue(""); }).bounds(bx, y, 18, 18).build());
		y += 30;

		yPrio = y;
		int pwBtn = (w - 12) / 4, i = 0;
		for (JsonElement e : Board.I.list("priorities")) {
			JsonObject p = e.getAsJsonObject();
			String k = Board.str(p, "key");
			boolean on = k.equals(priority);
			Component label = on ? Theme.b("✔ " + Board.str(p, "label"), prioColor(k)) : Theme.t(Board.str(p, "label"), prioColor(k));
			scrolled(Button.builder(label, b -> { priority = k; rebuildWidgets(); }).bounds(x + i * (pwBtn + 4), y, pwBtn, 18).build());
			i++;
		}
		y += 30;

		// Tags: alle bekannten plus die der Aufgabe, zum An-/Abwählen
		yTags = y;
		LinkedHashSet<String> list = new LinkedHashSet<>(Board.I.allTags());
		list.addAll(tags);
		int tx = x;
		for (String t : list) {
			boolean on = tags.contains(t);
			Component label = on ? Theme.t("✔ " + t, Theme.GOLD) : Theme.t(t, Theme.TEXT);
			int bw = font.width(label) + 10;
			if (tx + bw > x + w) { tx = x; y += 21; }
			scrolled(Button.builder(label, b -> { if (on) tags.remove(t); else tags.add(t); errs.remove("tags"); rebuildWidgets(); }).bounds(tx, y, bw, 18).build());
			tx += bw + 3;
		}
		y += 22;
		tagBox = scrolled(new EditBox(font, x, y, Math.max(60, w - 84), 18, Component.literal("Eigener Tag")));
		tagBox.setMaxLength(Board.I.limit("tag", 24));
		tagBox.setHint(Theme.t("Eigener Tag …", Theme.DIM));
		tagBox.setValue(newTag);
		tagBox.setResponder(v -> newTag = v);
		scrolled(button("Hinzufügen", b -> addTag(), x + w - 80, y, 80));
		y += 34;
		yEnd = y;

		int by = py + ph - 23, right = px + pw - 6;
		String save = busy ? "Speichert …" : base == null ? "Aufgabe anlegen" : "Änderungen speichern";
		int sw = font.width(Theme.b(save, Theme.GOLD)) + 14;
		Button s = addRenderableWidget(Button.builder(Theme.b(save, Theme.GOLD), b -> submit()).bounds(right - sw, by, sw, 18).build());
		s.active = !busy;
		addRenderableWidget(button("Abbrechen", b -> onClose(), right - sw - 4 - 70, by, 70));
		placeScrolled();
		if (base == null && title.isEmpty()) setInitialFocus(titleBox);
	}

	private void setDue(int days) {
		LocalDate d = LocalDate.now(ZoneId.of("Europe/Berlin")).plusDays(days);
		due = String.format(Locale.ROOT, "%02d.%02d.%04d", d.getDayOfMonth(), d.getMonthValue(), d.getYear());
		dueBox.setValue(due);
	}

	private void shiftDue(int days) {
		String iso = isoDate(due);
		if (iso == null || iso.isEmpty()) { setDue(days); return; }
		LocalDate d = LocalDate.parse(iso).plusDays(days);
		due = String.format(Locale.ROOT, "%02d.%02d.%04d", d.getDayOfMonth(), d.getMonthValue(), d.getYear());
		dueBox.setValue(due);
	}

	/** „TT.MM.JJJJ“ oder „JJJJ-MM-TT“ → „JJJJ-MM-TT“; leer → ""; ungültig → null. */
	static String isoDate(String s) {
		String v = s.trim();
		if (v.isEmpty()) return "";
		try {
			LocalDate d;
			if (v.matches("\\d{1,2}\\.\\d{1,2}\\.\\d{4}")) {
				String[] p = v.split("\\.");
				d = LocalDate.of(Integer.parseInt(p[2]), Integer.parseInt(p[1]), Integer.parseInt(p[0]));
			} else d = LocalDate.parse(v);
			return d.toString();
		} catch (Exception e) {
			return null;
		}
	}

	private void addTag() {
		String t = newTag.replaceAll("\\s+", " ").trim();
		if (t.isEmpty()) return;
		boolean exists = false;
		for (String x : tags) if (x.equalsIgnoreCase(t)) exists = true;
		if (!exists) tags.add(t);
		newTag = "";
		rebuildWidgets();
	}

	private void assigneeMenu(Button b) {
		List<Option> o = new ArrayList<>();
		o.add(new Option(Theme.t("Nicht zugewiesen", Theme.TEXT), () -> { assignee = ""; rebuildWidgets(); }, assignee.isEmpty()));
		for (JsonElement e : Board.I.list("members")) {
			String k = Board.str(e.getAsJsonObject(), "key");
			o.add(new Option(Theme.t(Board.I.memberName(k), hex(Board.str(e.getAsJsonObject(), "color"), Theme.TEXT)), () -> { assignee = k; rebuildWidgets(); }, k.equals(assignee)));
		}
		openMenu(b, o);
	}

	private void statusMenu(Button b) {
		List<Option> o = new ArrayList<>();
		for (JsonElement e : Board.I.list("statuses")) {
			String k = Board.str(e.getAsJsonObject(), "key");
			o.add(new Option(Theme.t(Board.str(e.getAsJsonObject(), "label"), statusColor(k)), () -> { status = k; rebuildWidgets(); }, k.equals(status)));
		}
		openMenu(b, o);
	}

	// ---------- Speichern ----------

	private void submit() {
		if (busy) return;
		msg = null;
		errs.clear();
		String t = title.replaceAll("\\s+", " ").trim();
		if (t.isEmpty()) errs.put("title", "Bitte gib einen Titel ein.");
		String iso = isoDate(due);
		if (iso == null) errs.put("due_date", "Bitte gib ein gültiges Datum ein (TT.MM.JJJJ).");
		if (!errs.isEmpty()) return;
		JsonObject body = new JsonObject();
		body.addProperty("title", title);
		body.addProperty("description", description);
		body.addProperty("assignee", assignee.isEmpty() ? null : assignee);
		body.addProperty("status", status);
		body.addProperty("priority", priority);
		body.addProperty("due_date", iso.isEmpty() ? null : iso);
		JsonArray ta = new JsonArray();
		for (String x : tags) ta.add(x);
		body.add("tags", ta);
		busy = true;
		rebuildWidgets();
		Board.I.save(base, body, n -> {
			busy = false;
			Board.I.toast(true, base == null ? "Aufgabe angelegt." : "Änderungen gespeichert.");
			if (parent instanceof TaskDetailScreen d) d.reloadAfterEdit();
			minecraft.gui.setScreen(parent);
		}, e -> {
			busy = false;
			if (e.status == 409 && e.body.has("task")) {
				base = e.body.getAsJsonObject("task");
				Board.I.toast(false, e.getMessage());
				msg = "Jemand hat diese Aufgabe inzwischen geändert. Deine Eingaben sind noch hier – prüfe die aktuelle Version im Board und speichere erneut, um sie zu übernehmen.";
			} else {
				if (e.body.has("fields") && e.body.get("fields").isJsonObject())
					for (var f : e.body.getAsJsonObject("fields").entrySet()) errs.put(f.getKey(), f.getValue().getAsString());
				msg = e.getMessage();
			}
			rebuildWidgets();
		});
	}

	@Override
	public boolean keyPressed(KeyEvent e) {
		boolean enter = e.key() == InputConstants.KEY_RETURN || e.key() == InputConstants.KEY_NUMPADENTER;
		if (!menuOpen() && enter) {
			if (tagBox != null && tagBox.isFocused()) { addTag(); return true; }
			if ((titleBox != null && titleBox.isFocused()) || (dueBox != null && dueBox.isFocused())) { submit(); return true; }
		}
		return super.keyPressed(e);
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double sx, double sy) {
		if (super.mouseScrolled(mx, my, sx, sy)) return true;
		if (descBox != null && descBox.isMouseOver(mx, my) && descBox.visible) return descBox.mouseScrolled(mx, my, sx, sy);
		scroll = Math.max(0, Math.min(maxScroll(), scroll - sy * 22));
		placeScrolled();
		return true;
	}

	// ---------- Zeichnen ----------

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float pt) {
		drawFrame(g, Theme.t(base == null ? "Neue Aufgabe" : "Aufgabe bearbeiten", Theme.CREAM),
			"Wird als „" + Board.I.selfName() + "“ protokolliert · Bitte keine Passwörter oder Zugangsdaten eintragen.", null);
		placeScrolled();
		g.enableScissor(px + 1, top, px + pw - 1, bottom);
		int x0 = px + 10, s = (int) scroll;
		label(g, "Titel *", top + 6 - s, x0);
		hint(g, "title", title.length() + "/" + Board.I.limit("title", 160), titleBox);
		label(g, "Beschreibung", top + 38 - s, x0);
		hint(g, "description", "optional", descBox);
		label(g, "Verantwortlich", yAssign - s, x0);
		label(g, "Fällig am", yDue - s, x0);
		hint(g, "due_date", "optional", dueBox);
		label(g, "Priorität", yPrio - s, x0);
		label(g, "Tags", yTags - s, x0);
		if (errs.containsKey("tags")) g.text(font, Theme.t(errs.get("tags"), Theme.ERR), tagBox.getX(), tagBox.getY() + 21, -1, false);
		int end = yEnd;
		if (msg != null) end = wrap(g, Theme.t("⚠ " + msg, 0xFFB4AE), x0, yEnd - s, pw - 20, 5) + s + 6;
		contentH = end - top;
		g.disableScissor();
		scrollbar(g);
		super.extractRenderState(g, mx, my, pt);
		drawToasts(g);
		drawMenu(g, mx, my);
	}

	private void label(GuiGraphicsExtractor g, String text, int y, int x0) {
		g.text(font, Theme.t(text, Theme.DIM), x0, y + 5, -1, false);
	}

	private void hint(GuiGraphicsExtractor g, String field, String normal, net.minecraft.client.gui.components.AbstractWidget w) {
		String e = errs.get(field);
		g.text(font, Theme.t(e != null ? e : normal, e != null ? Theme.ERR : Theme.DIM), w.getX(), w.getY() + w.getHeight() + 3, -1, false);
	}

	@Override
	public void onClose() {
		if (minecraft != null) minecraft.gui.setScreen(parent);
	}
}
