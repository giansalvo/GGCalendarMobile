    # Calendari di prova per GGCalendar

    File sintetici, senza dati di nessuno. Le date partono da **26/10/2026** (i primi giorni dopo la generazione): se i file si
    allontanano troppo dall'oggi, si rigenerano con `python make_feeds.py --start AAAA-MM-GG`.

    ## Come pubblicarli
    1. La cartella è `website/feeds` del repository: si carica su Altervista insieme al resto del sito (sulla cartella `GGCalendarAndroid`),
       senza cambiare i nomi. L'indirizzo è `https://profgusinu.altervista.org/GGCalendarAndroid/feeds/` (l'app rifiuta `http`). I `.php` girano su Altervista;
       servono ai casi sulle risposte lente, sugli errori, sui reindirizzamenti e sul file troppo grande.
    2. Scrivi l'indirizzo della cartella (con la barra finale) in `tools/emutest/data/feeds_base_url.txt`: gli script lo leggono da lì; il file non va in nessun repository.
    3. `.htaccess` evita che il sito serva una copia vecchia; senza, l'attesa dopo un cambio può arrivare a diversi minuti.
    4. **Altervista (piano gratuito) può aggiungere pubblicità alle pagine HTML**: i file `.ics` e gli script con un altro tipo di contenuto non dovrebbero essere toccati.
       Alla prima prova guarda che la fine di `ggcal_basic.ics` sia `END:VCALENDAR` e basta (aprilo in un browser). Il limite di tempo dei `.php` sul piano gratuito può essere
       inferiore ai 70 secondi di `slow.php`: se la prova mostra un errore del server invece del tempo scaduto, accorcia `seconds` oltre i 60 (la app aspetta al massimo 60 s).

    ## I file e a cosa servono
    | File | Per cosa |
    |---|---|
    | `ggcal_basic.ics` | T151 T153 T154 T155 T161 T162 T163 T168 (11 eventi mostrati, 1 annullato) |
| `ggcal_google_busy.ics` | T152: 109 eventi 'Busy' con lo stesso UID e RECURRENCE-ID, come il feed pubblico di Google |
| `ggcal_change_v1.ics` | T154 T164: la versione 1 (6 eventi) |
| `ggcal_change_v2.ics` | T154 T164: la versione 2 (B cambia titolo, C si sposta, D e F spariscono, G è nuovo, 5 eventi) |
| `ggcal_utc.ics` | T165: orari in UTC; cambiano con il fuso del telefono |
| `ggcal_rrule.ics` | T166: serie di un altro produttore (anche BYSETPOS e RDATE, non supportati e dichiarati) |
| `ggcal_windows_tz.ics` | fusi di Outlook (nomi Windows) e un fuso sconosciuto |
| `ggcal_apple.ics` | file in stile Apple (con VALARM e proprietà X-APPLE) |
| `ggcal_alarms.ics` | T161: un abbonamento non ha promemoria, anche se il file ha VALARM |
| `ggcal_5000.ics` | T160: esattamente 5000 eventi, accettato |
| `ggcal_5001.ics` | T160: 5001 eventi, rifiutato (troppi) |
| `ggcal_not_calendar.html` | T157: una pagina web con risposta 200: non è un calendario |
| `ggcal_empty.ics` | T157: file vuoto |
| `ggcal_malformed.ics` | file interrotto a metà di un evento: l'app legge i 6 eventi completi e segnala il troncamento |
| `ggcal_bom_lf.ics` | file con BOM UTF-8 e righe terminate da LF |
| `ggcal_dup_uid.ics` | tre eventi, due con lo stesso UID: l'app ne legge 2 (il secondo con lo stesso UID è scartato e segnalato) |
| `status.php` | T157 T158: risposte 403, 404, 410, 429, 500: status.php?code=404 |
| `slow.php` | T160 T167: risposta lenta (70 s): slow.php?seconds=70 |
| `big.php` | T160: oltre 10 MB, rifiutato (file troppo grande), fatto al volo: big.php?mb=11 e big.php?mb=11&declared=1 |
| `redirect.php` | T159: reindirizzamenti: redirect.php?kind=ok|to_http|chain |
| `.htaccess` | facoltativo: niente cache |

    ## Il cambio di contenuto (T154, T164)
    L'indirizzo è uno solo: `ggcal_cambia.ics`. Per prova:
    1. Pubblica la **versione 1**: carica `ggcal_change_v1.ics` e rinominalo (o copialo) come `ggcal_cambia.ics`. Aggiungi l'abbonamento a quell'indirizzo (6 eventi).
    2. Pubblica la **versione 2**: sostituisci `ggcal_cambia.ics` con il contenuto di `ggcal_change_v2.ics`. Poi "Aggiorna ora".
    3. Atteso: B cambia titolo, C si sposta di giorno, D e F spariscono, G compare, A ed E restano come erano (5 eventi).

    ## Esiti attesi di ogni file
    - `ggcal_basic.ics`: anteprima con **11 eventi** (il dodicesimo è annullato). Nessun promemoria, nessun invitato. Un evento a cavallo della mezzanotte, uno senza fine, uno con ora flottante.
    - `ggcal_google_busy.ics`: **109 eventi**, tutti "Busy", uno per giorno.
    - `ggcal_5000.ics` si aggiunge; `ggcal_5001.ics` dà "più di 5000 eventi"; `big.php?mb=11` dà "file troppo grande" (con `&declared=1` il rifiuto avviene prima di leggere).
    - `ggcal_malformed.ics`: l'anteprima mostra **6 eventi** (quelli completi) e il file è segnalato come troncato; `ggcal_dup_uid.ics`: **2 eventi**.
    - `ggcal_rrule.ics`: **8 eventi**; `RDATE` è ignorato e dichiarato; la regola `BYSETPOS` (secondo lunedì del mese) non è espansa dall'app.
    - `ggcal_not_calendar.html` dà "non restituisce un calendario"; `ggcal_empty.ics` "risposta vuota"; un indirizzo inesistente (404) "non più disponibile".
    - `redirect.php?kind=ok` si segue; `kind=to_http` è rifiutato; `kind=chain` si ferma dopo 5 passaggi.
    - `slow.php?seconds=70` dà "il server non ha risposto in tempo".
