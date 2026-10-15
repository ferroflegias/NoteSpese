#!/usr/bin/env python3
"""
Migraton script: Supabase -> SQLite / JSON + GDrive Folder Attachments Structure

This script downloads all expense records from Supabase PostgreSQL table 'spese',
creates a local SQLite database 'expense_report.db' and a 'spese_backup.json' file,
and downloads all attachment files (photos and PDFs) from Supabase Storage bucket 'allegati-spese'
organizing them in the Google Drive folder structure: NoteSpese_Allegati/YYYY/MM/filename.ext
"""

import os
import sys
import json
import sqlite3
import urllib.request
from datetime import datetime

# Attempt to read secrets from .streamlit/secrets.toml or prompt user
def get_supabase_credentials():
    url = os.environ.get("SUPABASE_URL")
    key = os.environ.get("SUPABASE_KEY")

    secrets_path = os.path.join(".streamlit", "secrets.toml")
    if os.path.exists(secrets_path):
        try:
            with open(secrets_path, "r", encoding="utf-8") as f:
                content = f.read()
                for line in content.splitlines():
                    if line.startswith("SUPABASE_URL"):
                        url = line.split("=")[1].strip().strip('"').strip("'")
                    elif line.startswith("SUPABASE_KEY"):
                        key = line.split("=")[1].strip().strip('"').strip("'")
        except Exception as e:
            print(f"Warning reading secrets.toml: {e}")

    if not url or not key:
        print("--- SUPABASE CREDENTIALS NEEDED ---")
        url = url or input("Enter SUPABASE_URL: ").strip()
        key = key or input("Enter SUPABASE_KEY: ").strip()

    return url, key

def main():
    print("🚀 Starting Supabase to Local Migration...")
    url, key = get_supabase_credentials()

    if not url or not key:
        print("❌ Error: Missing Supabase URL or Key!")
        sys.exit(1)

    print(f"Connecting to Supabase at: {url}")

    # Fetch expenses via Supabase REST API directly
    req_url = f"{url.rstrip('/')}/rest/v1/spese?select=*&order=id.asc"
    req = urllib.request.Request(req_url, headers={
        "apikey": key,
        "Authorization": f"Bearer {key}"
    })

    try:
        with urllib.request.urlopen(req) as response:
            expenses_data = json.loads(response.read().decode("utf-8"))
    except Exception as e:
        print(f"❌ Error fetching data from Supabase: {e}")
        sys.exit(1)

    print(f"✅ Fetched {len(expenses_data)} expense records.")

    # 1. Prepare Local Output Folders
    attachments_root = "NoteSpese_Allegati"
    os.makedirs(attachments_root, exist_ok=True)

    # 2. Create Local SQLite Database (Matching Android Room Entity Schema)
    db_filename = "expense_report.db"
    if os.path.exists(db_filename):
        os.remove(db_filename)

    conn = sqlite3.connect(db_filename)
    cursor = conn.cursor()

    cursor.execute("""
        CREATE TABLE IF NOT EXISTS spese (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            data TEXT NOT NULL,
            destinazione TEXT,
            scopo TEXT,
            categoria TEXT NOT NULL,
            metodo_pagamento TEXT,
            importo REAL NOT NULL DEFAULT 0.0,
            km REAL NOT NULL DEFAULT 0.0,
            note TEXT,
            allegato_path TEXT,
            valuta_straniera INTEGER NOT NULL DEFAULT 0,
            created_at TEXT
        );
    """)

    migrated_count = 0
    downloaded_attachments = 0

    for record in expenses_data:
        record_id = record.get("id")
        data_str = record.get("data", datetime.now().strftime("%Y-%m-%d"))
        destinazione = record.get("destinazione")
        scopo = record.get("scopo")
        categoria = record.get("categoria", "ALTRO")
        metodo_pagamento = record.get("metodo_pagamento")
        importo = float(record.get("importo") or 0.0)
        km = float(record.get("km") or 0.0)
        note = record.get("note")
        original_allegato_url = record.get("allegato_path")
        valuta_straniera = int(record.get("valuta_straniera") or 0)
        created_at = record.get("created_at")

        new_allegato_relative_path = None

        # Process attachment if exists
        if original_allegato_url and isinstance(original_allegato_url, str) and original_allegato_url.startswith("http"):
            try:
                # Parse YYYY and MM
                dt = datetime.strptime(data_str, "%Y-%m-%d")
                year_str = dt.strftime("%Y")
                month_str = dt.strftime("%m")

                # Extract original filename or create clean name
                clean_url = original_allegato_url.split("?")[0]
                ext = clean_url.split(".")[-1].lower()
                if ext not in ["jpg", "jpeg", "png", "pdf"]:
                    ext = "jpg"

                filename = os.path.basename(clean_url)
                if not filename or len(filename) < 3:
                    filename = f"rec_{data_str}_{record_id}.{ext}"

                target_dir = os.path.join(attachments_root, year_str, month_str)
                os.makedirs(target_dir, exist_ok=True)
                target_file_path = os.path.join(target_dir, filename)

                # Download file
                print(f"  Downloading attachment #{record_id} -> {target_file_path} ...")
                urllib.request.urlretrieve(original_allegato_url, target_file_path)
                downloaded_attachments += 1

                # Relative path for GDrive folder structure
                new_allegato_relative_path = f"{year_str}/{month_str}/{filename}"
            except Exception as att_err:
                print(f"  ⚠️ Warning: Could not download attachment for ID #{record_id}: {att_err}")
                new_allegato_relative_path = original_allegato_url

        cursor.execute("""
            INSERT INTO spese (id, data, destinazione, scopo, categoria, metodo_pagamento, importo, km, note, allegato_path, valuta_straniera, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """, (
            record_id, data_str, destinazione, scopo, categoria, metodo_pagamento,
            importo, km, note, new_allegato_relative_path, valuta_straniera, created_at
        ))
        migrated_count += 1

    conn.commit()
    conn.close()

    # 3. Save JSON Backup
    with open("spese_backup.json", "w", encoding="utf-8") as f_json:
        json.dump(expenses_data, f_json, indent=2, ensure_ascii=False)

    print("\n" + "="*60)
    print("🎉 MIGRATION SUCCESSFUL!")
    print(f"📊 Total Expense Records Migrated: {migrated_count}")
    print(f"🖼️ Total Attachments Downloaded: {downloaded_attachments}")
    print(f"📁 Local SQLite File Generated: {os.path.abspath(db_filename)}")
    print(f"📄 Local JSON File Generated: {os.path.abspath('spese_backup.json')}")
    print(f"📂 Attachments Organized In: {os.path.abspath(attachments_root)}/YYYY/MM/")
    print("="*60)

if __name__ == "__main__":
    main()
