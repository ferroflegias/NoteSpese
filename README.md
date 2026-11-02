# 🧾 Gestione Note Spese 2026 — App Android

**PerExpenseReport** è un'applicazione **Android nativa** (APK), sviluppata in **Kotlin** e **Jetpack Compose**, per la registrazione, la reportistica e l'esportazione delle note spese aziendali.

Le spese vengono salvate **in locale sul dispositivo** (database SQLite tramite Room), mentre allegati e backup del database vengono archiviati nel **Dropbox** personale dell'utente (in una cartella riservata all'app). L'app compila automaticamente il **modello Excel ufficiale** delle note spese e genera un **PDF cumulativo degli allegati in scala di grigi** pronto per la stampa.

> 📱 **iPhone**: è disponibile una **WebApp installabile** (cartella [`webapp/`](webapp/)) che usa gli stessi backup Dropbox dell'app Android. Vedi [Uso su iPhone (WebApp)](#-uso-su-iphone-webapp).

> ℹ️ Le versioni precedenti del progetto erano una WebApp in Python/Streamlit con database Supabase: il codice è stato rimosso dal repository ma resta consultabile nella cronologia git.

---

## 🚀 Caratteristiche Principali

L'app è organizzata in 5 schede accessibili dalla barra di navigazione inferiore.

* **➕ Spesa — Inserimento rapido**:
  * Allegato da **fotocamera**, **galleria / file** oppure **file PDF**.
  * **Ritaglio interattivo** della foto dello scontrino prima del salvataggio.
  * **OCR on-device** (Google ML Kit, funziona anche offline) su foto e sulla prima pagina dei PDF: precompila data, esercente, importo, categoria e metodo di pagamento suggeriti.
  * Categorie predefinite: *Ristoranti/Alberghi/Bar, Parcheggi/Taxi/Treni/Voli, Carburante, Telepass, Nolo, Altro*.
  * Metodi di pagamento: *Carta di credito, Contanti, Carta Carburante*; flag per le spese in **valuta estera**.

* **📑 Registri — Consultazione e modifica**:
  * Elenco di tutte le spese registrate, con indicazione degli allegati presenti.
  * Modifica dei dati, sostituzione dell'allegato, apertura dell'allegato (scaricato da Dropbox se necessario) ed eliminazione, previa conferma, con rimozione contestuale del file da Dropbox.

* **📊 Report — KPI mensili**:
  * Totale del mese e totali per metodo di pagamento (Carta, Contanti, Carta Carburante, Telepass).
  * Riepilogo per categoria con importo e numero di spese.

* **📁 Export — Excel & PDF**:
  * **Excel**: compilazione del modello ufficiale per **mese singolo**, **intervallo di mesi** o **anno completo** (es. `Note_Spese_Gennaio_2026.xlsx`).
  * **PDF allegati**: un unico PDF mensile con tutte le immagini impaginate e i PDF uniti in coda, convertiti in **scala di grigi** per risparmiare toner.
  * Condivisione diretta dei file generati (email, WhatsApp, Drive, ecc.).

* **⚙️ Settings — Account, backup e modello**:
  * Collegamento a **Dropbox** con un tocco (nessuna configurazione SHA-1 o Google Cloud).
  * **Backup automatico giornaliero** del database su Dropbox (orario configurabile, default 02:00), backup manuale e **ripristino** da un backup esistente. I backup più vecchi di 30 giorni vengono eliminati automaticamente.
  * Il nome di ogni backup contiene il **nome del dispositivo** che l'ha creato (es. `..._Pixel-8.db`, `..._iPhone.db`).
  * Indicatore **🟢 tutti i dati sono in un backup / 🟠 modifiche non ancora salvate**.
  * **Avviso prima di un ripristino rischioso**: modifiche locali non ancora salvate in un backup, oppure backup scelto più vecchio dell'ultimo disponibile.
  * **Copia di sicurezza automatica** dei dati attuali (cartella `Backups_PreRipristino`) prima di ogni ripristino: se non può essere salvata, il ripristino viene annullato.
  * **Caricamento su Dropbox degli allegati rimasti solo sul telefono** (salvati quando Dropbox non era collegato), così diventano visibili anche dagli altri dispositivi.
  * **Importazione** di un database esistente da file `.db` (SQLite) o `.json` (sostituisce tutte le spese presenti, previa conferma).
  * Caricamento del **modello Excel** (`Note spese 2026.xlsx`) usato per l'esportazione.

