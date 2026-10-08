import { useEffect, useRef, useState, type CSSProperties } from "react";
import { Link } from "react-router-dom";
import { fetchEvents } from "../api/events";
import { EventListSkeleton } from "../components/Skeleton";
import { useDocumentTitle } from "../hooks/useDocumentTitle";
import { dDayLabel, formatShortDate } from "../utils/date";
import { extractErrorMessage } from "../utils/error";
import { posterGlyph, posterThemeClass } from "../utils/poster";
import { CATEGORY_LABEL, CATEGORY_ORDER } from "../utils/category";
import type { EventCategory, EventStatus, EventSummary } from "../types";

const PAGE_SIZE = 12;

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

const STATUS_FILTERS: { label: string; value: EventStatus | undefined }[] = [
  { label: "전체", value: undefined },
  { label: "예매중", value: "OPEN" },
  { label: "오픈예정", value: "UPCOMING" },
  { label: "예매종료", value: "CLOSED" },
];

export function EventListPage() {
  useDocumentTitle("이벤트 목록");

  const [events, setEvents] = useState<EventSummary[]>([]);
  const [totalElements, setTotalElements] = useState(0);
  const [page, setPage] = useState(0);
  const [error, setError] = useState<string | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [isLoadingMore, setIsLoadingMore] = useState(false);

  const [keywordInput, setKeywordInput] = useState("");
  const [keyword, setKeyword] = useState("");
  const [status, setStatus] = useState<EventStatus | undefined>(undefined);
  const [category, setCategory] = useState<EventCategory | undefined>(undefined);
  const [sortDir, setSortDir] = useState<"asc" | "desc">("asc");

  const debounceRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  // Debounce the search box so we don't fire a request on every keystroke.
  useEffect(() => {
    if (debounceRef.current) clearTimeout(debounceRef.current);
    debounceRef.current = setTimeout(() => setKeyword(keywordInput.trim()), 300);
    return () => {
      if (debounceRef.current) clearTimeout(debounceRef.current);
    };
  }, [keywordInput]);

  useEffect(() => {
    setIsLoading(true);
    setError(null);
    fetchEvents({ status, category, keyword: keyword || undefined, sortDir, page: 0, size: PAGE_SIZE })
      .then((res) => {
        setEvents(res.content);
        setTotalElements(res.totalElements);
        setPage(0);
      })
      .catch((err) => setError(extractErrorMessage(err)))
      .finally(() => setIsLoading(false));
  }, [status, category, keyword, sortDir]);

  function handleLoadMore() {
    const nextPage = page + 1;
    setIsLoadingMore(true);
    fetchEvents({ status, category, keyword: keyword || undefined, sortDir, page: nextPage, size: PAGE_SIZE })
      .then((res) => {
        setEvents((prev) => [...prev, ...res.content]);
        setTotalElements(res.totalElements);
        setPage(nextPage);
      })
      .catch((err) => setError(extractErrorMessage(err)))
      .finally(() => setIsLoadingMore(false));
  }

  const hasMore = events.length < totalElements;

  return (
    <div>
      <header className="page-head list-head">
        <div>
          <p className="eyebrow">PickSeat</p>
          <h1>공연 둘러보기</h1>
          <p>
            {isLoading ? "공연을 불러오는 중이에요" : <span className="list-count">
                총 <span className="num">{totalElements}</span>개의 공연
              </span>}
          </p>
        </div>
        <Link to="/calendar" className="view-switch">
          월별 캘린더로 보기 →
        </Link>
      </header>

      <div className="category-tabs" role="tablist" aria-label="장르">
        {[undefined, ...CATEGORY_ORDER].map((c) => (
          <button
            key={c ?? "ALL"}
            type="button"
            role="tab"
            aria-selected={category === c}
            className={`category-tab ${category === c ? "active" : ""}`}
            onClick={() => setCategory(c)}
          >
            {c ? CATEGORY_LABEL[c] : "전체 장르"}
          </button>
        ))}
      </div>

      <div className="event-toolbar">
        <input
          type="search"
          className="event-search"
          placeholder="공연 제목으로 검색"
          value={keywordInput}
          onChange={(e) => setKeywordInput(e.target.value)}
        />
        <div className="event-filter-group">
          {STATUS_FILTERS.map((f) => (
            <button
              key={f.label}
              type="button"
              className={`filter-chip ${status === f.value ? "active" : ""}`}
              onClick={() => setStatus(f.value)}
            >
              {f.label}
            </button>
          ))}
        </div>
        <select className="event-sort" value={sortDir} onChange={(e) => setSortDir(e.target.value as "asc" | "desc")}>
          <option value="asc">공연일 빠른순</option>
          <option value="desc">공연일 늦은순</option>
        </select>
      </div>

      {isLoading ? (
        <EventListSkeleton />
      ) : error ? (
        <p className="form-error">{error}</p>
      ) : events.length === 0 ? (
        <p className="page-status">조건에 맞는 이벤트가 없습니다.</p>
      ) : (
        <>
          <ul className="event-list">
            {events.map((event, index) => {
              const glyph = posterGlyph(event.title);
              return (
              <li
                key={event.id}
                className={`event-card ${index === 0 && events.length >= 3 ? "featured" : ""}`}
                style={{ "--i": Math.min(index, 12) } as CSSProperties}
              >
                <Link to={`/events/${event.id}`}>
                  <div
                    className={`event-card-poster ${posterThemeClass(event.id)}`}
                    style={{ "--glyph-len": glyph.length } as CSSProperties}
                  >
                    <span className="poster-category">{CATEGORY_LABEL[event.category]}</span>
                    {event.status !== "CLOSED" && <span className="event-card-dday">{dDayLabel(event.startAt)}</span>}
                    <span className="poster-glyph">{glyph}</span>
                  </div>
                  <div className="event-card-body">
                    <p className="event-card-date">{formatShortDate(event.startAt)}</p>
                    <h2>{event.title}</h2>
                    <p className="event-card-meta">{event.venue}</p>
                    <div className="event-card-tags">
                      <span className={`badge ${STATUS_CLASS[event.status]}`}>{STATUS_LABEL[event.status]}</span>
                      {event.averageRating !== null && (
                        <span className="event-card-rating">
                          ★ {event.averageRating.toFixed(1)} <small>({event.reviewCount})</small>
                        </span>
                      )}
                    </div>
                  </div>
                </Link>
              </li>
              );
            })}
          </ul>
          {hasMore && (
            <div className="load-more-row">
              <button type="button" className="btn-secondary" onClick={handleLoadMore} disabled={isLoadingMore}>
                {isLoadingMore ? "불러오는 중..." : "더보기"}
              </button>
            </div>
          )}
        </>
      )}
    </div>
  );
}
