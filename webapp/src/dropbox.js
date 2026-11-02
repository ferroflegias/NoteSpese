// Dropbox API v2 dal browser con OAuth 2 PKCE: stessa App key e stessa cartella dell'app Android.
import { kvGet, kvSet, kvDelete } from './storage.js';

const AUTHORIZE_URL = 'https://www.dropbox.com/oauth2/authorize';
const TOKEN_URL = 'https://api.dropboxapi.com/oauth2/token';
const API_URL = 'https://api.dropboxapi.com/2';
const CONTENT_URL = 'https://content.dropboxapi.com/2';
const SCOPES = ['account_info.read', 'files.metadata.read', 'files.content.read', 'files.content.write'];
const PKCE_KEY = 'notespese.pkce';
const CREDENTIAL_KEY = 'dropbox_credential';

export const PATH_PREFIX = 'dropbox:';

export class DropboxError extends Error {
  constructor(message, { status, summary } = {}) {
    super(message);
    this.status = status;
    this.summary = summary ?? '';
  }
}

/** Indirizzo a cui Dropbox rimanda dopo l'autorizzazione: va registrato nella console Dropbox. */
export function redirectUri() {
  return new URL(import.meta.env.BASE_URL, location.origin).href;
}

function base64Url(bytes) {
  let bin = '';
  for (const b of bytes) bin += String.fromCharCode(b);
  return btoa(bin).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

async function pkcePair() {
  const verifier = base64Url(crypto.getRandomValues(new Uint8Array(48)));
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(verifier));
  return { verifier, challenge: base64Url(new Uint8Array(digest)) };
}

/**
 * Avvia l'autorizzazione. Con [manual] Dropbox mostra un codice da copiare e incollare
 * nell'app (utile se il ritorno automatico all'app installata non funziona).
 */
export async function startLogin(appKey, { manual = false } = {}) {
  const { verifier, challenge } = await pkcePair();
  localStorage.setItem(PKCE_KEY, JSON.stringify({ verifier, appKey, withRedirect: !manual }));
  const params = new URLSearchParams({
    client_id: appKey,
    response_type: 'code',
    code_challenge: challenge,
    code_challenge_method: 'S256',
    token_access_type: 'offline',
    scope: SCOPES.join(' ')
  });
  if (!manual) params.set('redirect_uri', redirectUri());
  const url = `${AUTHORIZE_URL}?${params}`;
  if (manual) {
    window.open(url, '_blank');
  } else {
    location.assign(url);
  }
}

/** True quando un'autorizzazione è stata avviata da questo dispositivo e attende il codice. */
export function hasPendingLogin() {
  return localStorage.getItem(PKCE_KEY) != null;
}

/** Scambia il codice di autorizzazione con le credenziali e restituisce l'email dell'account. */
export async function completeLogin(code) {
  const pending = JSON.parse(localStorage.getItem(PKCE_KEY) ?? 'null');
  if (!pending) {
    throw new DropboxError('Nessun accesso a Dropbox in corso su questo dispositivo: premi di nuovo "Collega Dropbox".');
  }
  const body = new URLSearchParams({
    code: code.trim(),
    grant_type: 'authorization_code',
    code_verifier: pending.verifier,
    client_id: pending.appKey
  });
  if (pending.withRedirect) body.set('redirect_uri', redirectUri());
  const res = await fetch(TOKEN_URL, { method: 'POST', body });
  const json = await res.json().catch(() => ({}));
  if (!res.ok) {
    throw new DropboxError(`Codice non valido o scaduto (${json.error_description ?? res.status}). Ripeti il collegamento.`, { status: res.status });
  }
  localStorage.removeItem(PKCE_KEY);
  const credential = {
    appKey: pending.appKey,
    refreshToken: json.refresh_token,
    accessToken: json.access_token,
    expiresAt: Date.now() + (json.expires_in ?? 0) * 1000,
    email: ''
  };
  await kvSet(CREDENTIAL_KEY, credential);
  try {
    const account = await rpc('users/get_current_account', null);
    credential.email = account.email ?? '';
  } catch {
    credential.email = 'Account Dropbox';
  }
  await kvSet(CREDENTIAL_KEY, { ...(await kvGet(CREDENTIAL_KEY)), email: credential.email || 'Account Dropbox' });
  return credential.email || 'Account Dropbox';
}

