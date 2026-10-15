# Changelog

Le modifiche di GGCalendar Mobile da una versione alla successiva, dalla più recente. Il dettaglio dei test è in `app_planning/test_emulator_e_device.md`, il piano in `app_planning/plan.md`.

## 1.0.1 (15/10/2026, in preparazione)

**Sveglia sopra le altre app**
- Nuovo permesso opzionale "Visualizza sopra altre app". Se è concesso, la schermata della sveglia (Posticipa, Ferma) si apre da sola anche mentre usi un'altra app o la schermata iniziale di Android, e resta finché non rispondi o scadono i minuti impostati. Prima, a telefono in uso, compariva solo un banner che Android ritirava dopo poco.
- Il permesso si concede una volta sola in Impostazioni → Notifiche e sveglie, alla riga "Sveglia sopra le altre app" (pulsante Correggi); l'app non lo richiede ogni volta che si imposta una sveglia.
- Quando la schermata della sveglia è aperta, la notifica resta silenziosa e non copre l'ora; lo stesso vale per le sveglie in coda dietro la prima.
- Come prima, se nessuno risponde la sveglia si ferma da sola alla scadenza e lascia la notifica "Sveglia persa".

**Banner della sveglia**
- Senza il permesso, il banner in alto è più grande: titolo, orario, luogo e i pulsanti Posticipa e Chiudi dentro la notifica, con colori leggibili in tema chiaro e scuro. La durata del banner la decide Android (circa un minuto sul Samsung A56): la sveglia continua a suonare e la notifica resta nella tendina.

## 1.0.0 (14/10/2026)

- Viste Programma, Giorno, 6 giorni, Settimana, Mese e Mese + Programma
- Sincronizzazione con più account Google Calendar, anche offline
- Conflitti: resta la versione di Google e la copia "(conflitto)"
- Eventi con ricorrenze, invitati, Meet, colore e fuso orario
- Trascinamento e ridimensionamento degli eventi nelle griglie
- Promemoria con notifica, sveglia a schermo intero ed email
- Elenco "Sveglie attive" con tempo rimanente e chiusura anticipata
- Esportazione .ics e CSV, condivisione e compleanni dalla rubrica
- Cambio calendario con conferma, uscita dall'account, passato attenuato
- Italiano e inglese, tema chiaro e scuro, scheda Informazioni
