"""Crea tests/fixtures/android_room_backup.db: un database con lo stesso schema e la stessa
modalità WAL di quello creato da Room nell'app Android (come un backup caricato su Dropbox)."""
import os
import sqlite3

path = os.path.join(os.path.dirname(__file__), '..', 'tests', 'fixtures', 'android_room_backup.db')
if os.path.exists(path):
    os.remove(path)
con = sqlite3.connect(path)
con.execute('PRAGMA journal_mode=WAL')
con.execute('CREATE TABLE android_metadata (locale TEXT)')
con.execute("INSERT INTO android_metadata VALUES ('it_IT')")
con.execute('CREATE TABLE IF NOT EXISTS `spese` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `data` TEXT NOT NULL, '
            '`destinazione` TEXT, `scopo` TEXT, `categoria` TEXT NOT NULL, `metodo_pagamento` TEXT, `importo` REAL NOT NULL, '
            '`km` REAL NOT NULL, `note` TEXT, `allegato_path` TEXT, `valuta_straniera` INTEGER NOT NULL, `created_at` TEXT)')
con.execute('CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)')
con.execute("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, 'fixture')")
rows = [
    ('2026-01-15', 'Autogrill Àèìòù', 'Cliente Rossi', 'RISTORANTI_CC', 'CC (Carta)', 23.5, 0.0, 'Pranzo 🍝', 'dropbox:/Allegati/2026/01/rec_2026-01-15_20260115_130000.jpg', 0, '2026-01-15 13:00:00'),
    ('2026-01-20', 'Telepass', None, 'TELEPASS', None, 12.4, 0.0, None, None, 0, '2026-01-20 18:00:00'),
    ('2026-02-02', 'Hotel Paris', 'Fiera', 'RISTORANTI_CONTANTI', 'Contanti', 110.0, 0.0, None, '/data/user/0/com.expensepereport.app/files/NoteSpese_Allegati/2026/02/rec_2026-02-02_1.jpg', 1, '2026-02-02 09:00:00'),
]
con.executemany('INSERT INTO spese (data, destinazione, scopo, categoria, metodo_pagamento, importo, km, note, allegato_path, valuta_straniera, created_at) '
                'VALUES (?,?,?,?,?,?,?,?,?,?,?)', rows)
con.commit()
con.execute('PRAGMA wal_checkpoint(FULL)')  # come SpesaRepository.getDatabaseFileForBackup()
con.close()
for suffix in ('-wal', '-shm'):
    if os.path.exists(path + suffix):
        os.remove(path + suffix)
with open(path, 'rb') as f:
    header = f.read(20)
print('creato', path, 'byte WAL (18,19):', header[18], header[19])
