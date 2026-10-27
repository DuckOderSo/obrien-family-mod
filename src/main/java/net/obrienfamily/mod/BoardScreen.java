package net.obrienfamily.mod;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.net.URI;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Family Task Board im Spiel – dieselben Aufgaben, Spalten und Regeln wie das Board im Admin-Bereich der Website.
 * Karten lassen sich per Drag &amp; Drop zwischen den Spalten verschieben (Rechtsklick: „Verschieben nach …“),
 * ein Klick öffnet die Details. Filter, Suche, Sortierung und Archiv entsprechen der Website.
 */
public final class BoardScreen extends BoardUi {
	private static final int GAP = 4, COL_HEAD = 24, MIN_COL = 104;
	private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("dd.MM.yyyy, HH:mm");

	// Filter bleiben für die Spielsitzung erhalten (wie die Auswahl auf der Website während der Sitzung).
	private static String q = "", fAssignee = "", fPriority = "", fTag = "", fDue = "", sort = "priority";
	private static boolean mine, overdueOnly, criticalOnly, archiveView;
	private static String chosenMe; // beim gemeinsamen Admin-Zugang: „Ansicht für“

	private int boardTop, colW, hx;
	private final double[] colScroll = new double[5];
	private final int[] colContent = new int[5];

	private record Hit(JsonObject task, int x, int y, int w, int h, int col) {}
	private final List<Hit> hits = new ArrayList<>();
	private final List<int[]> plus = new ArrayList<>();      // „+“ je Spalte: x, y, w, h, Spalte
	private record ArchHit(JsonObject task, boolean restore, int x, int y, int w, int h) {}
	private final List<ArchHit> archHits = new ArrayList<>();

	// Drag & Drop
	private JsonObject pressed;
	private double pressX, pressY, offX, offY;
	private boolean dragging;
	private int pressW, pressH;

	private EditBox search;

	public BoardScreen() {
		super(Component.literal("O’Brien – Family Task Board"), null);
		if (Board.I.meta == null || System.currentTimeMillis() - Board.I.lastLoad > 5000) Board.I.load(false);
		if (archiveView) Board.I.loadArchive();
	}

	/** Öffnet das Board – nach dem Schließen des Chats, damit es nicht sofort wieder zugeht. */
	public static void open() {
		Minecraft mc = Minecraft.getInstance();
		if (!ObrienMod.config.hasToken()) {
			Notifier.warn("Noch nicht verbunden – gib zuerst /obrien key <Schlüssel> ein.");
			return;
		}
		ObrienMod.worker.execute(() -> mc.execute(() -> mc.gui.setScreen(new BoardScreen())));
	}

	@Override
	public void onClose() { if (minecraft != null) minecraft.gui.setScreen(null); }

	// ---------- Aufbau ----------

	@Override
	protected void init() {
		frame(100_000);
		int x = px + 6, y = top + 4, right = px + pw - 6;

		search = new EditBox(font, x, y, 112, 18, Component.literal("Suche"));
		search.setHint(Theme.t("Suche in Titel und Text …", Theme.DIM));
		search.setMaxLength(80);
		search.setValue(q);
		search.setResponder(v -> q = v);
		addRenderableWidget(search);
		x += 116;

		List<Object[]> items = new ArrayList<>(); // Beschriftung, Breite, Aktion (Consumer<Button>)
		items.add(new Object[]{personLabel(), (Consumer<Button>) this::personMenu});
		items.add(new Object[]{fPriority.isEmpty() ? "Alle Prioritäten" : Board.str(Board.I.priority(fPriority), "label"), (Consumer<Button>) this::prioMenu});
		items.add(new Object[]{fTag.isEmpty() ? "Alle Tags" : fTag, (Consumer<Button>) this::tagMenu});
		items.add(new Object[]{dueLabel(fDue), (Consumer<Button>) this::dueMenu});
		items.add(new Object[]{switch (sort) { case "due" -> "Sortierung: Fälligkeit"; case "created" -> "Sortierung: Neueste"; default -> "Sortierung: Priorität"; }, (Consumer<Button>) this::sortMenu});
		int rowY = y;
		for (Object[] it : items) {
			String label = it[0] + " ▾";
			int w = Math.min(120, font.width(label) + 12);
			if (x + w > right) { x = px + 6; rowY += 21; }
			@SuppressWarnings("unchecked") Consumer<Button> act = (Consumer<Button>) it[1];
			Button b = addRenderableWidget(Button.builder(Component.literal(label), act::accept).bounds(x, rowY, w, 18).build());
			if (font.width(label) + 12 > w) b.setTooltip(Tooltip.create(Component.literal(label)));
			x += w + 4;
		}
		// Schnellfilter und Ansicht
		String meName = Board.I.memberName(meKey());
		x = chip("Meine Aufgaben" + (meKey() != null ? " (" + meName + ")" : ""), mine, Theme.EMERALD, x, rowY, right, b -> toggleMine());
		rowY = lastRowY;
		x = chip("Überfällig", overdueOnly, Theme.ERR, x, rowY, right, b -> { overdueOnly = !overdueOnly; rebuildWidgets(); });
		rowY = lastRowY;
		x = chip("Kritisch", criticalOnly, 0xFF6B60, x, rowY, right, b -> { criticalOnly = !criticalOnly; rebuildWidgets(); });
		rowY = lastRowY;
		if (filtered()) {
			int w = font.width("Filter zurücksetzen") + 12;
			if (x + w > right) { x = px + 6; rowY += 21; }
			addRenderableWidget(Button.builder(Theme.t("Filter zurücksetzen", Theme.GOLD), b -> { resetFilters(); rebuildWidgets(); }).bounds(x, rowY, w, 18).build());
			x += w + 4;
		}
		int aw = font.width("Archiv (000)") + 12, bw = font.width("Board") + 14;
		if (x + aw + bw + 4 > right) { x = px + 6; rowY += 21; }
		Button bb = addRenderableWidget(Button.builder(Component.literal("Board"), b -> { archiveView = false; scroll = 0; rebuildWidgets(); }).bounds(right - aw - bw - 4, rowY, bw, 18).build());
		String arch = "Archiv" + (Board.I.archived != null ? " (" + Board.I.archived.size() + ")" : "");
		Button ab = addRenderableWidget(Button.builder(Component.literal(arch), b -> { archiveView = true; scroll = 0; Board.I.loadArchive(); rebuildWidgets(); }).bounds(right - aw, rowY, aw, 18).build());
		bb.active = archiveView;
		ab.active = !archiveView;
		boardTop = rowY + 22;
		top = boardTop; // Bildlaufbereich der Archivliste

		// Fußzeile
		int by = py + ph - 23, w4 = 60, w3 = 70, w2 = 78, w1 = 96;
		addRenderableWidget(button("Schließen", b -> onClose(), right - w4, by, w4));
		addRenderableWidget(Button.builder(Component.literal("Im Browser"), ConfirmLinkScreen.confirmLink(this, URI.create(ObrienMod.config.base() + "/admin"))).bounds(right - w4 - w3 - 4, by, w3, 18).build());
		addRenderableWidget(button("Aktualisieren", b -> { Board.I.load(false); if (archiveView) Board.I.loadArchive(); }, right - w4 - w3 - w2 - 8, by, w2));
		addRenderableWidget(Button.builder(Theme.b("+ Neue Aufgabe", Theme.GOLD), b -> openForm(null, "backlog")).bounds(right - w4 - w3 - w2 - w1 - 12, by, w1, 18).build());

		int areaW = pw - 12;
		colW = Math.max(MIN_COL, (areaW - 4 * GAP) / 5);
		clampH();
	}

