# 📖 Guida alla Migrazione: Da Supabase a SQLite & Google Drive

Questa guida descrive i passaggi per migrare tutte le spese memorizzate su Supabase nel nuovo sistema offline dell'applicazione Android **Note Spese**, e per spostare tutti gli allegati sul tuo **Google Drive**.

---

## 🛠️ Step 1: Esecuzione dello Script di Esportazione in Locale

1. Apri il terminale / Prompt dei Comandi nella cartella del progetto.
2. Assicurati che il file `.streamlit/secrets.toml` contenga le credenziali di Supabase:
   ```toml
   SUPABASE_URL = "https://tuo-progetto.supabase.co"
   SUPABASE_KEY = "la_tua_anon_public_key"
   ```

3. **Esegui lo script Python**:
   ```bash
   python3 migrate_supabase_to_sqlite.py
   ```
   *Oppure su Windows:*
   ```cmd
   python migrate_supabase_to_sqlite.py
   ```

4. **Risultato generato**:
   - `expense_report_import.db`: File Database SQLite pronto per l'importazione nell'App Android.
   - `spese_backup.json`: Backup completo in formato JSON.
   - Cartella `NoteSpese_Allegati/`: Contiene tutti gli allegati scaricati e suddivisi per anno e mese (`NoteSpese_Allegati/YYYY/MM/`).

---

## ☁️ Step 2: Caricamento della Cartella Allegati su Google Drive

1. Apri **Google Drive** dal tuo browser ([drive.google.com](https://drive.google.com)).
2. Trascina la cartella `NoteSpese_Allegati` creata dallo script all'interno della radice del tuo Google Drive.
3. Gli allegati conserveranno la struttura organizzata per Anno e Mese (`YYYY/MM/`) mantenendo il collegamento 1-a-1 con le spese registrate.

---

## 📱 Step 3: Importazione dei Dati nell'App Android

1. Copia il file `expense_report_import.db` (o `spese_backup.json`) sul tuo smartphone Android.
2. Apri l'App **Note Spese** sul tuo telefono.
3. Vai nella sezione **Impostazioni** (Settings).
4. Tocca il pulsante **"Importa File Database (.db / .json)"**.
5. Seleziona il file `expense_report_import.db` o `spese_backup.json` presente sul telefono.
6. L'App importerà tutte le spese nel database locale dell'applicazione.
