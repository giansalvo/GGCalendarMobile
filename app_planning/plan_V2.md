# Piano della Versione 2 (GGCalendar Mobile)

Documento di lavoro del 19/10/2026. Le idee e le decisioni di sempre stanno in `plan.md`; i casi della V1 e i loro risultati in `test_emulator_e_device.md` e `test_pre_release_v1.md`. Qui: cosa si farà nella V2, in che ordine, e la batteria di test pronta da riempire.

## 1. Stato di partenza
- **1.0.1** pubblicata il 16/10/2026 (splash, lingua selezionabile, spagnolo e tedesco).
- **1.0.2** (in preparazione, build `1.0.2`, codice 15): un calendario nascosto dal menu laterale non fa più suonare sveglie né promemoria. Realizzata e provata su API 34 e API 36 (T66, T67, T68 passano su entrambe). Restano da provare i due telefoni (A23 e A56).
- **Aperto**: bug del menu laterale (non riprodotto in 8 prove); prova vera del Doze profondo (vedi TM101).

## 2. Funzionalità attese
Ordine di lavoro consigliato; ogni blocco si consegna e si prova da solo.

**Tabella 2.1 – Blocchi di lavoro della Versione 2**

| Blocco | Funzionalità | Dipende da | Fatto quando |
|---|---|---|---|
| B1 | **Motore iCalendar**: `IcsReader` (testo → componenti) e `IcsMapper` (componenti → eventi + rapporto), senza interfaccia | — | U101-U127 passano |
| B2 | **Schema nuovo con `kind`** e **calendari locali** minimi (crea, rinomina, elimina; eventi senza `outbox`) | — | T131-T142 passano |
| B3 | **Importazione**: apri un `.ics` (`VIEW`, `SEND`), voce nel menu, riepilogo, scelta della destinazione, doppioni, da indirizzo una volta | B1, B2 | T101-T123 passano |
| B4 | **Abbonamento a iCal pubblici di Google** (e altri), in sola lettura, sostituzione per differenza | B1, B2 | T151-T168 passano |
| B5 | **Secondo fuso orario** (seconda colonna in Giorno, N giorni, Settimana) | — | T171-T179 passano |
| B6 | **Aggiornamenti in-app** (controllo release su GitHub, download, SHA-256, installazione; alternativa leggera: avviso con link) | — | T191-T198 passano |
| B7 | **Suoni, seconda parte**: file dell'utente, suono per promemoria, volume crescente | — | T211-T218 passano |
| B8 | **Avviso batteria limitata** e pulsante **Invia log** | — | T231-T235 passano |
| B9 | **Rifinitura i18n**: revisione es/de, sito in più lingue (facoltativo) | — | TM113 |

### 2.1 Importazione e abbonamento: un solo motore
```
file .ics / indirizzo ──► IcsReader ──► IcsMapper ──► destinazione
 (VIEW, SEND, menu,        (testo →       (componenti →     ├─ Google       → EventWriter e outbox
  indirizzo)               componenti)     EventEntity       ├─ Locale       → scrittura diretta
                                           + rapporto)       └─ Abbonamento  → sostituzione per differenza
```
- **Id degli eventi**: `{calendario}|{UID}` per un evento o una serie, `{calendario}|{UID}|{RECURRENCE-ID}` per eccezioni e occorrenze già espanse. Reimportare la stessa riga la aggiorna. Un `RECURRENCE-ID` senza padre diventa un evento singolo (caso del feed pubblico di Google: 109 occorrenze con lo stesso `UID`, tutte "Busy", nessuna `RRULE`).
- **Mai invitare**: `ATTENDEE` e `ORGANIZER` si scartano (deciso); nessuna email parte da un file letto.
- **Verso Google**: operazione `IMPORT` nell'`outbox` che chiama `events.import` con l'`iCalUID` del file; un 409 (evento già presente) si tratta come "già presente", non come errore.
- **Promemoria**: i `VALARM` relativi si importano se l'interruttore del riepilogo è acceso (predefinito); altrimenti valgono quelli del calendario.
- **Ignorati e dichiarati nel riepilogo**: `VTODO`, `VJOURNAL`, `VFREEBUSY`, `ATTACH`, `CATEGORIES`, `RDATE`, `RANGE=THISANDFUTURE`.
- **Limiti** (deciso il 19/10/2026): **10 MB** per ogni file; verso un calendario **locale** nessun limite sul numero di eventi; verso **Google** al massimo **2000 eventi**, con le richieste distribuite nel tempo (pausa tra le chiamate, attesa su 403/429); nel riepilogo un **filtro per data** ("solo dal giorno...") riduce il volume e, se il file supera 2000 eventi verso Google, "Importa" resta disattivato finché non si restringe o si cambia calendario.
- **Sicurezza**: il file è dato non fidato. Solo `https` (con `webcal://` trasformato), tetto di dimensione (10 MB), campi con lunghezza massima, nessuna rete causata dal contenuto, `content://` letto una volta sola senza permesso persistente, l'attività non importa mai da sola. L'indirizzo di un calendario privato è un segreto: nessun log, nessuna esportazione, avviso nella finestra (`allowBackup` è già `false`).
- **Abbonamento**: scarica al massimo una volta all'ora e a "Aggiorna"; confronta l'hash del contenuto e, se uguale, non scrive; altrimenti inserisce, aggiorna e cancella per differenza in una transazione e avvisa i promemoria solo se è cambiato qualcosa. Se il link non risponde, i dati restano e compare un avviso discreto. Sola lettura (`reader`), senza promemoria.

