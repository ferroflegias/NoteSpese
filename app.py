import os
import sqlite3
from datetime import datetime
import pandas as pd
import openpyxl
from openpyxl.styles import PatternFill
from PIL import Image
import streamlit as st

# --- CONFIGURAZIONE PAGINA STREAMLIT ---
st.set_page_config(
    page_title="Note Spese 2026",
    page_icon="🧾",
    layout="centered",
    initial_sidebar_state="collapsed"
)

# --- COSTANTI & DB ---
DB_NAME = "note_spese.db"
ATTACHMENTS_DIR = "allegati"
EXCEL_TEMPLATE = "Note spese 2026_Ferrari.xlsx"

MANDI_NOMI = [
    "Gennaio", "Febbraio", "Marzo", "Aprile", "Maggio", "Giugno",
    "Luglio", "Agosto", "Settembre", "Ottobre", "Novembre", "Dicembre"
]

CATEGORIE_LISTA = [
    "RISTORANTI_CC", "RISTORANTI_CONTANTI",
    "PARCHEGGI_CC", "PARCHEGGI_CONTANTI",
    "CARBURANTE_CC", "CARBURANTE_CARTA",
    "TELEPASS", "NOLO", "ALTRO"
]

CATEGORIA_COLONNA = {
    "TELEPASS": 6,               # Colonna F
    "NOLO": 7,                   # Colonna G
    "PARCHEGGI_CONTANTI": 8,     # Colonna H
    "PARCHEGGI_CC": 9,           # Colonna I
    "RISTORANTI_CONTANTI": 10,   # Colonna J
    "RISTORANTI_CC": 11,         # Colonna K
    "CARBURANTE_CC": 12,         # Colonna L
    "CARBURANTE_CARTA": 13,      # Colonna M
    "ALTRO": 14                  # Colonna N
}

PAGAMENTI_LISTA = ["CC (Carta)", "Contanti", "Carta Carburante"]

FILL_ORANGE = PatternFill(start_color="FFC000", end_color="FFC000", fill_type="solid")
FILL_YELLOW = PatternFill(start_color="FFFF00", end_color="FFFF00", fill_type="solid")

if not os.path.exists(ATTACHMENTS_DIR):
    os.makedirs(ATTACHMENTS_DIR)

def get_db():
    conn = sqlite3.connect(DB_NAME, check_same_thread=False)
    cursor = conn.cursor()
    cursor.execute('''
        CREATE TABLE IF NOT EXISTS spese (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            data TEXT NOT NULL,
            destinazione TEXT,
            scopo TEXT,
            categoria TEXT NOT NULL,
            metodo_pagamento TEXT,
            importo REAL NOT NULL,
            km REAL,
            note TEXT,
            allegato_path TEXT,
            valuta_straniera INTEGER DEFAULT 0
        )
    ''')
    cursor.execute("PRAGMA table_info(spese)")
    cols = [col[1] for col in cursor.fetchall()]
    if 'valuta_straniera' not in cols:
        cursor.execute("ALTER TABLE spese ADD COLUMN valuta_straniera INTEGER DEFAULT 0")
    conn.commit()
    return conn

conn = get_db()

# --- FUNZIONI UTILITY ---
def save_uploaded_photo(uploaded_file, data_spesa):
    if uploaded_file is not None:
        now_str = datetime.now().strftime('%Y%m%d_%H%M%S')
        filename = f"rec_{data_spesa}_{now_str}.jpg"
        save_path = os.path.join(ATTACHMENTS_DIR, filename)
        img = Image.open(uploaded_file)
        img.convert('RGB').save(save_path, "JPEG")
        return save_path
    return None

