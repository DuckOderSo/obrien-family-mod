package net.obrienfamily.mod;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;

/**
 * Gemeinsame Grundlage der Board-Fenster: O’Brien-Rahmen, Aufklappmenüs (wie Auswahllisten auf der Website),
 * Hinweis-Meldungen, Farben für Priorität/Status/Mitglieder und mitscrollende Eingabefelder.
 */
abstract class BoardUi extends Screen {
	protected static final int HEADER = 30, FOOTER = 28;
	protected final Screen parent;
	protected int px, py, pw, ph, top, bottom;
	protected double scroll;
	protected int contentH;

	protected BoardUi(Component title, Screen parent) {
		super(title);
		this.parent = parent;
	}

	@Override
	public boolean isPauseScreen() { return false; }

	/** Rahmenmaße – das Board nutzt fast die ganze Breite, Detail und Formular etwas weniger. */
	protected void frame(int maxW) {
		pw = Math.min(maxW, width - 12);
		ph = height - 12;
		px = (width - pw) / 2;
		py = 6;
		top = py + HEADER + 1;
		bottom = py + ph - FOOTER;
	}

	// ---------- Farben (wie im Board der Website) ----------

	static int prioColor(String p) {
		return switch (p) { case "critical" -> 0xFF6B60; case "high" -> Theme.GOLD; case "normal" -> 0x7CC9D8; default -> 0x8A9A8F; };
	}
	static int prioStripe(String p) {
		return switch (p) { case "critical" -> 0xFF5A4E; case "high" -> Theme.GOLD; case "normal" -> 0x4FB3C8; default -> 0x5E6E63; };
	}
	static int statusColor(String s) {
		return switch (s) { case "todo" -> 0x4FB3C8; case "doing" -> Theme.GOLD; case "review" -> 0xB48CE0; case "done" -> 0x3F9D54; default -> 0x6B7D70; };
	}
	static int hex(String s, int fallback) {
		try { return Integer.parseInt(s.replace("#", ""), 16); } catch (Exception e) { return fallback; }
	}

	// ---------- Zeichnen ----------

	protected void drawFrame(GuiGraphicsExtractor g, Component title, String sub, String right) {
		g.fill(px, py, px + pw, py + ph, Theme.argb(0x0A110D, 240));
		g.outline(px, py, pw, ph, Theme.argb(0x2C4A36));
		g.fillGradient(px + 1, py + 1, px + pw - 1, py + HEADER, Theme.argb(0x1B3D26), Theme.argb(0x0E1D14));
		for (int x = px + 1; x < px + pw - 1; x++) g.fill(x, py + HEADER, x + 1, py + HEADER + 1, Theme.argb(Theme.at(new int[]{Theme.DARK, Theme.EMERALD, Theme.GOLD, Theme.EMERALD, Theme.DARK}, (float) (x - px) / pw)));
		g.fill(px + 1, bottom, px + pw - 1, bottom + 1, Theme.argb(0x24382B));
		g.text(font, Theme.brand(), px + 8, py + 6, -1, true);
		int bx = px + 8 + font.width(Theme.brand()) + 6;
		int rw = right == null ? 0 : font.width(right);
		fit(g, title, bx, py + 6, px + pw - 14 - rw - bx);
		if (right != null) g.text(font, Theme.t(right, Theme.MUTED), px + pw - 8 - rw, py + 6, -1, false);
		fit(g, Theme.t(sub, Theme.DIM), px + 8, py + 18, pw - 16);
	}

	protected void scrollbar(GuiGraphicsExtractor g) {
		int bodyH = bottom - top;
		if (contentH > bodyH) {
			int barH = Math.max(16, bodyH * bodyH / contentH), barY = top + (int) ((bodyH - barH) * (scroll / Math.max(1, maxScroll())));
			g.fill(px + pw - 4, top + 1, px + pw - 2, bottom - 1, Theme.argb(0x16241B));
			g.fill(px + pw - 4, barY, px + pw - 2, barY + barH, Theme.argb(0x3C6B4A));
		}
		scroll = Math.max(0, Math.min(maxScroll(), scroll));
	}

	protected int maxScroll() { return Math.max(0, contentH - (bottom - top)); }

	protected void center(GuiGraphicsExtractor g, Component c, int y) { g.text(font, c, px + (pw - font.width(c)) / 2, y, -1, false); }

