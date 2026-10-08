import { useEffect, useMemo, useState } from "react";
import { Link, useSearchParams } from "react-router-dom";
import { fetchEventStats, fetchOrderSummary } from "../api/admin";
import { OrdersTable } from "../components/OrdersTable";
import { TextRowsSkeleton } from "../components/Skeleton";
import { useDocumentTitle } from "../hooks/useDocumentTitle";
import { extractErrorMessage } from "../utils/error";
import type { EventStats, OrderSummary } from "../types";

type View = "revenue" | "orders" | "events";

const VIEWS: { key: View; label: string; hint: string }[] = [
  { key: "revenue", label: "총 매출", hint: "공연별 매출 보기" },
  { key: "orders", label: "결제 완료 주문", hint: "주문 목록 보기" },
  { key: "events", label: "등록된 공연", hint: "공연 목록 보기" },
];

export function DashboardPage() {
  useDocumentTitle("관리자 대시보드");
  const [searchParams, setSearchParams] = useSearchParams();
  const view: View = VIEWS.find((v) => v.key === searchParams.get("view"))?.key ?? "revenue";

  const [stats, setStats] = useState<EventStats[]>([]);
  const [orders, setOrders] = useState<OrderSummary[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    // Two services own the two halves of this view (seats in event-service, money in
    // order-service), so the page joins them by eventId instead of either service calling the other.
    Promise.all([fetchEventStats(), fetchOrderSummary()])
      .then(([eventStats, orderSummary]) => {
        setStats(eventStats);
        setOrders(orderSummary);
      })
      .catch((err) => setError(extractErrorMessage(err)))
      .finally(() => setIsLoading(false));
  }, []);

  const ordersByEvent = useMemo(() => new Map(orders.map((o) => [o.eventId, o])), [orders]);

  const totalRevenue = orders.reduce((sum, o) => sum + o.revenue, 0);
  const totalPaid = orders.reduce((sum, o) => sum + o.paidCount, 0);

  // "총 매출" ranks the shows by what they earned; "등록된 공연" keeps registration order.
  const rows = useMemo(() => {
    const revenueOf = (eventId: number) => ordersByEvent.get(eventId)?.revenue ?? 0;
    return view === "revenue" ? [...stats].sort((a, b) => revenueOf(b.eventId) - revenueOf(a.eventId)) : stats;
  }, [stats, view, ordersByEvent]);

  const values: Record<View, string> = {
    revenue: `${totalRevenue.toLocaleString()}원`,
    orders: `${totalPaid.toLocaleString()}건`,
    events: `${stats.length}개`,
  };

  if (isLoading) {
    return (
      <div>
        <h1>관리자 대시보드</h1>
        <TextRowsSkeleton rows={4} />
      </div>
    );
  }
  if (error) {
    return <p className="form-error">{error}</p>;
  }

  return (
    <div>
      <div className="admin-header">
        <div>
          <p className="eyebrow">Overview</p>
          <h1>대시보드</h1>
        </div>
        <Link to="/events/new" className="btn-link primary">
          + 새 공연 등록
        </Link>
      </div>

      <div className="admin-summary" role="group" aria-label="보기 선택">
        {VIEWS.map((v) => (
          <button
            key={v.key}
            type="button"
            className={`admin-summary-item ${view === v.key ? "active" : ""}`}
            aria-pressed={view === v.key}
            onClick={() => setSearchParams({ view: v.key }, { replace: true })}
          >
            <span>{v.label}</span>
            <strong>{values[v.key]}</strong>
            <em>{view === v.key ? "보는 중" : v.hint}</em>
          </button>
        ))}
      </div>

      {view === "orders" ? (
        <>
          <div className="admin-section-head">
            <h2 className="admin-section-title">결제 완료 주문</h2>
            <Link to="/orders" className="btn-link">
              전체 주문 내역 →
            </Link>
          </div>
          <OrdersTable filter="PAID" />
        </>
      ) : (
        <>
          <h2 className="admin-section-title">{view === "revenue" ? "공연별 매출" : "등록된 공연"}</h2>
          <div className="order-table-wrap">
            <table className="order-table admin-table">
              <thead>
                <tr>
                  <th>공연</th>
                  <th>판매율</th>
                  <th>잔여 / 홀드 / 판매</th>
                  <th>결제 / 취소</th>
                  <th>매출</th>
                  <th></th>
                </tr>
              </thead>
              <tbody>
                {rows.map((event) => {
                  const summary = ordersByEvent.get(event.eventId);
                  const soldRate =
                    event.totalSeats === 0 ? 0 : Math.round((event.soldSeats / event.totalSeats) * 100);
                  return (
                    <tr key={event.eventId}>
                      <td>
                        <Link to={`/events/${event.eventId}/edit`} className="event-name">
                          {event.title}
                        </Link>
                        <div className="admin-grade-line">
                          {event.grades.map((g) => `${g.grade} ${g.sold}/${g.total}`).join(" · ") || "좌석 미등록"}
                        </div>
                      </td>
                      <td>
                        <div className="sell-rate">
                          <div className={`sell-rate-bar ${soldRate >= 80 ? "is-high" : ""}`}>
                            <span style={{ width: `${soldRate}%` }} />
                          </div>
                          <strong>{soldRate}%</strong>
                        </div>
                      </td>
                      <td className="num">
                        {event.availableSeats} / {event.holdSeats} / {event.soldSeats}
                      </td>
                      <td className="num">
                        {summary?.paidCount ?? 0} / {summary?.cancelledCount ?? 0}
                      </td>
                      <td className="num">{(summary?.revenue ?? 0).toLocaleString()}원</td>
                      <td>
                        <Link to={`/events/${event.eventId}/edit`} className="btn-link">
                          수정
                        </Link>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        </>
      )}
    </div>
  );
}