def genera_excel(anno, modo, m_start, m_end):
    if not os.path.exists(EXCEL_TEMPLATE):
        st.error(f"File modello '{EXCEL_TEMPLATE}' non trovato!")
        return None

    wb = openpyxl.load_workbook(EXCEL_TEMPLATE)
    ROW_OFFSET = 5
    cursor = conn.cursor()

    for m in range(m_start, m_end + 1):
        nome_foglio = MANDI_NOMI[m - 1]
        if nome_foglio not in wb.sheetnames:
            continue

        ws = wb[nome_foglio]
        cursor.execute('''
            SELECT data, destinazione, scopo, categoria, importo, km, note, valuta_straniera 
            FROM spese 
            WHERE strftime('%Y', data) = ? AND strftime('%m', data) = ?
        ''', (str(anno), f"{m:02d}"))
        spese = cursor.fetchall()

        for spesa in spese:
            d_str, dest, scopo, cat_key, imp, km, note, is_foreign = spesa
            dt = datetime.strptime(d_str, "%Y-%m-%d")
            giorno = dt.day
            target_row = giorno + ROW_OFFSET

            if dest:
                curr_dest = str(ws.cell(row=target_row, column=3).value or "").strip()
                if not curr_dest:
                    ws.cell(row=target_row, column=3, value=dest)
                else:
                    dest_list = [d.strip() for d in curr_dest.split('\\')]
                    if dest not in dest_list:
                        ws.cell(row=target_row, column=3, value=f"{curr_dest}\\{dest}")

            if scopo:
                curr_scopo = str(ws.cell(row=target_row, column=4).value or "").strip()
                if not curr_scopo:
                    ws.cell(row=target_row, column=4, value=scopo)
                else:
                    scopo_list = [s.strip() for s in curr_scopo.split('\\')]
                    if scopo not in scopo_list:
                        ws.cell(row=target_row, column=4, value=f"{curr_scopo}\\{scopo}")

            if note:
                curr_note = str(ws.cell(row=target_row, column=15).value or "").strip()
                if not curr_note:
                    ws.cell(row=target_row, column=15, value=note)
                else:
                    note_list = [n.strip() for n in curr_note.split(';')]
                    if note not in note_list:
                        ws.cell(row=target_row, column=15, value=f"{curr_note}; {note}")

            col_idx = CATEGORIA_COLONNA.get(cat_key)
            if col_idx and imp:
                imp_val = round(imp, 2)
                cell = ws.cell(row=target_row, column=col_idx)
                curr_val = cell.value

                if curr_val is None or curr_val == "":
                    cell.value = imp_val
                    if is_foreign == 1:
                        cell.fill = FILL_ORANGE
                elif isinstance(curr_val, (int, float)):
                    cell.value = f"={curr_val}+{imp_val}"
                    if is_foreign == 1 or cell.fill == FILL_ORANGE or cell.fill == FILL_YELLOW:
                        cell.fill = FILL_YELLOW
                elif isinstance(curr_val, str) and curr_val.startswith("="):
                    cell.value = f"{curr_val}+{imp_val}"
                    if is_foreign == 1 or cell.fill == FILL_ORANGE or cell.fill == FILL_YELLOW:
                        cell.fill = FILL_YELLOW

    if modo == 'anno':
        output_filename = f"Note_Spese_Anno_{anno}.xlsx"
    elif modo == 'range':
        output_filename = f"Note_Spese_{MANDI_NOMI[m_start-1]}_{MANDI_NOMI[m_end-1]}_{anno}.xlsx"
    else:
        output_filename = f"Note_Spese_{MANDI_NOMI[m_start-1]}_{anno}.xlsx"

    wb.save(output_filename)
    return output_filename

# --- APPLICAZIONE INTERFACCIA WEB (TAB) ---
st.title("🧾 Gestione Note Spese 2026")

tab1, tab2, tab3 = st.tabs(["➕ Nuova Spesa", "📑 Registri / Modifica", "📊 Export Excel"])

# --- TAB 1: REGISTRAZIONE ---
with tab1:
    st.subheader("Registra Spesa")
    
    col_d, col_w = st.columns([1, 1])
    with col_d:
        data_input = st.date_input("When (Data)", datetime.now())
    with col_w:
        dest_input = st.text_input("Where (Destinazione)")

    scopo_input = st.text_input("Why (Scopo)")

    st.write("**What (Categoria)**")
    cat_selection = st.radio(
        "Categoria",
        options=["🍽️ Bar/Rist/Alb", "🅿️ Parcheggio/Taxi", "⛽ Carburante", "🛣️ Telepass", "Altro"],
        horizontal=True,
        label_visibility="collapsed"
    )

    st.write("**Payment (Metodo Pagamento)**")
    pay_selection = st.radio(
        "Pagamento",
        options=["💳 CC", "💶 Contanti", "📄⛽ Carta Carburante"],
        horizontal=True,
        label_visibility="collapsed"
    )

    col_imp, col_curr = st.columns([2, 1])
    with col_imp:
        importo_input = st.number_input("Value (€)", min_value=0.0, step=0.5, format="%.2f")
    with col_curr:
        st.write("")
        st.write("")
        is_foreign = st.checkbox("Non € (🔣)")

    note_input = st.text_area("Notes", height=70)
    
    # Upload Foto o Scatto da Fotocamera
    uploaded_photo = st.file_uploader("📷 Foto Scontrino", type=["jpg", "png", "jpeg"])

    if st.button("💾 Salva Spesa", type="primary", use_container_width=True):
        if importo_input <= 0 and "Telepass" not in cat_selection:
            st.warning("Inserisci un importo valido!")
        else:
            # Mappatura categorie
            if "Bar" in cat_selection:
                cat_key = "RISTORANTI_CC" if "CC" in pay_selection else "RISTORANTI_CONTANTI"
            elif "Parcheggio" in cat_selection:
                cat_key = "PARCHEGGI_CC" if "CC" in pay_selection else "PARCHEGGI_CONTANTI"
            elif "Carburante" in cat_selection:
                cat_key = "CARBURANTE_CC" if "CC" in pay_selection else "CARBURANTE_CARTA"
            elif "Telepass" in cat_selection:
                cat_key = "TELEPASS"
            else:
                cat_key = "ALTRO"

            metodo_str = "CC (Carta)" if "CC" in pay_selection else ("Contanti" if "Contanti" in pay_selection else "Carta Carburante")
            d_str = data_input.strftime("%Y-%m-%d")

            photo_path = save_uploaded_photo(uploaded_photo, d_str)

            cursor = conn.cursor()
            cursor.execute('''
                INSERT INTO spese (data, destinazione, scopo, categoria, metodo_pagamento, importo, km, note, allegato_path, valuta_straniera)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ''', (d_str, dest_input, scopo_input, cat_key, metodo_str, importo_input, 0.0, note_input, photo_path, 1 if is_foreign else 0))
            conn.commit()
            st.success("Spesa salvata correttamente!")
            st.rerun()