	/** Zeichnet eine Zeile; ist sie zu lang, wird sie mit „…“ gekürzt. Rückgabe: true, wenn gekürzt wurde. */
	protected boolean fit(GuiGraphicsExtractor g, Component c, int x, int y, int maxW) {
		if (maxW <= 8) return true;
		if (font.width(c) <= maxW) { g.text(font, c, x, y, -1, false); return false; }
		List<FormattedCharSequence> parts = font.split(c, maxW - font.width("…"));
		if (!parts.isEmpty()) g.text(font, parts.get(0), x, y, -1, false);
		int w = parts.isEmpty() ? 0 : font.width(parts.get(0));
		g.text(font, Theme.t("…", Theme.DIM), x + w, y, -1, false);
		return true;
	}

	/** Mehrzeiliger Text mit Zeilenbegrenzung; gibt die neue y-Position zurück. */
	protected int wrap(GuiGraphicsExtractor g, Component c, int x, int y, int w, int maxLines) {
		List<FormattedCharSequence> lines = font.split(c, w);
		for (int i = 0; i < lines.size() && i < maxLines; i++) {
			if (i == maxLines - 1 && lines.size() > maxLines) {
				g.text(font, lines.get(i), x, y, -1, false);
				g.text(font, Theme.t("…", Theme.DIM), x + Math.min(w - 6, font.width(lines.get(i))), y, -1, false);
			} else g.text(font, lines.get(i), x, y, -1, false);
			y += 10;
		}
		return y;
	}

	/** Mehrzeiliger Tooltip (eine Zeile pro Eintrag). */
	protected void tip(GuiGraphicsExtractor g, int mx, int my, Component... lines) {
		g.setComponentTooltipForNextFrame(font, List.of(lines), mx, my);
	}

	protected int lines(Component c, int w, int maxLines) { return Math.min(maxLines, Math.max(1, font.split(c, w).size())); }

	/** Kleines Etikett mit Rahmen; Rückgabe: Breite. */
	protected int pill(GuiGraphicsExtractor g, String text, int color, int x, int y, boolean solid) {
		int w = font.width(text) + 8;
		g.fill(x, y, x + w, y + 11, solid ? Theme.argb(color, 210) : Theme.argb(color, 40));
		g.outline(x, y, w, 11, Theme.argb(color, solid ? 255 : 150));
		g.text(font, Theme.t(text, solid ? 0xFFFFFF : color), x + 4, y + 2, -1, false);
		return w;
	}

	/** Breite eines Avatars (zweistellige Kürzel brauchen mehr Platz als ein kleines Quadrat). */
	protected int avatarW(String key, int size) {
		JsonObject m = Board.I.member(key);
		return Math.max(size, font.width(m == null ? "–" : Board.str(m, "initials")) + 4);
	}

	/** Kürzel des Mitglieds auf seiner Farbe (wie die runden Avatare der Website), „–“ für nicht zugewiesen. */
	protected void avatar(GuiGraphicsExtractor g, String key, int x, int y, int size) {
		JsonObject m = Board.I.member(key);
		int c = m == null ? 0x2A3A30 : hex(Board.str(m, "color"), 0x3F9D54);
		String ini = m == null ? "–" : Board.str(m, "initials");
		int w = Math.max(size, font.width(ini) + 4);
		g.fill(x, y, x + w, y + size, Theme.argb(c));
		g.outline(x, y, w, size, Theme.argb(0x000000, 90));
		g.text(font, Theme.t(ini, m == null ? Theme.MUTED : 0xFFFFFF), x + (w - font.width(ini)) / 2 + 1, y + (size - 8) / 2 + 1, -1, true);
	}

	/** Hinweis-Meldungen (✓ / ⚠) unten rechts über der Fußzeile, verschwinden nach einigen Sekunden. */
	protected void drawToasts(GuiGraphicsExtractor g) {
		long now = System.currentTimeMillis();
		Board.I.toasts.removeIf(t -> t.until() < now);
		int y = bottom - 4;
		for (int i = Board.I.toasts.size() - 1; i >= 0; i--) {
			Board.Toast t = Board.I.toasts.get(i);
			Component c = Theme.t((t.ok() ? "✓ " : "⚠ ") + t.text(), t.ok() ? Theme.OK : 0xFFB4AE);
			List<FormattedCharSequence> ls = font.split(c, Math.min(260, pw - 24));
			int w = 0;
			for (FormattedCharSequence l : ls) w = Math.max(w, font.width(l));
			int h = ls.size() * 10 + 6;
			y -= h;
			int x = px + pw - 10 - w - 8;
			g.fill(x, y, x + w + 8, y + h, Theme.argb(t.ok() ? 0x10261A : 0x2A1514, 245));
			g.outline(x, y, w + 8, h, Theme.argb(t.ok() ? 0x3F9D54 : 0xB33A32));
			for (int j = 0; j < ls.size(); j++) g.text(font, ls.get(j), x + 4, y + 4 + j * 10, -1, false);
			y -= 3;
		}
	}

