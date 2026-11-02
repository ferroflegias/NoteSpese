# 🧾 Gestione Note Spese 2026 — App Android

**PerExpenseReport** è un'applicazione **Android nativa** (APK), sviluppata in **Kotlin** e **Jetpack Compose**, per la registrazione, la reportistica e l'esportazione delle note spese aziendali.

Le spese vengono salvate **in locale sul dispositivo** (database SQLite tramite Room), mentre allegati e backup del database vengono archiviati nel **Dropbox** personale dell'utente (in una cartella riservata all'app). L'app compila automaticamente il **modello Excel ufficiale** delle note spese e genera un **PDF cumulativo degli allegati in scala di grigi** pronto per la stampa.

> ℹ️ Le versioni precedenti del progetto erano una WebApp in Python/Streamlit con database Supabase. Vedi la sezione [Versione legacy (WebApp Streamlit)](#-versione-legacy-webapp-streamlit) più in basso.

---

## 🚀 Caratteristiche Principali

L'app è organizzata in 5 schede accessibili dalla barra di navigazione inferiore.

* **➕ Spesa — Inserimento rapido**:
  * Allegato da **fotocamera**, **galleria / file** oppure **file PDF**.
  * **Ritaglio interattivo** della foto dello scontrino prima del salvataggio.
  * **OCR on-device** (Google ML Kit) su foto e sulla prima pagina dei PDF: precompila data, esercente, importo, categoria e metodo di pagamento suggeriti.
  * Categorie predefinite: *Ristoranti/Alberghi/Bar, Parcheggi/Taxi/Treni/Voli, Carburante, Telepass, Nolo, Altro*.
  * Metodi di pagamento: *Carta di credito, Contanti, Carta Carburante*; flag per le spese in **valuta estera**.

* **📑 Registri — Consultazione e modifica**:
  * Elenco di tutte le spese registrate, con indicazione degli allegati presenti.
  * Modifica dei dati, sostituzione dell'allegato, apertura dell'allegato (scaricato da Dropbox se necessario) ed eliminazione (con rimozione contestuale del file da Dropbox).

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
  * **Importazione** di un database esistente da file `.db` (SQLite) o `.json`.
  * Caricamento del **modello Excel** (`Note spese 2026.xlsx`) usato per l'esportazione.

### Struttura su Dropbox

L'app ha accesso solo alla propria cartella `Dropbox/App/<nome app Dropbox>/` e non vede il resto del tuo Dropbox.

```
Dropbox/App/<nome app Dropbox>/
├── Allegati/
│   └── AAAA/
│       └── MM/
│           └── rec_<data-spesa>_<AAAAMMGG_HHMMSS>.jpg | .pdf
└── Backups/
    └── expense_report_backup_<AAAAMMGG_HHMMSS>.db   (conservati 30 giorni)
```

Se Dropbox non è collegato (o il caricamento fallisce) gli allegati vengono salvati nella memoria privata dell'app sul telefono.

---

## 📲 Installazione dell'APK

1. Ottieni il file `PerExpenseReport-release.apk` compilandolo come descritto in [Compilazione dal codice sorgente](#️-compilazione-dal-codice-sorgente).
2. Sul telefono Android abilita l'installazione da **origini sconosciute** per l'app con cui apri il file (browser, file manager, Drive…).
3. Apri l'APK e conferma l'installazione.
4. Al primo avvio:
   * vai in **Settings → 🔗 Collega Dropbox** e autorizza l'accesso (si apre l'app Dropbox o il browser);
   * carica il modello Excel con **📁 Carica Modello Excel (.xlsx)** (è disponibile nel repository: `Note spese 2026.xlsx`);
   * facoltativamente importa i dati esistenti con **📥 Importa File Database (.db / .json)**.

**Requisiti**: Android 8.0 (API 26) o superiore. Permessi richiesti: Internet, Fotocamera, lettura memoria (solo Android ≤ 12).

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

## 🏗️ Compilazione dal codice sorgente

Il progetto Android si trova nella cartella [`ExpenseReport/`](ExpenseReport/).

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
   In alternativa puoi impostare le stesse chiavi come variabili d'ambiente (utile in CI).
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

## 🔄 Migrazione dati dalla vecchia WebApp (Supabase)

Lo script [`migrate_supabase_to_sqlite.py`](migrate_supabase_to_sqlite.py) esporta i dati dalla vecchia installazione Supabase:

```bash
SUPABASE_URL="https://tuo-progetto.supabase.co" SUPABASE_KEY="la_tua_key" \
  python3 migrate_supabase_to_sqlite.py
```

Lo script produce:

* `expense_report_import.db` (SQLite) e `spese_backup.json` — da importare nell'app con **Settings → 📥 Importa File Database**;
* gli allegati scaricati e già organizzati nella struttura `NoteSpese_Allegati/AAAA/MM/`.

---

## 🗄️ Versione legacy (WebApp Streamlit)

Nella radice del repository sono ancora presenti i file della precedente WebApp Python/Streamlit (`app.py`, `requirements.txt`, `packages.txt`), basata su database e storage **Supabase**. Questa versione **non è più sviluppata** ed è mantenuta solo come riferimento e per la migrazione dei dati: per l'uso quotidiano fare riferimento all'app Android.

---

## 📜 Licenza

Questo progetto è distribuito sotto licenza GNU General Public License v3.0 (GPL-3.0). Consulta il file [LICENSE](LICENSE) per ulteriori dettagli.
