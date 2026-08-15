import os
import io
import json
import re
from datetime import datetime, timedelta
import requests
import pandas as pd
import openpyxl
from openpyxl.styles import PatternFill
from PIL import Image, ImageOps
import streamlit as st
from streamlit_cropper import st_cropper
from supabase import create_client, Client
from pypdf import PdfReader, PdfWriter
from pdf2image import convert_from_bytes
import pytesseract
import time

# ReportLab imports per PDF
from reportlab.lib.pagesizes import A4
from reportlab.platypus import SimpleDocTemplate, Paragraph, Spacer, Image as RLImage, Table, TableStyle
from reportlab.lib.styles import getSampleStyleSheet, ParagraphStyle
from reportlab.lib import colors

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
        if pwd_input == st.secrets.get("PASSWORD"):
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
MAX_FILE_SIZE_MB = 5

MANDI_NOMI = [
    "Gennaio", "Febbraio", "Marzo", "Aprile", "Maggio", "Giugno",
    "Luglio", "Agosto", "Settembre", "Ottobre", "Novembre", "Dicembre"
]

CATEGORIE_LAVORO = [
    "RISTORANTI_CC", "RISTORANTI_CONTANTI",
    "PARCHEGGI_CC", "PARCHEGGI_CONTANTI",
    "CARBURANTE_CC", "CARBURANTE_CARTA",
    "TELEPASS", "NOLO", "ALTRO"
]

