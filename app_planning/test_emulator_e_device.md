# Piano e risultati dei test su emulatore e telefono (GGCalendar Mobile)

Scritto il 13/10/2026. Serve a rifare lo stesso giro di prove su qualunque immagine di emulatore (API 26, 30, 34, 36, 37...) e a ogni nuova versione dell'app, con il minimo intervento dell'utente. Complementa le prove sul Samsung (Doze profondo, riavvio con PIN, telefono vero), che restano manuali.

## 1. Regole di sicurezza (valgono sempre)
- Si lavora **solo su eventi creati dal test**, nel calendario principale di profgusinu@gmail.com. Titolo sempre con il prefisso `[TEST GGCAL]` seguito da sigla e data (es. `[TEST GGCAL] T21 2026-10-13`), così la pulizia finale cancella solo quelli.
- Mai toccare l'evento "doze" né altri eventi dell'utente. Mai eliminare in blocco: si cancella solo ciò che il test ha creato, riconosciuto dal prefisso.
- **Nessun invitato reale e nessuna email**: i test con invitati (T35) usano solo indirizzi del dominio `example.com` e scelgono sempre "Non inviare" agli avvisi. Se un test richiedesse di scrivere a persone vere, si salta.
- Permessi e consensi: l'accesso Google e le schermate di consenso restano **scelta dell'utente**. Per i permessi di sistema dell'app (contatti, notifiche, sveglie esatte, schermo intero) **l'utente ha autorizzato `pm grant` / `appops` solo sugli emulatori (13/10/2026)**: lo script lo fa da sé (`flows.grant_permissions`, che rifiuta di agire su un dispositivo vero) dopo ogni installazione o `pm clear`. Sul telefono vero non si concede mai nulla.
- Non toccare il blocco schermo (PIN) dell'utente. Le prove con PIN sull'emulatore usano un PIN solo se l'utente lo imposta lui.
- Norton Web/Mail Shield deve essere spento durante le prove con rete (altrimenti l'emulatore non si fida del certificato e il sync fallisce); lo riaccende l'utente alla fine.
- Il token GitHub e i segreti non compaiono mai nei log del test.

## 2. Prerequisiti (controllati da uno script prima di partire)
1. Emulatore acceso e avviato del tutto (`sys.boot_completed=1`), con orologio corretto e rete (`adb shell ping` non basta: si controlla con una pagina HTTPS in Chrome o con il sync dell'app).
2. APK sotto test installato: `dist/GGCalendar-<versione>.apk` (release firmata, la stessa degli amici). Per i test che leggono il database serve invece la build debug (`run-as`).
3. Account profgusinu@gmail.com già presente sull'emulatore e app già autorizzata (una volta sola, a mano: vedi "Prima volta").
4. Lingua dell'interfaccia dell'emulatore nota (inglese di default sulle immagini; i testi attesi sono controllati in inglese e in italiano).
5. Cartella dei risultati fuori dai repository: `<scratchpad>/emulator-runs/<data>_<api>_<versione>/` con screenshot, dump XML, logcat e `report.md`.

**Prima volta su una nuova immagine**: creare l'AVD (`sdkmanager` + `avdmanager`, immagine con Play Store se serve l'accesso Google), aggiungere l'account da Impostazioni → Account, aprire l'app e scegliere l'account. Se Play Services è vecchio, lasciarlo aggiornare (solo immagini con Play Store).

## 3. Strumenti e metodo
Tutto con adb, senza root, così funziona anche sulle immagini con Play Store.
- **Pilotare l'interfaccia**: `uiautomator dump` → file XML; lo script cerca un elemento per testo, descrizione o id risorsa e tocca il suo centro con `input tap` (non coordinate fisse, così regge schermi e lingue diversi). Scorrimenti con `input swipe`; pressione prolungata e trascinamento con `input swipe x y x y <ms>` e `input draganddrop`.
- **Oracolo, in ordine di affidabilità**:
  1. testo presente nel dump dell'interfaccia (evento visibile, titolo, orario);
  2. `adb logcat -d`: nessun `FATAL EXCEPTION`, nessun `ANR in`, nessun `WM-WorkerWrapper ... failed` dell'app;
  3. `dumpsys alarm` e `dumpsys notification` per sveglie registrate e notifiche mostrate;
  4. **giro completo con Google**: si cancellano i dati dell'app (`pm clear`), si riapre e si riautorizza in silenzio: se l'evento riappare, era davvero su Google (e viceversa per le eliminazioni);
  5. file esportati letti con `adb pull` da `/sdcard/Download`.
- **Screenshot** a ogni passo importante (`exec-out screencap -p`), nominati `<ID>_<passo>.png`.
- **Esito**: PASS / FAIL / SKIP (motivo) / SEMI (serve l'utente). Ogni FAIL salva screenshot, dump XML e le 200 righe di logcat precedenti.
- **Stato iniziale pulito**: prima di ogni gruppo `am force-stop`, tema chiaro, vista Programma, data di oggi.
- **Pulizia finale** (sempre, anche dopo un errore): elimina dal calendario gli eventi con prefisso `[TEST GGCAL]`, ripristina le Impostazioni, chiude le notifiche.
- Lo script (da scrivere, `tools/emutest/` nel repo o nello scratchpad) legge questo file come elenco di casi e produce `report.md` con una riga per ID.

## 4. Casi di test
Colonna "Auto": **A** = completamente automatico; **S** = serve un gesto dell'utente (consenso, password); **M** = solo manuale/non fattibile sull'emulatore.

### A. Avvio e struttura (nessun account necessario)
| ID | Cosa si prova | Esito atteso | Auto |
|---|---|---|---|
| T01 | Installazione pulita e primo avvio | App parte, nessun crash, vista Programma, "Nessun evento" o eventi | A |
| T02 | Aggiornamento sopra la versione precedente (`install -r`) | I dati restano, nessun crash | A |
| T03 | Menu laterale | Programma, Giorno, 6 giorni, Settimana, Mese, Mese + Programma, Aggiorna, Sveglie attive, Impostazioni, Esporta, Informazioni | A |
| T04 | Cambio vista con ciascuna voce | Ogni vista si apre e mostra la sua intestazione; ricordata al riavvio dell'app | A |
| T05 | Titolo del mese, griglia degli anni | Si apre la scelta dell'anno; selezione porta al mese giusto | A |
| T06 | Icona "oggi" | Torna alla data odierna da qualsiasi data | A |
| T07 | Scorrimento orizzontale tra periodi (Settimana, Mese, Giorno) | Il periodo cambia, l'ora visibile resta | A |
| T08 | Informazioni | Autore, sito, versione e data di build uguali a quelli dell'APK (`dumpsys package`) | A |
| T09 | `+` senza account | Avviso "Aggiungi prima un account Google", nessun crash | A (solo con app senza account) |
| T10 | Rotazione dello schermo e tema scuro del sistema | Nessun crash, stato conservato | A |
| T11 | Lingua italiana / inglese | Testi tradotti, nessuna chiave mancante | S (cambio lingua) |
| T12 | Dimensione del carattere e dello schermo maggiorate (`settings put system font_scale 1.3`, `wm density`) | Nessun testo tagliato nei controlli principali, nessun crash | A (controllo visivo su screenshot) |

### B. Impostazioni
| ID | Cosa si prova | Esito atteso | Auto |
|---|---|---|---|
| T13 | Tema Chiaro / Scuro / Sistema + Salva | Si applica subito e dopo il riavvio | A |
| T14 | Esci con modifiche non salvate | Chiede "Scartare le modifiche?"; Annulla le mantiene, Scarta le toglie | A |
| T15 | Primo giorno della settimana: Lunedì / Sabato / Domenica | Settimana, Mese, mini-calendario, Programma ed editor partono dal giorno scelto | A |
| T16 | Inizio della giornata | Le viste a griglia si aprono all'ora scelta | A |
| T17 | Eventi passati attenuati | Interruttore efficace nelle viste | A |
| T18 | Compleanni dalla rubrica | Chiede il permesso; con il permesso crea il calendario "Compleanni (rubrica)"; spento sparisce | S (permesso contatti) |
| T19 | "Notifiche e sveglie" dalle Impostazioni | Stato dei permessi, durata della sveglia 1-10 minuti salvata | A / S per permessi |

### C. Viste, ricerca ed esportazione (servono eventi: con account)
| ID | Cosa si prova | Esito atteso | Auto |
|---|---|---|---|
| T20 | Sincronizzazione iniziale e tasto Aggiorna | Calendari nel menu, eventi in vista, nessun messaggio d'errore; cerchio che gira e poi si ferma | A |
| T21 | Tocco su un evento | Si apre il dettaglio con titolo, data/ora, calendario | A |
| T22 | Tocco su spazio vuoto della griglia | Compare il segnaposto "(Nuovo evento)" e si apre l'editor con ora precompilata | A |
| T23 | Ricerca | Trova per titolo, luogo, descrizione, invitato; "nessun risultato"; il tocco apre il dettaglio | A (dopo T30) |
| T24 | Mini-calendario | Titolo e vista seguono lo scorrimento; pallini nei giorni con eventi | A |
| T25 | Esportazione `.ics` | File in Download, righe `CRLF`, nessun ORGANIZER/ATTENDEE/VALARM/CLASS; eventi privati esclusi con riepilogo | A (selettore file di sistema: si scrive il nome nel selettore) |
| T26 | Esportazione CSV | `;` come separatore, BOM UTF-8, una riga per occorrenza, colonne attese | A |
| T27 | Condividi evento come testo e come `.ics` | Il foglio di condivisione compare; il file in cache è valido | A (fino al foglio) |

### D. Eventi: creare, modificare, eliminare (round trip con Google)
| ID | Cosa si prova | Esito atteso | Auto |
|---|---|---|---|
| T30 | Crea evento singolo con titolo, ora, luogo, descrizione | Visibile in tutte le viste; dopo `pm clear` e nuovo accesso è ancora lì | A |
| T31 | Evento di tutto il giorno e di più giorni | Barra nella fascia "tutto il giorno" e nel Mese; dopo il round trip invariato | A |
| T32 | Modifica titolo e orario | Cambia in tutte le viste; round trip | A |
| T33 | Eliminazione con conferma | Sparisce; round trip | A |
| T34 | Cambio di calendario dell'evento (**richiede due account Google collegati nell'app**, cioè un secondo calendario scrivibile) | Cambia colore e calendario | A / SKIP (lo script salta; da fare a mano con due account) |
| T35 | Invitati (solo `@example.com`), "Non inviare" | Chip invitato, permessi, nessuna email; nessuna domanda superflua senza invitati | A |
| T36 | Avviso agli invitati su modifica, spostamento ed eliminazione | Compaiono le tre scelte Invia / Non inviare / Annulla (si sceglie sempre "Non inviare") | A |
| T37 | Google Meet | "Aggiungi Meet" crea il link; compare "Partecipa con Google Meet" | A |
| T38 | Fuso orario dell'evento | Evento a New York mostra l'ora nel suo fuso; ricerca fusi funziona | A |
| T39 | Annulla con modifiche non salvate | Chiede conferma; lo scorrimento oltre l'inizio non chiude l'editor | A |
| T40 | Colore, disponibilità, visibilità privata | Si salvano; l'evento privato non esce nell'esportazione | A |

### E. Ricorrenze
| ID | Cosa si prova | Esito atteso | Auto |
|---|---|---|---|
| T41 | Preset: ogni giorno, settimana, mese, anno, feriali | Occorrenze nei giorni giusti; testo "Si ripete..." | A |
| T42 | Personalizzata: ogni N giorni, giorni della settimana, fine mai / data / dopo N volte | Numero e date di occorrenze corrette | A |
| T43 | Modifica "solo questo / questo e successivi / tutti" | Ambito rispettato nelle viste; round trip | A |
| T44 | Eliminazione con i tre ambiti | Rimuove solo ciò che deve | A |

### F. Trascinamento
| ID | Cosa si prova | Esito atteso | Auto |
|---|---|---|---|
| T45 | Pressione prolungata e spostamento nello stesso giorno | Scatta ogni 15 minuti; ora aggiornata | A |
| T46 | Spostamento su un altro giorno | Cambia giorno | A |
| T47 | Ridimensionamento dal bordo inferiore | Cambia la durata | A |
| T48 | Trascinamento di un evento di serie | Chiede l'ambito | A |

### G. Promemoria, notifiche e sveglie (il cuore dell'app)
Eventi creati a 3-5 minuti da ora, con promemoria a 1-2 minuti.
| ID | Cosa si prova | Esito atteso | Auto |
|---|---|---|---|
| T50 | Notifica popup all'ora giusta | Compare nel pannello (`dumpsys notification`) con titolo e orario; azione "Posticipa" | A / S (permesso notifiche) |
| T51 | Tocco sulla notifica | Apre il dettaglio dell'evento | A |
| T52 | Sveglia con schermo spento e bloccato | Schermata a tutto schermo, suoneria (stream alarm attivo in `dumpsys audio`), titolo e ora | A / S (schermo intero su Android 14+) |
| T53 | "Ferma la sveglia" | Suoneria ferma, schermata chiusa, non risuona | A |
| T54 | Posticipa (5, 10, 15, 30, 60 min) | Risuona all'ora giusta; compare in "Sveglie attive" con etichetta "Posticipata" | A |
| T55 | Nessuna risposta | Dopo la durata impostata (1 minuto nel test) la suoneria si ferma e resta la notifica "Sveglia persa" | A |
| T56 | Due sveglie insieme | In coda, una dopo l'altra | A |
| T57 | Sveglie attive | Elenco ordinato con tempo rimanente; tocco apre l'evento | A |
| T58 | Chiusura anticipata di una sveglia | Con conferma; non suona più, l'evento resta | A |
| T59 | App chiusa dalle recenti (`am force-stop` e `kill`) | Promemoria suona comunque | A |
| T60 | Riavvio dell'emulatore con promemoria pendente | Dopo il boot le sveglie sono registrate di nuovo (`dumpsys alarm`); quello scaduto durante lo spegnimento è mostrato (recupero 24 h) | A (riavvio con `adb reboot`) |
| T61 | Cambio di fuso (`service call alarm 3 s16 Asia/Tokyo` e ritorno) | Istantanea e allarmi si spostano; eventi di tutto il giorno seguono la mezzanotte locale | A |
| T62 | Cambio dell'ora di sistema | Promemoria riprogrammati | A (`date` richiede root: SKIP sulle immagini con Play Store) |
| T63 | Avviso nel dettaglio se le notifiche sono bloccate | Appare e rimanda alle impostazioni | S (revoca permesso) |
| T64 | Promemoria email | Il promemoria `email` si salva nell'evento (non si verifica la posta) | A |
| T65 | Sveglia sincronizzata (`ggAlarm`) | Dopo round trip il promemoria resta una sveglia, non una notifica | A |

### H. Rete e conflitti
| ID | Cosa si prova | Esito atteso | Auto |
|---|---|---|---|
| T70 | Modifica offline (`svc wifi disable; svc data disable`) | La modifica resta in coda (badge sul tasto Aggiorna); al ritorno della rete parte da sola | A |
| T71 | Assenza di rete all'avvio | Nessun banner "Unable to resolve host"; nessun crash | A |
| T72 | Conflitto: modifica offline sull'emulatore e modifica dello stesso evento dal Samsung | Resta la versione di Google più la copia "(conflitto)" con messaggio | S (serve il Samsung) |
| T73 | Errore di autorizzazione | Un solo avviso per account; sparisce se l'autorizzazione riesce | S |

### I. Robustezza
| ID | Cosa si prova | Esito atteso | Auto |
|---|---|---|---|
| T80 | Monkey: `adb shell monkey -p io.github.giansalvo.ggcalendar --throttle 200 -s <seme> 3000` con calendari e account | Nessun `FATAL EXCEPTION` né ANR (i tocchi su "Elimina" si evitano: la prova è fatta solo con un account di prova o con la rete spenta) | A (da lanciare con rete spenta e senza eventi reali visibili) |
| T81 | Memoria bassa e app in background (`am send-trim-memory`, `am kill`) e ritorno | Stato conservato, nessun crash | A |
| T82 | Scansione `logcat` dell'intera sessione | Zero `FATAL EXCEPTION`, zero `ANR`, nessuna eccezione non gestita dell'app | A |

**Fuori dal campo dell'emulatore**: Doze profondo vero, riavvio con PIN e sveglia prima dello sblocco, telefono di marca diversa con restrizioni del produttore. Restano prove sul Samsung o su altri telefoni.

## 5. Ordine di esecuzione e durata
1. Prerequisiti e pulizia iniziale.
2. A (senza eventi) e B: circa 5 minuti.
3. D, E, F, C (creano gli eventi e li riusano): circa 15 minuti.
4. G: attese reali di 3-5 minuti per ogni sveglia; si programmano in parallelo (eventi a orari diversi) per stare sotto i 20 minuti.
5. H, I.
6. Pulizia finale e `report.md`.
Totale previsto: circa 45-60 minuti per ogni immagine.

## 6. Come ripeterlo su altre immagini
- Elenco consigliato: **API 26** (minimo supportato), **30** (ultimo con servizio in primo piano accelerato diverso), **34** (`USE_FULL_SCREEN_INTENT`, permesso notifiche), **36** (target) e **37**. Ogni immagine con Play Store per poter accedere all'account.
- Per ciascuna: stesso APK, stessa sequenza, cartella dei risultati separata; il confronto tra `report.md` delle varie API dice dove una funzione si rompe solo su certe versioni (per esempio il sync accelerato prima di Android 12, trovato il 13/10/2026).
- Differenze attese da registrare nel report: permesso notifiche da API 33, sveglie esatte da API 31, schermo intero da API 34, istanze di Doze diverse.

## 7. Gli script (scritti il 13/10/2026)
Cartella `tools/emutest/` (Python 3 con Pillow, adb dell'SDK, nessuna altra dipendenza):
- `adbx.py`: adb e UI Automator (dump dell'interfaccia, ricerca per tag, testo o descrizione, tocchi, scorrimenti, log).
- `strings.py`: legge i `strings.xml` dell'app (inglese e italiano) e trasforma il nome di una stringa in un'espressione regolare, così gli stessi test girano in entrambe le lingue senza copiare i testi.
- `flows.py`: i passi riutilizzabili (aprire il menu, scegliere una vista, creare un evento con promemoria e ricorrenza, cercare, eliminare, ripulire, giro completo con Google).
- `settings_state.py` (14/10/2026): all'inizio del run legge dalla schermata Impostazioni tema, colori dinamici, inizio settimana, inizio giornata e eventi passati attenuati (e `cases_g` la durata della sveglia), e alla fine li rimette com'erano; serve soprattutto sul telefono vero, dove i test non devono lasciare valori propri. Su Android 16 un radio scelto risulta `checked`, sulle versioni precedenti `selected`.
- `harness.py`: registro dei casi, esiti PASS/FAIL/SKIP, controllo dei crash nel log dopo ogni caso.
- `cases_ab.py` (gruppi A e B), `cases_c.py` (C), `cases_d.py` (D, E, F), `cases_g.py` (G), `cases_h.py` (H e I).
- `run.py`: `python run.py --serial emulator-5556 --apk <apk> [--only T03,T04] [--group G] [--skip-slow] [--list]`. **Animazioni (14/10/2026)**: all'inizio legge le tre scale di animazione del dispositivo (emulatore o telefono), le porta a 0 per tutta l'esecuzione e alla fine le rimette come erano (anche un valore mai impostato torna "assente"); `--no-speedup` le lascia come sono. Il dimezzamento delle pause resta solo sull'emulatore. Se l'esecuzione viene uccisa a metà le animazioni restano a 0 (sul telefono è il valore voluto; sull'emulatore si rimettono con `settings put global`).
- I risultati vanno in `tools/emutest/runs/<data>_api<N>_<versione>/` (screenshot, `report.md`, `results.json`), cartella ignorata da git e controllata dalla CI e dal hook locale, perché contiene immagini del calendario vero.

**Etichette stabili nell'app**: `testTag` sui controlli principali (barra, menu, editor, dettaglio, impostazioni, ricerca, sveglia, finestre di conferma), esposti come resource id con `Modifier.exposeTestTags()` (`core/notifications/UiTags.kt`), da applicare una volta alla radice di ogni finestra. Chi aggiunge una schermata o un dialogo nuovo deve dargli i tag e la radice.

**Regole di sicurezza nello script**: i permessi di sistema non si concedono mai da script (se compare una richiesta il caso si ferma con SKIP); il caso `monkey` gira solo senza rete e cancella i dati dell'app prima che la rete torni, così nulla può raggiungere Google; l'editor rifiuta di salvare se il calendario mostrato non è quello dell'account di prova.

**Limiti noti**: sull'API 26 il trascinamento (gruppo F) richiede `input motionevent` (Android 10+) e viene saltato; la tastiera dell'emulatore corregge le parole (si usano gettoni inventati come `Lab7Q`); dopo `pm clear` il permesso dei contatti va concesso di nuovo a mano.

## 8. Registro delle esecuzioni
| Data | Immagine | Versione app | Esito | Note |
|---|---|---|---|---|
| 13/10/2026 | API 26 (google_apis, senza account) | 0.4.0/0.4.1 | parziale, manuale | Trovati e corretti: sync accelerato prima di Android 12 (`getForegroundInfo` non implementato), crash di `+` senza account |
| 13/10/2026 | API 26 (google_apis_playstore x86, con account) | 0.4.1 | script a blocchi, tutti i gruppi provati almeno una volta | Passano: A (T01-T08, T10, T12), B (T13-T17, T19), C (T20-T27), D (T30-T33R, T37-T40), E (T41-T44), G (T50-T61, T64, T65), H (T71), I (T80-T82), T70 con la build di debug. Saltati: F (richiede Android 10+), T34 (scriverebbe fuori dal calendario principale), T35-T36 (servono il permesso dei contatti, scelta dell'utente). T70 passa con la build di debug; con la release sull'emulatore il badge non compare (non è un difetto, vedi sotto). |
| 13/10/2026 | API 36 (Android 16, google_apis_playstore x86_64), modalità rapida | 0.4.2 | tutti i casi della modalità rapida passano (47 casi: A-E, T35-T36 con permessi concessi da `pm grant`, T71, T81, T82) | Fallimenti trovati e risolti (tutti negli script, nessuno nell'app): menu laterale e ultimo campo dell'editor oltre il limite dell'altezza (schermo più alto), scheda impostazioni più lunga (ultima riga sotto la barra dei gesti), selettore di file `com.google.android.documentsui`, modalità aereo da `cmd connectivity airplane-mode`, Salva spento quando non cambia nulla. **Non eseguiti su API 36**: i casi lenti (G, T33R, T37, T65, T70, T80), cioè la modalità completa. |
| 13/10/2026 | API 36 (emulatore), casi lenti | 0.4.2 | passano T30, T32, T33, T33R, T37, T45–T48, T50, T52, T53, T55, T56, T59, T60, T61, T64, T65, T70, T80; falliscono T51, T54, T57, T58 | I quattro fallimenti: notifiche vecchie raggruppate (script) e foglio "Sveglie attive" (difetto dell'app, corretto in 0.4.3). Altri problemi risolti negli script: vista aperta su una data vecchia, selettore di date, `home()` con un foglio aperto. |
| 13/10/2026 | Samsung Galaxy A56, One UI 8.5, Android 16 (telefono, italiano) | 0.4.3 e 0.4.4 | 44 casi eseguiti, tutti passano (vedi sezione 9) | Primo giro: 28 passati, 8 falliti, 7 saltati; correzioni di script (fuso in italiano, barra di navigazione, ultima riga delle Impostazioni, trascinamento a tarda sera, tocco su slot vuoto che apre un evento reale) e due difetti dell'app: foglio "Sveglie attive" (0.4.3) ed errore 403 di limite richieste (0.4.4). |
| 14/10/2026 | API 36 (emulatore) | 0.4.5 | passano T03, T33; T30 fallisce con le animazioni spente e passa con `--no-speedup`; T74 fallisce | T30: con le animazioni di sistema spente il foglio di dettaglio si ferma a circa un quarto dello schermo (con le animazioni accese arriva a metà), quindi la descrizione resta fuori schermo. Era un difetto dell'app, confermato anche sul Samsung con le animazioni a 0 (il foglio compariva con un segnaposto e non si adattava al contenuto); corretto in 0.4.6. T74: dopo la rimozione Google chiede di nuovo il consenso, quindi il caso diventa manuale; la rimozione revoca l'accesso su tutti i dispositivi dello stesso account (ha invalidato il login del desktop). |
| 14/10/2026 | API 36 (emulatore, animazioni spente) | 0.4.6 | passa T30 | Verifica della correzione del foglio di dettaglio. |
| 14/10/2026 | Samsung Galaxy A56, Android 16 (telefono, italiano, animazioni a 0) | 0.4.6 | passa T30 | Foglio di dettaglio a metà schermo con luogo e descrizione visibili; l'evento è arrivato a Google e la pulizia lo ha tolto (verificato dall'utente su Google Calendar). |

**Difetti dell'app trovati dai test (13/10/2026)**
1. Prima di Android 12 il sync su richiesta falliva (corretto in 0.4.1).
2. Con nessun account, "+" chiudeva l'app (corretto in 0.4.1).
3. L'editor non ripartiva da zero dopo un annullamento: aprendo un nuovo evento ricompariva il testo scartato (corretto in 0.4.2, `EventEditorViewModel.forget()`).
4. **Non è un difetto dell'app**: sull'emulatore con la build di release il numero delle modifiche in attesa non compare sull'icona Aggiorna, mentre con la build di debug sì; **sul Samsung con la release il numero compare correttamente (provato dall'utente il 13/10/2026)**. Il test T70 accetta come prova anche il lavoro di sync in attesa della rete (`dumpsys jobscheduler`) e lo annota.

**Note di taratura**
- Con `-gpu host` (scheda grafica del PC) l'app ha avuto una volta "non risponde" mentre l'emulatore saturava i suoi servizi grafici: ora l'avvio predefinito è il disegno software (`emu.py start <avd> --gpu host` resta come opzione).
- Android 16: la tastiera, il selettore di file e il pannello delle impostazioni rapide sono diversi da Android 8; gli script ne tengono conto (`screen_h`, `scroll_to`, ricerca del pulsante Salva in qualunque pacchetto `documentsui`).
- La tastiera dell'emulatore a volte storpia il titolo digitato (`[TEST GGCAL] x` diventava `[To] x`): lo script ora rilegge il titolo e rifà la digitazione, e rifiuta di salvare se resta sbagliato.
- Offline = modalità aereo dal pannello delle impostazioni rapide (`svc wifi disable` e un proxy non isolano l'app sull'emulatore).
- `pm clear` (giro completo con Google) azzera anche il permesso dei contatti.

## 9. Risultati consolidati (aggiornato il 13/10/2026, sera)

Per ogni caso, l'ultimo esito su ciascuna piattaforma: **A** = emulatore API 26 (Android 8, build 0.4.1, inglese), **B** = emulatore API 36 (Android 16, build 0.4.2, inglese), **C** = Samsung Galaxy A56, One UI 8.5 / Android 16 = API 36 (build 0.4.3 e 0.4.4, italiano, account reale profgusinu@gmail.com). ✔ passa, ✖ fallisce, salt. = saltato dallo script, — = non eseguito su quella piattaforma.

Totali: A 54 pass, 3 skip (57 casi); B 4 fail, 49 pass, 1 skip (54 casi); C 1 fail, 51 pass (52 casi).

**Tabella 9.1 – Esito di ciascun caso di test per piattaforma (A: emulatore API 26, B: emulatore API 36, C: Samsung A56 con API 36); la colonna Tipo dice se il caso è automatico (A, c'è uno script in `tools/emutest/`) o manuale (M, senza script)**

| ID | Caso | Tipo | A: emul. API 26 | B: emul. API 36 | C: Samsung A56 (API 36) |
|---|---|---|---|---|---|
| T01 | Installazione pulita e primo avvio | A | — | — | — |
| T02 | Aggiornamento sopra la versione precedente (install -r) | A | — | — | — |
| T03 | Menu laterale | A | ✔ | ✔ | ✔ |
| T04 | Cambio vista con ciascuna voce | A | ✔ | — | ✔ |
| T05 | Titolo del mese, griglia degli anni | A | ✔ | — | ✔ |
| T06 | Icona "oggi" | A | ✔ | — | ✔ |
| T07 | Scorrimento orizzontale tra periodi (Settimana, Mese, Giorno) | A | ✔ | — | ✔ |
| T08 | Informazioni | A | ✔ | — | ✔ |
| T09 | + senza account | A | — | — | — |
| T10 | Rotazione dello schermo e tema scuro del sistema | A | ✔ | — | — |
| T11 | Lingua italiana / inglese | M | — | — | — |
| T12 | Dimensione del carattere e dello schermo maggiorate (settings put syst | A | ✔ | — | — |
| T13 | Tema Chiaro / Scuro / Sistema + Salva | A | ✔ | ✔ | ✔ |
| T14 | Esci con modifiche non salvate | A | ✔ | ✔ | ✔ |
| T15 | Primo giorno della settimana: Lunedì / Sabato / Domenica | A | ✔ | ✔ | ✔ |
| T16 | Inizio della giornata | A | ✔ | ✔ | ✔ |
| T17 | Eventi passati attenuati | A | ✔ | ✔ | ✔ |
| T18 | Compleanni dalla rubrica | M | — | — | — |
| T19 | "Notifiche e sveglie" dalle Impostazioni | A | ✔ | ✔ | ✔ |
| T20 | Sincronizzazione iniziale e tasto Aggiorna | A | ✔ | ✔ | ✔ |
| T21 | Tocco su un evento | A | ✔ | ✔ | ✔ |
| T22 | Tocco su spazio vuoto della griglia | A | ✔ | ✔ | ✔ |
| T23 | Ricerca | A | ✔ | ✔ | ✔ |
| T24 | Mini-calendario | A | ✔ | ✔ | ✔ |
| T25 | Esportazione .ics | A | ✔ | ✔ | ✔ |
| T26 | Esportazione CSV | A | ✔ | ✔ | ✔ |
| T27 | Condividi evento come testo e come .ics | A | ✔ | ✔ | ✔ |
| T30 | Crea evento singolo con titolo, ora, luogo, descrizione | A | ✔ | ✔ | ✔ |
| T31 | Evento di tutto il giorno e di più giorni | A | ✔ | ✔ | ✔ |
| T32 | Modifica titolo e orario | A | ✔ | ✔ | ✔ |
| T33 | Eliminazione con conferma | A | ✔ | ✔ | ✔ |
| T33R | Giro completo con Google dopo pm clear | A | ✔ | ✔ | — |
| T34 | Cambio di calendario dell'evento (richiede due account Google collegati nell'app) | A | salt. | salt. | — |
| T35 | Invitati (solo @example.com), "Non inviare" | A | salt. | ✔ | ✔ |
| T36 | Avviso agli invitati su modifica, spostamento ed eliminazione | A | salt. | ✔ | ✔ |
| T37 | Google Meet | A | ✔ | ✔ | ✔ |
| T38 | Fuso orario dell'evento | A | ✔ | ✔ | ✔ |
| T39 | Annulla con modifiche non salvate | A | ✔ | ✔ | ✔ |
| T40 | Colore, disponibilità, visibilità privata | A | ✔ | ✔ | ✔ |
| T41 | Preset: ogni giorno, settimana, mese, anno, feriali | A | ✔ | ✔ | ✔ |
| T42 | Personalizzata: ogni N giorni, giorni della settimana, fine mai / data | A | ✔ | ✔ | ✔ |
| T43 | Modifica "solo questo / questo e successivi / tutti" | A | ✔ | ✔ | ✔ |
| T44 | Eliminazione con i tre ambiti | A | ✔ | ✔ | ✔ |
| T45 | Pressione prolungata e spostamento nello stesso giorno | A | — | ✔ | ✔ |
| T46 | Spostamento su un altro giorno | A | — | ✔ | ✔ |
| T47 | Ridimensionamento dal bordo inferiore | A | — | ✔ | ✔ |
| T48 | Trascinamento di un evento di serie | A | — | ✔ | ✔ |
| T50 | Notifica popup all'ora giusta | A | ✔ | ✔ | ✔ |
| T51 | Tocco sulla notifica | A | ✔ | ✖ | ✔ |
| T52 | Sveglia con schermo spento e bloccato | A | ✔ | ✔ | — |
| T53 | "Ferma la sveglia" | A | ✔ | ✔ | — |
| T54 | Posticipa (5, 10, 15, 30, 60 min) | A | ✔ | ✖ | ✔ |
| T55 | Nessuna risposta | A | ✔ | ✔ | ✔ |
| T56 | Due sveglie insieme | A | ✔ | ✔ | ✔ |
| T57 | Sveglie attive | A | ✔ | ✖ | ✔ |
| T58 | Chiusura anticipata di una sveglia | A | ✔ | ✖ | ✔ |
| T59 | App chiusa dalle recenti (am force-stop e kill) | A | ✔ | ✔ | ✔ |
| T60 | Riavvio dell'emulatore con promemoria pendente | A | ✔ | ✔ | — |
| T61 | Cambio di fuso (service call alarm 3 s16 Asia/Tokyo e ritorno) | A | ✔ | ✔ | ✔ |
| T62 | Cambio dell'ora di sistema | M | — | — | — |
| T63 | Avviso nel dettaglio se le notifiche sono bloccate | M | — | — | — |
| T64 | Promemoria email | A | ✔ | ✔ | ✔ |
| T65 | Sveglia sincronizzata (ggAlarm) | A | ✔ | ✔ | — |
| T70 | Modifica offline (svc wifi disable; svc data disable) | A | ✔ | ✔ | ✖ |
| T71 | Assenza di rete all'avvio | A | ✔ | ✔ | — |
| T72 | Conflitto: modifica offline sull'emulatore e modifica dello stesso eve | M | — | — | — |
| T73 | Errore di autorizzazione | M | — | — | — |
| T80 | Monkey: adb shell monkey -p io.github.giansalvo.ggcalendar --throttle  | A | ✔ | ✔ | — |
| T81 | Memoria bassa e app in background (am send-trim-memory, am kill) e rit | A | ✔ | ✔ | ✔ |
| T82 | Scansione logcat dell'intera sessione | A | ✔ | ✔ | ✔ |
| T51D | Solo telefono: popup e tocco sulla notifica | A | — | — | ✔ |
| T74 | Rimozione dell'account dall'app (dopo la rimozione Google chiede di nuovo il consenso, quindi si fa a mano) | M | — | — | — |

**Lettura dei risultati**
- **Samsung (C)**: 51 casi su 52 passano con la 0.4.4; l'unico fallito è T70. T70 esegue anche "Cancella dati" dell'app: sul telefono ha azzerato account locale, impostazioni dell'app e permesso notifiche (nessun evento su Google è stato toccato). Per questo T70 non va più lanciato sul telefono e il giro con `pm clear` (`flows.roundtrip`) ora rifiuta di partire fuori dagli emulatori. T71 è stato interrotto con la serie e non ha un esito valido. Casi passati sul telefono in questa sessione: T03–T08, T13–T17, T19–T27 (T25 e T26 con il selettore di One UI), T30–T33, T35–T48, T50, T51, T54–T59, T61, T64, T81, T82 e T51D.
- **Non eseguiti sul telefono per scelta di sicurezza**: T01, T02, T09 (installano, disinstallano o richiedono l'assenza di account), T10, T12 (cambiano tema e dimensioni di sistema), T33R, T65, T80 (usano `pm clear`), T52, T53 (schermo spento con PIN), T60 (riavvio con PIN), T70, T71 (modalità aereo, vedi sopra). Restano nella lista manuale di `test_pre_release_v1.md`.
- **Emulatore API 36 (B)**: i quattro fallimenti (T51, T54, T57, T58) erano due problemi diversi, entrambi già risolti: T51 per notifiche vecchie raggruppate (script), T54, T57 e T58 per il foglio "Sveglie attive" che non mostrava le righe (**difetto dell'app**, corretto nella 0.4.3). Sull'emulatore non sono stati rilanciati con la 0.4.4; sul telefono passano.
- **Emulatore API 26 (A)**: tutti i casi eseguiti passano; salt. T34 (serve un secondo account Google collegato, vedi la tabella), T35 e T36 (richiedevano il permesso dei contatti, poi concesso con `pm grant` solo su API 36). Il gruppo F (trascinamento) richiede Android 10 o successivo e non gira su API 26.
- **Mai eseguiti da script**: T01, T02, T09 (provati a mano su API 26), T11 (lingua), T18 (compleanni, permesso contatti), T62 (ora di sistema, serve root), T63 (permessi notifiche revocati), T72 (conflitto con il telefono), T73 (autorizzazione), T74 (rimozione account: dopo la rimozione, per riaggiungerlo serve il consenso a mano sulla pagina di Google; lo script lo segna SKIP).

**Difetti dell'app trovati dai test e cambiamenti collegati (in ordine)**
1. Prima di Android 12 il sync su richiesta falliva (corretto in 0.4.1).
2. Con nessun account, "+" chiudeva l'app (0.4.1).
3. L'editor mostrava una bozza scartata (0.4.2).
4. **Foglio "Sveglie attive"** (0.4.3): la lista arriva dopo l'apertura del foglio e il foglio restava ancorato all'altezza iniziale, con le righe sotto il bordo dello schermo. Ora ha un'altezza fissa (60% dello schermo). Verificato sul Samsung (T57, T54, T58).
5. **Errore 403 "limite di richieste"** (0.4.4): una serie di creazioni di eventi ravvicinate faceva rispondere Google con un 403; l'app lo trattava come rifiuto definitivo, scartava l'operazione e riscaricava il calendario, quindi l'evento nuovo spariva dal telefono. L'ipotesi viene dalla lettura del codice e dal comportamento osservato (eventi non più ritrovati dopo circa 40 minuti di prove, poi regolari con la 0.4.4); la risposta di Google non è stata registrata. Ora 429 e 403 con `rateLimitExceeded`/`userRateLimitExceeded`/`quotaExceeded` restano in coda e si riprovano (`GoogleApiException.isRateLimit`, test `rateLimitKeepsTheQueueAndTheLocalEvent`).
6. **Eventi passati non attenuati** (segnalato dall'utente, corretto in 0.4.5): l'impostazione "Attenua gli eventi passati" valeva solo per le griglie (Giorno, 6 giorni, Settimana) e la fascia "tutto il giorno"; ora vale anche per Mese, Programma e Mese + Programma.
7. **Funzione mancante: uscire da un account** (richiesta dell'utente, 0.4.5): pulsante di rimozione accanto a ogni account nel menu laterale, con conferma; toglie dal telefono account, calendari, eventi e coda di invio di quell'account e prova a revocare l'accesso su Google (2 test unitari, `AccountRemovalTest`).

**Da verificare (non ancora risolto)**
- Dopo l'ultima modifica a `flows.reveal` T30 non è stato rilanciato sull'emulatore.

**Cambiamenti agli script (13/10/2026, sera)**: modalità `--mode slow`; `settings_state.py` (le impostazioni dell'utente si leggono all'inizio e si rimettono alla fine, con la durata della sveglia); sul telefono nessuna accelerazione, nessun `pm grant`, nessun cambio di tema, densità, timeout dello schermo, e `roundtrip` (cancellazione dei dati) vietato; `usable_h` (la barra di navigazione a tre pulsanti del Samsung toglie 126 px); ricerca del giorno nel selettore di date indipendente dalla lingua; fuso New York riconosciuto anche in italiano; chiusura della tendina delle notifiche prima di ogni caso (una tendina aperta bloccava `uiautomator`); `g_setup` crea solo gli eventi dei casi scelti; T61 rimette il fuso del telefono; il prefisso degli eventi di prova ora è **`[TEST GGCAL]`** (era `[TEST-EMU]`).

**Prossimi passi dei test**: T74 a mano (rimozione e nuovo accesso con consenso); emulatori API 30, 34 e 37 (serve il tuo account aggiunto con Norton spento) in modalità rapida e completa; modalità completa su API 26 in un'unica esecuzione; sul telefono, rilanciare le verifiche della 0.4.5 (eventi passati attenuati, rimozione account a mano); la lista manuale di `test_pre_release_v1.md`.
