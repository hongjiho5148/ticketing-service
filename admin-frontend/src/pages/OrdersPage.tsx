import { useEffect, useState } from "react";
import { Link, useSearchParams } from "react-router-dom";
import { fetchAdminOrders } from "../api/admin";
import { TextRowsSkeleton } from "../components/Skeleton";
import { useDocumentTitle } from "../hooks/useDocumentTitle";
import { extractErrorMessage } from "../utils/error";
import type { AdminOrder, AdminOrderStatus } from "../types";

const PAGE_SIZE = 20;

const FILTERS: { key: AdminOrderStatus | "ALL"; label: string }[] = [
  { key: "PAID", label: "결제완료" },
  { key: "CANCELLED", label: "취소" },
  { key: "PARTIALLY_REFUNDED", label: "부분환불" },
  { key: "PENDING", label: "결제대기" },
  { key: "FAILED", label: "결제실패" },
  { key: "ALL", label: "전체" },
];

const STATUS_LABEL: Record<AdminOrderStatus, string> = {
  PENDING: "결제대기",
  PAID: "결제완료",
  FAILED: "결제실패",
  CANCELLED: "취소",
  PARTIALLY_REFUNDED: "부분환불",
};

const PAY_METHOD_LABEL: Record<string, string> = {
  CARD: "카드",
  KAKAOPAY: "카카오페이",
  NAVERPAY: "네이버페이",
  TOSSPAY: "토스페이",
};

function statusTone(status: AdminOrderStatus) {
  if (status === "PAID") return "is-paid";
  if (status === "PENDING") return "is-pending";
  if (status === "FAILED") return "is-problem";
  return "is-ended";
}

export function OrdersPage() {
  useDocumentTitle("주문 내역");
  const [searchParams, setSearchParams] = useSearchParams();
  const requested = searchParams.get("status");
  const filter = FILTERS.find((f) => f.key === requested)?.key ?? "PAID";

  const [orders, setOrders] = useState<AdminOrder[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(0);
  const [isLoading, setIsLoading] = useState(true);
  const [isLoadingMore, setIsLoadingMore] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    fetchAdminOrders({ status: filter === "ALL" ? undefined : filter, page: 0, size: PAGE_SIZE })
      .then((res) => {
        if (cancelled) return;
        setOrders(res.content);
        setTotal(res.totalElements);
        setPage(0);
        setError(null);
      })
      .catch((err) => {
        if (!cancelled) setError(extractErrorMessage(err));
      })
      .finally(() => {
        if (!cancelled) setIsLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [filter]);

  function selectFilter(key: AdminOrderStatus | "ALL") {
    setIsLoading(true);
    setSearchParams({ status: key }, { replace: true });
  }

  function loadMore() {
    const next = page + 1;
    setIsLoadingMore(true);
    fetchAdminOrders({ status: filter === "ALL" ? undefined : filter, page: next, size: PAGE_SIZE })
      .then((res) => {
        setOrders((prev) => [...prev, ...res.content]);
        setTotal(res.totalElements);
        setPage(next);
      })
      .catch((err) => setError(extractErrorMessage(err)))
      .finally(() => setIsLoadingMore(false));
  }

  return (
    <div>
      <div className="admin-header">
        <div>
          <p className="eyebrow">Orders</p>
          <h1>주문 내역</h1>
        </div>
        <Link to="/" className="btn-link">
          ← 대시보드
        </Link>
      </div>

      <div className="admin-filters" role="group" aria-label="주문 상태 필터">
        {FILTERS.map((f) => (
          <button
            key={f.key}
            type="button"
            className={`admin-filter-chip ${filter === f.key ? "active" : ""}`}
            aria-pressed={filter === f.key}
            onClick={() => selectFilter(f.key)}
          >
            {f.label}
          </button>
        ))}
      </div>

      {isLoading ? (
        <TextRowsSkeleton rows={5} />
      ) : error ? (
        <p className="form-error">{error}</p>
      ) : orders.length === 0 ? (
        <p className="page-status">이 상태의 주문이 없어요.</p>
      ) : (
        <>
          <p className="admin-count">
            총 <span className="num">{total.toLocaleString()}</span>건
          </p>
          <div className="order-table-wrap">
            <table className="order-table admin-table">
              <thead>
                <tr>
                  <th>주문</th>
                  <th>공연</th>
                  <th>좌석</th>
                  <th>구매자</th>
                  <th>결제금액</th>
                  <th>결제수단</th>
                  <th>상태</th>
                  <th>주문일시</th>
                </tr>
              </thead>
              <tbody>
                {orders.map((order) => (
                  <tr key={order.orderId}>
                    <td className="num">#{order.orderId}</td>
                    <td>
                      {order.eventTitle ? (
                        <Link to={`/events/${order.eventId}/edit`} className="event-name">
                          {order.eventTitle}
                        </Link>
                      ) : (
                        "-"
                      )}
                    </td>
                    <td>{order.grade ? `${order.grade}석 · ${order.section} · ${order.seatNo}` : "-"}</td>
                    <td>
                      <div className="event-name">{order.buyerName ?? `회원 #${order.buyerId}`}</div>
                      {order.buyerEmail && <div className="admin-grade-line">{order.buyerEmail}</div>}
                    </td>
                    <td className="num">
                      {order.totalPrice.toLocaleString()}원
                      {order.status === "PARTIALLY_REFUNDED" && order.refundedAmount ? (
                        <div className="admin-grade-line">환불 {order.refundedAmount.toLocaleString()}원</div>
                      ) : null}
                    </td>
                    <td>{order.paymentMethod ? (PAY_METHOD_LABEL[order.paymentMethod] ?? order.paymentMethod) : "-"}</td>
                    <td>
                      <span className={`order-status ${statusTone(order.status)}`}>{STATUS_LABEL[order.status]}</span>
                    </td>
                    <td className="num">
                      {new Date(order.createdAt).toLocaleString("ko-KR", { dateStyle: "short", timeStyle: "short" })}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          {orders.length < total && (
            <div className="admin-more">
              <button type="button" className="btn-secondary" onClick={loadMore} disabled={isLoadingMore}>
                {isLoadingMore ? "불러오는 중..." : "더보기"}
              </button>
            </div>
          )}
        </>
      )}
    </div>
  );
}
