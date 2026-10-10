# GGCalendar Android: mockup delle schermate

Mockup ASCII che ricalcano gli screenshot di Google Calendar Android in `img/` (`vista_settimana`, `vista_mese`, `vista_lista`, `menu_laterale_sx`, `vista_dettaglio` e l'editor). Riferimento: sezione 5b di [plan.md](plan.md).
Stile: Material 3, tema scuro e chiaro, celle ed eventi arrotondati, oggi nel cerchio blu, FAB "+" quadrato arrotondato in basso a destra. Niente vista anno e niente Tasks.

## Barra in alto (comune a tutte le viste)
```
┌────────────────────────────────┐
│ ☰  Ottobre ▾        🔍  [11]  (👤)│
└────────────────────────────────┘
 Ottobre ▾ → mini-calendario del mese, che si apre a tendina, per saltare a una data
 [11]      → torna a oggi
 (👤)      → avatar dell'account: cambia account, impostazioni
 indicatore sync: sottile barra di avanzamento sotto la barra in alto; ⚠ se il sync fallisce
```

## 1. Vista Settimana (vale anche per Giorno e 6 giorni)
```
┌────────────────────────────────┐
│ ☰  Ottobre ▾        🔍  [11]  (👤)│
│      Lun Mar Mer Gio Ven Sab Dom│
│       5   6   7   8   9  10 (11)│  (11) = oggi, cerchio blu
│     [Inps]                      │  fascia eventi di tutto il giorno
├────────────────────────────────┤
│01:00 ▢   ▢   ▢   ▢   ▢   ▢   ▢ │  ▢ = cella oraria arrotondata
│02:00 ▢   ▢   ▢   ▢   ▢   ▢   ●━│  ●━ = linea dell'ora attuale
│05:00 ▢   ▢  ┌ttt┐┌ttt┐ ▢   ▢   ▢ │  evento = blocco arrotondato,
│06:00 ▢   ▢  └───┘│c/  │┌ggc┐ ▢  ▢│  titolo che va a capo
│08:00┌Ing┐ ▢   ▢   ▢   ▢  ┌ttt┐ ▢ │
│09:00│Gre│┌Ds─┐┌aaa┐ ▢   ▢  └───┘┌x┐│
│10:00 ▢  │cod│ ▢  ┌Wor┐ ▢   ▢   ▢ │
│12:00┌Var┐ ▢   ▢  ┌Spe┐ ▢   ▢ ┌Gri┐│
│                         ╭───╮  │
│                         │ + │  │  FAB
│                         ╰───╯  │
└────────────────────────────────┘
 ← scorri: periodo prec./succ. →   pinch: zoom delle ore
 tocco su una cella vuota → nuovo evento a quell'ora
 pressione prolungata su un evento → trascina o ridimensiona
 colonna del 2° fuso orario (facoltativa) accanto alle ore
```

## 2. Vista Mese
```
┌────────────────────────────────┐
│ ☰  Ottobre ▾        🔍  [11]  (👤)│
│ Lun  Mar  Mer  Gio  Ven  Sab Dom│
│┌28─┐┌29─┐┌30─┐┌ 1─┐┌ 2─┐┌ 3─┐┌4─┐│  celle arrotondate,
││Cdi││🎂Ba││Riu││Cor││M5S││Van││Sa││  6 settimane
││Cod││Lav││Cca││con││Med││Cod││Ci││  chip colorati con
│└───┘└───┘└───┘└───┘└───┘└───┘└──┘│  titoli troncati
│┌ 5─┐┌ 6─┐┌ 7─┐┌ 8─┐┌ 9─┐┌10─┐┌11┐│  (max 5 righe, poi "+N")
││Ing││Ds ││ttt││ttt││ggc││ttt││ x││
││Var││Fal││aaa││Wor││Cdi││cor││Gr││
││Lic││   ││+3 ││+3 ││+3 ││   ││  ││
│└───┘└───┘└───┘└───┘└───┘└───┘└──┘│
│ ...                       ╭───╮ │
│                           │ + │ │
└────────────────────────────────┘
 eventi di più giorni = barra continua tra le celle
 🎂 = compleanno (calendario compleanni di Google, sola lettura)
 tocco su un giorno → vista Giorno di quella data
```

## 3. Programma (agenda)
```
┌────────────────────────────────┐
│ ☰  Ottobre ▾        🔍  [11]  (👤)│
├────────────────────────────────┤
│ Dom ●━━━━━━━━━━━━━━━━━━━━━━━━━━│  linea dell'ora attuale
│(11) ┌──────────────────────────┐│
│     │ x                        ││  card del colore
│     │ 09:00–10:00              ││  del calendario
│     └──────────────────────────┘│
│     ┌──────────────────────────┐│
│     │ Grigliata Monte Bianchinu││
│     │ 12:30–16:30          ⏰  ││  ⏰ sveglia  📹 Meet
│     └──────────────────────────┘│
│     12–18 ott                   │  separatore di settimana
│ Lun ┌──────────────────────────┐│
│ 12  │ Eu code week launch? 📹  ││
│     │ 14:00–16:00              ││
│     └──────────────────────────┘│
│   (scorrimento infinito,  ╭───╮│
│    pull-to-refresh = sync) │ + ││
└────────────────────────────────┘
```

## 4. Menu laterale
```
┌──────────────────────────┐
│ [logo] GGCalendar        │
│                          │
│ ☰  Programma             │
│ ▭  Giorno                │
│ ▥  6 giorni              │
│(▦  Settimana           ) │  vista attiva = pillola
│ ▦  Mese                  │
├──────────────────────────┤
│ ⟳  Aggiorna              │  sync manuale
├──────────────────────────┤
│ (👤) profgusinu@gmail.com │
│      Google              │
│  ☑ Il mio calendario     │  ☑ del colore del calendario
│  ☑ Didattica Innovativa… │  pressione prolungata: colore,
│  ☑ Intelligenza Artif…   │  condividi, esporta .ics
│ (👤) giansalvo.gusinu@…   │
│      Google              │
│  ☑ Il mio calendario     │
│  Locali                  │
│  ☑ Personale             │
├──────────────────────────┤
│ ＋ Nuovo calendario locale│
│ ⭳  Importa .ics           │
│ ⚙  Impostazioni           │
│ ⓘ  Informazioni           │
└──────────────────────────┘
```

## 5. Dettaglio evento (bottom sheet che si espande)
```
┌────────────────────────────────┐
│ ✕                       ✎   ⋮  │  ⋮ = elimina, duplica, email agli
│                                │      invitati, condividi, esporta
│ ■  ttt                         │      .ics, stampa
│    Mercoledì 7 ott • 05:00–06:00│
│    Si ripete ogni giorno;      │
│    per 5 volte                 │
│ ┌────────────────────────────┐ │
│ │ 📹 Partecipa con Google Meet│ │  solo se c'è un link Meet
│ └────────────────────────────┘ │
│ 📍 Aula magna                ↗ │  apre Maps
│ ⏰ Sveglia 30 minuti prima     │
│    ⚠ Le sveglie richiedono un  │  avviso se manca un
│      permesso · Sistema        │  permesso, con link
│ ✉  Email 1 giorno prima        │
│ 👥 4 invitati · 2 sì, 1 forse  │
│ ≡  Descrizione con link…       │
│ 📅 Il mio calendario           │
│    profgusinu@gmail.com        │
└────────────────────────────────┘
```

## 6. Editor evento (bottom sheet a schermo intero)
```
┌────────────────────────────────┐
│              ﹀                │
│ ✕                   ( Salva )  │  pillola blu
│    ttt                         │  titolo grande
├────────────────────────────────┤
│   (● Il mio calendario)(● Dida…│  chip scorrevoli
├────────────────────────────────┤
│ 🕓 Tutto il giorno        ( ○ ) │
│    Mer 7 ott 2026        05:00 │
│    Mer 7 ott 2026        06:00 │
│ 🌐 Ora legale dell'Europa centr.│
│ ↻  Si ripete ogni giorno;      │  → preset / Personalizzata…
│    per 5 volte                 │
├────────────────────────────────┤
│ 👥 Aggiungi persone            │  email + autocompletamento
│    ☑ Invia inviti · Permessi ▾ │
├────────────────────────────────┤
│ 📹 Aggiungi videoconferenza    │  → Google Meet + copia link
├────────────────────────────────┤
│ 📍 Aggiungi posizione          │
├────────────────────────────────┤
│ 🔔 30 minuti prima          ✕  │  tocco: tipo (Notifica /
│ ⏰ Sveglia · 10 minuti · ♪  ✕  │  Sveglia / Email), anticipo,
│    Aggiungi notifica           │  suono con anteprima
├────────────────────────────────┤
│ ●  Colore predefinito          │
│ 💼 Occupato · Visibilità pred. │
│ ≡  Aggiungi descrizione        │
└────────────────────────────────┘
```

## 7. Sveglia (schermo intero, anche con lo schermo bloccato)
```
┌────────────────────────────────┐
│                                │
│             05:00              │
│              ttt               │
│          tra 30 minuti         │
│          📍 Aula magna         │
│                                │
│  ╭────────────╮ ╭────────────╮ │
│  │ Posticipa  │ │   Chiudi   │ │
│  │  10 min ▾  │ │            │ │
│  ╰────────────╯ ╰────────────╯ │
│                                │
│   si ferma da sola dopo 2:00   │
└────────────────────────────────┘
```

## 8. Dialog dell'ambito di una serie
```
┌──────────────────────────────┐
│ Modifica evento ricorrente   │
│ ◉ Solo questo evento         │
│ ○ Questo e i successivi      │
│ ○ Tutti gli eventi           │
│            Annulla    OK     │
└──────────────────────────────┘
```
