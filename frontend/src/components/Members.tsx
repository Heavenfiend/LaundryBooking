import { useCallback, useEffect, useState } from "react";
import { api, errorText, Laundry, Member, ROLE_LABEL, User } from "../api";
import { Avatar, confirmDialog } from "../ui";

interface Props {
  laundry: Laundry;
  user: User;
  onChanged: () => void; // обновить счётчик участников в шапке
  onLeft: () => void;
}

export default function Members({ laundry, user, onChanged, onLeft }: Props) {
  const [members, setMembers] = useState<Member[] | null>(null);
  const [error, setError] = useState("");
  const base = `/laundries/${laundry.code}`;
  const isOwner = laundry.my_role === "owner";
  const isAdmin = isOwner || laundry.my_role === "admin";

  const load = useCallback(() => {
    api<Member[]>(`${base}/members`)
      .then(setMembers)
      .catch((e) => setError(errorText(e)));
  }, [base]);
  useEffect(load, [load]);

  const run = async (path: string, method: string, body?: unknown) => {
    setError("");
    try {
      await api(base + path, { method, body });
      load();
      onChanged();
    } catch (e) {
      setError(errorText(e));
    }
  };

  const leave = async () => {
    const ok = await confirmDialog("Выйти из стиралки? Ваши будущие записи будут удалены.", {
      confirmLabel: "Выйти",
      danger: true,
    });
    if (!ok) return;
    try {
      await api(`${base}/leave`, { method: "POST" });
      onLeft();
    } catch (e) {
      setError(errorText(e));
    }
  };

  if (!members) return error ? <div className="card error">{error}</div> : <p className="muted">Загрузка…</p>;

  const pending = members.filter((m) => m.status === "pending");
  const active = members.filter((m) => m.status === "active");

  return (
    <div className="stack">
      {error && <div className="error">{error}</div>}

      {isAdmin && (
        <section className="card">
          <h2>Заявки ({pending.length})</h2>
          {pending.length === 0 && <p className="muted">Новых заявок нет.</p>}
          <ul className="list">
            {pending.map((m) => (
              <li key={m.user_id}>
                <span className="row">
                  <Avatar name={m.display_name} />
                  <b>{m.display_name}</b> <span className="muted">@{m.username}</span>
                </span>
                <span className="row">
                  <button className="primary" onClick={() => run(`/members/${m.user_id}/approve`, "POST")}>
                    Одобрить
                  </button>
                  <button onClick={() => run(`/members/${m.user_id}`, "DELETE")}>Отклонить</button>
                </span>
              </li>
            ))}
          </ul>
        </section>
      )}

      <section className="card">
        <h2>
          Участники ({active.length} из {laundry.capacity})
        </h2>
        <ul className="list">
          {active.map((m) => {
            const self = m.user_id === user.id;
            const canRemove = !self && isAdmin && (m.role === "member" || (isOwner && m.role === "admin"));
            return (
              <li key={m.user_id}>
                <span className="row">
                  <Avatar name={m.display_name} />
                  <b>{m.display_name}</b> <span className="muted">@{m.username}</span>
                  {self && <span className="muted">(вы)</span>}
                </span>
                <span className="row">
                  <span className={`badge ${m.role === "member" ? "" : "accent"}`}>{ROLE_LABEL[m.role]}</span>
                  {isOwner && m.role === "member" && (
                    <button onClick={() => run(`/members/${m.user_id}/role`, "PUT", { role: "admin" })}>
                      Сделать админом
                    </button>
                  )}
                  {isOwner && m.role === "admin" && (
                    <button onClick={() => run(`/members/${m.user_id}/role`, "PUT", { role: "member" })}>
                      Снять админа
                    </button>
                  )}
                  {canRemove && (
                    <button
                      className="danger"
                      onClick={async () => {
                        const ok = await confirmDialog(
                          `Удалить ${m.display_name} из стиралки? Будущие записи участника будут удалены.`,
                          { confirmLabel: "Удалить", danger: true },
                        );
                        if (ok) run(`/members/${m.user_id}`, "DELETE");
                      }}
                    >
                      Удалить
                    </button>
                  )}
                </span>
              </li>
            );
          })}
        </ul>
      </section>

      {!isOwner && (
        <div>
          <button className="danger" onClick={leave}>
            Выйти из стиралки
          </button>
        </div>
      )}
    </div>
  );
}
