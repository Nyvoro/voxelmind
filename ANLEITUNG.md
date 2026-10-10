# VoxelMind Local – Testanleitung

Stand 10.10.2026 · Version 0.4.2 für Minecraft **1.21 bis 26.2** (16 Versionen, Fabric); die Test-Instanz nutzt 26.2, Details in `docs/VERSIONS.md`

Drei Arten von KI-Wesen laufen komplett im Mod:
- **Begleiter** mit echtem Spielerkörper.
- **Builder:** eine Fee mit Konsolen-Macht.
- **God:** ein körperloser Spielleiter.

Es gibt keinen VoxelMind-Server, kein Pflicht-Konto und keine Weltdaten bei der UG. Gedacht wird mit der KI, die der Spieler wählt:
- Player2 (kostenlos, tägliches Kontingent).
- **VoxelMind Sparks** (unser Prepaid-Dienst, Anmeldung mit dem Minecraft-Konto).
- Das eigene OpenRouter-Konto.
- Ein lokales Modell über Ollama, LM Studio oder einen anderen OpenAI-kompatiblen Server.

## Starten

1. **CurseForge-App neu starten.** Unter „Minecraft“ erscheint die neue Instanz **„VoxelMind Local Test“** (26.2, Fabric 0.19.5). Sie enthält nur Fabric API und diesen Mod. Deine anderen Instanzen, auch NewMC mit dem alten Mod, sind unverändert.
   - Erscheint sie nicht: in CurseForge *Create Custom Profile* → Minecraft 26.2 → Fabric 0.19.5, dann über *Open Folder* die beiden JARs aus `V:\VoxelMind\voxelmind-local\dist\` in `mods` legen.
2. **Play**, neue Welt anlegen (Survival, gern „Allow Cheats“ für `/give`, `/time`).
3. **KI verbinden** (einmalig, gilt für alle Welten der Instanz): Beim Betreten der Welt steht im Chat ein Link **[Settings]**, alternativ `/vml setup` oder eine eigene Taste unter *Optionen → Steuerung → Sonstiges → „VoxelMind: KI-Einstellungen“*.
   - **„KI von: Player2 (kostenlos)“ → „Mit Player2 verbinden“**: Läuft die Player2-App, reicht ein Klick; sonst öffnet sich der Browser und du bestätigst einen Code. Player2 wählt das Modell, Spielen ist kostenlos (tägliches KI-Guthaben „Joules“, mehr mit Player2 Patron). Auf Servern: `/vml player2`. Die Game Client ID von VoxelMind (Player2 Developer Dashboard, „AI Mods“) ist eingebaut; `"player2ClientId"` in der Config überschreibt sie nur bei Bedarf.
   - **„KI von: VoxelMind Sparks“ → „Mit Minecraft anmelden“:**
     - Die Anmeldung läuft über dein Minecraft-Konto wie beim Betreten eines Servers: kein Passwort, keine E-Mail.
     - Neue Konten bekommen 60 Willkommens-Sparks, dazu gibt es täglich 10 gratis. 1 Spark ist etwa 1 Minute Spielzeit.
     - „Pakete und Preise“ öffnet voxel-mind.com/sparks. Nach der Anmeldung heißt der Knopf „Deine Sparks“ und öffnet deine private Kontoseite (Stand, Verbrauch pro Tag, Gutschriften).
     - Einen Sparks-Code (z. B. aus unserer Mail an ehemalige Abonnenten) löst du im Spiel mit `/vml sparks redeem <Code>` ein, ohne Mod auf voxel-mind.com/sparks/redeem.
     - „Token kopieren“ ist für dedizierte Server: dort `/vml endpoint voxelmind`, dann `/vml key <Token>`.
     - Hinweis: Das braucht die API unter `api.voxel-mind.com`, die erst nach Robins Deploy erreichbar ist. Im Dev-Client (Offline-Konto) scheitert die Mojang-Anmeldung absichtlich.
   - **„Mit OpenRouter anmelden“** öffnet den Browser. Nach der Anmeldung legt OpenRouter in deinem Konto einen Key für VoxelMind an, der Mod speichert ihn in `config/voxelmind-local.json`. Neue OpenRouter-Konten brauchen etwas Guthaben.
   - Oder einen vorhandenen Key ins Feld einfügen.
   - Oder oben „KI von“ auf **Ollama (dieser PC)** / **LM Studio** / **Anderer Server** umstellen.
   - **„Modell: …“** öffnet die Modellauswahl: Suche, nur Modelle mit Tool-Calls, sortiert nach geschätzten Kosten pro Spielstunde. Das getestete Standardmodell steht oben.
   - **„Verbindung testen“** schickt eine winzige Anfrage und zeigt, ob Key, Modell und Tool-Calls funktionieren; bei OpenRouter steht darüber das Restguthaben.
   - **„Fertig“** speichert. Die Änderung gilt sofort, auch für Begleiter, die schon in der Welt sind.
4. **Menü:** Taste **V**, der Knopf **♦ VoxelMind** oben links im Pausemenü oder `/vml`.
   - Karten mit Gesicht, Status (in der Welt / beschäftigt / weg / gefallen) und aktueller Tätigkeit.
   - Holen, Wegschicken, Bearbeiten, Monitor, Löschen (mit Rückfrage).
   - **+ Neuer Begleiter:** oben die Art wählen.
     - **Begleiter:** Name, Skin eines beliebigen Minecraft-Accounts, Charakter aus sechs Vorlagen oder eigener Text.
     - **Builder:** Name, Gestalt (Hilfsgeist, Biene, Plagegeist, Papagei, Fledermaus), Charakter.
     - **God:** Name, Chaos 1–5, Kampagne (Sandbox, Prüfungen der Götter, Lebendige Welt, Chaos-Arena), Gäste sichtbar oder verborgen, Charakter.
     - Builder und God gibt es nur für den Host einer Einzelspielerwelt oder einen Operator.
   - **Modus Normal oder Hardcore** (neben dem Namen): Normal respawnt nach einem Tod. Hardcore hat ein Leben: Stirbt er, verlässt er die Welt für immer, steht als „☠ gefallen“ im Menü und lässt sich nicht mehr holen; seine Erinnerungen bleiben lesbar, „Löschen“ entfernt ihn. Er kennt seinen Modus als Fakt, wie vorsichtig er spielt, entscheidet er selbst. In einer Hardcore-Welt starten neue Begleiter im Hardcore-Modus.
   - **Welten: Diese oder Alle** (neben dem Aussehen): „Alle“ nimmt den Begleiter in jede Welt dieser Instanz mit. Erinnerungen, Charakter, Aussehen und Modus reisen mit; Inventar und Position hat er je Welt eigene (in einer neuen Welt startet er mit leerem Inventar). Im Menü steht er mit ∞. Gespeichert wird er dann in `voxelmind-local/companions/` der Instanz statt im Weltordner.
   - **Monitor:** Leben, Hunger, Position, aktuelle Aktion, letztes Ergebnis (alle 2 s aktuell) und **Erinnerungen** („Wer ich geworden bin“, „Über dich“, letzte Ereignisse).
   - Oben rechts zeigt eine Pill das verbundene Modell; ein Klick darauf öffnet die KI-Einstellungen.
5. **Begleiter holen per Befehl (alternativ):**
   `/vml summon Lysa fröhlich, neugierig, ein bisschen frech`
   Danach einfach im normalen Chat mit ihr reden (sie hört dich im Umkreis von 48 Blöcken, dich als Besitzer immer).

## Befehle

| Befehl | Wirkung |
|---|---|
| `/vml` | Menü öffnen (ohne Mod im Client: Befehlsliste); `/vml help` zeigt immer die Befehle |
| `/vml summon <Name> [Charakter]` | neuen Begleiter holen oder bestehenden zu dir rufen |
| `/vml builder <Name> [Charakter]` | Builder-Fee holen: füllt Flächen, baut ganze Gebäude nach Beschreibung, macht rückgängig |
| `/vml god <Name> [Charakter]` | God holen: Spielleiter mit Befehlen, Regeln, Sequenzen und Kreaturen |
| `/vml campaign\|chaos\|guests <God> <Wert>` | Kampagne (sandbox, trials, living, chaos), Chaos 1–5, Gäste on/off |
| `/vml body <Builder> allay\|bee\|vex\|parrot\|bat` | Gestalt der Fee |
| `/vml dismiss [Name]` | wegschicken (Gedächtnis und Inventar bleiben); ein God räumt dabei Regeln, Effekte und Kreaturen auf |
| `/vml character <Name> <Text>` | Charakter neu beschreiben |
| `/vml mode <Name> normal\|hardcore` | Modus setzen, solange er lebt; ein Hardcore-Tod ist endgültig |
| `/vml worlds <Name> this\|all` | nur diese Welt oder mit dir in alle Welten |
| `/vml skin <Name> <Minecraft-Account>` | Skin eines echten Accounts übernehmen, z. B. `/vml skin Lysa Notch` |
| `/vml list`, `/vml status` | Überblick, aktuelle Aktion, Modell, Key-Anfang, Guthaben (OpenRouter, Player2 oder Sparks) |
| `/vml sparks` | Sparks-Stand, Link zu Paketen und Preisen, beim Host „[Account]“ (private Kontoseite) und „Token kopieren“ |
| `/vml sparks redeem <Code>` | Sparks-Code für das angemeldete Konto einlösen (Host oder Operator) |
| `/vml setup` | KI-Einstellungen als Bildschirm (Singleplayer und LAN-Host) |
| `/vml login` | Anmeldung mit OpenRouter per Code, auch auf einem dedizierten Server: Link anklicken, anmelden, dann `/vml login <Code>` |
| `/vml key <Key>` | Key für den aktuellen Endpunkt setzen |
| `/vml models [Suche]` | Modelle mit Tool-Calls und Kosten pro Stunde, anklickbar |
| `/vml model <id>`, `/vml model default` | Modell setzen (Tab ergänzt) |
| `/vml endpoint openrouter\|player2\|voxelmind\|ollama\|lmstudio\|<URL>` | KI-Quelle wechseln |
| `/vml player2` | Mit Player2 verbinden (Code im Browser bestätigen) |
| `/vml test` | Verbindungstest mit dem eingestellten Modell |
| `/vml reload` | Config neu einlesen |
| `/vml forget <Name>` | Begleiter samt Gedächtnis löschen |

Verlässt du die Welt, geht der Begleiter mit und kommt beim nächsten Betreten automatisch zurück.

## Was du ausprobieren kannst

Alles in normaler Sprache, sie entscheidet selbst, welche Werkzeuge sie nimmt:

- „Folge mir“, „Komm her“, „Erkunde mal Richtung Norden nach Tieren“
- „Sammel mir 8 Holz und gib es mir“
- „Bau dir eine Holzspitzhacke und hol 10 Stein“ (sie craftet Bretter, Stöcke, Werkbank selbst)
- „Brat das Rindfleisch“ (Ofen + Brennstoff), „Leg das in die Truhe“
- „Bau uns eine Hütte“ (eigenes Werkzeug `build_shelter`, ~55 Blöcke Material nötig)
- nachts: Zombies – sie kämpft, stirbt ggf., respawnt und meldet sich
- am nächsten Tag: „Weißt du noch, was wir gestern gemacht haben?“ (Langzeitgedächtnis)

**Builder** (`/vml builder Fee`):
- „Fee, bau mir eine kleine gemütliche Steinhütte mit Holztür vor mir“
- „Mach das wieder rückgängig“
- „Leg hier einen 5×5-Boden aus Eichenbrettern“
- „Wo ist hier Eisenerz?“

**God** (`/vml god Zeus`):
- „Zeus, mir ist langweilig, gib mir eine kleine Herausforderung“
- Er antwortet nur über sein `say`; sein freier Text bleibt bei ihm, wie im gehosteten Original.

Werkzeuge des Körpers: go_to, follow, explore, mine_block, gather, dig, collect, craft, smelt, collect_furnace, use_chest, use_block, attack, eat, equip, drop, place_block, place_blocks, build_shelter, survey, sleep, stop, wait. Sprechen: say, oder das optionale `say`-Argument eines Körper-Werkzeugs. Was er sagt und was er tut, entscheidet ein und derselbe Modellaufruf.

## Kosten und Tempo (gemessen am 07.10.)

Die Kosten zahlt der Spieler direkt an OpenRouter (oder gar nichts bei einem lokalen Modell). Die Modellauswahl schätzt für jedes Modell die Kosten pro Stunde aus dem Listenpreis und gemessenen ~530 Aufrufen à ~8.300 Tokens pro Spielstunde, ohne Prompt-Cache. Für das getestete Standardmodell steht der gemessene Wert.

- Modell `deepseek/deepseek-v4-flash-0731` über OpenRouter, Anbieter-Reihenfolge Relace → Sail Research → Together (US, ohne Datenspeicherung, wie bei VoxelMind Sparks). Bei jedem anderen Modell wählt OpenRouter den Anbieter selbst.
- **Etwa 10–37 Cent pro Stunde aktiven Spielens**, Schnitt ~20 Cent (151 Aufrufe in 17 Minuten Testspiel: $0,056).
- Reaktionszeit p50 3,0 s, p90 4,5 s.
- Technische Bremse: höchstens 20 Anfragen pro Minute (`maxRequestsPerMinute`).

## Getestete Modelle (Szenario „3 Holz sammeln und übergeben“, 08.10.2026, je ~3 Minuten)

| Modell | Ergebnis | Antwortzeit p50 | Kosten (hochgerechnet pro Stunde) |
|---|---|---|---|
| `deepseek/deepseek-v4-flash-0731` (Standard) | fehlerfrei, 20 Holz übergeben | 2,3 s | ~$0.09 (17 min am 07.10.: ~$0.20) |
| `z-ai/glm-5.3-flash` | fehlerfrei, genau 3 Holz übergeben | 2,3 s | ~$0.26 |
| `anthropic/claude-haiku-5.5` | fehlerfrei, 9 Holz übergeben | 1,4 s | ~$0.31 |
| `openai/gpt-5.6-luna` | fehlerfrei, aber einen Setzling statt Holz übergeben | 1,4 s | ~$0.45 |
| `qwen/qwen3.8-flash`, `mistralai/mistral-small-2603` | Anbieter lehnte 7 von 8 bzw. 8 von 12 Anfragen ab (HTTP 429) | – | – |
| `google/gemini-3.5-flash-lite` | nach Schema-Korrektur fehlerfrei, 5 Holz und eine Werkbank übergeben (Reasoning ist Pflicht, daher teurer) | 2,5 s | ~$1.20 |

Die fünf fehlerfreien Modelle stehen in der Auswahl oben mit „✔“ und gemessenem Preis. Zusätzlich haben 16 verbreitete Modelle (u. a. Gemini 3.8 Flash, GPT-5.4 mini, Grok 4.3, Kimi K2.6, Llama 4 Maverick, DeepSeek V4.1 Flash, Claude Sonnet 5.5) die komplette Werkzeugliste angenommen und richtig mit `gather` geantwortet; Mistral Small war beim Anbieter überlastet. Wie gut ein Modell spielt, entscheidet das Modell selbst; der Code korrigiert keine Entscheidungen.

## Wo die Daten liegen

- Begleiter für alle Welten: `voxelmind-local/companions/<uuid>.json` im Instanz-Ordner; ihr Inventar je Welt in `saves/<Welt>/playerdata`.
- Pro Welt: `saves/<Welt>/voxelmind-local/` – `<uuid>.json` (Charakter, Zeitleiste, Gedächtnis), `logs/<Name>.jsonl` (passives Protokoll jeder LLM-Entscheidung mit Latenz und Kosten). Mit `"logPrompts": true` in der Config stehen dort auch die vollständigen Prompts.
- Inventar und Position speichert Minecraft selbst (`playerdata`).

## Bekannte Grenzen

- Es gibt eine Datei pro Minecraft-Version (1.21 bis 26.2); die Endung des Dateinamens muss genau zur Spielversion passen. 1.21.x braucht Java 21, 26.x Java 25. Singleplayer und Server, auf denen der Mod installiert ist.
- Alle KI-Figuren sind für jeden sichtbar gekennzeichnet: „[AI]“ vor dem Namen (Namensschild, Tab-Liste, Chat), und wer eine Welt mit KI-Figuren betritt, bekommt einmal einen Hinweis.
- Keine Stimme (Voice) bisher.
- Builder und God haben Konsolen-Macht: Admin-Befehle (op, stop, ban …) und schädliche Befehle gegen alle Spieler (@a) sind technisch gesperrt. Einmal am Tag wird vor ihrer Ankunft ein Welt-Backup nach `backups/` geschrieben.
- Auf einem dedizierten Server gehören Key und Modell dem Server (`/vml login`, `/vml key`, `/vml models` als Operator); der Einstellungs-Bildschirm öffnet sich nur beim Host einer Singleplayer- oder LAN-Welt.
- Lokale Modelle brauchen Tool-Calls und lesen pro Entscheidung ~8.000 Tokens; auf schwacher Hardware dauert eine Antwort entsprechend (Timeout dafür: `localRequestTimeoutSeconds`, Standard 120 s).
- Der Begleiter ist technisch ein Spieler: Er steht in der Tab-Liste, „joined/left the game“ erscheint, er kann Advancements bekommen. Für das Überspringen der Nacht zählt er nicht.
- DeepSeek Flash plant Räume schwach: freie Bauten mit `place_blocks` werden manchmal Klötze. `build_shelter` baut zuverlässig eine Hütte.
- Die KI darf sich irren (z. B. ein falsch erinnertes Inventar). Der Code korrigiert das bewusst nicht.
- Übergaben sind echte Würfe: Die Items liegen kurz am Boden, nur der Empfänger kann sie aufheben.

## Rückbau

Instanz in CurseForge löschen (oder den Ordner `C:\Users\Robin\curseforge\minecraft\Instances\VoxelMind Local Test`). Sonst wurde nichts verändert.
