import os
import io
from datetime import datetime
import pandas as pd
import openpyxl
from openpyxl.styles import PatternFill
from PIL import Image
import streamlit as st
from supabase import create_client, Client

# --- CONFIGURAZIONE PAGINA STREAMLIT ---
st.set_page_config(
    page_title="Note Spese 2026",
    page_icon="🧾",
    layout="centered",
    initial_sidebar_state="collapsed"
)

# --- SYSTEM LOGIN ---
def check_password():
    if st.session_state.get("password_correct", False):
        return True

    pwd_input = st.text_input("🔑 Inserisci la Password di accesso", type="password")
    
    if pwd_input:
        if pwd_input == st.secrets["PASSWORD"]:
            st.session_state["password_correct"] = True
            st.rerun()
        else:
            st.error("😕 Password errata")
            return False
            
    return False

if not check_password():
    st.stop()

# --- CONNESSIONE SUPABASE ---
@st.cache_resource
def init_supabase() -> Client:
    url = st.secrets["SUPABASE_URL"]
    key = st.secrets["SUPABASE_KEY"]
    return create_client(url, key)

supabase = init_supabase()

# --- COSTANTI ---
BUCKET_NAME = "allegati-spese"
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

# --- UTILITY UPLOAD STORAGE & EXCEL ---
def save_uploaded_photo(uploaded_file, data_spesa, user_id="default_user"):
    if uploaded_file is not None:
        try:
            dt = datetime.strptime(data_spesa, "%Y-%m-%d")
            anno_mese = dt.strftime("%Y-%m")
            now_str = datetime.now().strftime('%Y%m%d_%H%M%S')
            
            filename = f"rec_{data_spesa}_{now_str}.jpg"
            storage_path = f"{user_id}/{anno_mese}/{filename}"

            img = Image.open(uploaded_file)
            img_byte_arr = io.BytesIO()
            img.convert('RGB').save(img_byte_arr, format='JPEG')
            file_bytes = img_byte_arr.getvalue()

            supabase.storage.from_(BUCKET_NAME).upload(
                path=storage_path,
                file=file_bytes,
                file_options={"content-type": "image/jpeg"}
            )

            return supabase.storage.from_(BUCKET_NAME).get_public_url(storage_path)

        except Exception as e:
            st.error(f"Errore durante l'upload della foto su Supabase Storage: {e}")
            return None
    return None

def delete_photo_from_storage(public_url):
    if not public_url or not isinstance(public_url, str):
        return
    
    try:
        target_token = f"{BUCKET_NAME}/"
        if target_token in public_url:
            storage_path = public_url.split(target_token)[-1]
            supabase.storage.from_(BUCKET_NAME).remove([storage_path])
    except Exception as e:
        st.warning(f"Impossibile eliminare l'immagine dallo Storage: {e}")

def genera_excel(anno, modo, m_start, m_end):
    if not os.path.exists(EXCEL_TEMPLATE):
        st.error(f"File modello '{EXCEL_TEMPLATE}' non trovato!")
        return None

    wb = openpyxl.load_workbook(EXCEL_TEMPLATE)
    ROW_OFFSET = 5

    for m in range(m_start, m_end + 1):
        nome_foglio = MANDI_NOMI[m - 1]
        if nome_foglio not in wb.sheetnames:
            continue

        ws = wb[nome_foglio]
        
        start_date = f"{anno}-{m:02d}-01"
        end_date = f"{anno+1}-01-01" if m == 12 else f"{anno}-{m+1:02d}-01"

        response = supabase.table("spese") \
            .select("*") \
            .gte("data", start_date) \
            .lt("data", end_date) \
            .execute()
        
        spese = response.data

        for spesa in spese:
            d_str = spesa["data"]
            dest = spesa.get("destinazione")
            scopo = spesa.get("scopo")
            cat_key = spesa.get("categoria")
            imp = spesa.get("importo", 0.0)
            note = spesa.get("note")
            is_foreign = spesa.get("valuta_straniera", 0)

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
                imp_val = round(float(imp), 2)
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

# --- INTERFACCIA STREAMLIT ---
st.title("🧾 Gestione Note Spese 2026")