	private int lastRowY;
	private int chip(String label, boolean on, int color, int x, int y, int right, Button.OnPress press) {
		MutableComponent c = on ? Theme.t("✔ " + label, color) : Theme.t(label, Theme.TEXT);
		int w = font.width(c) + 12;
		if (x + w > right) { x = px + 6; y += 21; }
		addRenderableWidget(Button.builder(c, press).bounds(x, y, w, 18).build());
		lastRowY = y;
		return x + w + 4;
	}

	private String personLabel() {
		if (fAssignee.isEmpty()) return "Alle Mitglieder";
		return fAssignee.equals("none") ? "Nicht zugewiesen" : Board.I.memberName(fAssignee);
	}

	private static String dueLabel(String d) {
		return switch (d) {
			case "overdue" -> "Überfällig"; case "today" -> "Heute fällig"; case "week" -> "Nächste 7 Tage";
			case "set" -> "Mit Datum"; case "none" -> "Ohne Datum"; default -> "Jede Fälligkeit";
		};
	}

	private String meKey() { return Board.I.self() != null ? Board.I.self() : chosenMe; }

	private boolean filtered() {
		return !q.isBlank() || !fAssignee.isEmpty() || !fPriority.isEmpty() || !fTag.isEmpty() || !fDue.isEmpty() || mine || overdueOnly || criticalOnly;
	}

	private void resetFilters() {
		q = ""; fAssignee = ""; fPriority = ""; fTag = ""; fDue = "";
		mine = overdueOnly = criticalOnly = false;
	}

	// ---------- Menüs ----------

	private void personMenu(Button b) {
		List<Option> o = new ArrayList<>();
		o.add(new Option(Theme.t("Alle Mitglieder", Theme.TEXT), () -> setF(() -> fAssignee = ""), fAssignee.isEmpty()));
		for (JsonElement e : Board.I.list("members")) {
			String k = Board.str(e.getAsJsonObject(), "key");
			o.add(new Option(teamLine(k), () -> setF(() -> fAssignee = k), fAssignee.equals(k)));
		}
		o.add(new Option(teamLine("none"), () -> setF(() -> fAssignee = "none"), fAssignee.equals("none")));
		openMenu(b, o);
	}

	/** „Noel · 3 offen · 1 in Arbeit · 1 überfällig“ – die Team-Übersicht der Website als Menüeintrag. */
	private Component teamLine(String k) {
		int open = 0, doing = 0, od = 0;
		for (JsonObject t : Board.I.tasks) {
			String a = Board.str(t, "assignee");
			if (!(a.isEmpty() ? "none" : a).equals(k) || "done".equals(Board.str(t, "status"))) continue;
			open++;
			if ("doing".equals(Board.str(t, "status"))) doing++;
			if (Board.bool(t, "overdue")) od++;
		}
		MutableComponent c = Theme.t(k.equals("none") ? "Nicht zugewiesen" : Board.I.memberName(k), Theme.CREAM).append(Theme.t("  " + open + " offen" + (doing > 0 ? " · " + doing + " in Arbeit" : ""), Theme.DIM));
		if (od > 0) c.append(Theme.t(" · " + od + " überfällig", Theme.ERR));
		return c;
	}

