// Archivio chiave-valore su IndexedDB: database, impostazioni e credenziali Dropbox
// restano solo su questo dispositivo.
const DB_NAME = 'notespese';
const STORE = 'kv';
let dbPromise;

function openIdb() {
  dbPromise ??= new Promise((resolve, reject) => {
    const req = indexedDB.open(DB_NAME, 1);
    req.onupgradeneeded = () => req.result.createObjectStore(STORE);
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
  });
  return dbPromise;
}

async function run(mode, action) {
  const idb = await openIdb();
  return new Promise((resolve, reject) => {
    const tx = idb.transaction(STORE, mode);
    const req = action(tx.objectStore(STORE));
    tx.oncomplete = () => resolve(req.result);
    tx.onerror = () => reject(tx.error);
    tx.onabort = () => reject(tx.error);
  });
}

export const kvGet = (key) => run('readonly', (s) => s.get(key));
export const kvSet = (key, value) => run('readwrite', (s) => s.put(value, key));
export const kvDelete = (key) => run('readwrite', (s) => s.delete(key));

/** Chiede al browser di non cancellare mai i dati della WebApp per liberare spazio. */
export async function requestPersistentStorage() {
  try {
    if (navigator.storage?.persist && !(await navigator.storage.persisted())) {
      await navigator.storage.persist();
    }
  } catch {
    // Non supportato: i dati restano comunque salvati.
  }
}