CATEGORIE_PERSONALI = [
    "Cibo/Delivery", "Spesa", "Casa", "Auto", 
    "B.", "Romeo", "Personale", "Vacanze", "Varie"
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

PAGAMENTI_LAVORO = ["CC (Carta)", "Contanti", "Carta Carburante"]
PAGAMENTI_PERSONALI = ["Cash", "Card", "PayPal/Satispay"]

FILL_ORANGE = PatternFill(start_color="FFC000", end_color="FFC000", fill_type="solid")
FILL_YELLOW = PatternFill(start_color="FFFF00", end_color="FFFF00", fill_type="solid")

# --- UTILITY FOTO OTTIMIZZATE ---

def optimize_pil_image(pil_img, max_dimension=1200, quality=80):
    try:
        pil_img = ImageOps.exif_transpose(pil_img)
        pil_img = pil_img.convert("RGB")

        w, h = pil_img.size
        if max(w, h) > max_dimension:
            ratio = max_dimension / float(max(w, h))
            new_size = (int(w * ratio), int(h * ratio))
            pil_img = pil_img.resize(new_size, Image.Resampling.LANCZOS)

        out_buffer = io.BytesIO()
        pil_img.save(out_buffer, format="JPEG", quality=quality, optimize=True)
        return out_buffer.getvalue()
    except Exception:
        out_buffer = io.BytesIO()
        pil_img.save(out_buffer, format="JPEG")
        return out_buffer.getvalue()

def upload_file_to_supabase(file_bytes, data_spesa, extension, content_type, user_id="default_user"):
    try:
        dt = datetime.strptime(data_spesa, "%Y-%m-%d")
        anno_mese = dt.strftime("%Y-%m")
        now_str = datetime.now().strftime('%Y%m%d_%H%M%S')
        
        filename = f"rec_{data_spesa}_{now_str}.{extension}"
        storage_path = f"{user_id}/{anno_mese}/{filename}"

        supabase.storage.from_(BUCKET_NAME).upload(
            path=storage_path,
            file=file_bytes,
            file_options={"content-type": content_type}
        )

        return supabase.storage.from_(BUCKET_NAME).get_public_url(storage_path)
    except Exception as e:
        st.error(f"Errore durante l'upload su Supabase Storage: {e}")
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
        st.warning(f"Impossibile eliminare il file dallo Storage: {e}")

# --- ANALISI LOCALE CON TESSERACT OCR & REGEX ---

def analyze_receipt_with_tesseract_ocr(file_bytes, mime_type, is_personal=False):
    try:
        if mime_type == "application/pdf":
            images = convert_from_bytes(file_bytes)
            if images:
                pil_img = images[0]
            else:
                return None
        else:
            pil_img = Image.open(io.BytesIO(file_bytes))

        pil_img = ImageOps.exif_transpose(pil_img).convert("L")
        text = pytesseract.image_to_string(pil_img, lang='ita')
        
        # 1. Estrazione Data
        data_str = datetime.now().strftime("%Y-%m-%d")
        date_matches = re.findall(r'\b(\d{1,2})[/-](\d{1,2})[/-](\d{2,4})\b', text)
        if date_matches:
            d, m, y = date_matches[0]
            if len(y) == 2:
                y = "20" + y
            try:
                parsed_dt = datetime(int(y), int(m), int(d))
                data_str = parsed_dt.strftime("%Y-%m-%d")
            except ValueError:
                pass

        # 2. Estrazione Importo
        importo = 0.0
        lines = text.split('\n')
        total_candidates = []
        all_amounts = re.findall(r'\b\d+[\.,]\d{2}\b', text)
        if all_amounts:
            floats = [float(amt.replace(',', '.')) for amt in all_amounts]
            for line in lines:
                if any(k in line.lower() for k in ['totale', 'tot', 'eur', '€', 'importo', 'somma', 'euro']):
                    line_amounts = re.findall(r'\d+[\.,]\d{2}', line)
                    if line_amounts:
                        for la in line_amounts:
                            total_candidates.append(float(la.replace(',', '.')))
            if total_candidates:
                importo = max(total_candidates)
            else:
                importo = max(floats) if floats else 0.0

        # 3. Estrazione Esercente
        destinazione = "Esercente Sconosciuto"
        valid_lines = [l.strip() for l in lines if len(l.strip()) > 3]
        for vl in valid_lines[:4]:
            if not re.match(r'^[\d\W]+$', vl) and not any(w in vl.lower() for w in ['ricevuta', 'scontrino', 'fattura', 'via', 'piazza', 'tel']):
                destinazione = vl
                break

        text_lower = text.lower()
        
        if is_personal:
            categoria_suggerita = "Spesa"
            pagamento_suggerito = "Card"
            if any(k in text_lower for k in ['deliveroo', 'just eat', 'glovo', 'pizza', 'sushi', 'ristorante']):
                categoria_suggerita = "Cibo/Delivery"
            elif any(k in text_lower for k in ['benzina', 'gasolio', 'eni', 'q8', 'autostrada']):
                categoria_suggerita = "Auto"
            elif any(k in text_lower for k in ['esselunga', 'conad', 'coop', 'lidl', 'supermercato']):
                categoria_suggerita = "Spesa"
        else:
            categoria_suggerita = "Altro"
            pagamento_suggerito = "Contanti"
            if any(k in text_lower for k in ['benzina', 'gasolio', 'q8', 'eni', 'agip']):
                categoria_suggerita = "Carburante"
                pagamento_suggerito = "Carta Carburante"
            elif any(k in text_lower for k in ['ristorante', 'pizzeria', 'bar', 'pranzo', 'cena']):
                categoria_suggerita = "Bar/Rist/Alb"
                pagamento_suggerito = "CC"

        return {
            "data": data_str,
            "destinazione": destinazione[:40],
            "importo": round(importo, 2),
            "categoria_suggerita": categoria_suggerita,
            "pagamento_suggerito": pagamento_suggerito,
            "note": text.replace('\n', ' ')[:120]
        }

    except Exception as e:
        st.error(f"❌ Errore OCR: {e}")
        return None

# --- INTERFACCIA STREAMLIT ---
st.title("🧾 Gestione Note Spese 2026")

tab1, tab2, tab3, tab4 = st.tabs(["➕ Nuova Spesa", "📑 Registri / Modifica", "📊 Report Spese", "📁 Export Excel & PDF"])

if "upload_key" not in st.session_state:
    st.session_state["upload_key"] = 0

if "ai_extracted_data" not in st.session_state:
    st.session_state["ai_extracted_data"] = {}

# --- TAB 1: REGISTRAZIONE SMART ---
with tab1:
    st.subheader("Registra Nuova Spesa")
    
    # Selettore tipo spesa
    tipo_spesa = st.radio("Tipo di Spesa", ["💼 Lavoro", "🏡 Personale"], horizontal=True)
    is_personal = (tipo_spesa == "🏡 Personale")
    
    st.markdown("#### Step 1: Carica Allegato (Foto o PDF)")
    col_up1, col_up2 = st.columns(2)
    k_img = f"img_uploader_{st.session_state['upload_key']}"
    k_pdf = f"pdf_uploader_{st.session_state['upload_key']}"
    
    with col_up1:
        uploaded_image = st.file_uploader("📷 Scatta/Scegli Foto", type=["jpg", "png", "jpeg"], key=k_img)
    with col_up2:
        uploaded_pdf = st.file_uploader("📄 Scegli PDF", type=["pdf"], key=k_pdf)

    prepared_file = None

    if uploaded_image is not None:
        if uploaded_image.size > MAX_FILE_SIZE_MB * 1024 * 1024:
            st.error("⚠️ Il file immagine supera il limite di 5MB!")
        else:
            img = Image.open(uploaded_image)
            img = ImageOps.exif_transpose(img)
            enable_crop = st.checkbox("✂️ Attiva ritaglio/crop immagine", value=True)

            if enable_crop:
                cropped_img = st_cropper(img, realtime_update=True, box_color="#00FF00", aspect_ratio=None)
                cropped_bytes = optimize_pil_image(cropped_img)
            else:
                cropped_bytes = optimize_pil_image(img)
                st.image(cropped_bytes, caption="Foto scontrino intera", use_container_width=True)

            prepared_file = {"bytes": cropped_bytes, "ext": "jpg", "mime": "image/jpeg"}

    elif uploaded_pdf is not None:
        if uploaded_pdf.size > MAX_FILE_SIZE_MB * 1024 * 1024:
            st.error("⚠️ Il file PDF supera il limite di 5MB!")
        else:
            st.success(f"📄 PDF pronto: `{uploaded_pdf.name}`")
            prepared_file = {"bytes": uploaded_pdf.getvalue(), "ext": "pdf", "mime": "application/pdf"}

    if prepared_file:
        if st.button("🔍 Estrai Dati con OCR", type="secondary", use_container_width=True):
            with st.spinner("Estrazione testo e riconoscimento in corso..."):
                extracted = analyze_receipt_with_tesseract_ocr(prepared_file["bytes"], prepared_file["mime"], is_personal)
                if extracted:
                    st.session_state["ai_extracted_data"] = extracted
                    st.success("✅ Dati estratti con successo!")

    st.divider()
    st.markdown("#### Step 2: Verifica e Conferma Dati Spesa")

    ai_data = st.session_state.get("ai_extracted_data", {})
    default_date = datetime.now()
    if ai_data.get("data"):
        try:
            default_date = datetime.strptime(ai_data["data"], "%Y-%m-%d")
        except ValueError:
            pass

    col_d, col_w = st.columns([1, 1])
    with col_d:
        data_input = st.date_input("When (Data)", value=default_date)
    with col_w:
        dest_input = st.text_input("Where (Destinazione/Esercente)", value=ai_data.get("destinazione", ""))

    scopo_input = st.text_input("Why (Scopo)") if not is_personal else ""

    st.write("**What (Categoria)**")
    cat_options = CATEGORIE_PERSONALI if is_personal else ["🍽️ Bar/Rist/Alb", "🅿️ Parcheggio/Taxi", "⛽ Carburante", "🛣️ Telepass", "🚗 Nolo", "Altro"]
    
    default_cat_idx = 0
    ai_cat = ai_data.get("categoria_suggerita", "")
    for idx, opt in enumerate(cat_options):
        if ai_cat.lower() in opt.lower():
            default_cat_idx = idx
            break

    cat_selection = st.selectbox("Categoria", options=cat_options, index=default_cat_idx) if is_personal else st.radio("Categoria", options=cat_options, index=default_cat_idx, horizontal=True, label_visibility="collapsed")

    is_telepass = not is_personal and ("Telepass" in cat_selection)

    if not is_telepass:
        st.write("**Payment (Metodo Pagamento)**")
        pay_options = PAGAMENTI_PERSONALI if is_personal else ["💳 CC", "💶 Contanti", "📄⛽ Carta Carburante"]
        default_pay_idx = 0
        ai_pay = ai_data.get("pagamento_suggerito", "")
        for idx, opt in enumerate(pay_options):
            if ai_pay.lower() in opt.lower():
                default_pay_idx = idx
                break

        pay_selection = st.radio("Pagamento", options=pay_options, index=default_pay_idx, horizontal=True, label_visibility="collapsed")
    else:
        pay_selection = None
        st.info("ℹ️ Per il Telepass il metodo di pagamento viene omesso.")

    col_imp, col_curr = st.columns([2, 1])
    with col_imp:
        ai_imp = float(ai_data.get("importo", 0.0))
        importo_input = st.number_input("Value (€)", value=ai_imp, min_value=0.0, step=0.5, format="%.2f")
    with col_curr:
        st.write("")
        st.write("")
        is_foreign = st.checkbox("Non € (🔣)") if not is_personal else False

    note_input = st.text_area("Notes", value=ai_data.get("note", ""), height=70)

    if st.button("💾 Salva Spesa", type="primary", use_container_width=True):
        if importo_input <= 0 and not is_telepass:
            st.warning("Inserisci un importo valido!")
        else:
            d_str = data_input.strftime("%Y-%m-%d")
            
            # Workflow allegato: Lavoro lo salva su Supabase bucket, Personale lo scarta (nessun upload)
            file_url = None
            if not is_personal and prepared_file:
                file_url = upload_file_to_supabase(
                    file_bytes=prepared_file["bytes"],
                    data_spesa=d_str,
                    extension=prepared_file["ext"],
                    content_type=prepared_file["mime"]
                )

            if is_personal:
                new_record = {
                    "data": d_str,
                    "destinazione": dest_input,
                    "scopo": "",
                    "categoria": cat_selection,
                    "metodo_pagamento": pay_selection,
                    "importo": importo_input,
                    "note": note_input,
                    "valuta_straniera": 0
                }
                supabase.table("spese_personali").insert(new_record).execute()
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

                metodo_str = None if cat_key == "TELEPASS" else ("CC (Carta)" if "CC" in pay_selection else ("Contanti" if "Contanti" in pay_selection else "Carta Carburante"))

                new_record = {
                    "data": d_str,
                    "destinazione": dest_input,
                    "scopo": scopo_input,
                    "categoria": cat_key,
                    "metodo_pagamento": metodo_str,
                    "importo": importo_input,
                    "km": 0.0,
                    "note": note_input,
                    "allegato_path": file_url,
                    "valuta_straniera": 1 if is_foreign else 0
                }
                supabase.table("spese").insert(new_record).execute()
            
            # Conferma visiva di avvenuta registrazione
            st.success("✅ **Spesa registrata con successo nel database!**")
            st.session_state["ai_extracted_data"] = {}
            st.session_state["upload_key"] += 1
            time.sleep(1)
            st.rerun()

# --- TAB 2: CONSULTAZIONE & MODIFICA ---
with tab2:
    st.subheader("Archivio Spese Registrate")
    tipo_archivio = st.radio("Seleziona archivio", ["Lavoro", "Personale"], horizontal=True, key="archivio_radio")
    
    table_name = "spese" if tipo_archivio == "Lavoro" else "spese_personali"
    response = supabase.table(table_name).select("*").order("data", desc=True).execute()
    data_list = response.data

    if not data_list:
        st.info("Nessuna spesa memorizzata in questo archivio.")
    else:
        df_spese = pd.DataFrame(data_list)
        st.dataframe(df_spese, use_container_width=True)

# --- TAB 3: REPORTISTICA & STATISTICHE PERSONALI ---
with tab3:
    st.subheader("📊 Report Spese & Statistiche Personali")
    
    report_type = st.radio("Tipologia Report", ["Lavoro", "Personale"], horizontal=True)
    
    if report_type == "Lavoro":
        rep_year = st.number_input("Anno", value=2026, step=1)
        rep_month = st.selectbox("Mese", range(1, 13), format_func=lambda x: MANDI_NOMI[x-1], index=datetime.now().month - 1)
        start_d, end_d = f"{rep_year}-{rep_month:02d}-01", f"{rep_year+1}-01-01" if rep_month == 12 else f"{rep_year}-{rep_month+1:02d}-01"
        res = supabase.table("spese").select("*").gte("data", start_d).lt("data", end_d).execute()
        if res.data:
            df = pd.DataFrame(res.data)
            st.metric("Totale Mese Lavoro", f"€ {df['importo'].sum():.2f}")
            st.dataframe(df.groupby('categoria')['importo'].sum().reset_index(), use_container_width=True)
        else:
            st.info("Nessuna spesa di lavoro trovata.")
    else:
        st.markdown("### 🏡 Statistiche Spese Personali")
        res_pers = supabase.table("spese_personali").select("*").execute()
        if res_pers.data:
            df_p = pd.DataFrame(res_pers.data)
            df_p['data'] = pd.to_datetime(df_p['data'])
            
            # Filtro mensile / settimanale
            oggi = datetime.now()
            mese_corrente = df_p[df_p['data'].dt.month == oggi.month]
            mese_precedente = df_p[df_p['data'].dt.month == (oggi.month - 1 if oggi.month > 1 else 12)]
            
            tot_mese_curr = mese_corrente['importo'].sum()
            tot_mese_prev = mese_precedente['importo'].sum()
            
            col_m1, col_m2 = st.columns(2)
            col_m1.metric("Spese Mese Corrente", f"€ {tot_mese_curr:.2f}", delta=f"€ {tot_mese_curr - tot_mese_prev:.2f} vs mese prec.")
            
            st.markdown("#### Spese per Categoria (Mese Corrente)")
            if not mese_corrente.empty:
                cat_sum = mese_corrente.groupby('categoria')['importo'].sum().reset_index()
                st.dataframe(cat_sum.sort_values(by='importo', ascending=False), hide_index=True, use_container_width=True)
            else:
                st.info("Nessuna spesa registrata per il mese corrente.")
        else:
            st.info("Nessuna spesa personale registrata.")

# --- TAB 4: ESPORTAZIONE EXCEL & PDF ---
with tab4:
    st.subheader("📁 Generazione Documenti (Lavoro)")
    st.info("L'export in Excel e PDF è disponibile esclusivamente per le note spese di lavoro.")
    # (Codice export invariato dal blocco precedente)
