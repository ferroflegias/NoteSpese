import os
import io
import json
from datetime import datetime
import requests
import pandas as pd
import openpyxl
from openpyxl.styles import PatternFill
from PIL import Image, ImageOps
import streamlit as st
from streamlit_cropper import st_cropper
from supabase import create_client, Client
from pypdf import PdfReader, PdfWriter
from google import genai
from google.genai import types

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

# --- UTILITY FOTO OTTIMIZZATE & FILE STORAGE ---

def optimize_pil_image(pil_img, max_dimension=1600, quality=75):
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

# --- ANALISI ALLEGATO CON GEMINI VISION (NUOVO SDK) ---

def analyze_receipt_with_gemini(file_bytes, mime_type):
    """Utilizza il nuovo SDK google-genai con modello gemini-2.0-flash."""
    api_key = st.secrets.get("GEMINI_API_KEY")
    if not api_key:
        st.error("⚠️ Chiave 'GEMINI_API_KEY' non trovata nei secrets di Streamlit!")
        return None

    prompt = """
    Sei un assistente per la gestione delle note spese aziendali italiane.
    Analizza questa ricevuta/scontrino/fattura ed estrai i seguenti dati in formato JSON strictly formattato:
    
    {
      "data": "YYYY-MM-DD",
      "destinazione": "Ragione Sociale o Nome Esercente e Città se visibile",
      "importo": 0.00,
      "categoria_suggerita": "Bar/Rist/Alb" oppure "Parcheggio/Taxi" oppure "Carburante" oppure "Telepass" oppure "Nolo" oppure "Altro",
      "pagamento_suggerito": "CC" oppure "Contanti" oppure "Carta Carburante",
      "note": "Eventuale breve descrizione o dettagli rilevati"
    }
    
    Se la data non è visibile, imposta la data di oggi.
    Se l'importo contiene la virgola, convertilo in numero float con punto.
    Restituisci ESCLUSIVAMENTE il JSON senza formattazione Markdown o blocchi di codice.
    """

    try:
        client = genai.Client(api_key=api_key)

        response = client.models.generate_content(
            model='gemini-2.0-flash',
            contents=[
                types.Part.from_bytes(
                    data=file_bytes,
                    mime_type=mime_type,
                ),
                prompt,
            ],
            config=types.GenerateContentConfig(
                response_mime_type="application/json"
            )
        )

        clean_text = response.text.strip().replace("```json", "").replace("
