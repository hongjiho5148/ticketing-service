import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { fetchWishlist, removeFromWishlist } from "../api/wishlist";
import { EventListSkeleton } from "../components/Skeleton";
import { useToast } from "../context/ToastContext";
import { useDocumentTitle } from "../hooks/useDocumentTitle";
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

export function WishlistPage() {
  useDocumentTitle("찜한 공연");
  const { showToast } = useToast();

  const [events, setEvents] = useState<EventSummary[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [removingId, setRemovingId] = useState<number | null>(null);

  useEffect(() => {
    fetchWishlist()
      .then(setEvents)
      .catch((err) => setError(extractErrorMessage(err)))
      .finally(() => setIsLoading(false));
  }, []);

  async function handleRemove(eventId: number) {
    setRemovingId(eventId);
    try {
      await removeFromWishlist(eventId);
      setEvents((prev) => prev.filter((e) => e.id !== eventId));
    } catch (err) {
      showToast(extractErrorMessage(err), "error");
    } finally {
      setRemovingId(null);
    }
  }

  return (
    <div>
      <h1>찜한 공연</h1>
      {isLoading ? (
        <EventListSkeleton />
      ) : error ? (
        <p className="form-error">{error}</p>
      ) : events.length === 0 ? (
        <p className="page-status">찜한 공연이 없어요. 관심 있는 공연의 ♡ 버튼을 눌러보세요.</p>
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
              <button
                type="button"
                className="btn-secondary wishlist-remove"
                onClick={() => handleRemove(event.id)}
                disabled={removingId === event.id}
              >
                찜 해제
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