	// ---------- Aufklappmenü (ersetzt die Auswahllisten der Website) ----------

	protected record Option(Component label, Runnable run, boolean selected) {}
	private List<Option> menu;
	private int mx0, my0, mw;
	private int menuScroll;

	protected void openMenu(AbstractWidget anchor, List<Option> options) {
		menu = options;
		mw = anchor.getWidth();
		for (Option o : options) mw = Math.max(mw, font.width(o.label()) + 16);
		mx0 = Math.min(anchor.getX(), width - mw - 4);
		int h = options.size() * 12 + 4;
		my0 = anchor.getY() + anchor.getHeight() + 1;
		if (my0 + h > height - 4) my0 = Math.max(4, anchor.getY() - h - 1);
		menuScroll = 0;
	}

	protected boolean menuOpen() { return menu != null; }
	protected void closeMenu() { menu = null; }

	private int menuVisible() { return Math.max(1, Math.min(menu.size(), (height - 8 - my0) / 12)); }

	protected void drawMenu(GuiGraphicsExtractor g, int mx, int my) {
		if (menu == null) return;
		g.nextStratum();
		int vis = menuVisible(), h = vis * 12 + 4;
		g.fill(mx0, my0, mx0 + mw, my0 + h, Theme.argb(0x0E1A13, 252));
		g.outline(mx0, my0, mw, h, Theme.argb(0x3F7A52));
		for (int i = 0; i < vis; i++) {
			Option o = menu.get(i + menuScroll);
			int y = my0 + 2 + i * 12;
			boolean hover = mx >= mx0 && mx < mx0 + mw && my >= y && my < y + 12;
			if (hover) g.fill(mx0 + 1, y, mx0 + mw - 1, y + 12, Theme.argb(0x24402E));
			if (o.selected()) g.text(font, Theme.t("✔", Theme.OK), mx0 + 3, y + 2, -1, false);
			g.text(font, o.label(), mx0 + 12, y + 2, -1, false);
		}
		if (menu.size() > vis) g.text(font, Theme.t("▼", Theme.DIM), mx0 + mw - 9, my0 + h - 10, -1, false);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
		if (menu != null) {
			int vis = menuVisible();
			if (e.x() >= mx0 && e.x() < mx0 + mw && e.y() >= my0 + 2 && e.y() < my0 + 2 + vis * 12) {
				Option o = menu.get((int) ((e.y() - my0 - 2) / 12) + menuScroll);
				menu = null;
				o.run().run();
			} else menu = null;
			return true;
		}
		return super.mouseClicked(e, doubleClick);
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double sx, double sy) {
		if (menu != null) {
			menuScroll = (int) Math.max(0, Math.min(menu.size() - menuVisible(), menuScroll - Math.signum(sy)));
			return true;
		}
		return false;
	}

	@Override
	public boolean keyPressed(KeyEvent e) {
		if (menu != null && e.key() == InputConstants.KEY_ESCAPE) { menu = null; return true; }
		return super.keyPressed(e);
	}

	/** Zurück zum vorherigen Fenster (Board ← Detail ← Formular) statt das Spielmenü zu schließen. */
	@Override
	public void onClose() {
		if (minecraft != null) minecraft.gui.setScreen(parent);
	}

	// ---------- Mitscrollende Eingabefelder (Formular, Detail) ----------

	private record Scrolled(AbstractWidget w, int baseY) {}
	private final List<Scrolled> scrolled = new ArrayList<>();

	protected <T extends AbstractWidget> T scrolled(T w) {
		scrolled.add(new Scrolled(w, w.getY()));
		addRenderableWidget(w);
		return w;
	}

	@Override
	protected void clearWidgets() {
		super.clearWidgets();
		scrolled.clear();
	}

	/** Verschiebt die Felder mit dem Bildlauf; nur vollständig sichtbare Felder werden gezeigt und sind bedienbar. */
	protected void placeScrolled() {
		for (Scrolled s : scrolled) {
			int y = s.baseY() - (int) scroll;
			s.w().setY(y);
			s.w().visible = y >= top && y + s.w().getHeight() <= bottom;
		}
	}

	protected Button button(String label, Button.OnPress onPress, int x, int y, int w) {
		return Button.builder(Component.literal(label), onPress).bounds(x, y, w, 18).build();
	}
}
