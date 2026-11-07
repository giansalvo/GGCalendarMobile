# Changelog

Le modifiche di GGCalendar Mobile da una versione alla successiva, dalla più recente.

## 1.0.8 (xx/11/2026)

- Barra superiore più pulita: tolto il cerchio con l'iniziale dell'account, che mostrava sempre e solo il primo account
- «Esporta» si apre con la pressione lunga su un calendario; si possono ancora aggiungerne altri. La voce è rimossa dal menu laterale

## 1.0.7 (07/11/2026)

- Aggiornamento ad Android 17 (livello API 37): sveglie, promemoria e notifiche provati anche sulla nuova versione del sistema
- Accesso con Google aggiornato alla libreria Google Play services auth 22
- Notifiche più sicure: si aprono con un collegamento esplicito all'app
- Aggiornate le librerie e gli strumenti dell'app (Kotlin 2.4, Android Gradle Plugin 9.5, Compose): nessuna modifica alle funzioni


## 1.0.6 (01/11/2026)

- Nuova lingua: francese
- Descrizione degli eventi anche in HTML
- Possibilità di aggiungere link come allegati ad un evento
- Menu laterale: la pressione lunga su un calendario visualizza il menu contestuale
- Correzione di anomalie (bug)

## 1.0.5 (24/10/2026)

- Versione per la pubblicazione su Google Play, firmata con la stessa chiave dell'APK: nessuna modifica alle funzioni rispetto alla 1.0.3

## 1.0.3 (23/10/2026)

- Registro diagnostico: scrive un file di registro con i titoli degli eventi; la voce «Invia registro diagnostico» nel menu laterale lo spedisce per mail
- Secondo fuso orario
- Importazione di file e indirizzi iCal in un calendario locale
- Tasto Indietro: nella schermata principale chiede di premerlo di nuovo per chiudere l'app; con il menu laterale aperto lo chiude

## 1.0.2 (22/10/2026)

- Calendari locali: stanno solo sul telefono, senza account e senza rete
- Abbonamento a calendari pubblici (iCal, solo lettura), con scelta dei giorni da mostrare ("Dal" e "Fino al") e del tempo di aggiornamento (da 15 minuti a 24 ore)
- Indirizzo degli abbonamenti cifrato, solo https; limiti di 10 MB, 5000 eventi e 10 abbonamenti
- Calendario nascosto dal menu laterale: le sveglie e i promemoria non suonano più; tornano quando si rimette la spunta
- Sveglie: notifiche in un gruppo proprio; le schermate si aggiornano dopo ogni scrittura nel database
- Schermata iniziale con il nome dell'app, l'autore, la versione e la data
- **Attenzione:** il database riparte da una versione nuova senza migrazione: aggiornando dalla 1.0.1 si perdono i dati locali e va riaggiunto l'account Google

## 1.0.1 (16/10/2026)

- Visualizza sveglia sopra le altre app
- Visualizza splash screen
- Lingua selezionabile dall'app
- Nuove lingue: spagnolo, tedesco

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
