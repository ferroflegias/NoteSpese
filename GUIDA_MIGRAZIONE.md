# 📖 Guida alla Migrazione: Da Supabase a SQLite & Google Drive

Questa guida descrive i passaggi per migrare tutte le spese memorizzate su Supabase nel nuovo sistema offline dell'applicazione Android **Note Spese**, e per spostare tutti gli allegati sul tuo **Google Drive**.

---

## 🛠️ Step 1: Esecuzione dello Script di Esportazione in Locale

Non è necessario installare ambienti complessi. È sufficiente avere **Python 3** installato sul proprio PC.

1. **Apri il terminale / Prompt dei Comandi** e naviga nella cartella del progetto.
2. Assicurati che il file `.streamlit/secrets.toml` contenga le credenziali di Supabase:
   ```toml
   SUPABASE_URL = "https://tuo-progetto.supabase.co"
   SUPABASE_KEY = "la_tua_anon_public_key"
   ```
   *(Oppure lo script ti chiederà di inserirle manualmente se non trova il file)*.

3. **Esegui lo script Python**:
   ```bash
   python3 migrate_supabase_to_sqlite.py
   ```
   *Oppure su Windows:*
   ```cmd
   python migrate_supabase_to_sqlite.py
   ```

4. **Risultato generato**:
   - `expense_report.db`: File Database SQLite pronto per l'importazione nell'App Android.
   - `spese_backup.json`: Backup completo in formato JSON.
   - Cartella `NoteSpese_Allegati/`: Contiene tutti gli allegati scaricati e già suddivisi per anno e mese (es. `NoteSpese_Allegati/2026/01/rec_...jpg`).

---

## ☁️ Step 2: Caricamento della Cartella Allegati su Google Drive

1. Apri **Google Drive** dal tuo browser ([drive.google.com](https://drive.google.com)).
2. Trascina la cartella `NoteSpese_Allegati` creata dallo script all'interno della radice del tuo Google Drive (oppure crea una cartella denominata `NoteSpese_Allegati` e caricavi le sottocartelle `2026`, `2025`, etc.).
3. Gli allegati conserveranno la struttura organizzata per Anno e Mese (`YYYY/MM/`) ed i nomi originali, mantenendo il collegamento 1-a-1 con le spese registrate.

---

## 📱 Step 3: Importazione del Database SQLite nell'App Android

1. Copia il file `expense_report.db` generato sul tuo smartphone Android (es. inviandotelo via Telegram/WhatsApp/Google Drive o tramite cavo USB).
2. Apri l'App **Note Spese** sul tuo telefono.
3. Vai nella sezione **Impostazioni** (Settings).
4. Tocca il pulsante **"Importa Database SQLite"**.
5. Seleziona il file `expense_report.db` scaricato sul telefono.
6. L'App importerà tutte le spese nel database locale Room della memoria dello smartphone.

---

## 🔐 Step 4: Configurazione Google Drive nell'App Android

1. Nella schermata **Impostazioni** dell'App Android, tocca **"Accedi con Google"**.
2. Seleziona il tuo account Google per autorizzare l'accesso a Google Drive.
3. Nella schermata Impostazioni potrai:
   - Toccare **"Apri Cartella Allegati GDrive"** per visualizzare direttamente la cartella degli scontrini.
   - Impostare l'orario del **Backup Automatico Giornaliero** (es. 02:00 di notte).
   - Eseguire un **Backup Manuale** su Google Drive in qualsiasi momento.
   - **Ripristinare** un backup salvato su Google Drive con un tap.
