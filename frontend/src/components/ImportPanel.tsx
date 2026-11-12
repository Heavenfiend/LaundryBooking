import { ChangeEvent, useRef, useState } from "react";
import { api, errorText, ImportConflict, ImportOut, ImportResult, ImportRow, Laundry } from "../api";

const STATUS_LABEL: Record<ImportResult["status"], string> = {
  new: "новая",
  update: "изменение",
  unchanged: "без изменений",
  conflict: "конфликт",
  error: "ошибка",
  skipped: "пропуск",
};

const fmt = (local: string) =>
  `${local.slice(8, 10)}.${local.slice(5, 7)} ${local.slice(11, 16)}`;

export default function ImportPanel({ laundry }: { laundry: Laundry }) {
  const base = `/laundries/${laundry.code}/import`;
  const fileRef = useRef<HTMLInputElement>(null);
  const [rows, setRows] = useState<ImportRow[]>([]);
  const [results, setResults] = useState<ImportResult[]>([]);
  const [deleteIds, setDeleteIds] = useState<number[]>([]);
  const [dirty, setDirty] = useState(false); // строки правили после последней проверки
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [done, setDone] = useState("");
  const [ignored, setIgnored] = useState(0);
  // записи сайта, для которых ячейка в таблице пуста
  const [missing, setMissing] = useState<ImportConflict[]>([]);

  const reset = () => {
    setRows([]);
    setResults([]);
    setDeleteIds([]);
    setDirty(false);
    setIgnored(0);
    setMissing([]);
    if (fileRef.current) fileRef.current.value = "";
  };

  const upload = async (e: ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    setError("");
    setDone("");
    setBusy(true);
    try {
      const form = new FormData();
      form.append("file", file);
      const out = await api<ImportOut>(`${base}/parse`, { method: "POST", form });
      setRows(out.rows);
      setResults(out.results);
      setDeleteIds([]);
      setDirty(false);
      setIgnored(out.ignored);
      setMissing(out.missing);
      if (out.rows.length === 0 && out.missing.length === 0) {
        setError("В таблице нет заполненных ячеек в пределах расписания");
      }
    } catch (err) {
      reset();
      setError(errorText(err));
    } finally {
      setBusy(false);
    }
  };

  // проверка (dryRun) или применение текущего состояния
  const send = async (nextRows: ImportRow[], nextDelete: number[], dryRun: boolean) => {
    setError("");
    setBusy(true);
    try {
      const out = await api<ImportOut>(`${base}/apply`, {
        method: "POST",
        body: { rows: nextRows, delete_booking_ids: nextDelete, dry_run: dryRun },
      });
      if (out.applied) {
        reset();
        setDone(
          `Импорт выполнен: добавлено ${out.created}, изменено ${out.updated}, удалено ${out.deleted}.`,
        );
        return;
      }
      setResults(out.results);
      setDirty(false);
      if (!dryRun) setError("Остались конфликты или ошибки — исправьте или пропустите эти строки.");
    } catch (err) {
      setError(errorText(err));
    } finally {
      setBusy(false);
    }
  };

  const edit = (index: number, patch: Partial<ImportRow>) => {
    setRows(rows.map((r, i) => (i === index ? { ...r, ...patch } : r)));
    setDirty(true);
  };

  const toggleSkip = (index: number) => {
    const next = rows.map((r, i) => (i === index ? { ...r, skip: !r.skip } : r));
    setRows(next);
    send(next, deleteIds, true);
  };

  const toggleDelete = (id: number) => {
    const next = deleteIds.includes(id) ? deleteIds.filter((x) => x !== id) : [...deleteIds, id];
    setDeleteIds(next);
    send(rows, next, true);
  };

  const missingIds = missing.map((m) => m.booking_id!);
  const allMissingMarked = missingIds.every((id) => deleteIds.includes(id));
  const toggleAllMissing = () => {
    const next = allMissingMarked
      ? deleteIds.filter((id) => !missingIds.includes(id))
      : [...new Set([...deleteIds, ...missingIds])];
    setDeleteIds(next);
    send(rows, next, true);
  };

  const skipProblems = () => {
    const next = rows.map((r, i) =>
      results[i]?.status === "conflict" || results[i]?.status === "error" ? { ...r, skip: true } : r,
    );
    setRows(next);
    send(next, deleteIds, true);
  };

  const count = (s: ImportResult["status"]) => results.filter((r) => r.status === s).length;
  const problems = count("conflict") + count("error");

  return (
    <section className="card stack">
      <p className="muted">
        Файл .xlsx в том же виде, что и расписание на сайте: в первой строке — <b>даты</b> (столбец
        = один день), в первом столбце — <b>время</b> (09:00, 10:00…), в ячейке — <b>кто записан</b>{" "}
        (имя или логин участника). Проще всего взять за основу экспорт из вкладки «Расписание».
        Имя, которого нет среди участников, запишется как гость. Если ячейка
        пустая, а на сайте запись есть — импорт предложит удалить её галочкой.
      </p>
      <div>
        <input ref={fileRef} type="file" accept=".xlsx" onChange={upload} disabled={busy} />
      </div>
      {error && <div className="error">{error}</div>}
      {done && <div className="ok">{done}</div>}

      {ignored > 0 && (
        <div className="muted small">
          Пропущено ячеек с датой вне расписания (раньше недели назад или позже трёх недель вперёд):{" "}
          {ignored}
        </div>
      )}

      {(rows.length > 0 || missing.length > 0) && (
        <>
          <div className="row spread">
            <span>
              Новых: {count("new")} · изменений: {count("update")} · без изменений:{" "}
              {count("unchanged")} · пропущено: {count("skipped")} ·{" "}
              <b className={problems ? "text-danger" : ""}>проблемных: {problems}</b>
              {deleteIds.length > 0 && <> · будет удалено с сайта: {deleteIds.length}</>}
            </span>
            <span className="row">
              {dirty && (
                <button disabled={busy} onClick={() => send(rows, deleteIds, true)}>
                  Перепроверить
                </button>
              )}
              {problems > 0 && (
                <button disabled={busy} onClick={skipProblems}>
                  Пропустить все проблемные
                </button>
              )}
              <button
                className="primary"
                disabled={busy || (!dirty && problems > 0)}
                onClick={() => send(rows, deleteIds, false)}
              >
                Применить
              </button>
              <button disabled={busy} onClick={reset}>
                Отмена
              </button>
            </span>
          </div>

          {missing.length > 0 && (
            <div className="missing">
              <div className="row spread">
                <b>На сайте записи есть, а в таблице эти ячейки пустые ({missing.length})</b>
                <button disabled={busy} onClick={toggleAllMissing}>
                  {allMissingMarked ? "Снять все" : "Отметить все"}
                </button>
              </div>
              <p className="muted small">
                Отметьте записи, которые нужно удалить с сайта. Неотмеченные останутся как есть.
              </p>
              {missing.map((m) => (
                <label key={m.booking_id} className="conflict">
                  <input
                    type="checkbox"
                    disabled={busy}
                    checked={deleteIds.includes(m.booking_id!)}
                    onChange={() => toggleDelete(m.booking_id!)}
                  />
                  удалить с сайта: {m.label}, {fmt(m.start)}–{m.end.slice(11, 16)}
                </label>
              ))}
            </div>
          )}

          {rows.length > 0 && (
          <div className="grid-wrap">
            <table className="import">
              <thead>
                <tr>
                  <th>№</th>
                  <th>Пропуск</th>
                  <th>Дата</th>
                  <th>Начало</th>
                  <th>Конец</th>
                  <th>Участник</th>
                  <th>Комментарий</th>
                  <th>Статус</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((row, i) => {
                  const res = results[i];
                  return (
                    <tr key={i} className={`st-${res?.status ?? ""}`}>
                      <td>{i + 1}</td>
                      <td>
                        <input type="checkbox" checked={row.skip} disabled={busy} onChange={() => toggleSkip(i)} />
                      </td>
                      <td>
                        <input className="w-date" value={row.date} disabled={row.skip} placeholder="ГГГГ-ММ-ДД" onChange={(e) => edit(i, { date: e.target.value })} />
                      </td>
                      <td>
                        <input className="w-time" value={row.start} disabled={row.skip} placeholder="ЧЧ:ММ" onChange={(e) => edit(i, { start: e.target.value })} />
                      </td>
                      <td>
                        <input className="w-time" value={row.end} disabled={row.skip} placeholder="ЧЧ:ММ" onChange={(e) => edit(i, { end: e.target.value })} />
                      </td>
                      <td>
                        <input value={row.member} disabled={row.skip} onChange={(e) => edit(i, { member: e.target.value })} />
                        {res?.resolved_name && (
                          <div className="muted small">
                            {res.is_guest ? "гость" : res.resolved_name}
                          </div>
                        )}
                      </td>
                      <td>
                        <input value={row.note} disabled={row.skip} maxLength={200} onChange={(e) => edit(i, { note: e.target.value })} />
                      </td>
                      <td className="status">
                        {res && (
                          <>
                            <span className={`badge st-${res.status}`}>{STATUS_LABEL[res.status]}</span>
                            {(res.status === "conflict" || res.status === "error") && (
                              <div className="small">{res.message}</div>
                            )}
                            {res.conflicts.map((c, k) =>
                              c.kind === "existing" && c.booking_id !== null ? (
                                <label key={k} className="conflict small">
                                  <input type="checkbox" disabled={busy} checked={deleteIds.includes(c.booking_id)} onChange={() => toggleDelete(c.booking_id!)} />
                                  удалить с сайта: {c.label}, {fmt(c.start)}–{c.end.slice(11, 16)}
                                </label>
                              ) : (
                                <div key={k} className="small">
                                  строка №{(c.index ?? 0) + 1}: {c.label}, {fmt(c.start)}–{c.end.slice(11, 16)}
                                </div>
                              ),
                            )}
                          </>
                        )}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
          )}
          {rows.length > 0 && (
          <p className="muted small">
            Конфликт — это когда время из таблицы на сайте уже занято другим человеком. Как разобрать: исправьте дату/время в строке и нажмите «Перепроверить», либо
            пропустите строку (останется запись с сайта), либо отметьте мешающую запись с сайта на
            удаление (останется запись из файла).
          </p>
          )}
        </>
      )}
    </section>
  );
}
