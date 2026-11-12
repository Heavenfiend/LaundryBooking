import { FormEvent, useCallback, useEffect, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { QRCodeSVG } from "qrcode.react";
import { api, errorText, Laundry, ROLE_LABEL, User } from "../api";
import Schedule from "../components/Schedule";
import Members from "../components/Members";
import ImportPanel from "../components/ImportPanel";
import { confirmDialog, toast } from "../ui";

type Tab = "schedule" | "members" | "share" | "import" | "settings";

export default function LaundryPage({ user }: { user: User }) {
  const { code = "" } = useParams();
  const navigate = useNavigate();
  const [laundry, setLaundry] = useState<Laundry | null>(null);
  const [error, setError] = useState("");
  const [tab, setTab] = useState<Tab>("schedule");

  const load = useCallback(() => {
    api<Laundry>(`/laundries/${encodeURIComponent(code)}`)
      .then(setLaundry)
      .catch((e) => setError(errorText(e)));
  }, [code]);
  useEffect(load, [load]);

  if (error) return <div className="card error">{error}</div>;
  if (!laundry) return <p className="muted">Загрузка…</p>;

  const act = async (path: string, after: () => void) => {
    try {
      await api(`/laundries/${laundry.code}${path}`, { method: "POST" });
      after();
    } catch (e) {
      toast(errorText(e));
    }
  };

  const header = (
    <div className="page-head">
      <div className="row">
        <span className="code">{laundry.code}</span>
        {laundry.my_role && laundry.my_status === "active" && (
          <span className="badge accent">{ROLE_LABEL[laundry.my_role]}</span>
        )}
        <span className="muted small">
          Участников: {laundry.member_count} из {laundry.capacity}
        </span>
      </div>
      <h1>{laundry.name}</h1>
      {laundry.description && <p className="pre muted">{laundry.description}</p>}
    </div>
  );

  // не участник: карточка стиралки и заявка на вступление
  if (laundry.my_status !== "active") {
    return (
      <div className="card stack">
        {header}
        {laundry.my_status === "pending" ? (
          <div className="row">
            <span className="badge warn">Заявка отправлена, ждёт одобрения администратора</span>
            <button onClick={() => act("/leave", load)}>Отозвать заявку</button>
          </div>
        ) : (
          <div className="row">
            <button className="primary" onClick={() => act("/join", load)}>
              Подать заявку на вступление
            </button>
            {laundry.member_count >= laundry.capacity && (
              <span className="muted small">Сейчас мест нет — заявку одобрят, когда место появится.</span>
            )}
          </div>
        )}
      </div>
    );
  }

  const isAdmin = laundry.my_role === "owner" || laundry.my_role === "admin";
  const isOwner = laundry.my_role === "owner";
  const tabs: [Tab, string][] = [
    ["schedule", "Расписание"],
    ["members", "Участники"],
    ["share", "Поделиться"],
    ...(isAdmin ? ([["import", "Импорт из Excel"], ["settings", "Настройки"]] as [Tab, string][]) : []),
  ];

  return (
    <div className="stack">
      {header}
      <nav className="tabs">
        {tabs.map(([key, label]) => (
          <button key={key} className={tab === key ? "active" : ""} onClick={() => setTab(key)}>
            {label}
          </button>
        ))}
      </nav>

      {tab === "schedule" && <Schedule laundry={laundry} isAdmin={isAdmin} />}
      {tab === "members" && (
        <Members laundry={laundry} user={user} onChanged={load} onLeft={() => navigate("/")} />
      )}
      {tab === "share" && <Share laundry={laundry} />}
      {tab === "import" && isAdmin && <ImportPanel laundry={laundry} />}
      {tab === "settings" && isAdmin && (
        <Settings laundry={laundry} isOwner={isOwner} onSaved={setLaundry} onDeleted={() => navigate("/")} />
      )}
    </div>
  );
}

function Share({ laundry }: { laundry: Laundry }) {
  const link = `${window.location.origin}/l/${laundry.code}`;
  const [copied, setCopied] = useState(false);
  const copy = async () => {
    try {
      await navigator.clipboard.writeText(link);
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    } catch {
      toast("Не удалось скопировать — выделите ссылку и скопируйте вручную");
    }
  };
  return (
    <section className="card stack">
      <p>
        Отправьте ссылку или покажите QR-код — человек откроет страницу стиралки и сможет подать
        заявку. Также стиралку можно найти по ID <b>{laundry.code}</b>.
      </p>
      <div className="row">
        <input readOnly value={link} onFocus={(e) => e.target.select()} />
        <button onClick={copy}>{copied ? "Скопировано" : "Копировать"}</button>
      </div>
      <div className="qr">
        <QRCodeSVG value={link} size={200} marginSize={2} />
      </div>
    </section>
  );
}

function Settings(props: {
  laundry: Laundry;
  isOwner: boolean;
  onSaved: (l: Laundry) => void;
  onDeleted: () => void;
}) {
  const { laundry } = props;
  const [name, setName] = useState(laundry.name);
  const [description, setDescription] = useState(laundry.description);
  const [capacity, setCapacity] = useState(laundry.capacity);
  const [message, setMessage] = useState("");
  const [error, setError] = useState("");

  const save = async (e: FormEvent) => {
    e.preventDefault();
    setError("");
    setMessage("");
    try {
      props.onSaved(
        await api<Laundry>(`/laundries/${laundry.code}`, {
          method: "PATCH",
          body: { name, description, capacity },
        }),
      );
      setMessage("Сохранено");
    } catch (err) {
      setError(errorText(err));
    }
  };

  const remove = async () => {
    const ok = await confirmDialog(
      `Удалить стиралку «${laundry.name}» со всеми записями? Это необратимо.`,
      { confirmLabel: "Удалить", danger: true },
    );
    if (!ok) return;
    try {
      await api(`/laundries/${laundry.code}`, { method: "DELETE" });
      props.onDeleted();
    } catch (err) {
      setError(errorText(err));
    }
  };

  return (
    <section className="card">
      <form className="stack" onSubmit={save}>
        <label>
          Название
          <input value={name} onChange={(e) => setName(e.target.value)} maxLength={100} required />
        </label>
        <label>
          Описание
          <textarea
            value={description}
            onChange={(e) => setDescription(e.target.value)}
            maxLength={1000}
            rows={3}
          />
        </label>
        <label>
          Лимит участников
          <input
            type="number"
            min={laundry.member_count}
            max={1000}
            value={capacity}
            onChange={(e) => setCapacity(Number(e.target.value))}
            required
          />
        </label>
        {error && <div className="error">{error}</div>}
        {message && <div className="ok">{message}</div>}
        <div className="row spread">
          <button className="primary">Сохранить</button>
          {props.isOwner && (
            <button type="button" className="danger" onClick={remove}>
              Удалить стиралку
            </button>
          )}
        </div>
      </form>
    </section>
  );
}
