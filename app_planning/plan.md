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
- **M3** Editor: creazione, modifica, eliminazione, invitati, Meet, ricorrenze, outbox offline, conflitti; trascinamento degli eventi (spostare e ridimensionare) nelle griglie orarie, spostato qui da M2 perché richiede la scrittura su Google.
- **M4** Notifiche: popup, sveglia di 2 minuti, promemoria email, riavvio, onboarding dei permessi.
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