	private void prioMenu(Button b) {
		List<Option> o = new ArrayList<>();
		o.add(new Option(Theme.t("Alle Prioritäten", Theme.TEXT), () -> setF(() -> fPriority = ""), fPriority.isEmpty()));
		for (JsonElement e : Board.I.list("priorities")) {
			JsonObject p = e.getAsJsonObject();
			String k = Board.str(p, "key");
			o.add(new Option(Theme.t(Board.str(p, "icon") + " " + Board.str(p, "label"), prioColor(k)), () -> setF(() -> fPriority = k), fPriority.equals(k)));
		}
		openMenu(b, o);
	}

	private void tagMenu(Button b) {
		List<Option> o = new ArrayList<>();
		o.add(new Option(Theme.t("Alle Tags", Theme.TEXT), () -> setF(() -> fTag = ""), fTag.isEmpty()));
		for (String t : Board.I.allTags()) o.add(new Option(Theme.t(t, Theme.TEXT), () -> setF(() -> fTag = t), fTag.equals(t)));
		openMenu(b, o);
	}

	private void dueMenu(Button b) {
		List<Option> o = new ArrayList<>();
		for (String k : new String[]{"", "overdue", "today", "week", "set", "none"}) o.add(new Option(Theme.t(dueLabel(k), Theme.TEXT), () -> setF(() -> fDue = k), fDue.equals(k)));
		openMenu(b, o);
	}

	private void sortMenu(Button b) {
		openMenu(b, List.of(
			new Option(Theme.t("Priorität", Theme.TEXT), () -> setF(() -> sort = "priority"), sort.equals("priority")),
			new Option(Theme.t("Fälligkeit", Theme.TEXT), () -> setF(() -> sort = "due"), sort.equals("due")),
			new Option(Theme.t("Neueste zuerst", Theme.TEXT), () -> setF(() -> sort = "created"), sort.equals("created"))));
	}

	private void setF(Runnable r) { r.run(); rebuildWidgets(); }

	private void toggleMine() {
		if (meKey() == null) {
			// Gemeinsamer Admin-Zugang: erst wählen, für wen „Meine Aufgaben“ gilt (wie „Ansicht für“ auf der Website).
			List<Option> o = new ArrayList<>();
			for (JsonElement e : Board.I.list("members")) {
				String k = Board.str(e.getAsJsonObject(), "key");
				o.add(new Option(Theme.t("Ansicht für " + Board.I.memberName(k), Theme.TEXT), () -> { chosenMe = k; mine = true; rebuildWidgets(); }, false));
			}
			Button anchor = (Button) children().stream().filter(c -> c instanceof Button bt && bt.getMessage().getString().startsWith("Meine")).findFirst().orElse(null);
			if (anchor != null) openMenu(anchor, o);
			return;
		}
		mine = !mine;
		rebuildWidgets();
	}

	// ---------- Filter &amp; Sortierung (wie match() und sorter() auf der Website) ----------

	private LocalDate today() {
		try { return LocalDate.parse(Board.I.today); } catch (Exception e) { return LocalDate.now(ZoneId.of("Europe/Berlin")); }
	}

	private Long dayDiff(JsonObject t) {
		String d = Board.str(t, "due_date");
		if (d.isEmpty()) return null;
		try { return ChronoUnit.DAYS.between(today(), LocalDate.parse(d)); } catch (Exception e) { return null; }
	}

	private boolean match(JsonObject t) {
		String query = q.trim().toLowerCase(Locale.ROOT);
		if (!query.isEmpty() && !(Board.str(t, "title") + "\n" + Board.str(t, "description")).toLowerCase(Locale.ROOT).contains(query)) return false;
		String a = Board.str(t, "assignee");
		if (!fAssignee.isEmpty() && !(a.isEmpty() ? "none" : a).equals(fAssignee)) return false;
		if (!fPriority.isEmpty() && !Board.str(t, "priority").equals(fPriority)) return false;
		if (!fTag.isEmpty()) {
			boolean has = false;
			for (JsonElement e : t.getAsJsonArray("tags")) if (e.getAsString().equals(fTag)) has = true;
			if (!has) return false;
		}
		if (mine && (meKey() == null || !a.equals(meKey()))) return false;
		boolean od = Board.bool(t, "overdue");
		if (overdueOnly && !od) return false;
		if (criticalOnly && !"critical".equals(Board.str(t, "priority"))) return false;
		if (!fDue.isEmpty()) {
			Long d = dayDiff(t);
			switch (fDue) {
				case "none" -> { if (d != null) return false; }
				case "set" -> { if (d == null) return false; }
				case "overdue" -> { if (!od) return false; }
				case "today" -> { if (d == null || d != 0) return false; }
				case "week" -> { if (d == null || d < 0 || d > 7) return false; }
				default -> {}
			}
		}
		return true;
	}