export async function connectedAccount() {
  return (await kvGet(CREDENTIAL_KEY))?.email ?? '';
}

export async function disconnect() {
  try {
    await rpc('auth/token/revoke', null);
  } catch {
    // Revoca non riuscita (es. offline): le credenziali vengono comunque dimenticate.
  }
  await kvDelete(CREDENTIAL_KEY);
}

async function accessToken() {
  const cred = await kvGet(CREDENTIAL_KEY);
  if (!cred) throw new DropboxError('Dropbox non è collegato.');
  if (cred.accessToken && cred.expiresAt - Date.now() > 60_000) return cred.accessToken;
  const res = await fetch(TOKEN_URL, {
    method: 'POST',
    body: new URLSearchParams({ grant_type: 'refresh_token', refresh_token: cred.refreshToken, client_id: cred.appKey })
  });
  const json = await res.json().catch(() => ({}));
  if (!res.ok) {
    throw new DropboxError('Accesso a Dropbox scaduto o revocato: scollega e ricollega Dropbox.', { status: res.status });
  }
  const updated = { ...cred, accessToken: json.access_token, expiresAt: Date.now() + (json.expires_in ?? 0) * 1000 };
  await kvSet(CREDENTIAL_KEY, updated);
  return updated.accessToken;
}

/** JSON nell'intestazione HTTP Dropbox-API-Arg: i caratteri non ASCII vanno scritti come \uXXXX. */
export function headerSafeJson(value) {
  return JSON.stringify(value).replace(/[\u007f-￿]/g, (c) => '\\u' + c.charCodeAt(0).toString(16).padStart(4, '0'));
}

async function failure(res) {
  const text = await res.text().catch(() => '');
  let summary = text;
  try {
    summary = JSON.parse(text).error_summary ?? text;
  } catch {
    // risposta non JSON
  }
  return new DropboxError(`Errore Dropbox (${res.status}): ${summary}`, { status: res.status, summary });
}

async function rpc(endpoint, args) {
  const res = await fetch(`${API_URL}/${endpoint}`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${await accessToken()}`, 'Content-Type': 'application/json' },
    body: JSON.stringify(args)
  });
  if (!res.ok) throw await failure(res);
  const text = await res.text();
  return text ? JSON.parse(text) : null;
}

/** File nella cartella, [] se la cartella non esiste ancora. */
export async function listFiles(folder) {
  const files = [];
  try {
    let page = await rpc('files/list_folder', { path: folder });
    while (true) {
      files.push(...page.entries.filter((e) => e['.tag'] === 'file'));
      if (!page.has_more) break;
      page = await rpc('files/list_folder/continue', { cursor: page.cursor });
    }
  } catch (e) {
    if (e instanceof DropboxError && e.summary.startsWith('path/not_found')) return [];
    throw e;
  }
  return files;
}

/** Carica un nuovo file: un file esistente con lo stesso nome non viene mai sovrascritto. */
export async function uploadFile(path, bytes) {
  const res = await fetch(`${CONTENT_URL}/files/upload`, {
    method: 'POST',
    headers: {
      Authorization: `Bearer ${await accessToken()}`,
      'Content-Type': 'application/octet-stream',
      'Dropbox-API-Arg': headerSafeJson({ path, mode: 'add', autorename: true, mute: true })
    },
    body: bytes
  });
  if (!res.ok) throw await failure(res);
  return res.json();
}

export async function downloadFile(path) {
  const res = await fetch(`${CONTENT_URL}/files/download`, {
    method: 'POST',
    headers: {
      Authorization: `Bearer ${await accessToken()}`,
      'Dropbox-API-Arg': headerSafeJson({ path })
    }
  });
  if (!res.ok) throw await failure(res);
  return new Uint8Array(await res.arrayBuffer());
}

export async function deleteFile(path) {
  await rpc('files/delete_v2', { path });
}