### Struttura su Dropbox

L'app ha accesso solo alla propria cartella `Dropbox/App/<nome app Dropbox>/` e non vede il resto del tuo Dropbox.

```
Dropbox/App/<nome app Dropbox>/
├── Allegati/
│   └── AAAA/
│       └── MM/
│           └── rec_<data-spesa>_<AAAAMMGG_HHMMSS>.jpg | .pdf
├── Backups/
│   └── expense_report_backup_<AAAAMMGG_HHMMSS>_<dispositivo>.db   (conservati 30 giorni)
└── Backups_PreRipristino/
    └── expense_report_backup_<AAAAMMGG_HHMMSS>_<dispositivo>.db   (copie di sicurezza, 30 giorni)
```

Se Dropbox non è collegato (o il caricamento fallisce) gli allegati vengono salvati nella memoria privata dell'app sul telefono.

---

## 📲 Installazione dell'APK

1. Ottieni il file `PerExpenseReport-release.apk`: scaricalo dagli *Artifacts* dell'ultima run della GitHub Action **Build Android APK** (scheda *Actions* del repository) oppure compilalo come descritto in [Compilazione dal codice sorgente](#️-compilazione-dal-codice-sorgente).
2. Sul telefono Android abilita l'installazione da **origini sconosciute** per l'app con cui apri il file (browser, file manager, Drive…).
3. Apri l'APK e conferma l'installazione.
4. Al primo avvio:
   * vai in **Settings → 🔗 Collega Dropbox** e autorizza l'accesso (si apre l'app Dropbox o il browser);
   * carica il modello Excel con **📁 Carica Modello Excel (.xlsx)** (è disponibile nel repository: [`modello/Note spese 2026.xlsx`](modello/));
   * facoltativamente importa i dati esistenti con **📥 Importa File Database (.db / .json)**.

**Requisiti**: Android 8.0 (API 26) o superiore. Permessi richiesti: Internet e Fotocamera (i file si scelgono con il selettore di sistema, senza permessi di accesso alla memoria).

---

## 📱 Uso su iPhone (WebApp)

Su iPhone l'app è una **WebApp installabile (PWA)**: nessun account Apple Developer, nessuna installazione da computer, nessuna scadenza. Si apre da Safari e si aggiunge alla schermata Home.