tab1, tab2, tab3, tab4 = st.tabs(["➕ Nuova Spesa", "📑 Registri / Modifica", "📊 Report Spese", "📁 Export Excel"])

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
        options=["🍽️ Bar/Rist/Alb", "🅿️ Parcheggio/Taxi", "⛽ Carburante", "🛣️ Telepass", "🚗 Nolo", "Altro"],
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

    # Campo note con gestione reset via Session State
    if "input_note" not in st.session_state:
        st.session_state["input_note"] = ""
        
    note_input = st.text_area("Notes", value=st.session_state["input_note"], height=70, key="note_widget")
    uploaded_photo = st.file_uploader("📷 Foto Scontrino", type=["jpg", "png", "jpeg"])

    if st.button("💾 Salva Spesa", type="primary", use_container_width=True):
        if importo_input <= 0 and "Telepass" not in cat_selection:
            st.warning("Inserisci un importo valido!")
        else:
            if "Bar" in cat_selection:
                cat_key = "RISTORANTI_CC" if "CC" in pay_selection else "RISTORANTI_CONTANTI"
            elif "Parcheggio" in cat_selection:
                cat_key = "PARCHEGGI_CC" if "CC" in pay_selection else "PARCHEGGI_CONTANTI"
            elif "Carburante" in cat_selection:
                cat_key = "CARBURANTE_CC" if "CC" in pay_selection else "CARBURANTE_CARTA"
            elif "Telepass" in cat_selection:
                cat_key = "TELEPASS"
            elif "Nolo" in cat_selection:
                cat_key = "NOLO"
            else:
                cat_key = "ALTRO"

            metodo_str = "CC (Carta)" if "CC" in pay_selection else ("Contanti" if "Contanti" in pay_selection else "Carta Carburante")
            d_str = data_input.strftime("%Y-%m-%d")

            photo_url = save_uploaded_photo(uploaded_photo, d_str)

            new_record = {
                "data": d_str,
                "destinazione": dest_input,
                "scopo": scopo_input,
                "categoria": cat_key,
                "metodo_pagamento": metodo_str,
                "importo": importo_input,
                "km": 0.0,
                "note": note_input,
                "allegato_path": photo_url,
                "valuta_straniera": 1 if is_foreign else 0
            }

            supabase.table("spese").insert(new_record).execute()
            
            # Svuota solo il campo note per il prossimo inserimento
            st.session_state["input_note"] = ""
            
            st.success("Spesa e allegato salvati con successo su Supabase!")
            st.rerun()

# --- TAB 2: CONSULTAZIONE & MODIFICA (CON SELEZIONE RIGA) ---
with tab2:
    st.subheader("Archivio Spese Registrate")
    
    response = supabase.table("spese").select("*").order("data", desc=True).execute()
    data_list = response.data

    if not data_list:
        st.info("Nessuna spesa memorizzata nel database.")
    else:
        df_spese = pd.DataFrame(data_list)
        
        st.write("💡 *Clicca su una riga della tabella per selezionarla e modificarla.*")
        
        # Dataframe interattivo con selezione riga
        event = st.dataframe(
            df_spese[['id', 'data', 'destinazione', 'scopo', 'categoria', 'metodo_pagamento', 'importo', 'valuta_straniera', 'note']],
            use_container_width=True,
            on_select="rerun",
            selection_mode="single-row"
        )

        st.divider()
        st.subheader("Modifica / Elimina Spesa")
        
        selected_rows = event.selection.rows if event and hasattr(event, 'selection') else []
        
        if selected_rows:
            selected_index = selected_rows[0]
            rec = df_spese.iloc[selected_index]
            record_id = rec['id']
            
            st.info(f"Stai modificando il record **ID #{record_id}** del {rec['data']}")

            with st.form("edit_form"):
                e_data = st.date_input("Data", datetime.strptime(str(rec['data']), "%Y-%m-%d"))
                e_dest = st.text_input("Destinazione", rec['destinazione'] or "")
                e_scopo = st.text_input("Scopo", rec['scopo'] or "")
                e_cat = st.selectbox("Categoria DB", CATEGORIE_LISTA, index=CATEGORIE_LISTA.index(rec['categoria']) if rec['categoria'] in CATEGORIE_LISTA else 8)
                e_pay = st.selectbox("Pagamento", PAGAMENTI_LISTA, index=PAGAMENTI_LISTA.index(rec['metodo_pagamento']) if rec['metodo_pagamento'] in PAGAMENTI_LISTA else 0)
                e_imp = st.number_input("Importo (€)", value=float(rec['importo']), step=0.5)
                e_foreign = st.checkbox("Valuta non-€ (🔣)", value=bool(rec['valuta_straniera']))
                e_note = st.text_area("Note", rec['note'] or "")

                allegato = rec['allegato_path']
                if isinstance(allegato, str) and allegato.startswith("http"):
                    st.image(allegato, caption="Allegato foto", width=250)
                
                c_sub, c_del = st.columns([1, 1])
                with c_sub:
                    save_mod = st.form_submit_button("💾 Salva Modifiche")
                with c_del:
                    delete_mod = st.form_submit_button("🗑️ Elimina Record", type="secondary")
                
                if save_mod:
                    updated_record = {
                        "data": e_data.strftime("%Y-%m-%d"),
                        "destinazione": e_dest,
                        "scopo": e_scopo,
                        "categoria": e_cat,
                        "metodo_pagamento": e_pay,
                        "importo": e_imp,
                        "note": e_note,
                        "valuta_straniera": 1 if e_foreign else 0
                    }
                    supabase.table("spese").update(updated_record).eq("id", int(record_id)).execute()
                    st.success("Record aggiornato!")
                    st.rerun()
                    
                if delete_mod:
                    photo_url = rec.get('allegato_path')
                    if photo_url:
                        delete_photo_from_storage(photo_url)

                    supabase.table("spese").delete().eq("id", int(record_id)).execute()
                    st.warning("Record ed eventuale foto eliminati!")
                    st.rerun()
        else:
            st.caption("👈 Seleziona una riga dalla tabella sopra per visualizzare il modulo di modifica.")

