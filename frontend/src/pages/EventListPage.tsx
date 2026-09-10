import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { fetchEvents } from "../api/events";
import { dDayLabel, formatDateTime } from "../utils/date";
import { extractErrorMessage } from "../utils/error";
import { posterGlyph, posterThemeClass } from "../utils/poster";
import type { EventStatus, EventSummary } from "../types";

const STATUS_LABEL: Record<EventStatus, string> = {
  OPEN: "예매중",
  UPCOMING: "오픈예정",
  CLOSED: "예매종료",
};

const STATUS_CLASS: Record<EventStatus, string> = {
  OPEN: "badge-open",
  UPCOMING: "badge-upcoming",
  CLOSED: "badge-closed",
};

export function EventListPage() {
  const [events, setEvents] = useState<EventSummary[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [isLoading, setIsLoading] = useState(true);

  useEffect(() => {
    fetchEvents({ page: 0, size: 20 })
      .then((res) => setEvents(res.content))
      .catch((err) => setError(extractErrorMessage(err)))
      .finally(() => setIsLoading(false));
  }, []);

  if (isLoading) {
    return <p className="page-status">이벤트를 불러오는 중...</p>;
  }
  if (error) {
    return <p className="form-error">{error}</p>;
  }

  return (
    <div>
      <div className="list-header">
        <h1>픽시트</h1>
        <p>지금 예매할 수 있는 공연을 확인해보세요.</p>
      </div>
      {events.length === 0 ? (
        <p className="page-status">등록된 이벤트가 없습니다.</p>
      ) : (
        <ul className="event-list">
          {events.map((event) => (
            <li key={event.id} className="event-card">
              <Link to={`/events/${event.id}`}>
                <div className={`event-card-poster ${posterThemeClass(event.id)}`}>
                  <span className="poster-glyph">{posterGlyph(event.title)}</span>
                  {event.status !== "CLOSED" && <span className="event-card-dday">{dDayLabel(event.startAt)}</span>}
                </div>
                <div className="event-card-body">
                  <h2>{event.title}</h2>
                  <p className="event-card-meta">{event.venue}</p>
                  <p className="event-card-meta">{formatDateTime(event.startAt)}</p>
                  <span className={`badge ${STATUS_CLASS[event.status]}`}>{STATUS_LABEL[event.status]}</span>
                </div>
              </Link>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