	private Comparator<JsonObject> sorter() {
		Comparator<JsonObject> pr = Comparator.comparingInt(t -> -Board.I.priority(Board.str(t, "priority")).get("rank").getAsInt());
		Comparator<JsonObject> due = Comparator.comparing(t -> Board.str(t, "due_date").isEmpty() ? "9999" : Board.str(t, "due_date"));
		Comparator<JsonObject> cr = Comparator.comparing((JsonObject t) -> Board.str(t, "created_at")).reversed();
		Comparator<JsonObject> od = Comparator.comparingInt(t -> Board.bool(t, "overdue") ? 0 : 1);
		return switch (sort) {
			case "due" -> due.thenComparing(pr).thenComparing(cr);
			case "created" -> cr;
			default -> od.thenComparing(pr).thenComparing(due).thenComparing(cr);
		};
	}

	private List<JsonObject> shown(List<JsonObject> src) {
		List<JsonObject> l = new ArrayList<>();
		for (JsonObject t : src) if (match(t)) l.add(t);
		l.sort(sorter());
		return l;
	}

	// ---------- Navigation ----------

	private void openDetail(JsonObject t) { minecraft.gui.setScreen(new TaskDetailScreen(this, Board.str(t, "id"))); }
	void openForm(JsonObject task, String status) { minecraft.gui.setScreen(new TaskFormScreen(this, task, status, false)); }

	private void moveMenu(JsonObject t, int x, int y) {
		List<Option> o = new ArrayList<>();
		o.add(new Option(Theme.b("Details öffnen", Theme.CREAM), () -> openDetail(t), false));
		for (JsonElement e : Board.I.list("statuses")) {
			String k = Board.str(e.getAsJsonObject(), "key");
			o.add(new Option(Theme.t("→ " + Board.str(e.getAsJsonObject(), "label"), statusColor(k)), () -> Board.I.moveTo(t, k), k.equals(Board.str(t, "status"))));
		}
		Button anchor = Button.builder(Component.empty(), b -> {}).bounds(x, y, 10, 0).build();
		openMenu(anchor, o);
	}

	// ---------- Eingaben ----------

	@Override
	public void tick() {
		// Aktuell halten ohne Echtzeit-Server: alle 15 Sekunden im Hintergrund neu laden (Website: beim Fokus und jede Minute).
		if (!dragging && !Board.I.loading && System.currentTimeMillis() - Board.I.lastLoad > 15_000) Board.I.load(true);
	}

	@Override
	public boolean keyPressed(KeyEvent e) {
		if (menuOpen()) return super.keyPressed(e);
		if (dragging && e.key() == InputConstants.KEY_ESCAPE) { dragging = false; pressed = null; return true; }
		if (getFocused() != search || !search.isFocused()) {
			if (e.key() == InputConstants.KEY_LEFT) { hx -= colW / 2; clampH(); return true; }
			if (e.key() == InputConstants.KEY_RIGHT) { hx += colW / 2; clampH(); return true; }
			if (e.key() == InputConstants.KEY_F5) { Board.I.load(false); if (archiveView) Board.I.loadArchive(); return true; }
		}
		return super.keyPressed(e);
	}

	private void clampH() {
		int total = 5 * colW + 4 * GAP, areaW = pw - 12;
		hx = Math.max(0, Math.min(Math.max(0, total - areaW), hx));
	}

	private int colAt(double x) {
		for (int i = 0; i < 5; i++) {
			int cx = colX(i);
			if (x >= cx && x < cx + colW) return i;
		}
		return -1;
	}

	private int colX(int i) { return px + 6 + i * (colW + GAP) - hx; }

	@Override
	public boolean mouseScrolled(double mx, double my, double sx, double sy) {
		if (super.mouseScrolled(mx, my, sx, sy)) return true;
		if (archiveView) { scroll = Math.max(0, Math.min(maxScroll(), scroll - sy * 22)); return true; }
		if (sx != 0 || (my >= boardTop && my < boardTop + COL_HEAD)) { hx -= (int) ((sx != 0 ? sx : sy) * 30); clampH(); return true; }
		int c = colAt(mx);
		if (c >= 0 && my >= boardTop) {
			int view = bottom - 2 - (boardTop + COL_HEAD);
			colScroll[c] = Math.max(0, Math.min(Math.max(0, colContent[c] - view), colScroll[c] - sy * 22));
		}
		return true;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
		if (menuOpen()) return super.mouseClicked(e, doubleClick);
		if (super.mouseClicked(e, doubleClick)) return true;
		if (search.isFocused() && !search.isMouseOver(e.x(), e.y())) search.setFocused(false);
		if (archiveView) {
			if (e.button() != InputConstants.MOUSE_BUTTON_LEFT) return false;
			for (ArchHit h : archHits) if (e.x() >= h.x() && e.x() < h.x() + h.w() && e.y() >= h.y() && e.y() < h.y() + h.h()) {
				if (h.task() == null) return true;
				if (h.restore()) restore(h.task()); else askDelete(h.task());
				return true;
			}
			for (Hit h : hits) if (inside(h, e.x(), e.y())) { openDetail(h.task()); return true; }
			return false;
		}
		for (int[] p : plus) if (e.x() >= p[0] && e.x() < p[0] + p[2] && e.y() >= p[1] && e.y() < p[1] + p[3]) {
			openForm(null, Board.str(Board.I.list("statuses").get(p[4]).getAsJsonObject(), "key"));
			return true;
		}
		for (Hit h : hits) if (inside(h, e.x(), e.y())) {
			if (e.button() == InputConstants.MOUSE_BUTTON_RIGHT) { moveMenu(h.task(), (int) e.x(), (int) e.y()); return true; }
			if (e.button() == InputConstants.MOUSE_BUTTON_LEFT) {
				pressed = h.task(); pressX = e.x(); pressY = e.y(); offX = e.x() - h.x(); offY = e.y() - h.y(); pressW = h.w(); pressH = h.h();
				dragging = false;
				return true;
			}
		}
		return false;
	}