* I dati sono salvati **sull'iPhone** (funziona anche offline) e si scambiano con il telefono Android tramite **gli stessi backup su Dropbox**: il file `.db` è compatibile in entrambe le direzioni.
* Nessun server: il sito su GitHub Pages contiene solo il codice dell'app, le spese restano nell'iPhone e nel tuo Dropbox.
* Funzioni (le stesse dell'app Android):
  * **Spesa**: allegato da **foto** (fotocamera o libreria, con **ritaglio** e rotazione) o **PDF**; **OCR sul dispositivo** (Tesseract.js, lingua italiana) che precompila data, esercente, importo, categoria e pagamento. Il motore OCR (~5 MB) si scarica al primo uso e poi funziona anche offline.
  * **Registri**: modifica ed eliminazione, apertura e **sostituzione dell'allegato**.
  * **Report** mensile.
  * **Export**: **Excel** sul modello ufficiale (mese, intervallo o anno, con le stesse regole: somme nello stesso giorno, evidenziazione della valuta estera) e **PDF degli allegati in scala di grigi** (4 per pagina, PDF uniti in coda). I file si condividono con il pannello di iOS (Mail, WhatsApp, Salva su File…).
  * **Settings**: Dropbox, backup/ripristino con le stesse protezioni dell'app Android, **modello Excel** (caricabile da File oppure il modello standard 2026 incluso), caricamento su Dropbox degli allegati rimasti sul dispositivo, importazione/esportazione del file `.db`.
* Gli allegati vanno su Dropbox nella stessa cartella `Allegati/AAAA/MM/` dell'app Android, quindi sono visibili da entrambi i telefoni. Senza connessione restano sull'iPhone finché non li carichi da *Settings*.

### Uso consigliato (lavoro alternato tra i due telefoni)

1. **All'inizio** ripristina l'ultimo backup (la WebApp lo propone da sola se ne trova uno più recente).
2. Inserisci o modifica le spese.
3. **Alla fine** esegui un backup (la WebApp mostra 🟠 finché ci sono modifiche non salvate).

### Configurazione (una volta sola)

1. **GitHub Pages**: nel repository vai in *Settings → Pages* e in *Build and deployment → Source* scegli **GitHub Actions**. La Action **WebApp (GitHub Pages)** pubblica la WebApp a ogni modifica su `main` all'indirizzo `https://<utente>.github.io/NoteSpese/`. Usa lo stesso secret/variabile `DROPBOX_APP_KEY` dell'APK.
2. **Dropbox**: nella console Dropbox ([dropbox.com/developers/apps](https://www.dropbox.com/developers/apps)) apri la tua app → *Settings → OAuth 2 → Redirect URIs* e aggiungi `https://<utente>.github.io/NoteSpese/` (l'indirizzo esatto è mostrato in fondo alla scheda Settings della WebApp).
3. **iPhone**: apri l'indirizzo in **Safari** → pulsante *Condividi* → **Aggiungi alla schermata Home**. Apri l'app dall'icona → *Settings* → **Collega Dropbox** → ripristina l'ultimo backup.

> Se dopo l'autorizzazione Dropbox non torna all'app installata, usa *Settings → "Il collegamento non torna all'app? Usa il codice"*: Dropbox mostra un codice da incollare nell'app.

### Sviluppo

```bash
cd webapp
npm install
npm test        # test, inclusa la compatibilità con i backup dell'app Android
npm run dev     # server locale
npm run build   # versione di produzione in webapp/dist
```

---

## 🛠️ Tech Stack

* **Linguaggio / UI**: [Kotlin](https://kotlinlang.org/), [Jetpack Compose](https://developer.android.com/jetpack/compose) + Material 3, Navigation Compose
* **Database locale**: [Room](https://developer.android.com/training/data-storage/room) (SQLite)
* **Preferenze**: DataStore Preferences
* **Backup pianificati**: [WorkManager](https://developer.android.com/topic/libraries/architecture/workmanager)
* **Cloud storage**: [Dropbox API v2](https://www.dropbox.com/developers/documentation) (SDK Java/Android, OAuth 2 PKCE)
* **OCR**: [ML Kit Text Recognition](https://developers.google.com/ml-kit/vision/text-recognition)
* **Excel**: [Apache POI](https://poi.apache.org/)
* **Immagini**: [Coil](https://coil-kt.github.io/coil/)

---

## 📂 Struttura del repository

```
NoteSpese/
├── ExpenseReport/                 Progetto Android (Gradle)
│   └── app/src/
│       ├── main/java/com/expensepereport/app/
│       │   ├── MainActivity.kt    Navigazione tra le 5 schede
│       │   ├── data/              Database Room, Dropbox, allegati, impostazioni, categorie
│       │   ├── ui/                Schermate, ritaglio foto, componenti condivisi
│       │   └── util/              Excel/PDF, OCR, immagini, backup pianificato, formati
│       └── test/                  Test unitari (OCR, categorie, formati)
├── webapp/                        WebApp per iPhone (PWA, Vite + sql.js)
├── modello/Note spese 2026.xlsx   Modello Excel da caricare nell'app
└── .github/workflows/             Build e firma dell'APK, pubblicazione della WebApp
```

---

## 🏗️ Compilazione dal codice sorgente

Il progetto Android si trova nella cartella [`ExpenseReport/`](ExpenseReport/). Ogni push su `main` (o su un branch `claude/**`) che modifica l'app avvia la GitHub Action **Build Android APK**, che produce l'APK firmato scaricabile dalla sezione *Artifacts* della run.

### Prerequisiti

* **JDK 17**
* **Android SDK** con platform API 34 (consigliato: Android Studio recente)

### Firma dell'APK (keystore)

Il keystore e le relative password **non sono inclusi nel repository**. Per firmare l'APK:

1. Genera un keystore (una volta sola) e conservalo in un posto sicuro, fuori da git:
   ```bash
   keytool -genkeypair -v -keystore expense-release.keystore -alias releaseKey \
     -keyalg RSA -keysize 2048 -validity 10000
   ```
2. Crea (o integra) il file `ExpenseReport/local.properties`, già escluso da git:
   ```properties
   RELEASE_STORE_FILE=/percorso/assoluto/expense-release.keystore
   RELEASE_STORE_PASSWORD=la_password_del_keystore
   RELEASE_KEY_ALIAS=releaseKey
   RELEASE_KEY_PASSWORD=la_password_della_chiave
   ```
   In alternativa puoi impostare le stesse chiavi come variabili d'ambiente.
3. Per la build automatica su GitHub crea in *Settings → Secrets and variables → Actions* i secret `RELEASE_KEYSTORE_BASE64` (il keystore codificato in base64, es. `base64 -w0 expense-release.keystore`), `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS` e `RELEASE_KEY_PASSWORD`. Senza questi secret la Action si interrompe con un errore esplicito.

Se la configurazione è presente, sia la build di debug sia quella di release vengono firmate con questo keystore. Senza configurazione la build di debug usa la chiave di debug standard di Android e quella di release viene prodotta non firmata.

> ⚠️ Conserva una copia di backup del keystore: senza lo stesso keystore non è possibile installare aggiornamenti sopra l'app esistente.

### Build

```bash
git clone https://github.com/ferroflegias/NoteSpese.git
cd NoteSpese/ExpenseReport

# APK di release
./gradlew assembleRelease
# APK di debug
./gradlew assembleDebug

# Test unitari
./gradlew test
```

L'APK generato si trova in:

```
ExpenseReport/app/build/outputs/apk/release/PerExpenseReport-release.apk
ExpenseReport/app/build/outputs/apk/debug/PerExpenseReport-debug.apk
```

---

## ☁️ Configurazione Dropbox (una volta sola)

L'app usa OAuth 2 con **PKCE**: serve solo l'**App key** (pubblica), nessun client secret e **nessuna impronta SHA-1**. L'App key non dipende dal keystore, quindi cambiare chiave di firma non rompe l'accesso.

1. Vai su [dropbox.com/developers/apps](https://www.dropbox.com/developers/apps) → **Create app**.
2. Scegli **Scoped access** e tipo di accesso **App folder**; dai un nome all'app (es. `NoteSpese-<tuonome>`, il nome deve essere univoco su Dropbox). Sarà anche il nome della cartella in `Dropbox/App/`.
3. Nella scheda **Permissions** abilita `account_info.read`, `files.metadata.read`, `files.content.read`, `files.content.write` e premi **Submit**.
4. Nella scheda **Settings** copia l'**App key**.
5. Rendi disponibile l'App key alla build:
   * **GitHub Actions**: *Settings → Secrets and variables → Actions* → crea il secret (o la variabile) `DROPBOX_APP_KEY`;
   * **build locale**: aggiungi `DROPBOX_APP_KEY=la_tua_app_key` a `ExpenseReport/local.properties`.

L'app può restare in stato *Development*: è sufficiente per l'uso personale (fino a 500 utenti collegati). Se l'App key manca, l'APK funziona comunque ma il pulsante **Collega Dropbox** mostra un avviso e gli allegati restano sul telefono.

> ℹ️ Gli allegati salvati dalle versioni precedenti come link Google Drive restano apribili nel browser dalla scheda **Registri**, ma non vengono inclusi nel PDF cumulativo: se servono, sostituiscili con **Sostituisci / Aggiungi Allegato**.

---

## 📜 Licenza

Questo progetto è distribuito sotto licenza GNU General Public License v3.0 (GPL-3.0). Consulta il file [LICENSE](LICENSE) per ulteriori dettagli.
