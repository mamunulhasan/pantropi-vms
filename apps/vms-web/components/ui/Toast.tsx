"use client";

/**
 * Transient notifications over a live region (US-06.2.1, F-06.4).
 *
 * The container is `role="status"` (polite): a toast announces after the current utterance, it
 * never interrupts. Anything important enough to interrupt for belongs in an `ErrorState` or a
 * dialog, not a toast that auto-dismisses whether or not it was heard.
 *
 * Auto-dismiss after {@link TOAST_MS}, and always a manual dismiss control — auto-only fails
 * whoever reads slowly; manual-only leaves a pile-up.
 */
import {
  createContext,
  useCallback,
  useContext,
  useRef,
  useState,
  type ReactNode,
} from "react";

export const TOAST_MS = 6000;

type Tone = "info" | "success" | "error";

type ToastItem = { id: number; message: string; tone: Tone };

const TONE_CLASSES: Record<Tone, string> = {
  info: "bg-surface-raised text-text border-border",
  success: "bg-success text-success-contrast border-success",
  error: "bg-danger text-danger-contrast border-danger",
};

const ToastContext = createContext<{ toast: (message: string, tone?: Tone) => void } | null>(null);

export function useToast() {
  const ctx = useContext(ToastContext);
  if (!ctx) {
    throw new Error("useToast must be used inside <ToastProvider>");
  }
  return ctx;
}

export function ToastProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<ToastItem[]>([]);
  const nextId = useRef(0);

  const dismiss = useCallback((id: number) => {
    setToasts((current) => current.filter((t) => t.id !== id));
  }, []);

  const toast = useCallback(
    (message: string, tone: Tone = "info") => {
      const id = nextId.current++;
      setToasts((current) => [...current, { id, message, tone }]);
      setTimeout(() => dismiss(id), TOAST_MS);
    },
    [dismiss],
  );

  return (
    <ToastContext.Provider value={{ toast }}>
      {children}
      <div
        role="status"
        aria-live="polite"
        className="fixed bottom-4 right-4 z-50 flex w-80 flex-col gap-2"
      >
        {toasts.map((t) => (
          <div
            key={t.id}
            className={`flex items-start justify-between gap-3 rounded-md border px-4 py-3 text-sm shadow-raised ${TONE_CLASSES[t.tone]}`}
          >
            <span>{t.message}</span>
            <button
              type="button"
              aria-label="Dismiss notification"
              onClick={() => dismiss(t.id)}
              className="shrink-0 font-medium opacity-70 hover:opacity-100"
            >
              ×
            </button>
          </div>
        ))}
      </div>
    </ToastContext.Provider>
  );
}
