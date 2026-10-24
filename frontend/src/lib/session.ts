import { create } from "zustand";

// Set after an explicit sign-out so the page shows a "signed out" screen
// instead of bouncing straight back through single sign-on.
const SIGNED_OUT_KEY = "asr:signed-out";

function readSignedOut() {
  try {
    return localStorage.getItem(SIGNED_OUT_KEY) === "1";
  } catch {
    return false;
  }
}

function writeSignedOut(value: boolean) {
  try {
    if (value) localStorage.setItem(SIGNED_OUT_KEY, "1");
    else localStorage.removeItem(SIGNED_OUT_KEY);
  } catch {
    // Storage disabled: the flag only lasts for this page.
  }
}

type SessionState = {
  /** True after the user signed out on this device. */
  signedOut: boolean;
  setSignedOut: (value: boolean) => void;
};

export const useSession = create<SessionState>((set) => ({
  signedOut: readSignedOut(),
  setSignedOut: (value) => {
    writeSignedOut(value);
    set({ signedOut: value });
  },
}));

export const goToLogin = () => window.location.assign("/auth/login");