	private static boolean inside(Hit h, double x, double y) { return x >= h.x() && x < h.x() + h.w() && y >= h.y() && y < h.y() + h.h(); }

	@Override
	public boolean mouseDragged(MouseButtonEvent e, double dx, double dy) {
		if (pressed != null) {
			if (!dragging && Math.hypot(e.x() - pressX, e.y() - pressY) > 4) dragging = true;
			if (dragging) {
				// Am Rand automatisch seitlich scrollen, falls nicht alle Spalten sichtbar sind
				if (e.x() < px + 20) { hx -= 6; clampH(); }
				if (e.x() > px + pw - 20) { hx += 6; clampH(); }
			}
			return true;
		}
		return super.mouseDragged(e, dx, dy);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent e) {
		if (pressed != null) {
			JsonObject t = pressed;
			pressed = null;
			if (dragging) {
				dragging = false;
				int c = colAt(e.x());
				if (c >= 0 && e.y() >= boardTop && e.y() < bottom) Board.I.moveTo(t, Board.str(Board.I.list("statuses").get(c).getAsJsonObject(), "key"));
			} else openDetail(t);
			return true;
		}
		return super.mouseReleased(e);
	}

	private void restore(JsonObject t) {
		JsonObject c = new JsonObject();
		c.addProperty("archived", false);
		Board.I.patch(t, c, "Aufgabe wiederhergestellt.", null);
	}

	void askDelete(JsonObject t) {
		minecraft.gui.setScreen(new ConfirmScreen(yes -> {
			if (yes) Board.I.delete(t, () -> {});
			minecraft.gui.setScreen(this);
		}, Theme.b("Aufgabe endgültig löschen?", Theme.ERR),
			Theme.t("„" + Board.str(t, "title") + "“ wird mit allen Kommentaren und dem Verlauf dauerhaft gelöscht. Das kann nicht rückgängig gemacht werden. Tipp: Archivieren behält alles.", Theme.TEXT),
			Theme.b("Endgültig löschen", Theme.ERR), Component.literal("Abbrechen")));
	}

