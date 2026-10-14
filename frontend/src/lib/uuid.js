/**
 * Generates an RFC 4122 v4 compliant UUID.
 * Works seamlessly in both Secure Contexts (HTTPS / localhost) and
 * non-secure contexts (e.g. cloud VM / container testing over HTTP).
 */
export function randomUUID() {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }
  if (typeof crypto !== 'undefined' && typeof crypto.getRandomValues === 'function') {
    return ([1e7] + -1e3 + -4e3 + -8e3 + -1e11).replace(/[018]/g, (c) =>
      (c ^ (crypto.getRandomValues(new Uint8Array(1))[0] & (15 >> (c / 4)))).toString(16)
    );
  }
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0;
    const v = c === 'x' ? r : (r & 0x3) | 0x8;
    return v.toString(16);
  });
}

// Defensive global polyfill attempt if window.crypto is extensible
if (typeof window !== 'undefined') {
  try {
    if (!window.crypto) {
      window.crypto = {};
    }
    if (typeof window.crypto.randomUUID !== 'function') {
      window.crypto.randomUUID = randomUUID;
    }
  } catch {
    // Silently ignore if window.crypto is sealed or read-only
  }
}