# --- TAB 2: CONSULTAZIONE & MODIFICA ---
with tab2:
    st.subheader("Archivio Spese Registrate")
    
    df_spese = pd.read_sql_query("SELECT * FROM spese ORDER BY data DESC", conn)
    
    if df_spese.empty:
        st.info("Nessuna spesa memorizzata nel database.")
    else:
        st.dataframe(
            df_spese[['id', 'data', 'destinazione', 'scopo', 'categoria', 'metodo_pagamento', 'importo', 'valuta_straniera', 'note']],
            use_container_width=True
        )
        
        st.divider()
        st.subheader("Modifica / Elimina Spesa")
        
        record_id = st.number_input("Inserisci ID spesa da modificare/eliminare", min_value=1, step=1)
        
        row = df_spese[df_spese['id'] == record_id]
        if not row.empty:
            rec = row.iloc[0]
            
            with st.form("edit_form"):
                e_data = st.date_input("Data", datetime.strptime(rec['data'], "%Y-%m-%d"))
                e_dest = st.text_input("Destinazione", rec['destinazione'] or "")
                e_scopo = st.text_input("Scopo", rec['scopo'] or "")
                e_cat = st.selectbox("Categoria DB", CATEGORIE_LISTA, index=CATEGORIE_LISTA.index(rec['categoria']) if rec['categoria'] in CATEGORIE_LISTA else 8)
                e_pay = st.selectbox("Pagamento", PAGAMENTI_LISTA, index=PAGAMENTI_LISTA.index(rec['metodo_pagamento']) if rec['metodo_pagamento'] in PAGAMENTI_LISTA else 0)
                e_imp = st.number_input("Importo (€)", value=float(rec['importo']), step=0.5)
                e_foreign = st.checkbox("Valuta non-€ (🔣)", value=bool(rec['valuta_straniera']))
                e_note = st.text_area("Note", rec['note'] or "")

                if rec['allegato_path'] and os.path.exists(rec['allegato_path']):
                    st.image(rec['allegato_path'], caption="Allegato attuale", width=250)
                
                c_sub, c_del = st.columns([1, 1])
                with c_sub:
                    save_mod = st.form_submit_button("💾 Salva Modifiche")
                with c_del:
                    delete_mod = st.form_submit_button("🗑️ Elimina Record", type="secondary")
                
                if save_mod:
                    cursor = conn.cursor()
                    cursor.execute('''
                        UPDATE spese 
                        SET data=?, destinazione=?, scopo=?, categoria=?, metodo_pagamento=?, importo=?, note=?, valuta_straniera=?
                        WHERE id=?
                    ''', (e_data.strftime("%Y-%m-%d"), e_dest, e_scopo, e_cat, e_pay, e_imp, e_note, 1 if e_foreign else 0, int(record_id)))
                    conn.commit()
                    st.success("Record aggiornato!")
                    st.rerun()
                    
                if delete_mod:
                    cursor = conn.cursor()
                    cursor.execute("DELETE FROM spese WHERE id=?", (int(record_id),))
                    conn.commit()
                    st.warning("Record eliminato!")
                    st.rerun()

# --- TAB 3: ESPORTAZIONE EXCEL ---
with tab3:
    st.subheader("Generazione Modello Excel 2026")
    
    anno_exp = st.number_input("Anno Esportazione", value=2026, step=1)
    tipo_exp = st.selectbox("Modalità Esportazione", ["Mese Singolo", "Range di Mesi", "Anno Completo"])
    
    if tipo_exp == "Mese Singolo":
        m_sel = st.selectbox("Seleziona Mese", range(1, 13), format_func=lambda x: MANDI_NOMI[x-1])
        m_in, m_fi = m_sel, m_sel
        modo_str = 'singolo'
    elif tipo_exp == "Range di Mesi":
        col_m1, col_m2 = st.columns(2)
        with col_m1:
            m_in = st.selectbox("Mese Inizio", range(1, 13), format_func=lambda x: MANDI_NOMI[x-1])
        with col_m2:
            m_fi = st.selectbox("Mese Fine", range(1, 13), index=11, format_func=lambda x: MANDI_NOMI[x-1])
        modo_str = 'range'
    else:
        m_in, m_fi = 1, 12
        modo_str = 'anno'

    if st.button("📊 Genera e Scarica Excel", type="primary"):
        res_file = genera_excel(anno_exp, modo_str, m_in, m_fi)
        if res_file and os.path.exists(res_file):
            with open(res_file, "rb") as f:
                st.download_button(
                    label="📥 Scarica File Excel Generato",
                    data=f,
                    file_name=res_file,
                    mime="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                )