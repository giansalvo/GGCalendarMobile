# GGCalendar: architettura, strumenti, repository e landing page

## Contesto
Si parte da una cartella vuota (`GGCalendarAndroid`). Vogliamo un'app Android di calendario simile a Google Calendar, sincronizzata in entrambe le direzioni con uno o più account Google tramite la Calendar API. Funzioni: eventi con Google Meet e invitati; tre tipi di notifica (popup, sveglia, email); viste giorno, 6 giorni (N configurabile), settimana, mese e programma (agenda); niente vista anno; stampa, invio, condivisione ed esportazione. L'app funziona anche offline. All'inizio si distribuisce come APK tramite una landing page su Altervista (profgusinu.altervista.org/GGCalendarMobile), senza Play Store. Il codice è open source con licenza GPL-3.0. Lo sviluppo avviene su Windows 11 e il dispositivo principale è un Samsung con One UI 8.5 (Android 16).

## Decisioni prese
| Tema | Scelta |
|---|---|
| Stack | Kotlin + Jetpack Compose, nativo |
| Integrazione | Google Calendar API v3 (REST) + cache locale |
| Email al proprietario | Promemoria Google con `method=email` (li invia Google) |
| Lingue | Italiano + inglese |
| MVP | Più account, ricorrenze complete, offline completo; conflitti gestiti come nel desktop (vince Google, la versione locale diventa un evento "(conflitto)") |
| SDK | minSdk 26 (Android 8), target/compile 36 (Android 16) e test sul Samsung personale |
| Repository | `github.com/giansalvo/GGCalendarMobile`, pubblico, GPL-3.0, monorepo `app/` + `site/` |
| Nome / ID | Nome sotto l'icona "GGCalendar", `applicationId` `io.github.giansalvo.ggcalendar` (permanente) |
| Landing | `https://profgusinu.altervista.org/GGCalendarMobile/`, nello stesso sito di `/GGCalendar/` (HTML, stesso stile), con una sola privacy policy e termini estesi ad Android; APK su GitHub Releases |
| OAuth | Si riusa il progetto Google Cloud del desktop (in Production). Il **branding è verificato**. Lo scope `calendar` non è dichiarato in "Accesso ai dati", quindi l'accesso ai dati non è verificato: avviso "app non verificata" e limite di 100 utenti condivisi tra desktop e Android. Azione: dichiarare lo scope e inviare la verifica con il video già pronto (`GGCalendar/Google_verifica_app`). Lo scope non cambia, quindi l'aggiunta dei client Android non dovrebbe richiedere una nuova verifica, ma va controllato. |
| Brand | Logo `img/GGCalendarMobile_logo.jpg`; palette blu, rosso e grigio; icona adattiva semplificata |

Nota su minSdk: l'app dovrà funzionare almeno su Android 16, ma consiglio di supportare da Android 8 in su. Il codice di compatibilità in più è minimo (controlli sui permessi per le API 31, 33 e 34) e il pubblico potenziale passa da pochi punti percentuali a circa il 95% dei dispositivi.

## 1. Architettura
Clean Architecture leggera + MVVM/UDF (ViewModel che espone `StateFlow`), con dependency injection tramite Hilt.

**Moduli Gradle**
- `:app`: navigazione, tema, entry point
- `:core:model`: entità di dominio (Account, Calendar, Event, Attendee, Reminder, Recurrence)
- `:core:database`: Room (vedi sezione 1b)
- `:core:network`: client della Calendar API sottile come `GoogleCalendarClient` del desktop (OkHttp + kotlinx.serialization), senza la libreria Java di Google
- `:core:auth`: Credential Manager (identità) + `AuthorizationClient` di Play Services (scope `calendar`) per ogni account; token di accesso ottenuti in modo silenzioso, senza backend
- `:core:data`: SyncEngine, espansione delle ricorrenze, repository, WorkManager (nel piano iniziale era `:core:sync`)
- `:core:notifications`: scheduler di allarmi, servizio sveglia, canali di notifica
- `:feature:calendar`: le viste
- `:feature:event`: dettaglio ed editor
- `:feature:settings`
- `:feature:share`: esportazione, stampa e invio

### 1b. Modello dati (Room)
Parte dal `CalendarStore` del desktop (SQLite, `events.data` in JSON, `outbox` con `payload` e `base`, `sync_state`), con tre differenze:
- **orari assoluti**: istante UTC + fuso orario per gli eventi con orario, `LocalDate` per quelli di tutto il giorno. Il desktop salva l'ora locale senza fuso, che si rompe quando il telefono cambia fuso;
- **colonne vere** per i campi usati nelle query; restano in JSON (TypeConverter kotlinx.serialization) solo le liste;
- **istanze delle ricorrenze precalcolate** in una tabella, per avere query veloci nelle viste, l'agenda paginata e la programmazione degli allarmi.

| Tabella | Colonne principali | Note |
|---|---|---|
| `accounts` | `email` PK, `display_name`, `photo_url`, `sort_order`, `added_at` | Nessun token salvato: li fornisce `AuthorizationClient` |
| `calendars` | `id` PK, `account_email` (null = locale), `remote_id`, `name`, `color`, `access_role`, `is_primary`, `is_visible`, `default_reminders` JSON, `time_zone`, `sort_order`, `sync_token`, `last_synced_at` | `id` = `email\|googleId` come nel desktop, oppure `local:<uuid>`; `sync_token` qui (nel desktop era in `sync_state`) |
| `events` | `id` PK (UUID locale), `calendar_id` FK con cascade, `remote_id`, `etag`, `ical_uid`, `title`, `location`, `description`, `start_utc`, `end_utc`, `start_day`/`end_day` (epochDay, solo eventi di tutto il giorno, fine esclusa), `all_day`, `time_zone`, `rrule`, `exdates` JSON, `series_master_id`, `original_start`, `is_cancelled`, `color`, `availability`, `visibility`, `meet_link`, `add_meet`, `send_invites`, `guests_can_modify`/`_invite`/`_see_list`, `organizer_email`, `attendees` JSON, `reminders` JSON, `updated_at` | Indici su `calendar_id`, `remote_id`, `series_master_id`, `ical_uid`. `reminders`: `{method: popup\|email\|alarm, minutes, soundUri?}` |
| `instances` | `event_id` FK con cascade, `begin_utc`, `end_utc`, `start_day`, `end_day`, `all_day`, `original_start`; PK (`event_id`, `begin_utc`) | Ricalcolate a ogni upsert di un master o di un'eccezione, per una finestra mobile (−1/+2 anni) che si estende quando l'utente naviga oltre. Gli eventi singoli hanno una sola istanza. Indici su `begin_utc` e (`start_day`, `end_day`) |
| `outbox` | `seq` PK autoincrement, `event_id`, `calendar_id`, `operation` (create, update, delete), `payload` JSON, `base` JSON, `created_at`, `attempts`, `last_error` | Come nel desktop; per le delete, il `payload` contiene il `remote_id` |
| `reminder_fired` | `key` PK (`eventId\|beginUtc\|method\|minutes`), `fire_at` | Evita di ripetere una notifica già mostrata, anche dopo un riavvio (`ReminderState.Fired` del desktop) |
| `reminder_snoozed` | `id` PK, `fire_at`, `event_id`, `begin_utc`, `method`, `minutes` | Posticipi, riprogrammati al riavvio |
| `sync_messages` | `id` PK, `at`, `account_email`, `kind` (errore o conflitto), `text`, `read` | Messaggi del sync, come gli `Errors` e `Conflicts` di `SyncResult`, mostrati in un banner o nel centro messaggi |

- Impostazioni in **DataStore** (non in Room), con le stesse chiavi di `AppSettings` del desktop.
- **Programmazione degli allarmi**: dopo ogni sync o modifica si leggono le istanze delle prossime 48 ore, si calcolano i promemoria dovuti (porting di `ReminderCalculator`) e si registrano in `AlarmManager` con un `PendingIntent` per chiave; un worker giornaliero estende l'orizzonte. All'avvio si recuperano le notifiche perse nelle ultime 24 ore, solo se l'evento non è ancora finito (stessa regola del desktop).
- La tabella `conflicts` prevista in precedenza non serve più: i conflitti diventano eventi "(conflitto)" e un messaggio in `sync_messages`.

**Sincronizzazione (offline-first)**
- Pull: per ogni calendario, `events.list` con `syncToken`, salvato in Room. Se l'API risponde 410 Gone, si rifà la sincronizzazione completa. Si sincronizza all'avvio, ogni 15 minuti con un worker periodico e su richiesta (pull-to-refresh).
- Push: ogni modifica locale scrive subito in Room e accoda una `pending_op` (outbox) con il nuovo stato dell'evento e la versione di partenza. Il worker la invia con `If-Match: <etag>`. Se l'API risponde 412, si confronta la versione remota con quella di partenza, ignorando i cambiamenti fatti da Google stesso (creazione del Meet, risposte degli invitati), come fa `EventContent.Same` nel desktop:
  - se il contenuto non è cambiato, si ritenta con il nuovo etag;
  - se è cambiato, vince Google e la versione locale viene salvata come nuovo evento "Titolo (conflitto)", con un messaggio all'utente (`ResolveConflict` del desktop).
- Gli errori 400/403/404 scartano solo quell'operazione senza bloccare la coda.
- Gli ID locali temporanei vengono sostituiti dagli ID Google dopo l'inserimento.
- Ricorrenze: RRULE/EXDATE espanse in locale con `lib-recur` (dmfs) nella tabella `instances`, per finestre di tempo scorrevoli. Le modifiche "solo questo / questo e successivi / tutti" seguono la semantica di Google: eccezioni con `recurringEventId`, oppure divisione della serie in due.

**Eventi**
- Google Meet: `conferenceData.createRequest` + `conferenceDataVersion=1`.
- Invitati: `attendees[]` + `sendUpdates=all`.
- Tipo di notifica:
  - popup: reminder Google `popup`;
  - email: reminder Google `email`;
  - sveglia: reminder `popup` più `extendedProperties.private.ggAlarm` = elenco dei minuti dei popup che sono sveglie (es. `"10,30"`), così la preferenza si sincronizza tra dispositivi. Va aggiornato anche il desktop C#: legge e scrive `ggAlarm` e usa `KeepLocalOptions` come ripiego quando il campo manca.

**Notifiche**
- Popup: `setExactAndAllowWhileIdle` + notifica ad alta priorità su un canale con suono breve.
- Sveglia: `AlarmManager.setAlarmClock()` (ignora la modalità Doze), poi activity a schermo intero con i pulsanti **Chiudi** e **Posticipa** (5 o 10 minuti, configurabile) e un foreground service che riproduce la suoneria in loop con `AudioAttributes.USAGE_ALARM`. Dopo 120 secondi senza risposta si ferma e lascia una notifica di "sveglia persa".
- Permessi: `USE_EXACT_ALARM` (ammesso per le app di calendario), `POST_NOTIFICATIONS`, `USE_FULL_SCREEN_INTENT` (onboarding guidato su Android 14 e successivi), `RECEIVE_BOOT_COMPLETED`, più una guida per escludere l'app dalla "sospensione" di Samsung.
- Il tipo di foreground service (`systemExempted` o `mediaPlayback`) va verificato in fase di implementazione.
- Gli allarmi vengono riprogrammati dopo ogni sincronizzazione, al riavvio e quando cambia il fuso orario.