### 2.2 Schema del database (non retrocompatibile, deciso il 19/10/2026)
- Tabella `calendars`: nuovi campi `kind` (`google`, `local`, `subscription`, `birthdays`), `source_url`, `source_hash`, `source_etag`, `last_fetched_at`, `last_error`, `refresh_minutes`; `account_email` nullo per i tipi non Google. Id: `{email}|{id}` (Google), `local:{uuid}`, `sub:{uuid}`, `birthdays`.
- Ripartenza da uno schema pulito con `fallbackToDestructiveMigrationFrom(1..5)`: si cancellano solo le versioni vecchie; da ora in poi servono migrazioni vere. La finestra delle istanze (DataStore) si azzera insieme. Gli account Google si riaggiungono (i token stanno in Android, non nel database).
- `EventWriter`: per i calendari non Google salva senza `outbox` e senza sincronizzazione.

### 2.3 Gli altri blocchi
- **Secondo fuso orario**: seconda colonna oraria con etichetta nelle viste a griglia (Giorno, 6 giorni, Settimana); scelta in Impostazioni, ricerca dei fusi come nell'editor; non compare in Programma né nelle viste Mese.
- **Aggiornamenti in-app**: controllo delle release di GitHub (al massimo una volta al giorno), avviso con note, download con avanzamento, verifica SHA-256 del file `.sha256` della release, installazione con il permesso "installa app sconosciute". Non ammesso sul Play Store: valido per la distribuzione fuori dal negozio.
- **Suoni, parte 2**: file scelto dall'utente copiato nell'app (limiti di dimensione e durata); suono diverso per ogni promemoria; volume crescente. Prima dello sblocco dopo un riavvio vale sempre il suono incluso.
- **Batteria limitata**: banner in Impostazioni e nel dettaglio solo se `isBackgroundRestricted()` è vero (da Android 9); riga generica per gli altri produttori.
- **Invia log**: l'app prepara un file con versione, modello, errori recenti e li condivide; nessun dato personale (niente titoli, email, indirizzi di abbonamento). Registra i crash in un file locale.

## 3. Decisioni
**Prese** (19/10/2026, salvo diversa indicazione)
1. Schema non retrocompatibile: poche installazioni di prova, si reinstallano. Con lo schema si azzerano in DataStore **solo le chiavi legate ai dati** (finestra delle istanze, ultimo controllo dei promemoria, contatore dei riavvii); le impostazioni dell'utente restano.
2. Un solo motore iCalendar per importazione e abbonamento, con un **lettore proprio** (nessuna libreria).
3. **Calendari locali**: niente invitati e niente Meet; promemoria predefiniti sì.
4. **Versione**: tutte le modifiche escono come **1.0.2** finché l'utente non dice di incrementare.
5. Le modifiche dei **calendari nascosti** sono in `main` (commit e push del 19/10/2026).
6. Verso Google l'importazione usa **`events.import`** (versione finale, conserva l'`UID`, Google rifiuta i doppioni con 409); serve una nuova operazione `IMPORT` nell'`outbox` e il metodo nell'API.
7. **Invitati del file**: scartati.
8. **Filtri di apertura**: `text/calendar`, `application/ics` e `.ics` per estensione; gli altri tipi si valutano al momento.
9. Posticipa su una notifica di un calendario nascosto suona comunque (1.0.2).
10. **Promemoria del file**: interruttore nel riepilogo, visibile solo se il file ha `VALARM`, **acceso di default** ("promemoria del file").
11. **Doppioni**: si riconoscono per `UID` (e `RECURRENCE-ID`); l'azione predefinita è **Salta**.
12. **Limiti dell'importazione**: locale senza limite sul numero e massimo 10 MB; Google massimo 2000 eventi con richieste distribuite nel tempo; **filtro per data** nel riepilogo.