	// ---------- Zeichnen ----------

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float pt) {
		Board b = Board.I;
		int open = 0, doing = 0, urgent = 0, od = 0, doneToday = 0;
		for (JsonObject t : b.tasks) {
			String s = Board.str(t, "status"), p = Board.str(t, "priority");
			if (!s.equals("done")) { open++; if (p.equals("high") || p.equals("critical")) urgent++; }
			if (s.equals("doing")) doing++;
			if (Board.bool(t, "overdue")) od++;
			if (s.equals("done") && isToday(Board.str(t, "completed_at"))) doneToday++;
		}
		String who = b.meta == null ? "Verbinde …" : b.self() != null
			? "Angemeldet als " + b.selfName() + " – Änderungen werden als „" + b.selfName() + "“ protokolliert."
			: "Gemeinsamer Admin-Zugang – Änderungen werden als „" + b.selfName() + "“ protokolliert.";
		String kpi = b.meta == null ? null : open + " offen · " + doing + " in Arbeit";
		drawFrame(g, Theme.t("Family Task Board", Theme.CREAM), who, kpi);
		if (kpi != null && mx >= px + pw - 8 - font.width(kpi) && mx < px + pw - 8 && my >= py + 4 && my < py + 16)
			tip(g, mx, my, Theme.b("Kennzahlen", Theme.CREAM), Theme.t("Offene Aufgaben: " + open, Theme.TEXT), Theme.t("In Arbeit: " + doing, Theme.TEXT),
				Theme.t("Hoch / Kritisch: " + urgent, urgent > 0 ? Theme.WARN : Theme.TEXT), Theme.t("Überfällig: " + od, od > 0 ? Theme.ERR : Theme.TEXT), Theme.t("Heute erledigt: " + doneToday, doneToday > 0 ? Theme.OK : Theme.TEXT));
		// Überfällige deutlich zeigen (KPI der Website)
		if (od > 0) {
			String s = "⚠ " + od + " überfällig";
			int w = font.width(s);
			g.text(font, Theme.t(s, Theme.ERR), px + pw - 8 - w, py + 18, -1, false);
		}

		hits.clear();
		plus.clear();
		archHits.clear();
		g.enableScissor(px + 1, boardTop, px + pw - 1, bottom);
		if (b.meta == null && b.loading) center(g, Theme.t("Aufgaben werden geladen …", Theme.MUTED), boardTop + 24);
		else if (b.meta == null && b.error != null) {
			center(g, Theme.t("✖ " + b.error, Theme.ERR), boardTop + 20);
			center(g, Theme.t("Prüfe die Verbindung mit /obrien status und klicke auf „Aktualisieren“.", Theme.DIM), boardTop + 34);
		} else if (archiveView) drawArchive(g, mx, my);
		else drawBoard(g, mx, my);
		g.disableScissor();

		// Fußzeile links: Stand oder Fehler
		int fy = py + ph - 18, maxW = (px + pw - 6 - 60 - 70 - 78 - 96 - 16) - (px + 8);
		if (b.error != null && b.meta != null) {
			fit(g, Theme.t("✖ " + b.error, Theme.ERR), px + 8, fy, maxW);
			if (mx >= px + 8 && mx < px + 8 + maxW && my >= fy - 2 && my < fy + 10) g.setTooltipForNextFrame(Theme.t(b.error, Theme.ERR), mx, my);
		} else if (b.loading && b.meta != null) fit(g, Theme.t("Aktualisiere …", Theme.MUTED), px + 8, fy, maxW);
		else if (!b.loadedAt.isEmpty()) fit(g, Theme.t("Stand " + b.loadedAt, Theme.DIM), px + 8, fy, maxW);

		super.extractRenderState(g, mx, my, pt);
		drawToasts(g);
		if (dragging && pressed != null) drawGhost(g, mx, my);
		drawMenu(g, mx, my);
	}

	private boolean isToday(String iso) {
		if (iso.isEmpty()) return false;
		try { return OffsetDateTime.parse(iso).atZoneSameInstant(ZoneId.of("Europe/Berlin")).toLocalDate().equals(today()); } catch (Exception e) { return false; }
	}

	private void drawBoard(GuiGraphicsExtractor g, int mx, int my) {
		List<JsonObject> shown = shown(Board.I.tasks);
		int over = dragging ? colAt(mx) : -1;
		int colTop = boardTop + COL_HEAD, colBottom = bottom - 2;
		var statuses = Board.I.list("statuses");
		for (int i = 0; i < statuses.size() && i < 5; i++) {
			JsonObject s = statuses.get(i).getAsJsonObject();
			String key = Board.str(s, "key");
			int x = colX(i);
			if (x + colW < px || x > px + pw) continue;
			boolean target = over == i && pressed != null && !key.equals(Board.str(pressed, "status"));
			g.fill(x, boardTop, x + colW, colBottom, Theme.argb(target ? 0x15301F : 0x0D1510));
			g.outline(x, boardTop, colW, colBottom - boardTop, Theme.argb(target ? Theme.GOLD : 0x22352A));
			g.fill(x, boardTop, x + colW, boardTop + 2, Theme.argb(statusColor(key)));
			// Kopf: Name, Anzahl (gefiltert/gesamt), „+“
			int total = 0;
			List<JsonObject> list = new ArrayList<>();
			for (JsonObject t : Board.I.tasks) if (Board.str(t, "status").equals(key)) total++;
			for (JsonObject t : shown) if (Board.str(t, "status").equals(key)) list.add(t);
			String count = filtered() && list.size() != total ? list.size() + "/" + total : String.valueOf(total);
			int plusX = x + colW - 14;
			boolean hp = mx >= plusX && mx < plusX + 11 && my >= boardTop + 5 && my < boardTop + 16;
			g.fill(plusX, boardTop + 5, plusX + 11, boardTop + 16, Theme.argb(hp ? 0x2E5A3D : 0x1A2A20));
			g.text(font, Theme.b("+", Theme.GOLD), plusX + 3, boardTop + 7, -1, false);
			plus.add(new int[]{plusX, boardTop + 5, 11, 11, i});
			if (hp) g.setTooltipForNextFrame(Theme.t("Neue Aufgabe in „" + Board.str(s, "label") + "“", Theme.TEXT), mx, my);
			int cw = font.width(count) + 6;
			g.fill(plusX - cw - 3, boardTop + 5, plusX - 3, boardTop + 16, Theme.argb(0x1A2A20));
			g.text(font, Theme.t(count, Theme.MUTED), plusX - cw, boardTop + 7, -1, false);
			fit(g, Theme.b(Board.str(s, "label"), Theme.CREAM), x + 5, boardTop + 7, plusX - cw - 8 - x - 5);
			if (my >= boardTop && my < boardTop + COL_HEAD && mx >= x && mx < plusX - cw - 3)
				tip(g, mx, my, Theme.b(Board.str(s, "label"), Theme.CREAM), Theme.t(Board.str(s, "hint"), Theme.MUTED));
			g.fill(x + 1, colTop - 2, x + colW - 1, colTop - 1, Theme.argb(0x1B2B21));

			// Karten
			g.enableScissor(Math.max(x + 1, px + 1), colTop, Math.min(x + colW - 1, px + pw - 1), colBottom);
			int y = colTop + 3 - (int) colScroll[i], cardW = colW - 8;
			for (JsonObject t : list) {
				int h = cardHeight(t, cardW);
				if (y + h >= colTop && y <= colBottom) {
					boolean isDragged = dragging && pressed != null && Board.str(pressed, "id").equals(Board.str(t, "id"));
					drawCard(g, t, x + 4, y, cardW, h, isDragged ? -1 : mx, my, isDragged);
					int vy = Math.max(y, colTop), vh = Math.min(y + h, colBottom) - vy;
					if (vh > 0) hits.add(new Hit(t, x + 4, y, cardW, h, i));
				}
				y += h + 4;
			}
			if (list.isEmpty()) {
				String empty = total > 0 ? "Keine Treffer für die aktuellen Filter." : key.equals("done") ? "Noch nichts erledigt – das kommt noch!" : "Noch keine Aufgaben. Ziehe eine Karte hierher oder lege mit „+“ eine neue an.";
				wrap(g, Theme.i(empty, Theme.DIM), x + 6, colTop + 6, colW - 12, 6);
			}
			colContent[i] = y + (int) colScroll[i] - colTop;
			int view = colBottom - colTop;
			colScroll[i] = Math.max(0, Math.min(Math.max(0, colContent[i] - view), colScroll[i]));
			g.disableScissor();
			if (colContent[i] > view) {
				int barH = Math.max(12, view * view / colContent[i]), barY = colTop + (int) ((view - barH) * (colScroll[i] / Math.max(1, colContent[i] - view)));
				g.fill(x + colW - 3, barY, x + colW - 1, barY + barH, Theme.argb(0x3C6B4A));
			}
		}
		// Hinweis, wenn nicht alle Spalten sichtbar sind
		int total = 5 * colW + 4 * GAP, areaW = pw - 12;
		if (total > areaW) {
			if (hx > 0) arrowHint(g, px + 2, "◀");
			if (hx < total - areaW) arrowHint(g, px + pw - 10, "▶");
		}
	}

	private void arrowHint(GuiGraphicsExtractor g, int x, String s) {
		int y = boardTop + (bottom - boardTop) / 2 - 6;
		g.fill(x, y, x + 8, y + 13, Theme.argb(0x0A110D, 220));
		g.text(font, Theme.t(s, Theme.GOLD), x + 1, y + 3, -1, false);
	}

	// Kartenaufbau wie auf der Website: Priorität (+ Überfällig), Titel, Beschreibung (gekürzt), Tags, Avatar · Fälligkeit · Kommentare
	private int cardHeight(JsonObject t, int w) {
		int h = 5 + 11 + lines(Theme.b(Board.str(t, "title"), Theme.CREAM), w - 10, 3) * 10;
		if (!Board.str(t, "description").isEmpty()) h += lines(Theme.t(desc(t), Theme.MUTED), w - 10, 2) * 10;
		if (!t.getAsJsonArray("tags").isEmpty()) h += 13;
		return h + 15;
	}

	private static String desc(JsonObject t) {
		String d = Board.str(t, "description").replace('\n', ' ');
		return d.length() > 140 ? d.substring(0, 140) + "…" : d;
	}

	private void drawCard(GuiGraphicsExtractor g, JsonObject t, int x, int y, int w, int h, int mx, int my, boolean placeholder) {
		String p = Board.str(t, "priority");
		boolean od = Board.bool(t, "overdue"), done = "done".equals(Board.str(t, "status"));
		boolean hover = mx >= x && mx < x + w && my >= y && my < y + h && !dragging;
		if (placeholder) {
			g.fill(x, y, x + w, y + h, Theme.argb(0x0F1A13));
			g.outline(x, y, w, h, Theme.argb(0x2C4A36));
			return;
		}
		g.fill(x, y, x + w, y + h, Theme.argb(od ? 0x22171A : hover ? 0x1D3125 : 0x141F18));
		g.outline(x, y, w, h, Theme.argb(od ? 0x7A3A36 : hover ? 0x3F7A52 : 0x24382B));
		g.fill(x, y, x + 2, y + h, Theme.argb(prioStripe(p)));
		int tx = x + 6, ty = y + 5, tw = w - 10;
		JsonObject pr = Board.I.priority(p);
		g.text(font, Theme.t(Board.str(pr, "icon") + " " + Board.str(pr, "label"), prioColor(p)), tx, ty, -1, false);
		if (od) pill(g, "Überfällig", 0xB33A32, x + w - font.width("Überfällig") - 12, ty - 2, true);
		ty += 11;
		ty = wrap(g, Theme.b(Board.str(t, "title"), done ? Theme.MUTED : Theme.CREAM), tx, ty, tw, 3);
		if (!Board.str(t, "description").isEmpty()) ty = wrap(g, Theme.t(desc(t), Theme.DIM), tx, ty, tw, 2);
		if (!t.getAsJsonArray("tags").isEmpty()) {
			int tagX = tx;
			ty += 1;
			for (JsonElement e : t.getAsJsonArray("tags")) {
				String tag = e.getAsString();
				int tw2 = font.width(tag) + 6;
				if (tagX + tw2 > x + w - 4) { g.text(font, Theme.t("…", Theme.DIM), tagX, ty + 1, -1, false); break; }
				g.fill(tagX, ty, tagX + tw2, ty + 10, Theme.argb(0x1F3326));
				g.text(font, Theme.t(tag, 0xA8C7B2), tagX + 3, ty + 1, -1, false);
				tagX += tw2 + 3;
			}
			ty += 12;
		}
		// Fuß
		int fy = y + h - 13;
		avatar(g, Board.str(t, "assignee"), tx, fy, 10);
		int fx = tx + 14;
		String due = Board.str(t, "due_text");
		int cmts = t.has("comments") ? t.get("comments").getAsInt() : 0;
		String cc = cmts > 0 ? "✉ " + cmts : "";
		int ccW = font.width(cc);
		if (cmts > 0) g.text(font, Theme.t(cc, Theme.MUTED), x + w - 5 - ccW, fy + 1, -1, false);
		if (!due.isEmpty()) fit(g, Theme.t(due, od ? Theme.ERR : due.startsWith("Heute") ? Theme.WARN : Theme.MUTED), fx, fy + 1, x + w - 8 - ccW - fx);
		if (hover) {
			tip(g, mx, my, Theme.b(Board.str(t, "title"), Theme.CREAM),
				Theme.t(Board.I.memberName(Board.str(t, "assignee")) + " · " + Board.str(pr, "label") + (due.isEmpty() ? "" : " · " + due), Theme.MUTED),
				Theme.t("Klicken: Details · Ziehen: verschieben · Rechtsklick: Menü", Theme.DIM));
		}
	}

	private void drawGhost(GuiGraphicsExtractor g, int mx, int my) {
		g.nextStratum();
		int x = (int) (mx - offX), y = (int) (my - offY);
		drawCard(g, pressed, x, y, pressW, pressH, -1, -1, false);
		g.outline(x - 1, y - 1, pressW + 2, pressH + 2, Theme.argb(Theme.GOLD));
		int c = colAt(mx);
		if (c >= 0) {
			String label = Board.str(Board.I.list("statuses").get(c).getAsJsonObject(), "label");
			String s = c >= 0 && Board.str(Board.I.list("statuses").get(c).getAsJsonObject(), "key").equals(Board.str(pressed, "status")) ? "bleibt in „" + label + "“" : "→ " + label;
			int w = font.width(s) + 8;
			g.fill(mx + 8, my + 10, mx + 8 + w, my + 22, Theme.argb(0x0A110D, 240));
			g.text(font, Theme.t(s, Theme.GOLD), mx + 12, my + 12, -1, false);
		}
	}

	private void drawArchive(GuiGraphicsExtractor g, int mx, int my) {
		List<JsonObject> arch = Board.I.archived;
		int x0 = px + 8, x1 = px + pw - 10, y = boardTop + 4 - (int) scroll, startY = y;
		if (arch == null) { center(g, Theme.t("Archiv wird geladen …", Theme.MUTED), boardTop + 20); contentH = 0; return; }
		List<JsonObject> list = shown(arch);
		if (list.isEmpty()) {
			String s = arch.isEmpty() ? "Das Archiv ist leer. Archivierte Aufgaben verschwinden vom Board, bleiben aber hier mit Kommentaren und Verlauf erhalten." : "Keine archivierten Aufgaben passen zu den Filtern.";
			wrap(g, Theme.i(s, Theme.DIM), x0 + 10, boardTop + 16, x1 - x0 - 20, 4);
			contentH = 0;
			return;
		}
		for (JsonObject t : list) {
			int h = 28;
			if (y + h >= boardTop && y <= bottom) {
				boolean hover = mx >= x0 && mx < x1 && my >= y && my < y + h && my >= boardTop && my < bottom;
				g.fill(x0, y, x1, y + h, Theme.argb(hover ? 0x1D3125 : 0x141F18));
				g.outline(x0, y, x1 - x0, h, Theme.argb(0x24382B));
				avatar(g, Board.str(t, "assignee"), x0 + 6, y + 8, 12);
				int dw = font.width("Löschen") + 10, rw = font.width("Wiederherstellen") + 10;
				int bx2 = x1 - 6 - dw, bx1 = bx2 - 4 - rw;
				fakeButton(g, "Wiederherstellen", bx1, y + 5, rw, mx, my, Theme.TEXT);
				fakeButton(g, "Löschen", bx2, y + 5, dw, mx, my, Theme.ERR);
				archHits.add(new ArchHit(t, true, bx1, y + 5, rw, 18));
				archHits.add(new ArchHit(t, false, bx2, y + 5, dw, 18));
				fit(g, Theme.b(Board.str(t, "title"), Theme.CREAM), x0 + 24, y + 5, bx1 - x0 - 30);
				String when = "";
				try { when = OffsetDateTime.parse(Board.str(t, "archived_at")).atZoneSameInstant(ZoneId.of("Europe/Berlin")).format(STAMP); } catch (Exception ignored) {}
				fit(g, Theme.t(Board.I.statusLabel(Board.str(t, "status")) + " · " + Board.str(Board.I.priority(Board.str(t, "priority")), "label") + " · archiviert " + when, Theme.DIM), x0 + 24, y + 16, bx1 - x0 - 30);
				hits.add(new Hit(t, x0, y, bx1 - x0 - 4, h, -1));
			}
			y += h + 4;
		}
		contentH = y - startY + 4;
		scrollbar(g);
	}

	private void fakeButton(GuiGraphicsExtractor g, String label, int x, int y, int w, int mx, int my, int color) {
		boolean hover = mx >= x && mx < x + w && my >= y && my < y + 18;
		g.fill(x, y, x + w, y + 18, Theme.argb(hover ? 0x2E5A3D : 0x1A2A20));
		g.outline(x, y, w, 18, Theme.argb(hover ? 0x5FA877 : 0x35553F));
		g.text(font, Theme.t(label, color), x + (w - font.width(label)) / 2, y + 5, -1, false);
	}
}
