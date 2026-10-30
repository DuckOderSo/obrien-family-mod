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
- **Lagerbereiche** (wie bei WorldEdit): `/obrien lager erstellen <name>`, dann auf eine Ecke schauen und
  `/obrien lager punkt1`, auf die gegenüberliegende Ecke schauen und `/obrien lager punkt2` (anvisierter Block, bis 64 Blöcke).
  Alle Truhen, Doppelkisten und Fässer im Quader werden automatisch erkannt – auch neu platzierte; abgebaute verschwinden
  automatisch. Verwalten: `/obrien lager liste | info [name] | umbenennen <alt> <neu> | löschen <name>`.
  Bereiche dürfen sich überschneiden; jede Truhe zählt trotzdem nur einmal.
- **Inhalte:** Öffnet ein Admin eine Truhe im Lager, liest die Mod beim Schließen **alle** Items (gleiche Items auf mehreren
  Slots werden addiert) und speichert den kompletten Inhalt – ohne Chatmeldung.
- **Suchen:** `/obrien findkiste [item]` (ohne Item: das Item in der Hand) zeigt kompakt, in welchen Bereichen wie viele Truhen
  das Item enthalten, und markiert die Truhen 15 Sekunden mit einem goldenen Partikelrahmen an ihren Kanten. Koordinaten gibt es über
  `/obrien bestand <item>`; `/obrien listkisten` und `/obrien infokiste` zeigen einzelne Truhen.
- **Lagerbestand:** `/obrien bestand [item]` (Alias `/obrien lager [item]`) zeigt den Gesamtbestand eines Items über alle
  Lagerbereiche – mit den einzelnen Truhen und Koordinaten. Ohne Item gibt es eine Übersicht aller Items
  (`name` = alphabetisch, `niedrig` = unter Mindestbestand, `ungeprüft` = unsichere Bestände, Zahl = Seite).
  Summiert werden nur echte Erfassungen: Bestätigt = in den letzten 24 Stunden gelesen, ältere als
  „letzter bekannter Stand“; noch nie geöffnete Truhen gelten als „unbekannt“ (nie als 0). Admins können mit
  `/obrien bestand <item> min <zahl|aus>` einen Mindestbestand festlegen.
- **Updates mit Nachfrage:** Die Mod prüft die [Releases](../../releases) dieses Repositorys. Erst nach Klick auf
  „Jetzt herunterladen“ (oder `/obrien update`) wird die neue Version geladen, ihre **Ed25519-Signatur** geprüft
  ([`signing-public-key.pem`](signing-public-key.pem)) und beim Beenden von Minecraft installiert.

## Was die Mod ausdrücklich **nicht** macht

- Keine Pakete an den Minecraft-Server, keine Chatnachrichten an andere Spieler.
- Keine Mixins, kein Eingriff in Spielmechanik, Bewegung oder Kampf; kein Automatisieren von Eingaben.
- **Keine Sicht durch Wände:** Die Kistenmarkierung besteht aus normalen Partikeln und wird von Blöcken verdeckt.
- **Kein Scannen der Welt:** Es werden keine Chunks geladen. Erkannt werden Truhen nur innerhalb eurer Lagerbereiche und nur
  in Chunks, die ohnehin bei dir geladen sind (gelesen wird die Liste der Block-Entities dieser Chunks, kein Block-für-Block-Scan).
- **Abgebaute Truhen** erkennt die Mod nur in geladenen Chunks: Fehlt eine erfasste Truhe dort bei zwei Prüfungen im Abstand
  von 2 Sekunden, entfernt ein Client mit Admin-Schlüssel sie samt Inhalt (bei Doppelkisten die ganze Kiste). Nicht geladene
  Truhen werden nie gelöscht.
- **Kisteninhalte** sieht die Mod nur, wenn ein Admin eine Truhe im Lager selbst öffnet. Truhen außerhalb der Lagerbereiche
  und Inventare anderer Spieler werden nie ausgelesen.
- Keine Telemetrie: Netzwerkverbindungen nur zur Website der Familie und zu GitHub (Updates).

## Datenfluss

| Ziel | Wann | Inhalt |
|---|---|---|
| `obrien-familie-shop.vercel.app/api/mod/…` | alle 60 s, nur während du in einer Welt bist | persönlicher Mod-Schlüssel → Antwort: neue Ereignisse aus dem Shop |
| `api.github.com/repos/DuckOderSo/obrien-family-mod/releases/latest` | beim Start und alle 6 h | öffentliche Release-Infos |
| `github.com/…/releases/download/…` | nur nach deinem Klick auf „Update“ | neue Mod-Datei + Signatur |
| `…/api/mod/chests` | bei Lagerbefehlen, beim Erkennen/Abbauen von Truhen in Lagerbereichen und beim Schließen einer Lagertruhe (Admin) | Serveradresse, Dimension, Koordinaten der Bereiche und Truhen, Item-IDs und Mengen |

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