# --- TAB 3: REPORTISTICA & RIEPILOGO ---
with tab3:
    st.subheader("📊 Riepilogo & Reportistica Spese")
    
    col_rep_year, col_rep_month = st.columns([1, 1])
    with col_rep_year:
        rep_year = st.number_input("Anno", value=2026, step=1, key="rep_year_input")
    with col_rep_month:
        rep_month = st.selectbox("Mese da analizzare", range(1, 13), format_func=lambda x: MANDI_NOMI[x-1], index=datetime.now().month - 1)
        
    start_d = f"{rep_year}-{rep_month:02d}-01"
    end_d = f"{rep_year+1}-01-01" if rep_month == 12 else f"{rep_year}-{rep_month+1:02d}-01"
    
    rep_res = supabase.table("spese").select("*").gte("data", start_d).lt("data", end_d).execute()
    rep_data = rep_res.data
    
    if not rep_data:
        st.info(f"Nessuna spesa trovata per **{MANDI_NOMI[rep_month-1]} {rep_year}**.")
    else:
        df_rep = pd.DataFrame(rep_data)
        
        # Metriche principali
        totale_mese = df_rep['importo'].sum()
        spese_telepass = df_rep[df_rep['categoria'] == 'TELEPASS']['importo'].sum()
        totale_senza_telepass = totale_mese - spese_telepass
        num_spese = len(df_rep)

        st.markdown(f"### Totali per **{MANDI_NOMI[rep_month-1]} {rep_year}**")
        
        m1, m2, m3 = st.columns(3)
        m1.metric("Totale Generale", f"€ {totale_mese:.2f}")
        m2.metric("Totale (Senza Telepass)", f"€ {totale_senza_telepass:.2f}")
        m3.metric("Totale Telepass 🛣️", f"€ {spese_telepass:.2f}")
        
        st.divider()
        
        col_c1, col_c2 = st.columns(2)
        
        with col_c1:
            st.markdown("#### Per Categoria")
            cat_group = df_rep.groupby('categoria')['importo'].agg(['sum', 'count']).reset_index()
            cat_group.columns = ['Categoria', 'Totale (€)', 'N. Spese']
            st.dataframe(cat_group.sort_values(by='Totale (€)', ascending=False), hide_index=True, use_container_width=True)
            
        with col_c2:
            st.markdown("#### Per Metodo Pagamento")
            pay_group = df_rep.groupby('metodo_pagamento')['importo'].agg(['sum', 'count']).reset_index()
            pay_group.columns = ['Metodo', 'Totale (€)', 'N. Spese']
            st.dataframe(pay_group.sort_values(by='Totale (€)', ascending=False), hide_index=True, use_container_width=True)

# --- TAB 4: ESPORTAZIONE EXCEL ---
with tab4:
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
