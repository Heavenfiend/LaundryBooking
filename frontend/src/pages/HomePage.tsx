import { FormEvent, useEffect, useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { api, errorText, Laundry, ROLE_LABEL } from "../api";

export default function HomePage() {
  const navigate = useNavigate();
  const [laundries, setLaundries] = useState<Laundry[] | null>(null);
  const [error, setError] = useState("");

  const [searchCode, setSearchCode] = useState("");
  const [searchError, setSearchError] = useState("");

  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const [capacity, setCapacity] = useState(30);

  useEffect(() => {
    api<Laundry[]>("/laundries")
      .then(setLaundries)
      .catch((e) => setError(errorText(e)));
  }, []);

  const search = async (e: FormEvent) => {
    e.preventDefault();
    setSearchError("");
    const code = searchCode.trim().toUpperCase();
    try {
      await api<Laundry>(`/laundries/${encodeURIComponent(code)}`);
      navigate(`/l/${code}`);
    } catch (err) {
      setSearchError(errorText(err));
    }
  };

  const create = async (e: FormEvent) => {
    e.preventDefault();
    setError("");
    try {
      const created = await api<Laundry>("/laundries", {
        method: "POST",
        body: { name, description, capacity },
      });
      navigate(`/l/${created.code}`);
    } catch (err) {
      setError(errorText(err));
    }
  };

  return (
    <div className="stack lg">
      <section className="stack">
        <h1>Мои стиралки</h1>
        {laundries === null && !error && <p className="muted">Загрузка…</p>}
        {laundries?.length === 0 && (
          <div className="card empty">
            Пока пусто — найдите стиралку по ID или создайте свою.
          </div>
        )}
        <div className="tiles">
          {laundries?.map((l) => (
            <Link key={l.code} to={`/l/${l.code}`} className="card tile">
              <div className="row spread">
                <span className="code">{l.code}</span>
                <span className={`badge ${l.my_status === "pending" ? "warn" : "accent"}`}>
                  {l.my_status === "pending" ? "заявка на рассмотрении" : ROLE_LABEL[l.my_role!]}
                </span>
              </div>
              <h3>{l.name}</h3>
              {l.description && <p className="muted clamp">{l.description}</p>}
              <span className="muted small">
                Участников: {l.member_count} из {l.capacity}
              </span>
            </Link>
          ))}
        </div>
      </section>

      <div className="two-col">
        <section className="card stack">
          <h2>Найти по ID</h2>
          <p className="muted">Введите ID стиралки, чтобы подать заявку на вступление.</p>
          <form className="row" onSubmit={search}>
            <input
              className="code-input"
              placeholder="K7M2QX"
              value={searchCode}
              onChange={(e) => setSearchCode(e.target.value)}
              maxLength={12}
              required
            />
            <button className="primary">Найти</button>
          </form>
          {searchError && <div className="error">{searchError}</div>}
        </section>

        <section className="card">
          <h2>Создать стиралку</h2>
          <form className="stack" onSubmit={create}>
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
                rows={2}
              />
            </label>
            <label>
              Лимит участников
              <input
                type="number"
                min={1}
                max={1000}
                value={capacity}
                onChange={(e) => setCapacity(Number(e.target.value))}
                required
              />
            </label>
            {error && <div className="error">{error}</div>}
            <div>
              <button className="primary">Создать</button>
            </div>
          </form>
        </section>
      </div>
    </div>
  );
}