**Viste (Compose personalizzato)**
- Griglia oraria condivisa per giorno, N giorni (6 e configurabile) e settimana, con pinch-zoom, eventi sovrapposti e trascinamento per spostarli.
- Griglia mensile a 6 settimane: celle arrotondate, fino a 5 righe di titoli troncati in chip colorati, poi "+N"; eventi di più giorni come barre continue. Niente vista anno.
- Agenda con paginazione infinita (Paging 3 su `instances`).
- Selettore della vista, salto a una data, colori per calendario, visibilità dei calendari per account.
- Calendari iscritti (festività, compleanni, calendari condivisi da altri) letti da `calendarList`. Se l'`accessRole` non consente la modifica sono in sola lettura e l'editor è disabilitato. Google Tasks e le risposte RSVP sono esclusi dall'MVP.

**Condivisione, esportazione, stampa**
- Evento come `.ics` o testo (con il link Meet) tramite FileProvider e Sharesheet.
- Esportazione di un intervallo o di un calendario in `.ics` (scrittore iCalendar proprio) e CSV.
- Stampa: PDF generato con `PdfDocument` e inviato a `PrintManager`. Tre formati: scheda del singolo evento (dettagli, invitati, link Meet), vista corrente (giorno, settimana o mese in A4 orizzontale), agenda per un intervallo di date.
- Condivisione dei calendari tramite l'ACL API (lettore, scrittore).

**Funzioni riprese dal desktop**
- Calendari locali: esistono solo sul telefono e non hanno una `pending_op` verso Google. Un evento si può spostare tra un calendario locale e uno Google.
- Importazione `.ics`: intent filter per `text/calendar` (apri o condividi verso l'app), scelta del calendario di destinazione e riepilogo dell'importazione. Gli eventi già importati vengono riconosciuti tramite `IcsUid`.
- Suoni personalizzati: suono per ogni promemoria (dalle suonerie di sistema o da un file scelto dall'utente, con permesso URI persistente), suono predefinito e volume nelle impostazioni. Per la sveglia si usa un'uscita audio di tipo `USAGE_ALARM`.
- Secondo fuso orario: una seconda colonna oraria nelle viste giorno, N giorni e settimana.
- Impostazioni come il desktop (`AppSettings`): ore di inizio e fine giornata, primo giorno della settimana, numeri delle settimane, weekend nascosti, tema, eventi passati attenuati, valori predefiniti per gli invitati, intervallo di sincronizzazione, vista predefinita, orologio a 24 ore, durata predefinita degli eventi, scatto del trascinamento.
- Esclusa da Android: l'integrazione con Ollama.

**Aggiornamenti in-app**: controllo della GitHub Releases API, download dell'APK e installazione con `PackageInstaller` (`REQUEST_INSTALL_PACKAGES`).

## 2. Strumenti di sviluppo (Windows 11)
- **Android Studio** (ultima stabile) con JDK 21 incluso, emulatori API 26, 34 e 36, plugin GitHub Copilot e plugin Claude Code. Gemini è già integrato.
- **VS Code** con Claude e Copilot, a scelta per scrivere il codice e per il sito. Le build si possono lanciare anche con `gradlew` da terminale.
- Gradle Kotlin DSL + version catalog (`libs.versions.toml`), Kotlin 2.x, Compose BOM, Hilt, Room, WorkManager, Paging 3, Retrofit/OkHttp, kotlinx.serialization, lib-recur, Coil (avatar).
- Qualità: ktlint/detekt, Android Lint, JUnit 5, Turbine, MockWebServer, Compose UI test, Robolectric.
- Git for Windows, `gh` CLI, adb (debug wireless sul Samsung).
- Google Cloud Console: si **riusa il progetto esistente dell'app desktop GGCalendar (C#)**, dove la Calendar API è già attiva. Si aggiungono due client OAuth di tipo *Android* per il pacchetto `io.github.giansalvo.ggcalendar`, uno con lo SHA-1 della chiave debug e uno con quello della chiave release. Il client Desktop del C# resta com'è: non si condivide né il client ID né il secret.
  - Si condividono tra le due app: schermata di consenso (nome, logo, privacy policy, scope), stato di pubblicazione (Testing/Production), utenti di test, limite di 100 utenti per le app non verificate e quote API.

## 3. Progetto GitHub
- Repository pubblico `giansalvo/GGCalendarMobile`: progetto Gradle nella radice (moduli `app/`, `core/`, `feature/`), `site/` (landing), `docs/`, `.github/`, `LICENSE` (GPL-3.0), `README` (IT/EN), `PRIVACY.md`.
- Branch: `main` protetto più branch di funzionalità con pull request e Conventional Commits.
- GitHub Actions:
  - `ci.yml` su ogni PR: lint, detekt, test unitari, `assembleDebug`;
  - `release.yml` sui tag `v*`: `assembleRelease` firmato con il keystore preso dai secret (in base64), APK e SHA-256 allegati alla Release, changelog generato;
  - `site.yml`: deploy via FTP su Altervista quando cambia `site/`.
- Dependabot, template per issue e PR.
- Il keystore di release va conservato fuori dal repository con almeno 2 backup: se si perde, non si possono più pubblicare aggiornamenti.

## 4. Landing page
- Stesso sito del desktop: pagine HTML/CSS statiche in `site/` che riusano `style.css` e lo stile di `GGCalendar/website`, in italiano e inglese. Vengono pubblicate in `/GGCalendarMobile/` su Altervista da GitHub Actions via FTP (`SamKirkland/FTP-Deploy-Action`, credenziali nei secret).
- Sito del desktop (repo `GGCalendar`): link reciproci tra le due app; `privacy.html` e `terms.html` estesi ad Android, cioè dati salvati sul telefono, token gestiti da Google Play Services, permessi Android e revoca dell'accesso. Questa è l'unica privacy policy della schermata di consenso.
- Pagine: home (funzionalità e screenshot), download (ultima release letta dalla GitHub API, con SHA-256), guida all'installazione da origini sconosciute con i passaggi Samsung, FAQ, privacy policy, termini, changelog.
- L'APK si scarica da GitHub Releases, così non si consuma lo spazio Altervista.
- OAuth: la schermata di consenso usa già home page e privacy policy di `/GGCalendar/` su Altervista, con branding verificato.

## 5. Brand e icona
- Partendo da `img/GGCalendarMobile_logo.jpg`: palette Material 3 con il blu dell'intestazione come colore primario e il rosso di "Mobile" come accento; supporto a tema chiaro, scuro e colori dinamici di Android 12 e successivi.
- Icona adattiva vettoriale semplificata (intestazione blu con "GG", anelli, griglia stilizzata) più l'icona monocromatica per le icone a tema.
- Logo completo usato per la splash screen e la landing page.

## 5b. Schermate e navigazione
Decisioni: **menu laterale + FAB** (come Google Calendar Android), **dettaglio evento in un bottom sheet**, **trascinamento degli eventi nell'MVP** (in M2/M3; per le serie chiede l'ambito: solo questo, questo e i successivi, tutti). Navigation Compose con destinazioni tipizzate. Mockup in [mockups.md](mockups.md). Un solo `CalendarViewModel` condiviso tra le viste: tiene data corrente, tipo di vista e calendari visibili.

**Riferimento visivo**: gli screenshot di Google Calendar Android in `img/` (`vista_settimana`, `vista_mese`, `vista_lista`, `menu_laterale_sx`, `vista_dettaglio`, editor). Lo stile segue Material 3 come Google Calendar: tema scuro e chiaro, celle orarie e della vista mese arrotondate, eventi come blocchi o chip arrotondati del colore del calendario, linea dell'ora attuale con un pallino, oggi evidenziato da un cerchio blu, FAB quadrato arrotondato. Il blu primario e il rosso di accento vengono dal logo.
Differenze rispetto a Google Calendar: nessuna icona e nessun calendario Tasks; la vista "3 giorni" diventa "6 giorni"; niente vista anno.

**Mappa delle schermate**
1. **Onboarding** (primo avvio): benvenuto → accesso con Google (si può saltare e usare solo i calendari locali) → permessi guidati (notifiche, schermo intero per la sveglia, esclusione dalla sospensione Samsung) → prima sincronizzazione con avanzamento.
2. **Calendario (home)**:
   - barra in alto come Google Calendar: ☰, "Ottobre ▾" (tocco → mini-calendario del mese, che si apre a tendina, per saltare a una data), 🔍 ricerca, icona "Oggi" con il numero del giorno e avatar dell'account (tocco → cambia account, impostazioni account). Un indicatore discreto segnala la sincronizzazione in corso o fallita;
   - corpo con la vista attiva; si scorre in orizzontale per cambiare periodo;
   - FAB "+" per creare un evento.
3. **Menu laterale** (come `menu_laterale_sx`): titolo GGCalendar; le viste (Programma, Giorno, 6 giorni, Settimana, Mese) con quella attiva evidenziata a pillola; "Aggiorna" (sincronizzazione manuale); per ogni account avatar, email e "Google", poi i suoi calendari con la casella di spunta del colore del calendario; sezione "Locali"; "Nuovo calendario locale", "Importa .ics", Impostazioni, Informazioni.
4. **Dettaglio evento** (bottom sheet che si espande, come `vista_dettaglio`): ✕, ✎ e ⋮ in alto; quadratino colorato + titolo; "Mercoledì 7 ott • 05:00–06:00"; testo della ricorrenza ("Si ripete ogni giorno; per 5 volte"); pulsante "Partecipa con Meet", luogo (apre Maps), promemoria ("Sveglia 1 ora prima"…), invitati, descrizione con link cliccabili. Azioni: modifica, elimina, email agli invitati, condividi (testo o `.ics`), esporta, stampa, duplica.
   Se le notifiche o le sveglie sono bloccate dai permessi, sotto il promemoria compare un avviso, come "Le notifiche sono disattivate…" in Google Calendar, con un link per sistemare.
5. **Editor evento** (bottom sheet a schermo intero, come lo screenshot dell'editor): ✕ e pulsante "Salva" a pillola blu; titolo grande; calendari come chip scorrevoli in orizzontale; tutto il giorno; data e ora di inizio e fine, con fuso orario; ricorrenza (preset + "Personalizzata…"); calendario; Meet (attiva o disattiva, con copia del link); luogo; promemoria (tipo notifica, sveglia o email, anticipo, suono con anteprima); invitati con email da autocompletare dai contatti (permesso facoltativo), flag facoltativo e permessi degli invitati; "Invia inviti"; colore, disponibilità e visibilità; descrizione. Se si esce con modifiche non salvate, chiede conferma.
6. **Ricorrenza personalizzata**: ogni N giorni, settimane, mesi o anni; giorni della settimana; fine mai, a una data o dopo N occorrenze.
7. **Ambito della serie** (dialog): solo questo evento / questo e i successivi / tutti gli eventi.
8. **Sveglia** (activity a schermo intero, anche con lo schermo bloccato): titolo, ora, luogo, pulsanti Chiudi e Posticipa.
9. **Ricerca**: testo libero su titolo, luogo, descrizione e invitati, con risultati in stile agenda.
10. **Impostazioni**, a sezioni: Generali, Calendario, Notifiche, Fusi orari, Account Google (aggiungi, scollega e revoca, risincronizza tutto), Informazioni e aggiornamenti, Privacy.
11. **Stampa ed esportazione**: scelta del formato e dell'intervallo, poi anteprima di stampa di sistema.
12. **Riepilogo importazione `.ics`**.

**Gesti**: scorrimento orizzontale per cambiare periodo; pinch per lo zoom della griglia oraria; tocco su uno spazio vuoto per creare un evento a quell'ora; pressione prolungata su un evento per trascinarlo o ridimensionarlo (con lo scatto delle impostazioni); pull-to-refresh nel Programma.

**Programma** (come `vista_lista`): giorno della settimana e numero a sinistra, con oggi nel cerchio blu; separatori di settimana ("12–18 ott"); card dell'evento larghe e del colore del calendario, con titolo e orario; linea dell'ora attuale tra le card di oggi.

**Da valutare dopo l'MVP**: "Visualizza pianificazioni" dell'editor di Google, cioè la disponibilità degli invitati tramite l'API freeBusy.

## 6. Riuso del progetto desktop (`C:\Users\utente\Desktop\dev\GGCalendar`)
Si portano in Kotlin, mantenendo la stessa logica e gli stessi casi limite:
- `GoogleEventMapper`: date di tutto il giorno ed esclusive, `null` su `date`/`dateTime` nelle PATCH, EXDATE con TZID, ID delle istanze, Meet asincrono, invitati di tipo risorsa esclusi;
- `GoogleCalendarClient`: `calendarList` con `minAccessRole=reader`, `events.list` con `singleEvents=false&showDeleted=true`, `conferenceDataVersion=1`, `sendUpdates`;
- `GoogleSyncService`: prima il push, poi il pull, poi di nuovo il push delle copie "(conflitto)"; master prima delle istanze; token senza eventi locali → sincronizzazione completa; calendari rimossi;
- `EventContent`, `ReminderCalculator`, `OverlapLayout`, `IcsParser`/`IcsWriter`, `RecurrenceRule`/`RecurrenceExpander` (si valuta lib-recur solo se servono regole che queste non coprono).

I test xUnit (`tests/GGCalendar.Core.Tests`) vengono tradotti in JUnit con gli stessi casi.

## Milestone
- **M0** Setup: repository, scheletro multi-modulo, CI, icona e tema, client OAuth Android aggiunti al progetto Google Cloud esistente, privacy policy pubblicata su Altervista, OAuth funzionante sul Samsung.
- **M1** Login con più account, sincronizzazione in lettura, Room, viste agenda e mese.
  - CI verde (run 38097542767).
  - Stato all'11/10/2026: fatto. Moduli `:core:database` (Room v2, schema esportato in `core/database/schemas`) e `:core:data` (sync, ricorrenze con lib-recur, repository, `SyncWorker` ogni 15 minuti, Hilt WorkManager). Programma e Mese verificati sul Samsung con 2 account e 21 calendari; compleanni dei contatti richiesti con `eventTypes`.
  - Semplificazioni di M1 risolte in M2: barre continue nel Mese, Programma che scorre nel passato, mini-calendario a tendina.
  - Compleanni mancanti (es. "Babbo", "Nietta"): indagati in M2, vedi sotto.
- **M2** (completata) Viste giorno, 6 giorni e settimana (sola lettura) e rifinitura delle viste di M1. Dettaglio nella sezione "M2: piano dettagliato".
  - Stato all'11/10/2026: passi 1–12 fatti e provati sul Samsung (settimana uguale a `vista_settimana.jpeg`, Giorno con eventi affiancati e ora mantenuta tra le pagine, Mese, Programma nel passato, mini-calendario, ultima vista riaperta, torta dei compleanni). Pinch provato da te: funziona. Corretto un errore della vista 6 giorni: dopo essere tornati alla settimana corrente la data selezionata diventava il lunedì e 6 giorni mostrava giorni passati; ora, se la pagina contiene oggi, si seleziona oggi. Mini-calendario: scorrendolo, titolo e vista seguono il mese; l'intestazione apre la griglia degli anni (salto diretto, richiesto per il Mese). Navigazione a 3 anni provata (novembre 2029, ricorrenze presenti). Cambio di fuso orario provato da te (Tokyo con app aperta, New York con app chiusa, ritorno a Roma): ok.
  - **M2 completata (11/10/2026)**.
  - Compleanni: l'API restituisce solo i compleanni salvati come eventi `eventType=birthday` nel calendario principale (3 sul tuo account). Il calendario dei contatti (`addressbook#contacts@...`) non è più in `calendarList`; Google Calendar per Android legge gli altri dalla **rubrica del telefono**. Soluzione proposta: calendario locale virtuale "Compleanni" letto da `ContactsContract` (`Event.TYPE_BIRTHDAY`, anche senza anno), con permesso facoltativo `READ_CONTACTS`: nessuno scope Google in più, quindi nessuna nuova verifica OAuth; si evitano i doppioni con i compleanni già arrivati da Google. **Spostato in M6** (deciso l'11/10/2026), insieme ai calendari locali.
- **M3** (completata) Editor: creazione, modifica, eliminazione, invitati, Meet, ricorrenze, outbox offline, conflitti; trascinamento degli eventi (spostare e ridimensionare) nelle griglie orarie, spostato qui da M2 perché richiede la scrittura su Google. Dettaglio nella sezione "M3: piano dettagliato".
  - Stato all'11/10/2026: passi 1–12 fatti. Provati sul Samsung: creazione, modifica ed eliminazione nei due sensi (dall'utente); spostamento tra account; trascinamento e ridimensionamento; modifica offline in modalità aereo con invio automatico al ritorno della rete; ricorrenza personalizzata; tendina dei calendari con l'account. Le prove si fanno solo su eventi creati apposta, nel calendario principale di profgusinu@gmail.com.
  - Corretto durante le prove: Google rifiuta `ggAlarm: null` (400 "Required"), ora si manda una stringa vuota; dopo un rifiuto definitivo il calendario si risincronizza da zero, così il telefono torna alla versione di Google.
  - Provati anche (11/10/2026, eventi di prova nella settimana 5–11 ottobre): serie "ogni giorno, 7 volte" con modifica ed eliminazione per solo questo / questo e i successivi / tutti, anche col trascinamento; invito ricevuto da giansalvo.gusinu@itiangioy.org; conflitto (titolo cambiato offline sul telefono, orario cambiato sul web): resta la versione di Google più la copia "(conflitto)". Nota: spostando l'orario di tutta la serie Google annulla le eccezioni, come sul web.
  - Miglioramenti chiesti durante le prove: tocco sulla griglia che apre subito l'editor; tendina dei calendari con l'account; elenco dei contatti scorrevole dall'icona degli invitati; suggerimenti senza perdita del testo; editor sopra la barra di navigazione; spazio sotto l'ultima ora; messaggi di conflitto ed errore che restano finché l'utente non li chiude.
  - Meet provato: il link arriva subito dopo il salvataggio, con il pulsante "Partecipa con Google Meet" anche nella copia dell'invitato. Corretto: le copie degli inviti (organizzatore diverso, invitati senza permesso di modifica) sono in sola lettura.
  - **M3 completata (11/10/2026)**, CI verde (run 38123898234, con i test Robolectric).
  - Nota per la build su questo PC: l'antivirus ispeziona HTTPS, quindi Gradle scarica le dipendenze nuove solo con `-Djavax.net.ssl.trustStoreType=Windows-ROOT` (i test Robolectric lo impostano da soli su Windows).
