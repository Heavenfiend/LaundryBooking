// Собственные диалог подтверждения и всплывающие сообщения.
// Нативные confirm()/alert() не используем: встроенные браузеры и веб-вью их часто
// молча блокируют, и действие «не срабатывает».
import { useEffect, useState } from "react";

interface ConfirmState {
  message: string;
  confirmLabel: string;
  danger: boolean;
  resolve: (ok: boolean) => void;
}

interface Toast {
  id: number;
  text: string;
  kind: "error" | "ok";
}

let showConfirm: ((state: ConfirmState) => void) | null = null;
let showToast: ((toast: Toast) => void) | null = null;
let nextToastId = 1;

export function confirmDialog(
  message: string,
  opts: { confirmLabel?: string; danger?: boolean } = {},
): Promise<boolean> {
  return new Promise((resolve) => {
    if (!showConfirm) return resolve(true);
    showConfirm({
      message,
      confirmLabel: opts.confirmLabel ?? "Подтвердить",
      danger: opts.danger ?? false,
      resolve,
    });
  });
}

export function toast(text: string, kind: Toast["kind"] = "error") {
  showToast?.({ id: nextToastId++, text, kind });
}

export function UiHost() {
  const [dialog, setDialog] = useState<ConfirmState | null>(null);
  const [toasts, setToasts] = useState<Toast[]>([]);

  useEffect(() => {
    showConfirm = setDialog;
    showToast = (t) => {
      setToasts((list) => [...list, t]);
      setTimeout(() => setToasts((list) => list.filter((x) => x.id !== t.id)), 4000);
    };
    return () => {
      showConfirm = null;
      showToast = null;
    };
  }, []);

  const close = (ok: boolean) => {
    dialog?.resolve(ok);
    setDialog(null);
  };

  useEffect(() => {
    if (!dialog) return;
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && close(false);
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  });

  return (
    <>
      {dialog && (
        <div className="overlay" onMouseDown={(e) => e.target === e.currentTarget && close(false)}>
          <div className="dialog" role="alertdialog" aria-modal="true">
            <p>{dialog.message}</p>
            <div className="row end">
              <button onClick={() => close(false)}>Отмена</button>
              <button
                className={dialog.danger ? "danger solid" : "primary"}
                autoFocus
                onClick={() => close(true)}
              >
                {dialog.confirmLabel}
              </button>
            </div>
          </div>
        </div>
      )}
      <div className="toasts">
        {toasts.map((t) => (
          <div key={t.id} className={`toast ${t.kind}`}>
            {t.text}
          </div>
        ))}
      </div>
    </>
  );
}

export function Avatar({ name }: { name: string }) {
  const initials = name
    .trim()
    .split(/\s+/)
    .slice(0, 2)
    .map((w) => w[0]?.toUpperCase() ?? "")
    .join("");
  // стабильный оттенок по имени
  let hash = 0;
  for (const ch of name) hash = (hash * 31 + ch.charCodeAt(0)) % 360;
  return (
    <span className="avatar" style={{ background: `hsl(${hash} 55% 50%)` }} aria-hidden>
      {initials || "?"}
    </span>
  );
}
