# OrbitaWeb

App Android che apre il web server di OrbitaCasa a schermo intero, 
con più sessioni indipendenti (ognuna ha il suo login) e download dei PDF.

Grafica Material 3 come OrbitaNotify: 
segue il tema chiaro/scuro del telefono (su Android 12+ anche i colori dello sfondo), 
schede con bordo, campi e interruttori Material.

- Sessioni: ogni voce ha un profilo WebView proprio (cookie e dati separati), 
  quindi si può passare da una all'altra senza rifare il login. 
  Funziona con server diversi o con lo stesso server e utenti diversi. 
  Il menu ⋮ della scheda permette di modificare o eliminare.
- Aggiunta: a mano, oppure scansionando il QR Indirizzo di Configurazione → Sync (si tiene solo il server). 
  Lo scanner è quello di Google, senza permesso fotocamera.
- Schermata web: barra in alto con nome e server della sessione, 
  freccia per tornare all'elenco, Ricarica e "Apri nel browser".
- Certificato autofirmato: l'app chiede conferma al primo accesso e la ricorda per quella sessione.
- Download: i PDF vanno nella cartella Download e si aprono nel visualizzatore del telefono.
- Richiede Android 10 o successivo.

