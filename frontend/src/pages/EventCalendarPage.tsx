import { useEffect, useMemo, useState } from "react";
import { Link } from "react-router-dom";
import { fetchEvents } from "../api/events";
import { useDocumentTitle } from "../hooks/useDocumentTitle";
import { CATEGORY_LABEL } from "../utils/category";
import { extractErrorMessage } from "../utils/error";
import type { EventSummary } from "../types";

const WEEKDAYS = ["일", "월", "화", "수", "목", "금", "토"];

// Built from local date parts, not toISOString() - that converts to UTC and shifts dates by a day in KST.
function dateKey(year: number, month: number, day: number) {
  return `${year}-${String(month + 1).padStart(2, "0")}-${String(day).padStart(2, "0")}`;
}

function formatTime(iso: string) {
  return new Date(iso).toLocaleTimeString("ko-KR", { hour: "numeric", minute: "2-digit" });
}

export function EventCalendarPage() {
  useDocumentTitle("공연 캘린더");

  const today = new Date();
  const [year, setYear] = useState(today.getFullYear());
  const [month, setMonth] = useState(today.getMonth());
  const [events, setEvents] = useState<EventSummary[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [selectedKey, setSelectedKey] = useState<string | null>(dateKey(today.getFullYear(), today.getMonth(), today.getDate()));

  const daysInMonth = new Date(year, month + 1, 0).getDate();
  const firstWeekday = new Date(year, month, 1).getDay();

  useEffect(() => {
    setIsLoading(true);
    setError(null);
    fetchEvents({
      startDate: dateKey(year, month, 1),
      endDate: dateKey(year, month, daysInMonth),
      sortDir: "asc",
      size: 100,
    })
      .then((res) => setEvents(res.content))
      .catch((err) => setError(extractErrorMessage(err)))
      .finally(() => setIsLoading(false));
  }, [year, month, daysInMonth]);

  const eventsByDay = useMemo(() => {
    const map = new Map<string, EventSummary[]>();
    for (const event of events) {
      const key = event.startAt.slice(0, 10);
      map.set(key, [...(map.get(key) ?? []), event]);
    }
    return map;
  }, [events]);

  function moveMonth(delta: number) {
    const next = new Date(year, month + delta, 1);
    setYear(next.getFullYear());
    setMonth(next.getMonth());
    setSelectedKey(null);
  }

  const todayKey = dateKey(today.getFullYear(), today.getMonth(), today.getDate());
  const selectedEvents = selectedKey ? (eventsByDay.get(selectedKey) ?? []) : [];

  const cells: (number | null)[] = [
    ...Array.from({ length: firstWeekday }, () => null),
    ...Array.from({ length: daysInMonth }, (_, i) => i + 1),
  ];

  return (
    <div>
      <div className="calendar-header">
        <h1>공연 캘린더</h1>
        <Link to="/" className="view-switch">
          ☰ 목록으로 보기
        </Link>
      </div>

      <div className="calendar-nav">
        <button type="button" className="btn-secondary" onClick={() => moveMonth(-1)} aria-label="이전 달">
          ‹
        </button>
        <strong>
          {year}년 {month + 1}월
        </strong>
        <button type="button" className="btn-secondary" onClick={() => moveMonth(1)} aria-label="다음 달">
          ›
        </button>
        <button
          type="button"
          className="btn-secondary"
          onClick={() => {
            setYear(today.getFullYear());
            setMonth(today.getMonth());
            setSelectedKey(todayKey);
          }}
        >
          오늘
        </button>
      </div>

      {error && <p className="form-error">{error}</p>}

      <div className={`calendar-grid ${isLoading ? "loading" : ""}`}>
        {WEEKDAYS.map((w) => (
          <div key={w} className="calendar-weekday">
            {w}
          </div>
        ))}
        {cells.map((day, index) => {
          if (day === null) return <div key={`blank-${index}`} className="calendar-cell blank" />;
          const key = dateKey(year, month, day);
          const dayEvents = eventsByDay.get(key) ?? [];
          return (
            <button
              key={key}
              type="button"
              className={`calendar-cell ${key === todayKey ? "today" : ""} ${key === selectedKey ? "selected" : ""}`}
              onClick={() => setSelectedKey(key)}
            >
              <span className="calendar-day">{day}</span>
              {dayEvents.slice(0, 2).map((event) => (
                <span key={event.id} className="calendar-chip">
                  {event.title}
                </span>
              ))}
              {dayEvents.length > 2 && <span className="calendar-more">+{dayEvents.length - 2}</span>}
            </button>
          );
        })}
      </div>

      <section className="calendar-day-list">
        <h2 className="detail-section-title">
          {selectedKey ? `${Number(selectedKey.slice(5, 7))}월 ${Number(selectedKey.slice(8, 10))}일 공연` : "날짜를 선택해주세요"}
        </h2>
        {selectedKey && selectedEvents.length === 0 ? (
          <p className="page-status">이 날은 예정된 공연이 없어요.</p>
        ) : (
          <ul className="calendar-event-list">
            {selectedEvents.map((event) => (
              <li key={event.id}>
                <Link to={`/events/${event.id}`}>
                  <span className="calendar-event-time">{formatTime(event.startAt)}</span>
                  <span className="calendar-event-title">{event.title}</span>
                  <span className="calendar-event-meta">
                    {CATEGORY_LABEL[event.category]} · {event.venue}
                    {event.averageRating !== null && ` · ★ ${event.averageRating.toFixed(1)}`}
                  </span>
                </Link>
              </li>
            ))}
          </ul>
        )}
      </section>
    </div>
  );
}
