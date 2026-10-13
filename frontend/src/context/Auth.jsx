import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import { api, tokenStore } from '../lib/api';

/**
 * Staff session. Login goes to account-service; first sign-in on a device
 * returns MFA_REQUIRED and an emailed code. Only staff roles are let in.
 */
const AuthContext = createContext(null);
const STAFF_KEY = 'aura.console.staff';
const STAFF_ROLES = ['ADMIN', 'TELLER', 'MANAGER'];

/** Zel's Segregation of Duties on the live roles: ADMIN is his compliance and fraud desk, TELLER and MANAGER his branch operations desk. */
export const BRANCH_ROLES = ['TELLER', 'MANAGER'];
export const REVERSAL_CHECKERS = ['ADMIN', 'MANAGER'];
const ACCESS = { '/customers': BRANCH_ROLES, '/sar': ['ADMIN'], '/geo': ['ADMIN'], '/audit': ['ADMIN'], '/dlq': ['ADMIN'] };
export const canOpen = (role, path) => (ACCESS[path] || STAFF_ROLES).includes(role);

/** Display details for seeded staff; unknown staff fall back to their email. */
const DIRECTORY = {
  // Team members
  'ADM-TM-WAX': { name: 'Wax', title: 'Admin' },
  'ADM-TM-HANS': { name: 'Hans', title: 'Admin' },
  'ADM-TM-JM': { name: 'JM', title: 'Teller' },
  'ADM-TM-ZEL': { name: 'Zel', title: 'Admin' },
  'ADM-TM-JESSY': { name: 'Jessy', title: 'Teller' },
  'ADM-TM-MAYE': { name: 'Maye', title: 'Admin' },
  'ADM-TM-ANGEL': { name: 'Angel', title: 'Teller' },
  'wax.staff@bank.com': { name: 'Wax', title: 'Admin' },
  'hans.staff@bank.com': { name: 'Hans', title: 'Admin' },
  'jm.staff@bank.com': { name: 'JM', title: 'Teller' },
  'zel.staff@bank.com': { name: 'Zel', title: 'Admin' },
  'jessy.staff@bank.com': { name: 'Jessy', title: 'Teller' },
  'maye.staff@bank.com': { name: 'Maye', title: 'Admin' },
  'angel.staff@bank.com': { name: 'Angel', title: 'Teller' },
};
export const staffName = (id) => DIRECTORY[id]?.name || id || 'Unknown';

export function AuthProvider({ children }) {
  const [staff, setStaff] = useState(() => {
    try {
      return tokenStore.get() ? JSON.parse(sessionStorage.getItem(STAFF_KEY)) : null;
    } catch {
      return null;
    }
  });

  const adopt = useCallback((data, email) => {
    const mapped = DIRECTORY[data.user_id] || DIRECTORY[email];
    const role = mapped?.title?.toUpperCase() || String(data.role || '').replace('ROLE_', '');
    if (!STAFF_ROLES.includes(role)) {
      throw new Error('This console is for Aura staff. Customers sign in on the Aura app.');
    }
    const s = { id: data.user_id, email, role, ...(mapped || { name: email, title: role }) };
    tokenStore.set(data.access_token);
    sessionStorage.setItem(STAFF_KEY, JSON.stringify(s));
    setStaff(s);
    return { status: 'AUTHENTICATED' };
  }, []);

  const login = useCallback(
    async (email, password) => {
      const { data } = await api.post('/auth/login', { email, password, deviceType: 'WEB', deviceName: 'Aura Console' });
      if (data.status === 'MFA_REQUIRED') return { status: 'MFA_REQUIRED', userId: data.user_id, maskedEmail: data.masked_email };
      return adopt(data, email);
    },
    [adopt],
  );

  const verifyOtp = useCallback(
    async (userId, otp, email) => {
      // VerifyLoginOtpRequest binds snake_case only (no camelCase aliases, unknown keys are dropped).
      const { data } = await api.post('/auth/verify-login-otp', { user_id: userId, otp, device_type: 'WEB', device_name: 'Aura Console' });
      return adopt(data, email);
    },
    [adopt],
  );

  const logout = useCallback(async () => {
    try {
      await api.post('/auth/logout');
    } catch {
      /* the local session ends either way */
    }
    tokenStore.set(null);
    sessionStorage.removeItem(STAFF_KEY);
    setStaff(null);
  }, []);

  useEffect(() => {
    const expire = () => {
      tokenStore.set(null);
      setStaff(null);
    };
    window.addEventListener('aura:session-expired', expire);
    return () => window.removeEventListener('aura:session-expired', expire);
  }, []);

  const value = useMemo(() => ({ staff, login, verifyOtp, logout }), [staff, login, verifyOtp, logout]);
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export const useAuth = () => useContext(AuthContext);
