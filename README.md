# O’Brien Family Mod

Kleine **Client-Mod für Fabric (Minecraft 26.3)**, mit der vier Admins der O’Brien Family Benachrichtigungen aus ihrem
eigenen Item-Shop (<https://obrien-familie-shop.vercel.app>) direkt im Spiel sehen.

> **For server owners (short English summary):** Purely informational client-side mod. It does **not** send anything to the
> Minecraft server, has **no mixins**, does not read or change the world, inventory, other players or game mechanics,
> and does not automate any input. It only talks to the family’s own website (HTTPS) and to GitHub for updates.
> Every release is built from this public source by GitHub Actions and carries a build provenance attestation.

## Was die Mod macht

- **Benachrichtigungen:** neue Bestellanfragen und Dienstleistungsanfragen aus dem Shop, Aufgaben, die dir zugewiesen
  wurden, und Kommentare zu deinen Aufgaben – als Einblendung oben rechts und als Chatzeile (nur für dich sichtbar).
- **Befehle** (laufen nur auf deinem PC, werden nicht an den Server gesendet):
  `/obrien bestellungen`, `/obrien aufgaben`, `/obrien status`, `/obrien update`, `/obrien key <Schlüssel>`,
  `/obrien ton an|aus`, `/obrien toasts an|aus`, `/obrien test`.
- **Updates mit Nachfrage:** Die Mod prüft die [Releases](../../releases) dieses Repositorys. Erst nach Klick auf
  „Jetzt herunterladen“ (oder `/obrien update`) wird die neue Version geladen, ihre **Ed25519-Signatur** geprüft
  ([`signing-public-key.pem`](signing-public-key.pem)) und beim Beenden von Minecraft installiert.

## Was die Mod ausdrücklich **nicht** macht

- Keine Pakete an den Minecraft-Server, keine Chatnachrichten an andere Spieler.
- Keine Mixins, kein Eingriff in Spielmechanik, Rendering von Welt/Entities, Bewegung oder Kampf.
- Kein Auslesen von Welt, Inventar, Koordinaten oder anderen Spielern; kein Automatisieren von Eingaben.
- Keine Telemetrie: Netzwerkverbindungen nur zur Website der Familie und zu GitHub (Updates).

## Datenfluss

| Ziel | Wann | Inhalt |
|---|---|---|
| `obrien-familie-shop.vercel.app/api/mod/…` | alle 60 s, nur während du in einer Welt bist | persönlicher Mod-Schlüssel → Antwort: neue Ereignisse aus dem Shop |
| `api.github.com/repos/DuckOderSo/obrien-family-mod/releases/latest` | beim Start und alle 6 h | öffentliche Release-Infos |
| `github.com/…/releases/download/…` | nur nach deinem Klick auf „Update“ | neue Mod-Datei + Signatur |

Der Mod-Schlüssel wird lokal in `config/obrien-mod.json` gespeichert und lässt sich im Admin-Bereich jederzeit sperren.

## Installation

1. Fabric-Instanz mit **Minecraft 26.3**, **Fabric Loader ≥ 0.19.5** und **Fabric API** (z. B. im LabyMod-Launcher).
2. `obrien-mod-<version>.jar` aus den [Releases](../../releases) in den `mods`-Ordner legen.
3. Im Admin-Bereich des Shops unter **Konto → Minecraft-Mod** einen Schlüssel erstellen und im Spiel
   `/obrien key <Schlüssel>` eingeben.

## Echtheit einer Datei prüfen

```bash
# 1) Build-Nachweis von GitHub (zeigt Commit und Workflow, aus dem die Datei gebaut wurde)
gh attestation verify obrien-mod-1.0.0.jar --repo DuckOderSo/obrien-family-mod
# 2) Signatur der Familie
openssl pkeyutl -verify -pubin -inkey signing-public-key.pem -rawin -in obrien-mod-1.0.0.jar -sigfile obrien-mod-1.0.0.jar.sig
```

## Selbst bauen

Java 25 und Gradle 9.8: `gradle build` → `build/libs/obrien-mod-<version>.jar`

## Lizenz

[MIT](LICENSE)
