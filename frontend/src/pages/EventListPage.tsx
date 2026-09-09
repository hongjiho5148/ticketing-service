import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { fetchEvents } from "../api/events";
import { extractErrorMessage } from "../utils/error";
import type { EventSummary } from "../types";

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
      <h1>이벤트 목록</h1>
      {events.length === 0 ? (
        <p className="page-status">등록된 이벤트가 없습니다.</p>
      ) : (
        <ul className="event-list">
          {events.map((event) => (
            <li key={event.id} className="event-card">
              <Link to={`/events/${event.id}`}>
                <h2>{event.title}</h2>
                <p>{event.venue}</p>
                <p>{new Date(event.startAt).toLocaleString()}</p>
                <span className={`badge badge-${event.status.toLowerCase()}`}>{event.status}</span>
              </Link>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
