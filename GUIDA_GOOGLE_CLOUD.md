# 🚀 Guida Configurazione Google Cloud Console e Distribuzione APK

Questa guida fornisce le istruzioni dettagliate passo-passo per configurare **una sola volta** il progetto su **Google Cloud Console**, consentendo a te e a qualsiasi tuo collega di installare l'APK dell'applicazione **PerExpenseReport** e utilizzare il proprio spazio **Google Drive** per il salvataggio degli allegati (ricevute, scontrini, PDF) e i backup del database.

---

## 📋 Informazioni Chiave dell'Applicazione

* **Nome Pacchetto (Package Name):** `com.expensepereport.app`
* **Impronta Digitale SHA-1 del Keystore (Sign Certificate):**
  `5D:6C:3B:16:C8:09:2E:4D:4A:8E:1D:B7:F5:B2:D3:FD:AD:17:51:69`

*(Nota: L'APK generato in questo repository usa un keystore dedicato denominato `release.keystore`, garantendo che l'impronta SHA-1 rimanga sempre identica per tutte le build ed esportazioni).*

---

## 🛠️ PARTE 1: Configurazione Google Cloud Console (Da fare 1 sola volta)

### 1️⃣ Creazione del Progetto Google Cloud
1. Vai su [Google Cloud Console](https://console.cloud.google.com/) ed effettua l'accesso con il tuo account Google.
2. In alto a sinistra, clicca sul menu a tendina dei progetti e seleziona **"Nuovo Progetto"** (New Project).
3. Inserisci il nome del progetto (es. `PerExpenseReport`) e clicca su **Crea** (Create).
4. Seleziona il progetto appena creato.

---

### 2️⃣ Abilitare l'API Google Drive
1. Nel menu laterale di sinistra, vai su **API e servizi** (APIs & Services) > **Libreria** (Library).
2. Cerca `Google Drive API` nella barra di ricerca.
3. Seleziona **Google Drive API** dai risultati e clicca su **Abilita** (Enable).

---

### 3️⃣ Configurazione della Schermata di Consenso OAuth (OAuth Consent Screen)
*Questa procedura permette a qualsiasi utente/collega con un account Google di accedere dall'app.*

1. Nel menu laterale, vai su **API e servizi** > **Schermata consenso OAuth** (OAuth consent screen).
2. Seleziona il tipo di utente: **Esterno** (External) e clicca su **Crea** (Create).
3. **Informazioni sull'app**:
   - **Nome app**: `PerExpenseReport`
   - **Email per l'assistenza utenti**: Seleziona il tuo indirizzo email.
   - **Dati di contatto dello sviluppatore**: Inserisci il tuo indirizzo email.
4. Clicca su **Salva e continua**.
5. **Ambito (Scopes)**:
   - Clicca su **Aggiungi o rimuovi ambiti** (Add or Remove Scopes).
   - Cerca ed aggiungi l'ambito: `.../auth/drive.file` (Permette all'app di accedere solo ai file e cartelle che essa stessa crea in Google Drive).
   - Clicca su **Aggiorna** e poi **Salva e continua**.
6. **Utenti di test** (Test Users):
   - Clicca direttamente su **Salva e continua**.
7. **Riepilogo e Pubblicazione**:
   - Torna alla schermata principale **Schermata consenso OAuth**.
   - Sotto la sezione **Stato pubblicazione** (Publishing status), clicca sul pulsante **PUBBLICA APP** (PUBLISH APP) e conferma.
   - *Importante:* Pubblicando l'app (passando da "In prova/Testing" a "In produzione"), **tutti i tuoi colleghi** potranno accedere con il proprio account Google senza bisogno di essere aggiunti manualmente come tester.

---

### 4️⃣ Creazione delle Credenziali Client ID Android
1. Nel menu laterale, vai su **API e servizi** > **Credenziali** (Credentials).
2. Clicca in alto su **+ Crea credenziali** (+ Create Credentials) > **ID client OAuth** (OAuth client ID).
3. Seleziona come **Tipo di applicazione**: `Android`.
4. Compila i campi richiesti:
   - **Nome**: `Android App - PerExpenseReport`
   - **Nome pacchetto**: `com.expensepereport.app`
   - **Impronta digitale del certificato SHA-1**:
     `5D:6C:3B:16:C8:09:2E:4D:4A:8E:1D:B7:F5:B2:D3:FD:AD:17:51:69`
5. Clicca su **Crea** (Create).

---

## 📦 PARTE 2: Generazione dell'APK e Condivisione con i Colleghi

### 1️⃣ Generare il file APK
Per compilare l'APK pronto da condividere, esegui il seguente comando nel terminale dalla cartella principale del progetto:

```bash
cd ExpenseReport
./gradlew assembleRelease
```

Il file APK firmato verrà generato nella seguente posizione:
`ExpenseReport/app/build/outputs/apk/release/PerExpenseReport-release.apk`

*(È possibile utilizzare anche `PerExpenseReport-debug.apk` in quanto entrambe le build condividono lo stesso keystore firmato).*

---

### 2️⃣ Condividere l'APK con i Colleghi
Puoi inviare il file `.apk` ai tuoi colleghi tramite qualsiasi canale:
* **WhatsApp / Telegram**
* **Google Drive** (caricando l'APK e condividendo il link di download)
* **Email / WeTransfer**

---

### 3️⃣ Istruzioni di Installazione per i Colleghi

Fornisci queste semplici istruzioni ai tuoi colleghi:

1. **Scaricare ed Installare l'APK**:
   - Scarica il file `PerExpenseReport-release.apk` sul telefono Android.
   - Clicca sul file scaricato per installarlo. Se Android mostra l'avviso *"Installazione da fonti sconosciute"*, seleziona **Consenti** / **Autorizza per questa fonte**.

2. **Primo Accesso e Collegamento Google Drive**:
   - Apri l'App **PerExpenseReport**.
   - Vai nella scheda **⚙️ Impostazioni** in basso a destra.
   - Clicca sul pulsante **🔑 Accedi con Google**.
   - Seleziona il proprio account Google personale o aziendale e concedi l'autorizzazione per Google Drive.

3. **Funzionalità Automatiche Attive**:
   - **Allegati**: Tutti gli scontrini e le ricevute caricate verranno automaticamente salvati nella cartella `NoteSpese_Allegati/ANNO/MESE/` nel Google Drive dell'utente.
   - **Backup**: Ogni giorno all'orario impostato (oppure premendo *"Esegui Backup Ora su GDrive"* in Impostazioni), l'app salverà un backup criptato del database nella cartella `NoteSpese_Backups/` nel Google Drive dell'utente.

---

## 🔍 Risoluzione dei Problemi Comuni

* **Errore Google 10 (DEVELOPER_ERROR)**:
  - Assicurati che su Google Cloud Console il Client ID Android sia stato creato con Nome Pacchetto `com.expensepereport.app` e l'impronta SHA-1 `5D:6C:3B:16:C8:09:2E:4D:4A:8E:1D:B7:F5:B2:D3:FD:AD:17:51:69`.
  - Verifica che la schermata di consenso OAuth sia stata **Pubblicata** ("In produzione").

* **Errore Google 12500**:
  - Verifica di aver abilitato l'API Google Drive nella libreria del progetto Google Cloud.
