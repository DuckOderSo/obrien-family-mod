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
  `/obrien bestellungen`, `/obrien board`, `/obrien aufgaben`, `/obrien status`, `/obrien update`, `/obrien key <Schlüssel>`,
  `/obrien ton an|aus`, `/obrien toasts an|aus`, `/obrien test`, `/obrien hilfe`.
- **Kistenfinder** für die Lager der Familie:
  `/obrien findkiste [item] [all]`, `/obrien setkiste`, `/obrien removekiste [item]`, `/obrien listkisten`, `/obrien infokiste`.
  Admins registrieren eigene Lagerkisten („diese Kiste = Diamanten“); gesucht wird in diesen Registrierungen –
  auch ohne das Item im Inventar. Gefundene Kisten in der Nähe werden 10 Sekunden mit Partikeln markiert.
- **Updates mit Nachfrage:** Die Mod prüft die [Releases](../../releases) dieses Repositorys. Erst nach Klick auf
  „Jetzt herunterladen“ (oder `/obrien update`) wird die neue Version geladen, ihre **Ed25519-Signatur** geprüft
  ([`signing-public-key.pem`](signing-public-key.pem)) und beim Beenden von Minecraft installiert.

## Was die Mod ausdrücklich **nicht** macht

- Keine Pakete an den Minecraft-Server, keine Chatnachrichten an andere Spieler.
- Keine Mixins, kein Eingriff in Spielmechanik, Bewegung oder Kampf; kein Automatisieren von Eingaben.
- **Keine Sicht durch Wände:** Die Kistenmarkierung besteht aus normalen Partikeln und wird von Blöcken verdeckt.
- **Kein Scannen der Welt:** Es werden keine Chunks geladen und keine Gegend nach Kisten durchsucht. Gelesen wird nur
  der Blockzustand einer Kiste, die du gerade anschaust oder die bereits registriert ist und in deiner Nähe geladen ist.
- **Kisteninhalte** sieht die Mod nur, wenn ein Admin eine registrierte Kiste selbst öffnet (dann zählt sie die
  registrierten Items). Fremde Kisten und Inventare anderer Spieler werden nie ausgelesen.
- Keine Telemetrie: Netzwerkverbindungen nur zur Website der Familie und zu GitHub (Updates).

## Datenfluss

| Ziel | Wann | Inhalt |
|---|---|---|
| `obrien-familie-shop.vercel.app/api/mod/…` | alle 60 s, nur während du in einer Welt bist | persönlicher Mod-Schlüssel → Antwort: neue Ereignisse aus dem Shop |
| `api.github.com/repos/DuckOderSo/obrien-family-mod/releases/latest` | beim Start und alle 6 h | öffentliche Release-Infos |
| `github.com/…/releases/download/…` | nur nach deinem Klick auf „Update“ | neue Mod-Datei + Signatur |
| `…/api/mod/chests` | nur bei Kistenfinder-Befehlen bzw. beim Öffnen einer registrierten Kiste durch einen Admin | Serveradresse, Dimension, Koordinaten der Kiste, Item-ID, ggf. gezählte Menge |

Der Mod-Schlüssel wird lokal in `config/obrien-mod.json` gespeichert und lässt sich im Admin-Bereich jederzeit sperren.
Es gibt Admin-Schlüssel (alle Funktionen) und Spieler-Schlüssel (nur Kistensuche, nur lesen); Schreibrechte prüft die Website.

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
