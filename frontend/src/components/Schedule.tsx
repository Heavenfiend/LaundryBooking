import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
import { api, Booking, download, errorText, Laundry, Schedule as ScheduleData } from "../api";
import { confirmDialog, toast } from "../ui";

const WEEKDAYS = ["вс", "пн", "вт", "ср", "чт", "пт", "сб"];
const HOURS = Array.from({ length: 24 }, (_, h) => h);
const HOUR_MS = 3600_000;

// Время стиралки приходит без часового пояса; для арифметики считаем его как UTC.
const toMs = (local: string) => Date.parse(local + "Z");
const toLocal = (ms: number) => new Date(ms).toISOString().slice(0, 19);
const pad = (n: number) => String(n).padStart(2, "0");
const hhmm = (local: string) => local.slice(11, 16);
const weekday = (day: string) => new Date(toMs(day + "T00:00:00")).getUTCDay();
const isWeekend = (day: string) => weekday(day) % 6 === 0;

export default function Schedule({ laundry, isAdmin }: { laundry: Laundry; isAdmin: boolean }) {
  const [data, setData] = useState<ScheduleData | null>(null);
  const [error, setError] = useState("");
  const wrapRef = useRef<HTMLDivElement>(null);
  const todayRef = useRef<HTMLTableCellElement>(null);
  const scrolled = useRef(false);

  const load = useCallback(() => {
    api<ScheduleData>(`/laundries/${laundry.code}/bookings`)
      .then((d) => {
        setData(d);
        setError("");
      })
      .catch((e) => setError(errorText(e)));
  }, [laundry.code]);

  useEffect(() => {
    load();
    const timer = setInterval(load, 30_000);
    return () => clearInterval(timer);
  }, [load]);

  // при первом показе прокручиваем к сегодняшнему дню
  useLayoutEffect(() => {
    if (data && !scrolled.current && wrapRef.current && todayRef.current) {
      wrapRef.current.scrollLeft = todayRef.current.offsetLeft - 56;
      scrolled.current = true;
    }
  }, [data]);

  const days = useMemo(() => {
    if (!data) return [];
    const result: string[] = [];
    for (let t = toMs(data.date_from + "T00:00:00"); t <= toMs(data.date_to + "T00:00:00"); t += 24 * HOUR_MS) {
      result.push(toLocal(t).slice(0, 10));
    }
    return result;
  }, [data]);

  // ключ "дата|час" → записи, задевающие этот час
  const cells = useMemo(() => {
    const map = new Map<string, Booking[]>();
    for (const b of data?.bookings ?? []) {
      const end = toMs(b.end);
      for (let t = Math.floor(toMs(b.start) / HOUR_MS) * HOUR_MS; t < end; t += HOUR_MS) {
        const local = toLocal(t);
        const key = `${local.slice(0, 10)}|${Number(local.slice(11, 13))}`;
        map.set(key, [...(map.get(key) ?? []), b]);
      }
    }
    return map;
  }, [data]);

  if (error && !data) return <div className="card error">{error}</div>;
  if (!data) return <p className="muted">Загрузка…</p>;

  const nowMs = toMs(data.now);
  const today = data.now.slice(0, 10);

  const book = async (day: string, hour: number) => {
    const start = toMs(`${day}T${pad(hour)}:00:00`);
    try {
      await api(`/laundries/${laundry.code}/bookings`, {
        method: "POST",
        body: { start: toLocal(start), end: toLocal(start + HOUR_MS) },
      });
    } catch (e) {
      toast(errorText(e));
    }
    load();
  };

  const remove = async (b: Booking) => {
    const when = `${b.start.slice(8, 10)}.${b.start.slice(5, 7)} ${hhmm(b.start)}–${hhmm(b.end)}`;
    const ok = await confirmDialog(
      b.mine ? `Отменить вашу запись ${when}?` : `Удалить запись «${b.name}» ${when}?`,
      { confirmLabel: b.mine ? "Отменить запись" : "Удалить", danger: true },
    );
    if (!ok) return;
    try {
      await api(`/laundries/${laundry.code}/bookings/${b.id}`, { method: "DELETE" });
      toast("Запись удалена", "ok");
    } catch (e) {
      toast(errorText(e));
    }
    load();
  };

  return (
    <section className="stack">
      <div className="row spread">
        <span className="muted small">
          Нажмите на свободную ячейку, чтобы занять час. Нажмите на свою запись, чтобы отменить её.
        </span>
        <button
          onClick={() =>
            download(`/laundries/${laundry.code}/export`, `laundry-${laundry.code}.xlsx`).catch((e) =>
              toast(errorText(e)),
            )
          }
        >
          Экспорт в .xlsx
        </button>
      </div>
      {error && <div className="error">{error}</div>}
      <div className="grid-wrap" ref={wrapRef}>
        <table className="grid">
          <thead>
            <tr>
              <th className="hour" />
              {days.map((day) => (
                <th key={day} ref={day === today ? todayRef : undefined} className={`${day === today ? "today" : day < today ? "past" : ""} ${isWeekend(day) ? "weekend" : ""}`}>
                  <span className="wd">{WEEKDAYS[weekday(day)]}</span>
                  <span className="dm">
                    {day.slice(8, 10)}.{day.slice(5, 7)}
                  </span>
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {HOURS.map((hour) => (
              <tr key={hour}>
                <th className="hour">{pad(hour)}:00</th>
                {days.map((day) => {
                  const list = cells.get(`${day}|${hour}`) ?? [];
                  const past = toMs(`${day}T${pad(hour)}:00:00`) + HOUR_MS <= nowMs;
                  const canBook = !past && list.length === 0;
                  return (
                    <td key={day} className={`${past ? "past" : ""} ${day === today ? "today" : ""} ${isWeekend(day) ? "weekend" : ""}`}>
                      {list.map((b) => {
                        const canRemove = isAdmin || (b.mine && toMs(b.end) > nowMs);
                        const title = `${b.name}, ${hhmm(b.start)}–${hhmm(b.end)}${b.note ? ` — ${b.note}` : ""}`;
                        return canRemove ? (
                          <button key={b.id} className={`chip ${b.mine ? "mine" : ""}`} title={title + " (нажмите, чтобы удалить)"} onClick={() => remove(b)}>
                            <span>{b.name}</span>
                            <span className="x" aria-hidden>
                              ×
                            </span>
                          </button>
                        ) : (
                          <span key={b.id} className={`chip ${b.mine ? "mine" : ""}`} title={title}>
                            {b.name}
                          </span>
                        );
                      })}
                      {canBook && (
                        <button className="add" title={`Записаться на ${pad(hour)}:00`} onClick={() => book(day, hour)}>
                          +
                        </button>
                      )}
                    </td>
                  );
                })}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  );
}
