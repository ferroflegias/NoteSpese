"""Legge un file .db come fa l'app Android quando ripristina un backup
(SpesaRepository.importFromFile: tabella 'spese', SELECT *). Uso: python3 android_import_check.py file.db"""
import sqlite3
import sys

con = sqlite3.connect(f'file:{sys.argv[1]}?mode=ro', uri=True)
assert con.execute("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'spese'").fetchone(), 'tabella spese mancante'
con.row_factory = sqlite3.Row
rows = [dict(r) for r in con.execute('SELECT * FROM spese')]
import json
print(json.dumps(rows, ensure_ascii=False))
