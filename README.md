# 🧾 Gestione Note Spese 2026 — App Android

**PerExpenseReport** è un'applicazione **Android nativa** (APK), sviluppata in **Kotlin** e **Jetpack Compose**, per la registrazione, la reportistica e l'esportazione delle note spese aziendali.

Le spese vengono salvate **in locale sul dispositivo** (database SQLite tramite Room), mentre allegati e backup del database vengono archiviati sul **Google Drive** personale dell'utente. L'app compila automaticamente il **modello Excel ufficiale** delle note spese e genera un **PDF cumulativo degli allegati in scala di grigi** pronto per la stampa.

> ℹ️ Le versioni precedenti del progetto erano una WebApp in Python/Streamlit con database Supabase. Vedi la sezione [Versione legacy (WebApp Streamlit)](#-versione-legacy-webapp-streamlit) più in basso.

---

## 🚀 Caratteristiche Principali

L'app è organizzata in 5 schede accessibili dalla barra di navigazione inferiore.

* **➕ Spesa — Inserimento rapido**:
  * Allegato da **fotocamera**, **galleria / Drive** oppure **file PDF**.
  * **Ritaglio interattivo** della foto dello scontrino prima del salvataggio.
  * **OCR on-device** (Google ML Kit) su foto e sulla prima pagina dei PDF: precompila data, esercente, importo, categoria e metodo di pagamento suggeriti.
  * Categorie predefinite: *Ristoranti/Alberghi/Bar, Parcheggi/Taxi/Treni/Voli, Carburante, Telepass, Nolo, Altro*.
  * Metodi di pagamento: *Carta di credito, Contanti, Carta Carburante*; flag per le spese in **valuta estera**.

* **📑 Registri — Consultazione e modifica**:
  * Elenco di tutte le spese registrate, con indicazione degli allegati presenti.
  * Modifica dei dati, sostituzione dell'allegato, apertura dell'allegato su Google Drive ed eliminazione (con rimozione contestuale del file da Drive).

* **📊 Report — KPI mensili**:
  * Totale del mese e totali per metodo di pagamento (Carta, Contanti, Carta Carburante, Telepass).
  * Riepilogo per categoria con importo e numero di spese.

* **📁 Export — Excel & PDF**:
  * **Excel**: compilazione del modello ufficiale per **mese singolo**, **intervallo di mesi** o **anno completo** (es. `Note_Spese_Gennaio_2026.xlsx`).
  * **PDF allegati**: un unico PDF mensile con tutte le immagini impaginate e i PDF uniti in coda, convertiti in **scala di grigi** per risparmiare toner.
  * Condivisione diretta dei file generati (email, WhatsApp, Drive, ecc.).

* **⚙️ Settings — Account, backup e modello**:
  * Accesso con **Account Google** e collegamento rapido alla cartella allegati su Drive.
  * **Backup automatico giornaliero** del database su Google Drive (orario configurabile, default 02:00), backup manuale e **ripristino** da un backup esistente. I backup più vecchi di 30 giorni vengono eliminati automaticamente.
  * **Importazione** di un database esistente da file `.db` (SQLite) o `.json`.
  * Caricamento del **modello Excel** (`Note spese 2026.xlsx`) usato per l'esportazione.

### Struttura su Google Drive

```
NoteSpese_Allegati/
└── AAAA/
    └── MM/
        └── rec_<data-spesa>_<AAAAMMGG_HHMMSS>.jpg | .pdf
NoteSpese_Backups/
└── expense_report_backup_<AAAAMMGG_HHMMSS>.db   (conservati 30 giorni)
```

---

## 📲 Installazione dell'APK

1. Ottieni il file `PerExpenseReport-release.apk` compilandolo come descritto in [Compilazione dal codice sorgente](#️-compilazione-dal-codice-sorgente).
2. Sul telefono Android abilita l'installazione da **origini sconosciute** per l'app con cui apri il file (browser, file manager, Drive…).
3. Apri l'APK e conferma l'installazione.
4. Al primo avvio:
   * vai in **Settings → 🔑 Accedi con Google** per collegare Google Drive;
   * carica il modello Excel con **📁 Carica Modello Excel (.xlsx)** (è disponibile nel repository: `Note spese 2026.xlsx`);
   * facoltativamente importa i dati esistenti con **📥 Importa File Database (.db / .json)**.

**Requisiti**: Android 8.0 (API 26) o superiore. Permessi richiesti: Internet, Fotocamera, lettura memoria (solo Android ≤ 12).

---

## 🛠️ Tech Stack

* **Linguaggio / UI**: [Kotlin](https://kotlinlang.org/), [Jetpack Compose](https://developer.android.com/jetpack/compose) + Material 3, Navigation Compose
* **Database locale**: [Room](https://developer.android.com/training/data-storage/room) (SQLite)
* **Preferenze**: DataStore Preferences
* **Backup pianificati**: [WorkManager](https://developer.android.com/topic/libraries/architecture/workmanager)
* **Cloud storage**: Google Sign-In + [Google Drive API v3](https://developers.google.com/drive)
* **OCR**: [ML Kit Text Recognition](https://developers.google.com/ml-kit/vision/text-recognition)
* **Excel**: [Apache POI](https://poi.apache.org/)
* **Immagini**: [Coil](https://coil-kt.github.io/coil/)

---

## 🏗️ Compilazione dal codice sorgente

Il progetto Android si trova nella cartella [`ExpenseReport/`](ExpenseReport/).

### Prerequisiti

* **JDK 17**
* **Android SDK** con platform API 34 (consigliato: Android Studio recente)

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

Entrambe le build sono firmate con il keystore incluso nel progetto (`ExpenseReport/app/release.keystore`), in modo che l'impronta SHA-1 resti la stessa per la configurazione OAuth di Google.

---

## ☁️ Configurazione Google Cloud (Accesso Google / Drive)

Per far funzionare l'accesso con Google e il salvataggio su Drive è necessario un progetto su [Google Cloud Console](https://console.cloud.google.com/):

1. Abilita la **Google Drive API**.
2. Configura la **Schermata di consenso OAuth** e impostane lo stato su **In produzione** (oppure aggiungi il tuo account tra gli utenti di test).
3. Crea un **ID client OAuth** di tipo **Android** con:
   * **Package name**: `com.expensepereport.app`
   * **Impronta SHA-1**: `5D:6C:3B:16:C8:09:2E:4D:4A:8E:1D:B7:F5:B2:D3:FD:AD:17:51:69`

Package name e impronta SHA-1 sono mostrati anche nell'app, nella scheda **Settings**. Se l'accesso fallisce con *errore 10 (DEVELOPER_ERROR)*, verifica che questi valori corrispondano.

---

## 🔄 Migrazione dati dalla vecchia WebApp (Supabase)

Lo script [`migrate_supabase_to_sqlite.py`](migrate_supabase_to_sqlite.py) esporta i dati dalla vecchia installazione Supabase:

```bash
SUPABASE_URL="https://tuo-progetto.supabase.co" SUPABASE_KEY="la_tua_key" \
  python3 migrate_supabase_to_sqlite.py
```

Lo script produce:

* `expense_report_import.db` (SQLite) e `spese_backup.json` — da importare nell'app con **Settings → 📥 Importa File Database**;
* gli allegati scaricati e già organizzati nella struttura `NoteSpese_Allegati/AAAA/MM/`, da caricare sul proprio Google Drive.

---

## 🗄️ Versione legacy (WebApp Streamlit)

Nella radice del repository sono ancora presenti i file della precedente WebApp Python/Streamlit (`app.py`, `requirements.txt`, `packages.txt`), basata su database e storage **Supabase**. Questa versione **non è più sviluppata** ed è mantenuta solo come riferimento e per la migrazione dei dati: per l'uso quotidiano fare riferimento all'app Android.

---

## 📜 Licenza

Questo progetto è distribuito sotto licenza GNU General Public License v3.0 (GPL-3.0). Consulta il file [LICENSE](LICENSE) per ulteriori dettagli.