**Da prendere**
1. Abbonamenti: frequenza (1 ora fissa e "Aggiorna"?), limiti di dimensione e di eventi, promemoria (nessuno?), indirizzo segreto in chiaro o cifrato, esportazione degli abbonamenti (no?), dove ospitare i feed di prova HTTPS (GitHub Pages o sito), il feed reale del 19/10/2026 (calendario di lavoro) **non va nel repository**.
2. Aggiornamenti in-app: installatore completo o solo avviso con il link (proposta: partire dall'avviso).
3. Suoni, parte 2: il suono per promemoria sta in una tabella a parte, locale al telefono (Google non lo conosce); limiti del file (proposta: 5 MB, 30 secondi).
4. Secondo fuso: uno solo, etichetta breve scelta dall'utente (proposta), solo nelle viste a griglia.
5. Invia log: cosa registra e se registrare i crash con un gestore proprio (proposta: sì, file locale limitato a 200 KB).
6. Traduzioni: quattro lingue per ogni blocco (proposta: sì).
7. Bug del menu laterale: resta aperto finché non ricompare (proposta).

## 4. Numerazione e regole dei test
- **`T1xx`**: casi su dispositivo, per gruppo (intervalli riservati: K importazione T101-T130, L locali T131-T150, M abbonamenti T151-T170, N secondo fuso T171-T190, O aggiornamenti T191-T210, P suoni T211-T230, Q batteria e log T231-T250, R regressione T251-T270). Non collidono con i T01-T82 della V1.
- **`TM1xx`**: casi manuali (TM101-TM116), non collidono con TM1-TM19 della V1.
- **`U1xx`**: test unitari sulla JVM (U101-U144), senza dispositivo.
- **Tipo**: **A** automatico (script `tools/emutest`), **S** semi-automatico (serve un gesto o un permesso dell'utente), **M** manuale.
- **Colonne** (in ordine di API, con ogni telefono accanto all'emulatore della stessa API): `API 26 emu` emulatore API 26, `API 34 emu` emulatore API 34, `A23 (API 34)` Samsung A23 (Android 14; versione da confermare con `getprop ro.build.version.sdk`), `API 36 emu` emulatore API 36, `A56 (API 36)` Samsung A56 (Android 16).
- **Valori nelle celle**: `—` da eseguire, `M` da eseguire a mano dall'utente, `NA` non applicabile, `✔` passa, `✖` fallisce, `salt.` saltato, `bloc.` bloccato. Dopo ogni run si scrive il numero di versione (es. `✔ 2.0.0`).
- **Samsung A23 e A56** (telefoni dell'utente, stesse regole; su entrambi l'account è **profgusinu**): script solo con il via libera dell'utente, eventi con prefisso `[TEST GGCAL]` nel calendario principale, nessun invito, **mai cancellare eventi dell'utente** (si tolgono solo quelli con il prefisso). Niente `monkey`, `pm clear`, riavvii forzati né cambi di fuso. Gli screenshot sono ammessi su entrambi. Sul A23 il selettore degli account elenca anche account di altre persone: si sceglie profgusinu e gli screenshot che mostrano quel selettore si cancellano a fine run. Le colonne dei due telefoni hanno quindi gli stessi valori.
- **Eventi di prova**: prefisso `[TEST GGCAL]`; a fine run si cancellano (anche dopo un errore); il calendario nascosto si rimostra sempre.
- **Un solo dispositivo alla volta per account** (regola del 19/10/2026): tutti i dispositivi usano profgusinu, quindi gli eventi `[TEST GGCAL]` passano da Google a ogni dispositivo e la pulizia di fine run di uno cancella quelli degli altri. Lo ha dimostrato la prova del Doze sul A56: la pulizia di una prova sull'emulatore ha cancellato i suoi due eventi e l'app ha tolto gli allarmi alle 22:45. Finché una prova con attese è in corso su un dispositivo, non se ne lancia nessun'altra con lo stesso account.
- **Indirizzi di prova** per gli abbonamenti: serve un server **HTTPS** controllato da noi (sito del progetto o GitHub Pages; l'app rifiuta `http`). Per i test che cambiano il contenuto si ripubblicano due versioni del file e si attende la cache (circa un minuto).

## 5. File di prova da preparare (`tools/emutest/data/ics/`)
`google_export.ics`, `google_public_busy_sintetico.ics` (file **sintetico** con la stessa struttura del feed pubblico di Google: stesso `UID` ripetuto, `RECURRENCE-ID` senza padre, titolo "Busy"; mai dati reali di nessuno), `outlook_fusi_windows.ics`, `apple.ics`, `thunderbird.ics`, `serie_con_eccezioni.ics`, `tuttoilgiorno_piu_giorni.ics`, `con_valarm.ics`, `method_request.ics`, `method_cancel.ics`, `rrule_non_google.ics`, `malformato_troncato.ics`, `non_ical.html`, `vuoto.ics`, `grande_5000.ics`, `doppioni.ics`.

## 6. Test unitari (JVM)
**Tabella 6.1 – Test unitari sulla JVM (U101–U144)**

| ID | Cosa si prova | Modulo |
|---|---|---|
| U101 | Righe ripiegate (CRLF + spazio o tab) e file con soli LF | IcsReader |
| U102 | Escape `\\`, `\;`, `\,`, `\n` nei testi | IcsReader |
| U103 | UTF-8, caratteri fuori dal piano base, BOM iniziale | IcsReader |
| U104 | Parametri tra virgolette con `:` e `;` (es. `ATTENDEE;CN="A:B"`) | IcsReader |
| U105 | Componenti annidati (`VALARM` dentro `VEVENT`) e sconosciuti (`X-...`) | IcsReader |
| U106 | File troncato o `END` mancante: gli eventi completi si leggono, l'errore va nel rapporto | IcsReader |
| U107 | Limiti: riga oltre 64 KB, troppi componenti, annidamento profondo: rifiutati senza eccezioni | IcsReader |
| U108 | File che non è iCalendar (HTML, JSON, binario): errore "non è un calendario" | IcsReader |
| U109 | `DTSTART` in UTC, con `TZID` Olson, floating, `VALUE=DATE` | IcsMapper |
| U110 | `DTEND`, `DURATION`, solo `DTSTART` (durata zero o un giorno) | IcsMapper |
| U111 | Nomi di fuso Windows di Outlook ("W. Europe Standard Time") convertiti in Olson | IcsMapper |
| U112 | `TZID` sconosciuto con e senza `VTIMEZONE`: fuso del telefono e avviso nel rapporto | IcsMapper |
| U113 | `RRULE` valida e non valida (la non valida si scarta e si dichiara), `UNTIL` in UTC e locale, `COUNT` | IcsMapper |
| U114 | `EXDATE` singole e multiple, `DATE` e `DATE-TIME` | IcsMapper |
| U115 | `RECURRENCE-ID` con evento padre: eccezione; `RANGE=THISANDFUTURE`: non supportato, nel rapporto | IcsMapper |
| U116 | `RECURRENCE-ID` **senza** padre: evento singolo con id distinto (caso del feed pubblico di Google) | IcsMapper |
| U117 | 109 eventi con lo stesso `UID` e `RECURRENCE-ID` diversi: 109 righe distinte (copia ridotta del feed `Busy`) | IcsMapper |
| U118 | `STATUS:CANCELLED`: evento saltato; occorrenza annullata di una serie | IcsMapper |
| U119 | `CLASS` PRIVATE o CONFIDENTIAL: visibilità privata; `TRANSP`: disponibilità | IcsMapper |
| U120 | `VALARM` relativo (`-PT15M`, `-P1D`, `+PT0S`), assoluto scartato, `ACTION` AUDIO, DISPLAY, EMAIL | IcsMapper |
| U121 | `ATTENDEE` e `ORGANIZER` non diventano mai invitati; testo in descrizione solo se richiesto | IcsMapper |
| U122 | Campi troppo lunghi troncati ai tetti, caratteri di controllo tolti | IcsMapper |
| U123 | `METHOD` PUBLISH, REQUEST, CANCEL | IcsMapper |
| U124 | `VTODO`, `VJOURNAL`, `VFREEBUSY` contati e ignorati; `RDATE` scartato e dichiarato | IcsMapper |
| U125 | Id deterministici: la stessa riga reimportata dà lo stesso id | IcsMapper |
| U126 | Andata e ritorno con `IcsWriter`: scrivi, leggi, confronta campo per campo (a parte ciò che l'esportazione toglie) | IcsReader + IcsWriter |
| U127 | Corpus di file veri (Google, Outlook, Apple, Thunderbird) con i conteggi attesi | IcsReader + IcsMapper |
| U128 | Sostituzione per differenza: inserisce, aggiorna, cancella; gli invariati non vengono toccati | Subscription sink |
| U129 | Contenuto con lo stesso hash: nessuna scrittura e nessuna notifica ai promemoria | Subscription sink |
| U130 | Errore a metà della sostituzione: resta lo stato precedente (transazione) | Subscription sink |
| U131 | Doppioni in un calendario Google (`iCalUid`): salta, aggiorna, importa come nuovo | Import sink |
| U132 | `EventWriter`: nessuna riga in `outbox` per calendari locali, riga per quelli Google | EventWriter |
| U133 | `CalendarVisibility` per calendari locali e abbonamenti (snooze cancellati, persi segnati) | CalendarVisibility |
| U134 | Schema nuovo: creazione pulita; il salto distruttivo vale solo dalle versioni 1-5; test di migrazione per la prossima versione | Database |
| U135 | Finestra delle istanze (DataStore) azzerata insieme allo schema | Database + AppPreferences |
| U136 | Indirizzi: solo `https`, `webcal` convertito, reindirizzamenti, tetto di dimensione, timeout | IcsFetcher |
| U137 | L'indirizzo segreto non compare in `toString`, nelle eccezioni né nei log (test che cattura `Log`) | IcsFetcher |
| U138 | Aggiornamenti: confronto di versioni (1.0.2 < 2.0.0, 2.0.0-rc < 2.0.0), SHA-256 | UpdateChecker |
| U139 | Secondo fuso: scarto orario e ora legale per fusi diversi, anche a mezz'ora e 45 minuti | SecondZone |
| U140 | Suoni: validazione del file (tipo, dimensione, durata) | SoundImport |
| U141 | Invia log: nessuna email, nessun indirizzo, nessun titolo nel file | LogExporter |
| U142 | Filtro per data: restano gli eventi dal giorno scelto e le serie che continuano dopo; i precedenti no | ImportPlan |
| U143 | Limiti: 10 MB per tutti; 2000 eventi solo verso Google; nessun tetto sul numero verso un calendario locale | ImportPlan |
| U144 | Ritmo delle richieste `IMPORT` nell'`outbox`: pausa tra le chiamate, attesa su 403/429, un 409 vale "già presente" | OutboxPusher |

## 7. Test su dispositivo
Le tabelle sono già predisposte per le cinque piattaforme, in ordine di API (26, 34, A23, 36, A56): ogni cella parte da `—` (da eseguire), `M` (la esegue l'utente) o `NA`. Il A23 esegue gli stessi casi del A56.

### K. Importazione
**Tabella 7.1 – Gruppo K, importazione di file e indirizzi iCal, esito per piattaforma (T101–T123)**

| ID | Caso | Esito atteso | Tipo | API 26 emu | API 34 emu | A23 (API 34) | API 36 emu | A56 (API 36) |
|---|---|---|---|---|---|---|---|---|
| T101 | Apri un `.ics` dal gestore file (`VIEW`) | L'app si apre sul riepilogo; nulla viene importato finché non si conferma | S | — | — | — | — | — |
| T102 | Condividi un `.ics` da un'altra app (`SEND`) | Stesso riepilogo; l'app è nell'elenco di condivisione | S | — | — | — | — | — |
| T103 | Riepilogo: conteggi | Eventi, serie, intervallo di date e componenti ignorati corrispondono al file di prova | A | — | — | — | — | — |
| T104 | Destinazione | Elenco dei calendari scrivibili (Google e locali) e "Nuovo calendario locale"; nessun calendario in sola lettura | A | — | — | — | — | — |
| T105 | Importa in un calendario locale | Eventi in tutte le viste con orari e serie corretti | A | — | — | — | — | — |
| T106 | Importa in un calendario Google (account di prova) | Eventi in coda, sincronizzati; dopo `pm clear` e nuovo accesso sono ancora lì; nessun invito partito | A | — | — | — | — | — |
| T107 | Doppioni: reimporta lo stesso file | Il riepilogo dice quanti sono già presenti; l'azione predefinita è **Salta** (si può scegliere Aggiorna o Importa come nuovo); nessun raddoppio | A | — | — | — | — | — |
| T108 | Serie con eccezioni ed `EXDATE` | Occorrenze giuste; le eccezioni al loro posto | A | — | — | — | — | — |
| T109 | Fusi: `TZID` Olson e nomi Windows di Outlook | Orari esatti nel fuso dell'evento e in quello del telefono | A | — | — | — | — | — |
| T110 | Tutto il giorno e più giorni | Barra nella fascia dei tutto-il-giorno e nel Mese; fine esclusiva rispettata | A | — | — | — | — | — |
| T111 | `VALARM` con l'interruttore del riepilogo | La riga compare solo se il file ha `VALARM` e dice quanti eventi li hanno; **acceso di default** (promemoria del file; eventi senza `VALARM` senza promemoria); spento: predefiniti del calendario | A | — | — | — | — | — |
| T112 | Invitati ignorati | Nessun invitato nell'evento e nessuna riga di invito in `outbox`; nessuna email | A | — | — | — | — | — |
| T113 | File vuoto, malformato, non iCal | Messaggio chiaro, nessun crash, nessun dato scritto | A | — | — | — | — | — |
| T114 | File grande verso un calendario locale | Nessun limite sul numero di eventi (es. 5000, senza ANR); un file oltre 10 MB è rifiutato con un messaggio chiaro | A | — | — | — | — | — |
| T115 | Verso Google oltre 2000 eventi | Il riepilogo dice che il massimo è 2000 e propone di restringere le date o di scegliere un calendario locale; "Importa" resta disattivato finché si supera | A | — | — | — | — | — |
| T116 | Richieste a Google distribuite nel tempo | Un'importazione da centinaia di eventi parte a ritmo regolato: nessun errore di limite (403/429), avanzamento visibile, l'app resta usabile | A | — | — | — | — | — |
| T117 | Filtro per data nel riepilogo | Scelta una data di inizio il conteggio si aggiorna; si importano gli eventi dal giorno scelto in poi (le serie che continuano dopo la data restano) | A | — | — | — | — | — |
| T118 | Annulla dal riepilogo | Non resta niente nel database | A | — | — | — | — | — |
| T119 | `METHOD:REQUEST` e `METHOD:CANCEL` | REQUEST si importa come evento senza risposta di partecipazione; CANCEL è ignorato con un avviso | A | — | — | — | — | — |
| T120 | Importa da indirizzo, una volta | `https` e `webcal://` funzionano, `http` è rifiutato; nulla si ripete | A | — | — | — | — | — |
| T121 | Rotazione e processo ucciso con il riepilogo aperto | Stato conservato o ripartenza pulita; nessun dato a metà | A | — | — | — | — | — |
| T122 | I promemoria degli eventi importati suonano | Un evento fra pochi minuti con `VALARM` fa comparire il popup (e la sveglia, se è una sveglia) | A | — | — | — | — | — |
| T123 | Andata e ritorno: esporta `.ics` e reimporta | Stessi eventi (tranne ciò che l'esportazione toglie: privati, invitati, promemoria) | A | — | — | — | — | — |

### L. Calendari locali
**Tabella 7.2 – Gruppo L, calendari locali, esito per piattaforma (T131–T142)**

| ID | Caso | Esito atteso | Tipo | API 26 emu | API 34 emu | A23 (API 34) | API 36 emu | A56 (API 36) |
|---|---|---|---|---|---|---|---|---|
| T131 | Crea un calendario locale (nome, colore) | Compare nella sezione "Locali" del menu laterale | A | — | — | — | — | — |
| T132 | Crea un evento in un calendario locale | Nessuna operazione in coda (badge di Aggiorna vuoto), nessun traffico di rete | A | — | — | — | — | — |
| T133 | Modifica, eliminazione e ricorrenze di un evento locale | I tre ambiti funzionano come per Google | A | — | — | — | — | — |
| T134 | Promemoria e sveglia di un evento locale | Il popup e la sveglia suonano all'ora giusta | A | — | — | — | — | — |
| T135 | Nascondi e mostra un calendario locale | Regola della 1.0.2: nascosto non suona; rimostrato, i futuri tornano e i persi non suonano | A | — | — | — | — | — |
| T136 | Rinomina e cambia colore | Aggiornati in menu, viste e dettaglio | A | — | — | — | — | — |
| T137 | Elimina un calendario locale | Eventi spariti e nessun allarme residuo (`dumpsys alarm`) | A | — | — | — | — | — |
| T138 | Esportazione e ricerca | L'esportazione include i locali; la ricerca li trova | A | — | — | — | — | — |
| T139 | Senza account Google | Con un calendario locale il `+` crea in locale e non chiede l'account | A | — | — | NA | — | NA |
| T140 | Rimuovere un account Google | I calendari locali restano intatti | A | — | — | — | — | — |
| T141 | Riavvio e processo ucciso | I dati locali ci sono ancora | A | — | — | — | — | — |
| T142 | Installazione sopra la 1.0.x (schema azzerato) | L'app riparte pulita; messaggio chiaro; gli account si riaggiungono e i calendari tornano | S | — | — | — | — | — |

### M. Abbonamenti a calendari pubblici
**Tabella 7.3 – Gruppo M, abbonamenti a calendari pubblici, esito per piattaforma (T151–T168)**

| ID | Caso | Esito atteso | Tipo | API 26 emu | API 34 emu | A23 (API 34) | API 36 emu | A56 (API 36) |
|---|---|---|---|---|---|---|---|---|
| T151 | Aggiungi un abbonamento con un link pubblico | Anteprima con numero di eventi, nome da `X-WR-CALNAME`; calendario in sola lettura nel menu | A | — | — | — | — | — |
| T152 | Feed senza evento padre (`RECURRENCE-ID`) | Il feed `Busy` di prova mostra 109 occorrenze, non una | A | — | — | — | — | — |
| T153 | Sola lettura | Modifica, eliminazione e trascinamento disabilitati; il calendario non è proposto dal `+` | A | — | — | — | — | — |
| T154 | Aggiorna a mano | Si applica la differenza; gli eventi invariati non vengono toccati | A | — | — | — | — | — |
| T155 | Contenuto uguale (stesso hash) | Nessuna scrittura nel database | A | — | — | NA | — | NA |
| T156 | Aggiornamento periodico con l'intervallo ridotto | Parte da solo allo scadere (`cmd jobscheduler run`), solo con rete | A | — | — | NA | — | NA |
| T157 | Rete assente, 404, 500 | I dati vecchi restano; avviso discreto, non un'allerta; riprende da solo | A | — | — | — | — | — |
| T158 | Calendario non più pubblico | Messaggio chiaro "non più disponibile"; i dati restano finché non si rimuove | A | — | — | NA | — | NA |
| T159 | Solo `https` | `http` e reindirizzamento da https a http rifiutati; `webcal://` accettato | A | — | — | NA | — | NA |
| T160 | File oltre il limite e risposta lenta | Rifiutato con messaggio; nessun blocco dell'interfaccia | A | — | — | NA | — | NA |
| T161 | Nascondi e mostra l'abbonamento | Spunta nel menu; nessun promemoria (non ne ha) | A | — | — | — | — | — |
| T162 | Rimuovi l'abbonamento | Calendario ed eventi spariti | A | — | — | — | — | — |
| T163 | L'indirizzo segreto non trapela | Non è in `logcat`, nell'esportazione, nelle schermate d'errore né nel rapporto del dettaglio | A | — | — | NA | — | NA |
| T164 | Evento eliminato o spostato nel feed | Al successivo aggiornamento sparisce o si sposta | A | — | — | NA | — | NA |
| T165 | Feed in UTC e cambio di fuso del telefono | Orari mostrati nel fuso del telefono e seguono il cambio | A | — | — | NA | — | NA |
| T166 | Feed con `RRULE` (non Google) | La serie si espande correttamente | A | — | — | NA | — | NA |
| T167 | Nessun scaricamento senza rete | Il lavoro periodico aspetta la rete (vincolo di WorkManager) | A | — | — | NA | — | NA |
| T168 | Lo stesso indirizzo due volte | Avviso; nessun calendario doppio | A | — | — | NA | — | NA |

### N. Secondo fuso orario
**Tabella 7.4 – Gruppo N, secondo fuso orario, esito per piattaforma (T171–T179)**

| ID | Caso | Esito atteso | Tipo | API 26 emu | API 34 emu | A23 (API 34) | API 36 emu | A56 (API 36) |
|---|---|---|---|---|---|---|---|---|
| T171 | Attiva e scegli il fuso in Impostazioni | Ricerca dei fusi; impostazione salvata | A | — | — | — | — | — |
| T172 | Colonna in Giorno, 6 giorni e Settimana | Seconda colonna oraria con etichetta; allineata alla griglia | A | — | — | — | — | — |
| T173 | Altre viste | Non compare in Programma, Mese, Mese + Programma | A | — | — | — | — | — |
| T174 | Ora esatta della seconda colonna | Scarto corretto rispetto al fuso del telefono | A | — | — | — | — | — |
| T175 | Cambio dell'ora legale | Corretta nei giorni a cavallo del cambio | A | — | — | NA | — | NA |
| T176 | Mezz'ore e 45 minuti | Asia/Kolkata e Asia/Kathmandu: scarti esatti | A | — | — | NA | — | NA |
| T177 | Carattere grande e rotazione | Nessun testo tagliato | A | — | — | — | — | — |
| T178 | Disattiva | La colonna sparisce; scelta ricordata al riavvio | A | — | — | — | — | — |
| T179 | Spagnolo e tedesco | Etichette e testi della funzione tradotti | A | NA | — | — | — | — |

### O. Aggiornamenti in-app
**Tabella 7.5 – Gruppo O, aggiornamenti in-app, esito per piattaforma (T191–T198)**

| ID | Caso | Esito atteso | Tipo | API 26 emu | API 34 emu | A23 (API 34) | API 36 emu | A56 (API 36) |
|---|---|---|---|---|---|---|---|---|
| T191 | Nuova release disponibile | Avviso con versione e note; non invasivo | A | — | — | — | — | — |
| T192 | Nessun aggiornamento | Messaggio "sei aggiornato" | A | — | — | — | — | — |
| T193 | Download con avanzamento e annullamento | L'annullamento ferma e cancella il file parziale | A | — | — | — | — | — |
| T194 | SHA-256 sbagliato | Il file è rifiutato e cancellato; nessuna installazione | A | — | — | NA | — | NA |
| T195 | Installazione | Chiede il permesso "installa app sconosciute" per GGCalendar; l'aggiornamento riesce | S | — | — | — | — | — |
| T196 | Rete assente o GitHub non raggiungibile | Messaggio chiaro; nessun crash | A | — | — | — | — | — |
| T197 | Frequenza del controllo | Non più di una volta al giorno; "non avvisare più" rispettato | A | — | — | NA | — | NA |
| T198 | Dopo l'aggiornamento | I dati restano (`install -r`) e Informazioni mostra la versione nuova | A | — | — | — | — | — |

### P. Suoni, seconda parte
**Tabella 7.6 – Gruppo P, suoni (seconda parte), esito per piattaforma (T211–T218)**

| ID | Caso | Esito atteso | Tipo | API 26 emu | API 34 emu | A23 (API 34) | API 36 emu | A56 (API 36) |
|---|---|---|---|---|---|---|---|---|
| T211 | Scegli un file audio proprio | Copiato nell'app; anteprima; il nome compare nelle Impostazioni | S | — | — | — | — | — |
| T212 | Limiti di dimensione e durata | Un file oltre i limiti è rifiutato con un messaggio chiaro | A | — | — | — | — | — |
| T213 | Suono diverso per promemoria | Scelto per l'evento; suona quello | A | — | — | — | — | — |
| T214 | Volume crescente | Il volume sale nel tempo durante la sveglia | S | — | — | — | — | — |
| T215 | File sparito o corrotto | Ricade sul suono di sistema, poi su quello incluso | A | — | — | — | — | — |
| T216 | Prima dello sblocco dopo un riavvio | Suona il suono incluso (la copia non è leggibile a dispositivo bloccato) | S | — | — | — | — | — |
| T217 | Si cancella l'originale dopo la scelta | L'app usa la sua copia | A | — | — | — | — | — |
| T218 | Pulizia delle copie | Una copia non più usata viene eliminata | A | — | — | — | — | — |

### Q. Batteria limitata e Invia log
**Tabella 7.7 – Gruppo Q, batteria limitata e Invia log, esito per piattaforma (T231–T235)**

| ID | Caso | Esito atteso | Tipo | API 26 emu | API 34 emu | A23 (API 34) | API 36 emu | A56 (API 36) |
|---|---|---|---|---|---|---|---|---|
| T231 | Banner "batteria limitata" | Con l'app in "Limitata" il banner compare in Impostazioni e nel dettaglio di un evento con promemoria | S | NA | — | — | — | — |
| T232 | Il banner sparisce | Tolta la restrizione, il banner non c'è più | S | NA | — | — | — | — |
| T233 | Invia log: foglio di condivisione | File valido, condivisibile | A | — | — | — | — | — |
| T234 | Il log è pulito | Nessuna email, nessun indirizzo di abbonamento, nessun titolo di evento | A | — | — | NA | — | NA |
| T235 | Crash simulato (`am crash`) | Registrato nel file locale; all'avvio dopo si propone di inviarlo | A | — | — | NA | — | NA |

### R. Regressione e lingue
**Tabella 7.8 – Gruppo R, regressione e lingue, esito per piattaforma (T251–T252)**

| ID | Caso | Esito atteso | Tipo | API 26 emu | API 34 emu | A23 (API 34) | API 36 emu | A56 (API 36) |
|---|---|---|---|---|---|---|---|---|
| T251 | Rilancio completo dei casi della V1 (gruppi A-I) | Nessuna regressione: stessi esiti del registro della V1; i casi che non passano si riportano qui con l'id originale | A | — | — | — | — | — |
| T252 | Lingue it, en, es, de nelle schermate nuove | Nessuna stringa mancante o tagliata (giri `tour_*`) | A | — | — | — | — | — |

## 8. Test manuali
**Tabella 8.1 – Test manuali, esito per piattaforma (TM101–TM116)**

| ID | Caso | Esito atteso | API 26 emu | API 34 emu | A23 (API 34) | API 36 emu | A56 (API 36) |
|---|---|---|---|---|---|---|---|
| TM101 | Doppia prova del Doze profondo (telefono fermo, non in carica, circa un'ora) | Popup e sveglia puntuali; `batterystats` mostra l'ingresso in Doze profondo | NA | NA | M | NA | M |
| TM102 | Sveglia sul blocco schermo con PIN, senza tocchi | La schermata a tutto schermo compare sopra il PIN (osservato il 19/10/2026 sul A56 con un test che toccava il telefono) | NA | NA | M | NA | M |
| TM103 | Importa un `.ics` ricevuto davvero per email (Gmail) | Il tocco sull'allegato apre GGCalendar sul riepilogo | NA | NA | M | NA | M |
| TM104 | Importa file veri di Outlook e di Apple (se disponibili) | Orari e serie giusti | NA | NA | M | NA | M |
| TM105 | Abbonamento al calendario pubblico del 19/10/2026 (link dell'utente) | 109 eventi `Busy`; cambia un evento nel calendario sorgente e dopo "Aggiorna" la modifica compare | — | — | M | — | M |
| TM106 | Abbonamento a un calendario privato (indirizzo segreto) | Funziona; il token non compare in schermate, log né esportazioni | — | — | M | — | M |
| TM107 | Aggiornamento in-app su telefono reale | Scarica, verifica e installa la versione successiva; i dati restano | — | — | M | — | M |
| TM108 | Suono da un file reale e riavvio con il PIN non inserito | Prima dello sblocco suona quello incluso; dopo lo sblocco quello scelto | — | — | M | — | M |
| TM109 | Avviso di batteria limitata su Samsung | Mettere l'app in "Limitata" in One UI mostra il banner | NA | — | M | — | M |
| TM110 | Invia log da un crash vero o simulato | Condiviso via Gmail o WhatsApp; il file si legge e non ha dati personali | NA | NA | M | NA | M |
| TM111 | Secondo fuso orario con carattere grande | Nessun testo tagliato; ora corretta | — | — | M | — | M |
| TM112 | Uso quotidiano dei calendari locali (3 giorni) | Eventi, promemoria e sveglie senza sorprese | NA | NA | M | NA | M |
| TM113 | Revisione di spagnolo e tedesco da un madrelingua | Testi giusti e naturali | NA | NA | M | NA | M |
| TM114 | TalkBack nelle schermate nuove (riepilogo importazione, abbonamento) | Ogni elemento ha un nome e un ordine sensato | NA | NA | M | NA | M |
| TM115 | Aggiornamento sopra la 1.0.x sui due Samsung (A23 e A56) | L'app parte; i dati restano o ripartono puliti come descritto in TM116; Informazioni mostra la versione nuova | NA | NA | M | NA | M |
| TM116 | Schema azzerato: messaggio e riaggiunta dell'account | Chi aggiorna sopra la 1.0.x capisce cosa è successo e riaggiunge l'account in pochi passi | — | — | M | — | M |

## 9. Ordine di esecuzione
1. Unitari (U101-U127) a ogni consegna di B1; nessun dispositivo.
2. Emulatori API 34 e 36 per ogni blocco; API 26 per B2-B4 (versione minima) e per i casi senza `NA`.
3. Telefoni A23 (API 34) e A56 (API 36) con il via libera dell'utente, un blocco alla volta (casi `-`), con l'account profgusinu.
4. Prima del rilascio: regressione completa (T251) su tutte le piattaforme e lista manuale TM101-TM116.

## 10. Registro delle esecuzioni
**Tabella 10.1 – Registro delle esecuzioni**

| Data | Piattaforma | Versione | Blocco | Esito | Note |
|---|---|---|---|---|---|
| 19/10/2026 | API 34 (emulatore) | 1.0.2 | calendari nascosti | T66, T67, T68 passano | vedi `test_emulator_e_device.md` |
| 19/10/2026 | API 36 (emulatore) | 1.0.2 | calendari nascosti | T66, T67, T68 passano | prima esecuzione saltata per un difetto dello script (account non riconosciuto), poi rifatta |
