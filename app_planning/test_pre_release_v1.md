# Test pre-release v1.0 sul telefono (Samsung A56, Android 16)

Due parti: (1) casi automatici lanciati sul telefono con uno degli account reali; (2) lista manuale, corta. Gli emulatori (API 26, 30, 34, 36, 37) restano nel piano `test_emulator_e_device.md`.

## 1. Casi automatici sul telefono
**Prima di lanciare, l'utente dice quale account usare** (`--account`) e se parte la modalità rapida o quella completa. Io non lancio nulla sul telefono senza questo via libera.

**Regole di sicurezza (valgono sul telefono più che sull'emulatore)**
- Si scrivono solo eventi con prefisso `[TEST GGCAL]`, nel calendario principale dell'account scelto; a fine run tutti quelli con il prefisso vengono cancellati (anche dopo un errore).
- Invitati solo `@example.com`, sempre "Non inviare"; nessun invito parte.
- Nessun `pm clear`, `pm grant`, riavvio, cambio di fuso o monkey: sul telefono sono vietati. I permessi si danno a mano prima (notifiche, schermo intero, sveglie esatte; contatti solo se si prova il punto 11 della lista manuale).
- Il telefono resta in carica e con lo schermo acceso; lo script rimette a posto animazioni e "schermo sempre acceso" alla fine.
- Prima del run: sincronizzazione completata e nessuna modifica in coda (badge del tasto Aggiorna vuoto).

**Casi inclusi** (id del piano `test_emulator_e_device.md`)
| Gruppo | Casi | Note |
|---|---|---|
| A, B | T03–T08, T10, T13–T17, T19 | struttura, viste, Informazioni (versione e data uguali all'APK), Impostazioni con Salva |
| C | T20–T24, T26, T27 | sincronizzazione, dettaglio, ricerca, mini-calendario, CSV, condivisione |
| D | T30–T32, T35–T40 | creazione, modifica, Meet, invitati, fuso, colore, visibilità; T33 (eliminazione) |
| E | T41–T44 | ricorrenze e ambiti |
| F | T45–T48 | trascinamento e ridimensionamento |
| G | T50–T58, T64, T65 | popup, sveglia a schermo spento, ferma, posticipa, nessuna risposta, due sveglie, Sveglie attive, chiusura anticipata, email, `ggAlarm` |
| H, I | T71, T81, T82 | rete assente all'avvio, memoria bassa, scansione del log |

**Esclusi sul telefono** (restano sugli emulatori o manuali): T01, T02, T09 (cancellano i dati o richiedono assenza di account), T11, T12, T18, T63, T73, T33R (`pm clear` e nuovo accesso), T25 (selettore file di One UI diverso: manuale), T34, T59–T62 (riavvio, fuso e `am kill` da emulatore), T70 e T80 (modalità aereo via `cmd` e monkey), T72.

**Come si lancia** (dopo il via libera): `python tools/emutest/run.py --serial RZGYB28HPTJ --apk dist/GGCalendar-<versione>.apk --account <email> --only <elenco sopra>`; durata circa 45 minuti in modalità completa, 20 in quella rapida (togliere G). Il report va in `tools/emutest/runs/`.

## 2. Test manuali (circa 30 minuti)
Spunta ogni riga; annota modello, versione e qualsiasi stranezza.
1. **Installazione sopra la versione precedente**: i dati restano, l'app parte, Informazioni mostra versione e data della build che stai consegnando.
2. **Primo login pulito** (se hai un secondo telefono o un account mai usato): schermata "app non verificata" (o, a verifica Google completata, il consenso normale) e sincronizzazione.
3. **Sveglia a telefono bloccato e schermo spento**: schermata a tutto schermo, suoneria udita, Ferma; ripeti con Posticipa 5 minuti.
4. **Silenzioso / Non disturbare**: la sveglia suona lo stesso; il popup rispetta Non disturbare.
5. **Riavvio con il PIN non ancora inserito** e una sveglia in arrivo: suona prima dello sblocco; dopo il PIN i promemoria risultano registrati.
6. **App chiusa dalle recenti**: un popup e una sveglia arrivano comunque.
7. **Modalità aereo**: modifica un evento, il badge sul tasto Aggiorna mostra le modifiche in coda, al ritorno della rete partono da sole e il badge sparisce.
8. **Due account**: eventi, colori e promemoria di entrambi; un evento presente in due account non duplica sveglia e popup.
9. **Esporta `.ics` e CSV** con il selettore di One UI: file in Download, aprili (il CSV in Excel con accenti e `;`), eventi privati esclusi con il riepilogo.
10. **Condividi un evento** (testo e `.ics`) e "Scrivi agli invitati" con un evento con invitati.
11. **Compleanni dalla rubrica**: interruttore, richiesta del permesso, calendario "Compleanni (rubrica)", spegnendo sparisce.
12. **Tema chiaro / scuro / di sistema, lingua italiana e inglese, carattere grande**: nessun testo tagliato.
13. **Cambio di fuso orario del telefono** con l'app aperta e chiusa: orari e sveglie seguono; i compleanni di tutto il giorno seguono la mezzanotte locale.
14. **Eventi di prova sul web** (calendar.google.com): crea, modifica ed elimina da lì e controlla che il telefono li allinei; un conflitto (modifica offline sul telefono e sul web) lascia la versione di Google più la copia "(conflitto)".
15. **Pulizia finale**: nessun evento `[TEST GGCAL]` rimasto, nessun messaggio d'errore pendente nel menu.

## Stato al 13/10/2026
- **Parte 1 (automatica sul Samsung A56, build 0.4.4)**: 44 casi eseguiti, tutti passano (dettaglio nella sezione 9 di `test_emulator_e_device.md`). Mancano sul telefono i casi che cambiano le Impostazioni (T13, T15–T17, T19): gli script ora le leggono e le rimettono, da rilanciare. Il prefisso degli eventi di prova è `[TEST GGCAL]`.
- **Parte 2 (lista manuale)**: da fare.

## Casi automatici che sul telefono restano manuali (decisione del 13/10/2026)
Non si lanciano da script sul Samsung perché cancellano i dati dell'app, richiedono lo sblocco con il PIN o cambiano impostazioni di sistema. Da fare a mano e spuntare:
- **T52, T53** sveglia a schermo spento e bloccato, poi "Ferma la sveglia" (punto 3 della lista sopra).
- **T60** riavvio con il PIN e promemoria pendente (punto 5).
- **T33R, T65** dopo "Cancella dati" dell'app e nuovo accesso: gli eventi creati e la sveglia (resta una sveglia, non diventa una notifica) tornano da Google. Crea un evento di prova con una sveglia, cancella i dati dell'app da Impostazioni di Android, riapri e accedi.
- **T80** monkey: non si fa sul telefono (tocchi casuali su dati reali).
- **T10, T12** tema scuro di sistema, carattere e schermo ingranditi (punto 12).
- **T01, T02, T09** installazione pulita, aggiornamento sopra la versione precedente (punto 1), "+" senza account (solo con l'app appena installata).
- **T11, T18, T62, T63, T72, T73** lingua, compleanni con il permesso contatti (punto 11), cambio dell'ora, avviso con le notifiche bloccate (revoca il permesso notifiche e apri un evento con promemoria), conflitto con una modifica dal web (punto 14), errore di autorizzazione.
- **T34** cambio di calendario di un evento (da fare solo con un evento di prova).