- **M4** Notifiche: popup, sveglia (durata impostabile da 1 a 10 minuti, predefinita 2), promemoria email, riavvio, onboarding dei permessi. Dettaglio nella sezione "M4: piano dettagliato".
  - Aggiunta dell'11/10/2026: vista **"Sveglie attive"** (voce nel menu laterale, sotto "Notifiche e sveglie"): elenco delle sveglie che suoneranno nei prossimi 60 giorni, in ordine di ora, con data e ora in cui suonano, titolo dell'evento, quanti minuti prima dell'evento sono, **tempo rimanente** (aggiornato ogni 15 secondi) ed etichetta "Posticipata" per i posticipi. Il tocco apre il dettaglio dell'evento. Include solo i promemoria di tipo Sveglia non ancora suonati e i posticipi di sveglie. Ogni riga ha un pulsante per la **chiusura anticipata** (con conferma): la sveglia non suona più, viene tolta da AlarmManager e l'evento resta nel calendario; vale anche per un posticipo. Fatto e provato sul Samsung.
  - Stato all'11/10/2026 (in corso): passi 1-5 e 7 fatti, CI verde fino al commit 98db86f. Provati sul Samsung: popup all'ora giusta; sveglia con suoneria in loop come allarme di sistema (`setAlarmClock` + servizio in primo piano di tipo `systemExempted`); schermata a tutto schermo che si apre anche a telefono in uso e a schermo spento (titolo, ora, luogo, descrizione, scelta del posticipo 5/10/15/30/60 minuti, "Ferma la sveglia", testi grandi e centrati, "si ferma da sola dopo N minuti"); posticipo che torna a suonare all'ora giusta; due sveglie insieme in coda; schermata "Notifiche e sveglie" dal menu laterale (stato dei permessi, durata da 1 a 10 minuti); avviso nel dettaglio dell'evento se le notifiche sono bloccate; tocco sulla notifica che apre il dettaglio, anche di un calendario nascosto. 13 test automatici (calcolo dei promemoria, recupero dei persi, posticipo, riprogrammazione con AlarmManager).
  - Provati il 11/10/2026 sul Samsung: sveglia con app chiusa (processo ucciso, come dalle recenti) che suona comunque; dopo 2 minuti senza risposta la suoneria si ferma e resta la notifica "Sveglia persa" (sulla schermata di blocco di questo telefono compare solo l'icona, perché è impostato "solo icone" per le notifiche: è una scelta di Android/Samsung, non dell'app).
  - **Provati il 12/10/2026 sul Samsung** con l'evento "Prova_due_account" (organizzatore profgusinu, invitato itiangioy, Meet, promemoria email 15, notifica 10, sveglia 5): due copie in due calendari, ognuna visibile nel suo (rossa e azzurra nella settimana); un solo popup (identico a quello predefinito di itiangioy) e una sola sveglia, entrambi dalla copia dell'organizzatore; i promemoria dell'organizzatore non finiscono sul calendario dell'invitato (la sveglia vive in una proprietà privata della sua copia); email di promemoria arrivata a profgusinu alle 00:30; tocco sulla notifica che apre il dettaglio dell'evento; "Partecipa con Meet" che apre il link (con più account Google Meet chiede l'account: scelta interattiva voluta, nessun `authuser` aggiunto); una sola sveglia suonata e fermata. Eliminando l'evento sull'organizzatore sparisce anche la copia dell'invitato.
  - **Ancora da fare per chiudere M4**: (1) Doze profondo vero (telefono fermo e non in carica, circa un'ora); (2) soluzione 7, guida e avviso per le restrizioni del produttore; poi CI verde e chiusura.
- **M4bis** Vista "Mese + Programma" e APK di prova da condividere (anche via WhatsApp) prima della pubblicazione ufficiale, più la scheda informativa dell'app (autore, sito, versione 0.4.0 e data di build). Dettaglio nella sezione "M4bis: piano dettagliato".
- **M5** Condivisione, esportazione `.ics`/CSV, stampa PDF, ACL dei calendari.
- **M6** Impostazioni, calendari locali, calendario "Compleanni" dalla rubrica del telefono (`READ_CONTACTS`, vedi M2), importazione `.ics`, suoni personalizzati, secondo fuso orario, rifinitura i18n, aggiornamenti in-app; aggiornamento del desktop C# per `ggAlarm`.
- **M7** Landing page completa e release v1.0. La privacy policy estesa ad Android si pubblica già in M0; la verifica dello scope `calendar` si invia in parallelo, prima possibile.

## M0: passi operativi
Stato del PC (verificato l'11/10/2026): Git c'è; mancano Android Studio, Android SDK, JDK, adb e `gh`.

**A. Da fare tu (servono il tuo account o installazioni con interfaccia grafica)**
1. Installare Android Studio (ultima stabile) da winget (`winget install Google.AndroidStudio`) o dal sito, completare il setup wizard (SDK, emulatore) e aggiungere i plugin GitHub Copilot e Claude Code.
2. Installare `gh` (`winget install GitHub.cli`) ed eseguire `gh auth login`.
3. Sul Samsung: Opzioni sviluppatore → Debug USB/wireless; collegare il telefono ad Android Studio.
4. Google Cloud (progetto GGCalendar): **inviare subito la verifica dello scope `calendar`** con la giustificazione già preparata. Dopo il passo B6, creare i 2 client OAuth *Android* (pacchetto `io.github.giansalvo.ggcalendar` + SHA-1 di debug e di release).
5. Altervista: recuperare le credenziali FTP (servono più avanti per i secret di GitHub).

**B. Da fare io (dopo la tua approvazione)**
1. `git init` in `GGCalendarAndroid`, `.gitignore` per Android, `LICENSE` GPL-3.0 (stesso testo e stessi termini aggiuntivi su nome e logo del desktop), `README` IT/EN.
2. Scheletro Gradle (Kotlin DSL + `libs.versions.toml`) con i moduli `:app`, `:core:*`, `:feature:*`; minSdk 26, target 36; Compose, Hilt, Room, WorkManager.
3. Tema Material 3 (blu e rosso del logo, chiaro, scuro, colori dinamici), icona adattiva semplificata e splash screen dal logo.
4. Schermata segnaposto con il menu laterale e le 5 viste vuote, e navigazione funzionante.
5. Workflow GitHub `ci.yml` (lint, test, `assembleDebug`). Creazione del repo `giansalvo/GGCalendarMobile` con `gh` e primo push, solo dopo tua conferma.
6. Generazione del keystore di release (te lo consegno da conservare con 2 backup) e lettura degli SHA-1 di debug e di release per il passo A4.
7. `:core:auth`: login con un account Google tramite AuthorizationClient e una chiamata di prova a `calendarList`, per dimostrare che OAuth funziona sul Samsung.
8. Bozza delle modifiche al sito del desktop (privacy e termini estesi ad Android, link a `/GGCalendarMobile/`) nel repo `GGCalendar`, senza pubblicarle finché non le approvi.

**Stato al 11/10/2026**:
- Fatto: B1 (git locale, `.gitignore`, `.gitattributes`, LICENSE, README); B2 (moduli `:app`, `:core:model`, `:core:network`, `:core:auth`, `:feature:calendar`; gli altri moduli arrivano con le rispettive milestone); B3 (tema, icona, splash); B4 (menu laterale, 5 viste segnaposto, "Aggiungi account Google" → elenco dei calendari); B5 (solo `ci.yml`, il repo GitHub non è ancora creato); B8 (bozza nel branch locale `bozza-android-privacy` del repo `GGCalendar`, non committata).
- Fatto anche: sync di Android Studio (JVM di Gradle 21); `test`, `lint`, `assembleDebug` e `assembleRelease` riusciti; B6 (keystore di release in `Documenti\GGCalendar-keystore`, fuori dal repo, `keystore.properties` ignorato da git).
- SHA-1 per i client OAuth Android: debug `8B:62:73:8B:07:AE:E5:51:EC:AF:6D:79:7D:B4:0C:04:9D:26:AE:B0`, release `22:40:D7:B5:CB:C0:CC:36:88:24:70:9F:8E:F2:31:B4:A5:AA:1F:07`.
- Fatto anche: A4 (2 client OAuth Android creati); B7: login riuscito sul Samsung (11/10/2026), con consenso di GGCalendar ed elenco dei calendari.
- **M0 completata (11/10/2026)**: repo pubblico `giansalvo/GGCalendarMobile` creato, push di `main`, CI verde (run 38095758963).

**Criterio di completamento di M0**: l'APK di debug si installa sul Samsung, il login Google mostra la schermata di consenso di GGCalendar e l'app elenca i calendari dell'account; la CI su GitHub è verde.

## M2: piano dettagliato
**Obiettivo**: le viste Giorno, 6 giorni e Settimana a griglia oraria, come `img/vista_settimana.jpeg`, in sola lettura, più le rifiniture rimandate da M1. Nessuna scrittura su Google: il trascinamento è in M3.

**Decisioni**
- Griglia di 24 ore scorrevole in verticale, come Google Calendar. All'apertura si posiziona sull'ora attuale meno un'ora se il periodo contiene oggi, altrimenti sull'ora d'inizio giornata (7:00, impostazione `dayStartHour`).
- Tocco su uno spazio vuoto: compare un segnaposto "(Nuovo evento)" di un'ora, agganciato al quarto d'ora; in M3 aprirà l'editor con data e ora precompilate.
- Tocco su un evento: per ora nessuna azione; il dettaglio arriva in M3.
- Paginazione orizzontale: la settimana parte dal primo giorno della settimana del locale (lunedì in Italia); Giorno e 6 giorni partono dalla data selezionata e avanzano di 1 o 6 giorni, come i "3 giorni" di Google.

**1. Componente `TimeGrid`** (`feature/calendar`, condiviso dalle tre viste, parametri `startDate` e `dayCount` = 1, 6 o 7)
- Intestazione dei giorni: nome breve e numero, oggi nel cerchio blu. Il tocco su un giorno apre la vista Giorno.
- Fascia "tutto il giorno": eventi di tutto il giorno e di più giorni come barre continue tra le colonne, con corsie assegnate come nel Mese; al massimo 2 righe più "+N", con espansione al tocco.
- Area oraria: colonna delle ore (48 dp) e celle orarie arrotondate come nello screenshot. Ogni evento è un blocco arrotondato del colore del calendario, con il titolo che va a capo e l'orario se c'è spazio; altezza minima di 18 dp per gli eventi brevi.
- Eventi sovrapposti affiancati: porting di `OverlapLayout` del desktop, con i suoi test.
- Eventi con orario che attraversano la mezzanotte: divisi tra i giorni.
- Linea dell'ora attuale con il pallino, aggiornata ogni minuto; eventi passati attenuati (`dimPast`, attivo di default come nel desktop).
- Zoom con il pinch sull'altezza dell'ora (da 32 a 120 dp, default 48 come nel desktop), mantenuto tra le pagine e salvato nelle preferenze.
- Layout Compose personalizzato (`Layout` con misure calcolate), senza una lista per colonna; scroll verticale condiviso tra le pagine, così scorrendo di lato l'ora resta la stessa.

**2. Rifiniture delle viste di M1**
- Mese: gli eventi di più giorni diventano barre continue all'interno di ogni settimana (corsie stabili), con "+N" calcolato per giorno.
- Programma: scorrimento infinito anche verso il passato. Il periodo caricato si estende di qualche mese quando ci si avvicina a un bordo e la lista si apre sulla data selezionata.
- Titolo del mese: un mini-calendario a tendina sotto la barra in alto (scorrevole tra i mesi, con un pallino nei giorni con eventi) sostituisce il DatePicker.
- Icona della torta per i compleanni (`eventType` = `birthday`): nuova colonna `event_type` con migrazione v3, che azzera anche i `syncToken` per riscaricare i tipi.
- Indagine sui compleanni mancanti ("Babbo", "Nietta"): confronto con la risposta grezza dell'API; possibili cause sono i contatti senza anno di nascita e i compleanni da "Altri contatti". Se l'API non li espone, lo si documenta come limite noto.

**3. Dati e ciclo di vita**
- Finestra delle istanze mobile: se l'utente naviga oltre −1/+2 anni, il repository estende la finestra e ricalcola le serie; la finestra si sposta anche con il passare del tempo, al primo avvio di ogni giorno.
- Cambio di fuso orario del telefono (`ACTION_TIMEZONE_CHANGED`): ricalcolo delle istanze, perché i giorni dipendono dal fuso.
- Preferenze minime in DataStore, senza schermata, che arriva in M6: ultima vista usata (riaperta all'avvio), `dayStartHour`, altezza dell'ora, `dimPast`.

**4. Ordine di lavoro**
1. Porting di `OverlapLayout` e test (unit test JVM).
2. `TimeGrid` statica per una settimana: intestazione, ore, celle, eventi posizionati.
3. Fascia "tutto il giorno" con le barre continue (logica delle corsie condivisa con il Mese), con test.
4. Paginazione e viste Giorno e 6 giorni; navigazione dal Mese e dalle intestazioni al Giorno.
5. Linea dell'ora attuale, eventi passati attenuati, scroll iniziale.
6. Zoom con il pinch e segnaposto del tocco su uno spazio vuoto.
7. Barre continue nel Mese.
8. Programma con scorrimento infinito.
9. Mini-calendario a tendina.
10. Finestra delle istanze mobile e cambio di fuso.
11. Compleanni: colonna `event_type`, migrazione v3, icona, indagine.
12. Preferenze in DataStore (ultima vista, zoom).
13. Verifica e commit.

**Note di implementazione**
- Logica pura e testata in `GridLayout.kt` (`OverlapLayout`, `Lanes.assign/fit`, `weekStart`, `minutesOn`, `barSpans`); viste in `TimeGridView.kt` (griglia), `MonthView.kt` (settimane con barre sovrapposte alle celle), `MiniCalendar.kt`.
- Eventi di 24 ore o più vanno nella fascia "tutto il giorno"; nel Mese ogni evento che copre più giorni è una barra.
- Finestra delle istanze: `InstanceWindows.plan` (testata) decide se ricalcolare niente, solo le serie (nuovo giorno o periodo fuori finestra, con 180 giorni di margine) o tutto (nuovo fuso). La usa `SyncEngine.updateWindow`, chiamata da ogni query delle viste, all'avvio e da `TimeZoneReceiver`.
- Preferenze in DataStore (`AppPreferences`, in `:core:data`): ultima vista, altezza dell'ora, ora d'inizio giornata, eventi passati attenuati, finestra delle istanze.
- Room v3: colonna `event_type`, `MIGRATION_2_3` azzera i `syncToken`.

**5. Verifica di M2**
- Unit test: `OverlapLayout` (gli stessi casi del desktop), corsie delle barre multi-giorno, inizio della settimana per locale, divisione degli eventi a mezzanotte, estensione della finestra delle istanze, migrazione v3.
- Sul Samsung, confronto con gli screenshot di Google in `img/`: settimana dell'11 ottobre contro `vista_settimana.jpeg` (stessi blocchi negli stessi orari e affiancati quando si sovrappongono), Mese con barre continue, Programma che scorre nel passato.
- Prove manuali: pinch, scorrimento tra le settimane con l'ora mantenuta, cambio del fuso orario nelle impostazioni del telefono, navigazione a 3 anni di distanza.
- CI verde.

## M3: piano dettagliato
**Obiettivo**: dal telefono si creano, modificano ed eliminano eventi (anche ricorrenti), con invitati, Meet, promemoria e fuso orario. Le modifiche funzionano offline e si sincronizzano con Google. I conflitti si gestiscono come nel desktop. Gli eventi si trascinano nelle griglie orarie. Logica portata da `GoogleSyncService.PushAsync`, `ResolveConflict`, `EventContent`, `GoogleEventMapper.ToGoogle` e `MainViewModel.SaveOccurrence/DeleteOccurrence/MoveOccurrence` del desktop.

**Decisioni (11/10/2026)**
- Invitati: suggerimenti dagli indirizzi già presenti negli eventi più la **rubrica del telefono** (`READ_CONTACTS`, chiesto solo quando si tocca il campo invitati; se negato restano gli indirizzi già visti). Lo stesso permesso servirà in M6 per i compleanni.
- Trascinamento solo nelle griglie orarie (Giorno, 6 giorni, Settimana): pressione prolungata, spostamento anche su un altro giorno, ridimensionamento dal bordo inferiore, scatto di 15 minuti; per le serie chiede l'ambito.
- Fuso orario: per gli eventi con orario si mostra il fuso dell'evento (di default quello del telefono) e lo si può cambiare da una lista ricercabile.
- Dettaglio come bottom sheet ed editor come bottom sheet a schermo intero (mockup 5 e 6); eliminazione con conferma, come Google e il desktop.
- Si modificano solo i calendari Google con ruolo `owner` o `writer`; gli altri aprono il dettaglio in sola lettura. I calendari locali arrivano in M6.
- Promemoria: tipo Notifica, Sveglia (popup + `ggAlarm`) o Email, con l'anticipo. Il suono per promemoria arriva in M6; la sveglia che suona arriva in M4.

**1. Rete** (`:core:network`)
- `GoogleCalendarApi`: `insertEvent`, `patchEvent` (con `If-Match: etag`), `deleteEvent`, `getEvent`, sempre con `conferenceDataVersion=1` e `sendUpdates=all|none`.
- `GoogleEventMapper.toGoogle(event)`: porting di `ToGoogle`. Nelle PATCH la forma di data non usata si manda come `null`. Si mandano RRULE ed EXDATE, `reminders.useDefault=false` più gli override (la sveglia diventa un popup), `extendedProperties.private.ggAlarm` con i minuti delle sveglie, `conferenceData.createRequest` se si aggiunge il Meet, e il fuso IANA dell'evento.
- `instanceId(masterRemoteId, originalStart, allDay)` per modificare un'occorrenza.
- Test con MockWebServer: corpo delle richieste, `If-Match`, 412, 404, 410.

**2. Outbox e push** (`:core:database`, `:core:data`)
- Room v4: tabella `outbox` (`seq`, `event_id`, `calendar_id`, `operation` create/update/delete, `payload` = istantanea JSON dell'evento, `base` = versione Google da cui parte la modifica, `created_at`, `attempts`, `last_error`), più `MIGRATION_3_4`.
- `EventWriter`: ogni modifica scrive subito in Room, ricalcola le istanze, accoda l'operazione e chiede un sync immediato (`SyncScheduler.syncNow`, con vincolo di rete: offline parte quando torna la connessione).
- `SyncEngine`: prima il push, poi il pull, poi di nuovo il push delle copie "(conflitto)", come il desktop. Durante il pull gli eventi con operazioni in coda non si sovrascrivono.
- 412: si confronta la versione remota con `base` tramite `EventContent.same`, che ignora Meet, risposte degli invitati, promemoria e il proprietario. Se sono uguali si ritenta con il nuovo etag. Altrimenti vince Google e la versione locale diventa "Titolo (conflitto)", senza invitati né Meet, con un messaggio di tipo `conflict` (snackbar).
- 400, 403 e 404 scartano l'operazione con un messaggio, senza bloccare la coda. Gli errori di rete lasciano la coda com'è.
- Cambio di calendario: nello stesso account si usa `events.move`, tra account diversi si inserisce nel nuovo calendario e si elimina dal vecchio.
- Test JVM: `EventContent`, `ResolveConflict`, ordine della coda, sostituzione degli ID temporanei.

**3. Dettaglio evento** (`:feature:event`, nuovo modulo)
- Bottom sheet aperto dal tocco su un evento in ogni vista: titolo con il colore, data e ora (con il fuso se diverso da quello del telefono), testo della ricorrenza ("Si ripete ogni giorno; per 5 volte"), pulsante "Partecipa con Google Meet", luogo (apre Maps), promemoria, invitati con le risposte, descrizione con link cliccabili, calendario e account.
- Azioni: ✎ modifica, ⋮ elimina e duplica. Email agli invitati, condividi, esporta e stampa arrivano in M5.

**4. Editor** (`:feature:event`)
- Bottom sheet a schermo intero (mockup 6): titolo; calendario come chip scorrevoli (solo quelli modificabili); tutto il giorno; date e ore di inizio e fine (la fine segue l'inizio mantenendo la durata); fuso orario; ricorrenza; invitati con "Invia inviti" e permessi; Meet; luogo; promemoria; colore (palette Google dei `colorId`); disponibilità e visibilità; descrizione.
- Si apre dal FAB (prossima ora piena, durata di un'ora come nel desktop), dal segnaposto "(Nuovo evento)" delle griglie e da ✎ del dettaglio. Se si esce con modifiche non salvate chiede conferma.
- Ricorrenza: preset (non si ripete, ogni giorno, ogni settimana di \<giorno\>, ogni mese il \<n\>, ogni anno, giorni feriali) più "Personalizzata…" (ogni N giorni, settimane, mesi o anni; giorni della settimana; fine mai, a una data o dopo N volte). La RRULE si costruisce e si legge con un piccolo `RecurrenceRule` portato dal desktop, testato; l'espansione resta con lib-recur.
- Ambito della serie (mockup 8): solo questo evento (eccezione con l'ID dell'istanza Google), questo e i successivi (la serie si divide: UNTIL nella vecchia, nuova serie dalla data scelta), tutti gli eventi (si sposta anche ogni EXDATE ed eccezione). Vale per modifica, eliminazione e trascinamento.
- Invitati: campo con suggerimenti (indirizzi già visti più rubrica), chip con "facoltativo", controllo del formato email.

**5. Trascinamento** (griglie orarie)
- Pressione prolungata su un evento: compare in rilievo e segue il dito, scattando ogni 15 minuti; spostandosi sopra un'altra colonna cambia giorno. La maniglia sul bordo inferiore cambia la durata.
- Al rilascio: per le serie si mostra il dialog dell'ambito, poi si salva tramite `EventWriter`. Gli eventi in sola lettura non si trascinano.
- Le pagine non scorrono durante il trascinamento. Lo scorrimento automatico vicino ai bordi è facoltativo, da valutare.

**6. Ordine di lavoro** (commit e prova sul Samsung a ogni blocco)
1. Rete: `toGoogle`, chiamate di scrittura, test MockWebServer.
2. Outbox (Room v4), `EventWriter`, push nel `SyncEngine`, `EventContent` e conflitti, test.
3. Modulo `:feature:event` e dettaglio in sola lettura, aperto da tutte le viste.
4. Editor per gli eventi singoli: creazione, modifica, eliminazione, cambio di calendario.
5. Promemoria (notifica, sveglia, email) e colore.
6. Fuso orario con la lista ricercabile.
7. Ricorrenze: preset, personalizzata, testo leggibile, `RecurrenceRule` con test.
8. Ambiti della serie per modifica ed eliminazione.
9. Invitati: indirizzi già visti più rubrica (`READ_CONTACTS`), invia inviti, permessi degli invitati.
10. Meet: aggiunta, stato "in creazione", copia del link.
11. Trascinamento e ridimensionamento nelle griglie.
12. Messaggi di conflitto ed errore (snackbar), stato della coda.
13. Verifica e commit.

**7. Verifica di M3**
- Unit test: mapper verso Google, outbox, conflitti (412 con contenuto uguale o diverso), ambiti della serie (stessi casi del desktop), `RecurrenceRule`, migrazione v4.
- Sul Samsung, insieme a calendar.google.com:
  - evento creato sul telefono che compare sul web, e viceversa;
  - modifica e eliminazione nelle due direzioni;
  - serie con "solo questo", "questo e i successivi", "tutti";
  - Meet creato e link copiato;
  - invito ricevuto da un altro account;
  - sveglia registrata (`ggAlarm`) e letta dal pull;
  - evento a New York creato con il fuso di New York;
  - trascinamento con e senza serie.
- Offline: modalità aereo, modifica, modifica dello stesso evento sul web, riconnessione. Risultato atteso: la versione di Google e la copia "(conflitto)".
- CI verde.

## M4: piano dettagliato
**Obiettivo**: i promemoria degli eventi suonano sul telefono all'ora giusta, anche a schermo spento, in Doze, dopo un riavvio e con l'app chiusa. Tre tipi: notifica popup con suono breve; sveglia con suoneria in loop (al massimo 2 minuti) e i pulsanti Chiudi e Posticipa; email, che invia Google. Logica portata da `ReminderCalculator` e `ReminderScheduler` del desktop: calcolo dei promemoria dovuti, quelli già mostrati, quelli posticipati e il recupero di quelli persi.

**Decisioni (11/10/2026)**
- Notifiche per **tutti i calendari**, anche quelli nascosti nel menu laterale: nascondere serve a pulire la vista.
- **Posticipa**: 10 minuti, con un menu per 5, 15 o 30. Nella notifica popup c'è l'azione "Posticipa 10 min". Il valore predefinito diventa un'impostazione in M6.
- **Sveglia** come l'app Orologio: a telefono bloccato schermo intero (anche a schermo spento); a telefono sbloccato una notifica in primo piano con Chiudi e Posticipa. In entrambi i casi la suoneria va in loop per la durata impostata, poi si ferma e lascia una notifica di "sveglia persa". Toccando la notifica si apre lo schermo intero.
- **Durata della sveglia**: parametro da 1 a 10 minuti, predefinito 2 (richiesta dell'11/10/2026). Si salva in DataStore e si regola nella schermata "Notifiche e sveglie"; in M6 entra nelle Impostazioni.
- **Un evento presente in due account** (un invito tra profgusinu e itiangioy): ogni copia nel suo calendario, con i suoi promemoria. Decisione del 12/10/2026: suonano **tutti** i promemoria di entrambe le copie, e si registra uno solo quando due sono identici (stessa occorrenza, stesso tipo e stessa ora, riconosciuti da iCalUID e inizio; per quelli identici si mostra la copia dell'organizzatore). Regola precedente (una sola copia, quella con la sveglia) scartata: perdeva i promemoria personali dell'altro account. Nascondere un calendario non toglie i suoi promemoria; ne dipendono solo dall'account presente.
- I promemoria "calendario predefinito" (reminders null) usano i promemoria predefiniti del calendario, come Google.
- Suoni: la notifica usa il suono predefinito del canale, la sveglia la suoneria della sveglia di sistema. I suoni per promemoria arrivano in M6.

**1. Calcolo** (`:core:data`, logica pura testata)
- Porting di `ReminderCalculator.Due`: per ogni istanza dalla tabella `instances` (quindi già con eccezioni ed EXDATE), ogni promemoria non email dà un orario `inizio − minuti`; sono dovuti quelli in un intervallo `(dopo, fino a]` il cui evento non è ancora finito.
- Chiave del promemoria: `eventId|beginUtc|metodo|minuti`, come nel desktop. Eliminazione dei soli promemoria identici tra account (iCalUID, inizio, tipo, ora).
- Per gli eventi di tutto il giorno "N minuti prima" si conta dalla mezzanotte locale, come Google.

**2. Programmazione** (`:core:notifications`, nuovo modulo)
- Si registrano in `AlarmManager` i promemoria delle prossime 48 ore: `setAlarmClock` per le sveglie (ignora Doze e mostra l'icona della sveglia), `setExactAndAllowWhileIdle` per le notifiche. Un `PendingIntent` per chiave; quelli non più dovuti si cancellano.
- Si riprogramma dopo ogni sync e ogni modifica locale, all'avvio, al riavvio (`BOOT_COMPLETED`), all'aggiornamento dell'app (`MY_PACKAGE_REPLACED`), al cambio di ora o di fuso (`TIME_SET`, `TIMEZONE_CHANGED`) e con un worker giornaliero che sposta l'orizzonte.
- Room v5: tabelle `reminder_fired` (chiave, ora) per non ripetere un promemoria già mostrato, e `reminder_snoozed` (ora, evento, inizio, metodo, minuti) per i posticipi, riprogrammati anche dopo un riavvio. Le chiavi vecchie di più di 2 giorni si cancellano.
- **Recupero**: all'avvio e al riavvio si mostrano i promemoria persi nelle ultime 24 ore, solo se l'evento non è ancora finito (regola del desktop).

**3. Notifica popup**
- Canale "Promemoria" con importanza alta e suono breve. Contenuto: titolo, orario ("tra 10 minuti · 10:00–11:00"), luogo. Tocco: apre l'app sul dettaglio dell'evento. Azioni: "Posticipa 10 min" e, se c'è, "Partecipa con Meet".

**4. Sveglia**
- Canale "Sveglie" senza suono proprio: la suoneria la riproduce un foreground service con `AudioAttributes.USAGE_ALARM`, in loop, con vibrazione, così suona anche in modalità silenziosa e Non disturbare come le sveglie di sistema. Il tipo di foreground service (`mediaPlayback` o `shortService`) si sceglie in implementazione, con i limiti di Android 14–16.
- Notifica con full-screen intent verso `AlarmActivity` (`showWhenLocked`, `turnScreenOn`), come il mockup 7: ora, titolo, "tra 30 minuti", luogo, Posticipa (10 min ▾) e Chiudi, "si ferma da sola dopo 2:00".
- Dopo la durata impostata (1–10 minuti, predefinito 2) senza risposta la suoneria si ferma e resta una notifica di "sveglia persa". Il mockup 7 mostra "si ferma da sola dopo N:00". Più sveglie insieme si mettono in fila.

**5. Permessi e onboarding**
- `POST_NOTIFICATIONS` (Android 13 e successivi), chiesto la prima volta che c'è un promemoria da programmare o dalla schermata dei permessi.
- Allarmi esatti: `USE_EXACT_ALARM` (concesso alle app di calendario) più `SCHEDULE_EXACT_ALARM` per Android 12 (fino all'API 32).
- Schermo intero: su Android 14 e successivi `USE_FULL_SCREEN_INTENT` va abilitato dall'utente; se manca, la sveglia resta una notifica in primo piano con la suoneria, e l'app spiega come attivarlo.
- Batteria: richiesta di esclusione dall'ottimizzazione e guida per togliere GGCalendar dalle "app in sospensione" di Samsung.
- Una schermata "Notifiche e sveglie" (dal menu laterale) mostra lo stato di ogni permesso con il pulsante per sistemarlo, e il cursore della durata della sveglia (1–10 minuti). Nel dettaglio dell'evento, sotto i promemoria, compare l'avviso del mockup 5 se un permesso blocca quel promemoria.

**Ripristino dopo il riavvio: soluzione deterministica (valutazione del 11/10/2026)**
- Problema: gli allarmi di `AlarmManager` non sopravvivono al riavvio (è documentato) e vanno registrati di nuovo. Prova sul Samsung (11/10/2026): dopo il riavvio e il PIN l'app non ha ripristinato nulla, probabilmente perché `BOOT_COMPLETED` non è stato consegnato. Il polling (controllo ogni 15 minuti) è escluso: una sveglia nei primi minuti dopo l'accensione andrebbe persa o in ritardo, e consuma batteria.
- Meccanismi che offre Android, nell'ordine in cui conviene usarli:
  1. `LOCKED_BOOT_COMPLETED` con ricevitore, servizio e schermata **direct boot aware**: è consegnato prima dello sblocco, a tutte le app con il componente dichiarato, ed è il metodo documentato per le app di sveglia. Funziona solo con dati in memoria "device protected": l'app salva lì un'istantanea (JSON) delle sveglie e dei promemoria delle prossime 48 ore (chiave, ora, metodo, titolo, orari, luogo, descrizione abbreviata, posticipi) ogni volta che riprogramma. Al boot si registrano di nuovo gli allarmi dall'istantanea, senza Room (che è cifrato fino allo sblocco); la sveglia suona anche prima del PIN. I posticipi fatti a telefono bloccato restano nell'istantanea e passano in Room allo sblocco.
  2. `BOOT_COMPLETED` (dopo lo sblocco), `MY_PACKAGE_REPLACED`, `TIME_SET` e `TIMEZONE_CHANGED` (già presenti): ripristino completo da Room con recupero dei promemoria persi nelle ultime 24 ore (evento non finito).
  3. Avvio del processo per qualunque motivo (apertura dell'app, un tocco su una notifica, un sync): il contatore di avvio di Android (`Settings.Global.BOOT_COUNT`) dice se il telefono si è riavviato dall'ultimo ripristino, e in quel caso si ripristina subito. Non è polling: scatta quando l'app parte.
  4. Il lavoro giornaliero resta solo per spostare in avanti l'orizzonte di 48 ore (i job periodici di WorkManager sono persistenti), non per il riavvio.
- Limiti noti, non risolvibili da codice: alcuni produttori (Samsung "app in sospensione", Xiaomi/Huawei "avvio automatico") bloccano il segnale di avvio alle app che non sono escluse dal risparmio energetico. Si usano le API per **rilevarlo e dirlo all'utente** nella schermata "Notifiche e sveglie": `ActivityManager.isBackgroundRestricted()`, `PowerManager.isIgnoringBatteryOptimizations()`, `UsageStatsManager.getAppStandbyBucket()` (il bucket "Restricted" limita i broadcast), con il pulsante per le impostazioni giuste. Il permesso `USE_EXACT_ALARM` e le sveglie `setAlarmClock` sono già quelli riservati alle app di calendario e sveglia.
- Prima di scrivere l'istantanea: **diagnosi** sul Samsung con un file di controllo scritto da un ricevitore `LOCKED_BOOT_COMPLETED` e da uno `BOOT_COMPLETED`, per vedere quale dei due arriva e quando. Poi riavvio con il PIN davanti all'utente, con test automatici dove possibile (Robolectric) e un test manuale per ogni passo: riavvio prima del PIN, dopo il PIN, aggiornamento dell'app, cambio di ora.
- Analisi pro e contro delle soluzioni, aggiornata con i risultati degli esperimenti dell'11/10/2026 sul Samsung (Android 16). Le soluzioni 3 (controllo ogni 15 minuti), 5 (delega all'app Orologio) e 6 (servizio sempre attivo) restano scartate.

| Descrizione soluzione | Pro | Contro |
|---|---|---|
| **1. Ricevitori su `BOOT_COMPLETED`, `MY_PACKAGE_REPLACED`, `TIME_SET`, `TIMEZONE_CHANGED`** con ripristino completo da Room e recupero dei promemoria persi (ultime 24 ore, evento non finito). | **Provata, prova C**: dopo il PIN, senza esclusioni batteria, l'app è ripartita da sola (circa 20 secondi dopo lo sblocco), ha mostrato il promemoria scaduto a telefono spento e ha registrato di nuovo la sveglia. Funziona anche con le esclusioni (prova delle 21:05). Standard Android, già nel codice, nessun costo di batteria. Gestisce aggiornamento dell'app, cambio di ora e di fuso. | Arriva solo dopo lo sblocco con il PIN: una sveglia che cade tra l'accensione e lo sblocco non suona. Una prova su quattro (20:25) non ha ripristinato nulla: causa non chiarita (controllo troppo presto o stato di sospensione di Samsung), quindi su altri telefoni può non arrivare. |
| **2. Contatore di avvio (`Settings.Global.BOOT_COUNT`)** controllato quando il processo parte (apertura dell'app, sync, tocco su una notifica, lavoro giornaliero). | Rete di sicurezza senza polling: se il segnale di avvio non è arrivato, appena l'app parte per qualunque motivo ripristina tutto. Costo trascurabile, già nel codice, coperto da test automatici. | Non parte da solo: se nessuno apre l'app e non c'è un sync non fa nulla. Non provata isolatamente sul telefono (nelle prove il ripristino è partito dal segnale di avvio). |
| **4. Direct boot**: ricevitore, servizio e schermata della sveglia `directBootAware`, con `LOCKED_BOOT_COMPLETED` e istantanea delle sveglie delle prossime 48 ore in memoria "device protected". **Decisione dell'utente: si fa.** | **Provata con la sonda (prove A e B)**, con e senza esclusioni batteria: `LOCKED_BOOT_COMPLETED` arriva a 27 secondi dall'accensione con il telefono bloccato, l'allarme registrato da lì con `setAlarmClock` **suona a telefono bloccato** (117 secondi) e la suoneria come allarme di sistema è riprodotta. Unica soluzione che suona prima del PIN. Metodo documentato, indipendente da Room. | Lavoro più ampio: classi leggere senza Hilt né Room per ricevitore, servizio e schermata, durata della sveglia e giornale delle azioni in memoria "device protected", riconciliazione con Room allo sblocco, rimozione della sonda, test. Scoperto con la sonda: se un componente è `directBootAware` anche `Application.onCreate` gira prima dello sblocco, e l'app andava in crash perché creava subito WorkManager e Room (corretto con iniezione `Lazy` e avvio rimandato a `ACTION_USER_UNLOCKED`). Non provati prima del PIN: schermata a tutto schermo, Posticipa e Ferma. Copia dei dati da tenere allineata a Room. |
| **7. Rilevare le restrizioni e guidare l'utente**: richiesta di esclusione dalla batteria (`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`), voce "App mai in sospensione" di Samsung, controllo con `isBackgroundRestricted()`, `isIgnoringBatteryOptimizations()` e bucket di standby, avviso nel dettaglio dell'evento. | Usa solo API di sistema, poco codice, nessun costo a regime. Utile dove un produttore blocca davvero l'avvio e per la puntualità con Doze. | **Non è più il presupposto**: le prove B e C mostrano il ripristino anche senza esclusioni, e non è dimostrato che la prima prova fallita dipendesse dalle restrizioni. Dipende dall'utente. Non rileva i blocchi di alcuni produttori (elenchi di Samsung, avvio automatico di Xiaomi e Huawei). Non si sa chi abbia tolto l'app dall'elenco "App mai in sospensione" tra la prova A e la B: un'esclusione può sparire senza avvisi, quindi non va considerata stabile. |
| **Combinazione scelta: 4 + 1 + 2 (+ 7 come guida)** | Copertura massima: sveglie anche prima del PIN (4), ripristino completo dopo lo sblocco con promemoria persi (1), rete di sicurezza quando il processo parte (2), avviso per i telefoni che bloccano l'avvio (7). Provata in tutte le parti tranne la schermata della sveglia prima del PIN. | Il lavoro maggiore è la 4. Più superficie di bug e più test. Va verificata su altri telefoni, non solo sul Samsung di prova. |

- Esito: decisione A del 11/10/2026, vedi sotto. Priorità di lavoro: prima la 4 (istantanea, giornale, livello leggero), poi la guida della 7.
- **Esperimento sulla soluzione 7 (11/10/2026, Samsung con Android 16)**:
  - Prima prova (app senza esclusione dalla batteria): dopo riavvio e PIN l'app non si è avviata e non ha registrato nessuna sveglia.
  - Seconda prova, dopo che l'utente ha escluso GGCalendar dall'ottimizzazione batteria ("App mai in sospensione" e "Senza restrizioni"; lo stato risulta `deviceidle whitelist` e bucket di standby "exempted"): dopo riavvio e PIN, **senza aprire l'app**, in circa 2 minuti l'app è partita da sola, ha registrato di nuovo la sveglia delle 21:11 e ha mostrato il promemoria scaduto mentre il telefono era spento (21:05). Il codice era lo stesso della prova fallita (stesso ricevitore su `BOOT_COMPLETED`), quindi la differenza è dovuta alle esclusioni.
  - Conclusione provvisoria, poi indebolita dalla prova B (vedi sotto): sul Samsung il problema sembrava la restrizione del produttore, non il codice; la soluzione 7 (rilevare e guidare l'utente) resta una mitigazione utile. Limiti: una sola prova per caso e senza ripetere la prima condizione come controllo; la prova non copre le sveglie che cadono prima dello sblocco con il PIN (è il caso della soluzione 4).
  - Da fare per la 7: richiesta di esclusione dalla batteria con `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` al primo avvio (e se risulta tolta), voce chiara nella schermata "Notifiche e sveglie" per "App mai in sospensione" di Samsung con il passaggio indicato, avviso nel dettaglio dell'evento se `isBackgroundRestricted()` è vero o la batteria non è esclusa.
- **Esperimento sulla soluzione 4, prova A (11/10/2026, Samsung con Android 16, esclusioni batteria attive)**: una sonda solo per le build di debug (due ricevitori `directBootAware` e un file di controllo nella memoria "device protected") ha misurato il riavvio senza inserire il PIN per 4 minuti.
  - Primo tentativo: `LOCKED_BOOT_COMPLETED` è arrivato 18 secondi dopo l'accensione, ma l'app è andata in crash subito ("Unable to create application"): `GGCalendarApp` riceve per iniezione `CalendarRepository` e `ReminderStartup`, che creano WorkManager e Room, la cui memoria non esiste prima dello sblocco. Correzione: iniezione `Lazy` nella classe `Application` e avvio vero e proprio rimandato a `ACTION_USER_UNLOCKED` (registrato dinamicamente) se il telefono è ancora bloccato.
  - Secondo tentativo, risultato: `LOCKED_BOOT_COMPLETED` ricevuto a 27 s dall'accensione con il telefono bloccato; l'allarme armato da quel ricevitore con `setAlarmClock` è **suonato a telefono ancora bloccato** (117 s, suoneria come allarme di sistema per 8 s, udita dall'utente); `BOOT_COMPLETED` è arrivato subito dopo l'inserimento del PIN (306 s).
  - Conclusione provata: con le esclusioni, il direct boot è fattibile sul Samsung (segnale pre-sblocco consegnato, allarme registrato e suonato prima del PIN). Ancora da provare: la prova B senza esclusioni (se `LOCKED_BOOT_COMPLETED` arriva anche così, la 4 funziona da sola; se no, dipende dalla 7), e l'intera schermata della sveglia prima del PIN (servizio e schermata `directBootAware` con dati dall'istantanea).
- **Esperimento sulla soluzione 4, prova B (11/10/2026, Samsung, nessuna esclusione batteria: whitelist assente, bucket di standby "attivo", app tolta dall'elenco "App mai in sospensione")**: stesso risultato della prova A. `LOCKED_BOOT_COMPLETED` ricevuto a 27 s dall'accensione con telefono bloccato, allarme armato da lì e **suonato a telefono bloccato** (117 s), `BOOT_COMPLETED` arrivato subito dopo l'inserimento del PIN (273 s).
  - Conclusione provata: sul Samsung il direct boot funziona **anche senza le esclusioni batteria**. La soluzione 4 è fattibile da sola e non dipende dalla 7.
  - **Correzione dell'esperimento sulla soluzione 7**: la conclusione "il problema era la restrizione del produttore" non è dimostrata. La prima prova fallita (20:25, app senza esclusioni) può avere altre spiegazioni: controllo fatto troppo presto (nella prova delle 21:05 il primo controllo, a 1 minuto dallo sblocco, non vedeva ancora nulla, il ripristino risultava 50 secondi dopo), oppure uno stato di sospensione di Samsung che nel frattempo è cambiato (l'utente ha trovato GGCalendar tolta dall'elenco "App mai in sospensione" dopo la prova A: chi l'abbia tolta non è noto; ipotesi non verificata, l'aggiornamento dell'app con `adb install -r` alle 21:22). Prima della prova C la 7 resta una mitigazione utile, non una causa accertata.
  - Prova C da fare: riavvio con evento di prova (sveglia futura e promemoria che scade mentre il telefono è spento), senza esclusioni, controllando **solo dopo 2 minuti** dallo sblocco, per sapere se il ripristino completo da `BOOT_COMPLETED` (non la sonda) funziona senza esclusioni.
- **Prova C (11/10/2026, Samsung, nessuna esclusione batteria, ripristino completo senza la sonda)**: evento con sveglia alle 21:58 e promemoria alle 21:49 (scaduto a telefono spento). Dopo il PIN (21:49:37) l'app è partita da sola per il broadcast del ricevitore dei promemoria (`ReminderReceiver`), ha mostrato il promemoria perso e ha registrato di nuovo la sveglia delle 21:58 (visibile come allarme di sistema). `BOOT_COMPLETED` e `LOCKED_BOOT_COMPLETED` arrivano entrambi.
  - Conclusione provata: sul Samsung il ripristino da `BOOT_COMPLETED` (soluzione 1) e il direct boot (soluzione 4, con sonda) funzionano **senza nessuna esclusione batteria**. La prima prova fallita (20:25) non si è ripetuta in tre prove (A, B, C): resta senza spiegazione certa (ipotesi: controllo troppo presto, stato di sospensione di Samsung temporaneo). Il rischio non è eliminato su altri telefoni.
  - Conseguenze per le soluzioni: la **7** diventa una guida e un avviso per i telefoni che bloccano l'avvio (non il presupposto); la **1 + 2** (già nel codice) funzionano; la **4** è fattibile e dà in più le sveglie prima del PIN. Prossimo passo da decidere: scrivere il direct boot vero (istantanea in memoria "device protected", servizio e schermata della sveglia `directBootAware`) oppure fermarsi alla 1 + 2 + 7.
- **Decisione dell'utente (11/10/2026): si procede ad implementare la soluzione 4, il direct boot vero**, insieme a 1 + 2 già presenti; la 7 resta come guida e avviso per i telefoni che bloccano l'avvio. Nessun codice scritto per ora: questa è l'impostazione del lavoro.
  - **Obiettivo**: una sveglia o un promemoria suona all'ora giusta anche se il telefono è stato riavviato e il PIN non è ancora stato inserito (riavvio notturno, aggiornamento di sistema).
  - **Istantanea in memoria "device protected"**: a ogni riprogrammazione (sync, modifica, avvio, cambio di ora o fuso) l'app scrive un file JSON con i promemoria delle prossime 48 ore (chiave, ora, metodo, minuti prima, titolo, luogo, descrizione abbreviata, orari, tutto il giorno, link Meet, id dell'evento) più i posticipi in corso. Room resta la fonte di verità; l'istantanea è una copia scritta in modo atomico (file temporaneo e rinomina).
  - **Componenti `directBootAware`**: ricevitore di avvio (`LOCKED_BOOT_COMPLETED`, `BOOT_COMPLETED`, `MY_PACKAGE_REPLACED`, `TIME_SET`, `TIMEZONE_CHANGED`), ricevitore degli allarmi, `AlarmService`, `AlarmActivity` e notifiche. Dividere le classi in due livelli: uno **leggero senza Hilt né Room**, che legge solo l'istantanea e usa `AlarmManager`, notifiche, suoneria e vibrazione (già provati nella sonda: allarme registrato e suonato prima del PIN, suoneria riprodotta); uno completo, che parte dopo lo sblocco.
  - **Impostazioni usate a telefono bloccato**: la durata della sveglia, oggi in DataStore (memoria normale), si copia nell'istantanea o in preferenze "device protected".
  - **Azioni fatte a telefono bloccato** (Posticipa, Ferma, Chiudi): si registrano in un giornale nella stessa memoria "device protected" (posticipi con ora, chiavi già mostrate o chiuse) e si riportano in Room appena il telefono è sbloccato (`ACTION_USER_UNLOCKED`), prima di riscrivere l'istantanea.
  - **Dopo lo sblocco**: ripristino completo da Room (già presente: `BOOT_COMPLETED`, contatore di avvio, recupero dei promemoria persi nelle ultime 24 ore) più riconciliazione con il giornale; evitare doppioni con la chiave del promemoria e la tabella dei già mostrati.
  - **Avvio dell'app prima dello sblocco**: già fatto (iniezione `Lazy` in `GGCalendarApp` e avvio rimandato a `ACTION_USER_UNLOCKED`). Va verificato che nessun altro componente creato dalla classe `Application` apra Room, DataStore o WorkManager.
  - **Passi di lavoro**:
    1. **Istantanea e giornale, con test unitari.**
       - Istantanea: file JSON nella memoria "device protected" con i promemoria delle prossime 48 ore (chiave, ora, metodo, titolo, orari, luogo, descrizione abbreviata, link Meet) e i posticipi in corso; scritta in modo atomico (file temporaneo e rinomina).
       - Giornale: registro delle azioni fatte a telefono bloccato (Posticipa, Ferma, Chiudi) e delle chiavi già mostrate.
       - Test: serializzazione, scrittura atomica, riconciliazione con Room, posticipi.
    2. **Livello leggero, senza Hilt né Room.**
       - Ricevitore di avvio (`LOCKED_BOOT_COMPLETED`, `BOOT_COMPLETED`, `MY_PACKAGE_REPLACED`, cambio di ora e di fuso) e ricevitore degli allarmi, dichiarati `directBootAware`.
       - Notifica, servizio e suoneria che leggono solo l'istantanea.
       - `AlarmActivity` con i dati dall'istantanea e durata della sveglia in memoria "device protected".
    3. **Collegamento con il livello completo.**
       - L'istantanea si riscrive a ogni riprogrammazione (sync, modifica, avvio, cambio di ora o fuso).
       - Allo sblocco (`ACTION_USER_UNLOCKED`) il giornale si riporta in Room, senza doppioni, e l'istantanea si riscrive.
    4. **Rimozione della sonda di debug** (`BootProbe`, ricevitori di prova e relativo manifest di debug).
    5. **Verifica sul Samsung.**
       - Riavvio senza inserire il PIN con una sveglia in arrivo: schermata a tutto schermo sopra il blocco, suoneria, Posticipa e Ferma prima del PIN.
       - Poi PIN e controllo che posticipo e chiusura risultino in Room e in "Sveglie attive".
    6. **Lint, test automatici e CI verde**, poi commit e aggiornamento di plan.md.
  - **Rischi**: telefoni senza cifratura a file (il segnale pre-sblocco non esiste, vale solo il segnale dopo lo sblocco); la suoneria predefinita a telefono bloccato (provata sul Samsung, da provare su altri telefoni); l'istantanea non aggiornata se l'app non gira (l'ultima nota resta valida fino a 48 ore); doppioni tra istantanea e Room.
- **Stato (11/10/2026): direct boot fatto e provato sul Samsung** (passi 1-6). Nuovi file in `:core:notifications`: `DirectBootStore` (istantanea, giornale, chiavi registrate e durata della sveglia in memoria "device protected"), `AlarmRegistrar`, `LightReminders` (ripristino, suoneria e Posticipa senza Hilt né Room), `JournalReconciler` (giornale in Room allo sblocco). `ReminderReceiver`, `AlarmService` e `AlarmActivity` sono `directBootAware` e senza iniezione; lo scheduler riscrive l'istantanea a ogni riprogrammazione e dopo ogni promemoria mostrato. Sonda di debug rimossa. 10 test nuovi (16 nel modulo), lint e test verdi.
  - **Prova 1** (riavvio senza PIN, sveglia dopo 4 minuti): app partita da sola con `LOCKED_BOOT_COMPLETED`, schermata a tutto schermo comparsa, Posticipa 5 minuti prima del PIN arrivato in Room (riga in `reminder_snoozed`, sveglia segnata come già suonata). **Difetto trovato**: nessun suono, perché la suoneria predefinita è un contenuto del media provider, non disponibile prima dello sblocco (`user not unlocked`).
  - **Correzione**: suoneria di riserva inclusa nell'app (`res/raw/alarm_fallback.wav`, 4 beep in loop), usata se quella di sistema non si apre.
  - **Prova 2**: riavvio senza PIN, la sveglia delle 23:46 ha suonato con la suoneria di riserva e la schermata a tutto schermo, prima del PIN. Dopo "Ferma" e il PIN: sveglia in `reminder_fired` una sola volta, nessun posticipo pendente, giornale svuotato.
  - **Cambio di fuso provato (11/10/2026, con adb `cmd alarm set-timezone`)**: Roma → Tokyo → Roma, a telefono sbloccato. Il ricevitore riceve `TIMEZONE_CHANGED`, ricalcola le istanze e riscrive l'istantanea. Il promemoria di un evento di tutto il giorno (compleanno) si sposta con la mezzanotte locale: da 1791788400000 a 1791763200000 (7 ore prima) a Tokyo, e torna al valore di prima a Roma; quelli degli eventi con orario restano agli stessi istanti. La prima serie di prove non era affidabile (una volta su due l'istantanea restava con i valori vecchi): causa trovata, due corse. (1) `SyncEngine.updateWindow` pubblicava la nuova finestra prima di aver ricostruito le istanze, e il secondo dei due ricevitori del cambio di fuso (quello dell'app e quello dei promemoria) vedeva "niente da fare" e riprogrammava sulle istanze vecchie: ora la finestra si pubblica a ricostruzione finita. (2) Il cambio di fuso arriva anche come `TIME_SET`, che riprogrammava senza ricostruire: ora anche quello aspetta `refreshInstances`. Dopo la correzione, 6 cambi di fuso su 6 (Tokyo, Roma, New York) con il valore giusto già dopo 4 secondi.
  - **In sospeso (M4)**: (1) **Doze profondo vero**, da provare con telefono fermo e non in carica per circa un'ora; (2) **soluzione 7**, cioè guida e avviso per le restrizioni del produttore (richiesta di esclusione dalla batteria, "App mai in sospensione" di Samsung, `isBackgroundRestricted()`), ancora da scrivere.
  - Non provato: altri telefoni, Posticipa dalla notifica (non dalla schermata) a telefono bloccato, due riavvii di seguito senza sblocco.

**6. Email**
- Le invia Google (promemoria `method=email`, già scritti dall'editor in M3). Solo verifica.

**7. Ordine di lavoro** (commit e prova sul Samsung a ogni blocco)
1. Calcolo dei promemoria (porting, doppioni, giornata intera) con test.
2. Room v5 (`reminder_fired`, `reminder_snoozed`) e modulo `:core:notifications`.
3. Scheduler con AlarmManager e receiver; riprogrammazione dopo sync, modifiche, avvio, riavvio, aggiornamento, cambio di ora e worker giornaliero.
4. Notifica popup con le azioni (apri, posticipa, Meet).
5. Sveglia: foreground service con la suoneria, `AlarmActivity`, stop dopo la durata impostata, sveglia persa, Posticipa con menu.
6. Recupero dei promemoria persi.
7. Permessi: richieste, schermata "Notifiche e sveglie" con la durata della sveglia, avviso nel dettaglio.
8. Verifica e commit.

**8. Verifica di M4**
- Unit test: calcolo (predefiniti del calendario, doppioni, giornata intera, eventi finiti, eccezioni ed EXDATE), recupero, posticipo, pulizia delle chiavi.
- Sul Samsung, con eventi di prova creati apposta nel calendario principale di profgusinu a pochi minuti da adesso:
  - notifica all'ora giusta a schermo acceso e spento;
  - sveglia a telefono bloccato (schermo intero) e sbloccato (notifica), con Chiudi, Posticipa e stop dopo la durata impostata (provata con 1 minuto);
  - sveglia in Doze (`adb shell dumpsys deviceidle force-idle`);
  - promemoria ancora attivi dopo il riavvio del telefono e con l'app chiusa dalle recenti;
  - cambio di fuso;
  - una sola notifica per un invito presente in due account;
  - arrivo dell'email di promemoria.
- CI verde.

## M4bis: piano dettagliato
**Obiettivo** (richiesta dell'11/10/2026): una nuova vista che unisce Mese e lista, e un APK di prova firmato che si può mandare ad altri telefoni (anche con WhatsApp) per provare l'app prima di passare per la piattaforma Google ufficiale.

**1. Vista "Mese + Programma"**
- Descrizione e mockup (`img/vista_mese_e_lista_giuli.jpeg`, indicati il 11/10/2026): in alto il mese selezionato come griglia di numeri senza celle (nome dei giorni sopra, giorni dei mesi vicini sbiaditi, un puntino sotto ogni numero, quello del giorno selezionato in un cerchio, oggi in colore di accento); sotto, separata da una maniglia, la lista degli eventi del giorno selezionato con una intestazione relativa ("Oggi", "Domani", "Tra 4 giorni"). Ogni evento è una scheda arrotondata: a sinistra ora di inizio e di fine una sotto l'altra, una barra verticale del colore del calendario, il titolo (su una riga, troncato) e le etichette (es. "Conflitto"). Per creare un evento si usa il "+" circolare sospeso in basso a destra (sopra la lista), che apre l'editor sul giorno selezionato.
- Il Mese pieno con i chip dei titoli e il Programma restano.
- Toccando il titolo (il mese) non scende un calendario: si apre solo la scelta dell'anno (griglia di anni), perché tra i mesi ci si sposta già con lo swipe sulla vista. Fatto e provato sul Samsung (11/10/2026).
- Tocco su un giorno: seleziona il giorno e aggiorna l'elenco; scorrimento orizzontale della griglia: cambia mese; tocco su un evento: dettaglio.
- Aggiunta al menu laterale come sesta vista, con il nome "Mese + Programma" (fatto), riusa `MiniCalendar` (senza intestazione) e le query di `CalendarViewModel`; l'ultima vista usata viene ricordata come le altre. Il "+" crea l'evento sul giorno selezionato (ora piena successiva se è oggi, altrimenti le 9:00). Da fare: l'intestazione della lista non mostra l'anno per le date lontane.
- Prova sul Samsung: cambio mese e giorno, elenco dei giorni senza eventi, eventi di tutto il giorno e di più giorni, tema scuro.

**2. APK di prova da condividere**
- Build `assembleRelease` firmata con il keystore di release (fuori dal repo), `versionName` "0.4.0-beta" e `versionCode` aumentato a ogni consegna; nome file `GGCalendar-0.4.0-beta.apk`. L'APK non contiene segreti: l'ID del client OAuth Android è pubblico e legato a pacchetto e SHA-1 di release, già registrato.
- **Prima verifica da fare**: la build release ha R8 attivo e le regole `proguard-rules.pro` sono quasi vuote, mentre ora ci sono classi `@Serializable` (Room, outbox), Room, Hilt, WorkManager e lib-recur. Si aggiungono le regole necessarie e si prova la release sul Samsung (login, sync, modifica, notifiche e sveglia) prima di consegnarla.
- Installazione sui telefoni degli altri (guida breve da mandare insieme al file, IT/EN):
  - WhatsApp può rifiutare o rinominare i file `.apk`: si manda come documento, e se non va si invia lo ZIP (`GGCalendar-0.4.0-beta.zip`) da estrarre, oppure un link a Google Drive o a GitHub (pre-release non pubblicizzata). Si manda anche l'SHA-256 per controllare il file.
  - Sul telefono: consentire l'installazione da origini sconosciute all'app da cui si apre il file; Play Protect può mostrare un avviso ("app non riconosciuta"), si sceglie "Installa comunque".
  - Al primo accesso Google mostra l'avviso "app non verificata" (lo scope `calendar` non è ancora verificato): "Avanzate" → "Vai a GGCalendar". Vale il limite di 100 utenti condivisi con il desktop; **gli account dei tester vanno aggiunti come utenti di test se il progetto torna in Testing**, altrimenti il login funziona già in Production.
  - I tester devono sapere che l'app scrive sul loro vero Google Calendar: per le prove usare eventi creati apposta o un account di prova.
- Aggiornamenti: la v0.4.1 si installa sopra la precedente (stessa firma e stesso pacchetto); i dati restano. Gli aggiornamenti in-app arrivano in M6.
- Raccolta dei problemi: i tester scrivono a te (WhatsApp) con modello del telefono, versione Android e una descrizione; per i crash si valuta in M6 un pulsante "Invia log".
- Verifica: installazione pulita su un secondo telefono (anche non Samsung), login con un altro account Google, sincronizzazione, una notifica e una sveglia, aggiornamento sopra la versione precedente.
- La landing page e il canale ufficiale (GitHub Releases) restano in M7; questo è solo un test chiuso con amici.

**3. Scheda informativa dell'app** (richiesta del 12/10/2026)
- Voce "Informazioni" nel menu laterale (c'è già, ora senza contenuto) che apre una scheda con:
  - nome e icona dell'app;
  - **Autore**: Giansalvo Gusinu;
  - **Sito web**: https://profgusinu.altervista.org/GGCalendar/ (tocco: apre il browser);
  - **Versione**: 0.4.0, con la **data di build** (12/10/2026 per questa consegna);
  - licenza GPL-3.0 e link al codice su GitHub (giansalvo/GGCalendarMobile), come già deciso per il progetto.
- Versione e data non si scrivono a mano nel testo: la versione viene da `BuildConfig.VERSION_NAME`, la data di build da un campo `BuildConfig` generato da Gradle a ogni build, così non resta indietro rispetto all'APK consegnato (la versione e la data della scheda devono coincidere con quelle del nome del file, vedi punto 2).
- Testi in italiano e inglese. Verifica: apertura dal menu laterale, tocco sul sito, tema chiaro e scuro, versione e data uguali a quelle dell'APK installato (`dumpsys package`).

## Verifica
- Test unitari: SyncEngine (syncToken, 410, 412 → conflitto), espansione RRULE, mapping dei DTO, scrittore ICS, scheduler degli allarmi. Le chiamate API sono simulate con MockWebServer.
- Test strumentali e UI Compose per editor e viste, sugli emulatori API 26 e 36.
- Test manuali sul Samsung (Android 16):
  - evento creato sul telefono → visibile su calendar.google.com, e viceversa;
  - creazione del link Meet e arrivo degli inviti agli invitati;
  - modalità aereo → modifica → modifica dallo stesso evento sul web → riconnessione → la versione di Google viene applicata e compare l'evento "(conflitto)";
  - sveglia a schermo spento e in Doze (`adb shell dumpsys deviceidle force-idle`): suono continuo che si ferma a 120 secondi;
  - allarmi ancora attivi dopo il riavvio;
  - arrivo dell'email di promemoria.
- Release: un tag `v0.x` produce un APK firmato su GitHub Releases, che si scarica dalla landing page, si installa e si aggiorna dall'app.
