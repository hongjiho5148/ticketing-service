import { isAxiosError } from "axios";
import { useEffect, useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { cancelOrder, fetchOrders, fetchRefundPreview } from "../api/orders";
import { fetchMyTickets } from "../api/tickets";
import { ConfirmDialog } from "./ConfirmDialog";
import { HoldRemaining } from "./Countdown";
import { SeatLocationModal } from "./SeatLocationModal";
import { TicketQrModal } from "./TicketQrModal";
import { TransferDialog } from "./TransferDialog";
import { TextRowsSkeleton } from "./Skeleton";
import { useToast } from "../context/ToastContext";
import { extractErrorMessage } from "../utils/error";
import type { ApiErrorBody, OrderHistoryItem, OrderStatus, RefundPreview, TicketHistoryItem } from "../types";

const PAGE_SIZE = 20;

const STATUS_LABEL: Record<OrderStatus, string> = {
  PENDING: "결제대기",
  PAID: "결제완료",
  FAILED: "결제실패",
  CANCELLED: "취소완료",
  PARTIALLY_REFUNDED: "부분환불",
};

type OrderFilter = "all" | "paid" | "pending" | "closed";

const FILTERS: { key: OrderFilter; label: string }[] = [
  { key: "all", label: "전체" },
  { key: "paid", label: "결제완료" },
  { key: "pending", label: "결제대기" },
  { key: "closed", label: "취소·만료" },
];

function matchesFilter(order: OrderHistoryItem, filter: OrderFilter): boolean {
  if (filter === "all") return true;
  if (filter === "paid") return order.status === "PAID";
  if (filter === "pending") return order.status === "PENDING" && !!order.holdExpireAt;
  return order.status !== "PAID" && !(order.status === "PENDING" && !!order.holdExpireAt);
}

// Colors the card's left edge and the dot in front of the status: paid = green, waiting = amber,
// ended = gray, failed/expired = red.
function statusTone(order: OrderHistoryItem): string {
  if (order.status === "PAID") return "is-paid";
  if (order.status === "PENDING") return order.holdExpireAt ? "is-pending" : "is-problem";
  if (order.status === "FAILED") return "is-problem";
  return "is-ended";
}

const PAY_METHOD_LABEL: Record<string, string> = {
  CARD: "카드",
  KAKAOPAY: "카카오페이",
  NAVERPAY: "네이버페이",
  TOSSPAY: "토스페이",
};

interface CancelTarget {
  order: OrderHistoryItem;
  preview: RefundPreview;
}

export function OrdersPanel() {
  const { showToast } = useToast();
  const navigate = useNavigate();

  const [orders, setOrders] = useState<OrderHistoryItem[]>([]);
  const [totalElements, setTotalElements] = useState(0);
  const [page, setPage] = useState(0);
  const [error, setError] = useState<string | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [isLoadingMore, setIsLoadingMore] = useState(false);
  const [cancellingId, setCancellingId] = useState<number | null>(null);
  const [viewingOrder, setViewingOrder] = useState<OrderHistoryItem | null>(null);
  const [cancelTarget, setCancelTarget] = useState<CancelTarget | null>(null);
  const [transferTarget, setTransferTarget] = useState<OrderHistoryItem | null>(null);
  const [filter, setFilter] = useState<OrderFilter>("all");
  const [ticketsByOrder, setTicketsByOrder] = useState<Map<number, TicketHistoryItem>>(new Map());
  const [qrOrder, setQrOrder] = useState<OrderHistoryItem | null>(null);

  useEffect(() => {
    fetchOrders({ page: 0, size: PAGE_SIZE })
      .then((res) => {
        setOrders(res.content);
        setTotalElements(res.totalElements);
      })
      .catch((err) => setError(extractErrorMessage(err)))
      .finally(() => setIsLoading(false));
    // Tickets only exist once the show is close, so this just decorates paid orders with their QR state.
    fetchMyTickets()
      .then((list) => setTicketsByOrder(new Map(list.map((t) => [t.orderId, t]))))
      .catch(() => undefined);
  }, []);

  function handleLoadMore() {
    const nextPage = page + 1;
    setIsLoadingMore(true);
    fetchOrders({ page: nextPage, size: PAGE_SIZE })
      .then((res) => {
        setOrders((prev) => [...prev, ...res.content]);
        setTotalElements(res.totalElements);
        setPage(nextPage);
      })
      .catch((err) => showToast(extractErrorMessage(err), "error"))
      .finally(() => setIsLoadingMore(false));
  }

  // The refund depends on how close the show is, so ask the server what cancelling would return
  // *before* the user confirms - and never show a number computed here.
  async function handleStartCancel(order: OrderHistoryItem) {
    setCancellingId(order.orderId);
    try {
      const preview = await fetchRefundPreview(order.orderId);
      if (!preview.cancellable) {
        showToast("공연 24시간 전부터는 취소할 수 없어요.", "error");
        return;
      }
      setCancelTarget({ order, preview });
    } catch (err) {
      showToast(extractErrorMessage(err), "error");
    } finally {
      setCancellingId(null);
    }
  }

  async function handleConfirmCancel() {
    if (!cancelTarget) return;
    const { order, preview } = cancelTarget;
    setCancelTarget(null);
    setCancellingId(order.orderId);
    try {
      // Sent along so the server refuses (instead of silently refunding less) if the tier ticked
      // over between this dialog opening and the click.
      await cancelOrder(order.orderId, preview.refundAmount);
      const status: OrderStatus = preview.refundPercent === 100 ? "CANCELLED" : "PARTIALLY_REFUNDED";
      setOrders((prev) =>
        prev.map((o) =>
          o.orderId === order.orderId ? { ...o, status, refundedAmount: preview.refundAmount, transferable: false } : o,
        ),
      );
      showToast(`주문이 취소됐어요. ${preview.refundAmount.toLocaleString()}원이 환불돼요.`);
    } catch (err) {
      if (isAxiosError<ApiErrorBody>(err) && err.response?.data?.code === "REFUND_QUOTE_CHANGED") {
        showToast("환불 금액이 바뀌었어요. 새 금액을 확인해주세요.", "error");
        await handleStartCancel(order);
      } else {
        showToast(extractErrorMessage(err), "error");
      }
    } finally {
      setCancellingId(null);
    }
  }

  // The seat hold ran out while the list was open: the order can no longer be paid.
  function handleHoldExpired(orderId: number) {
    setOrders((prev) => prev.map((o) => (o.orderId === orderId ? { ...o, holdExpireAt: null } : o)));
  }

  function handleTransferSent() {
    if (!transferTarget) return;
    const orderId = transferTarget.orderId;
    setTransferTarget(null);
    setOrders((prev) =>
      prev.map((o) => (o.orderId === orderId ? { ...o, transferStatus: "PENDING", transferable: false } : o)),
    );
    showToast("양도 요청을 보냈어요. 받는 분이 수락하면 티켓이 넘어가요.");
  }

  function describeRefund({ preview }: CancelTarget) {
    const lines = [
      `환불 ${preview.refundAmount.toLocaleString()}원 (결제금액의 ${preview.refundPercent}%)`,
      preview.feeAmount > 0 ? `취소 수수료 ${preview.feeAmount.toLocaleString()}원` : "취소 수수료 없음",
    ];
    if (preview.pointsRestored > 0) {
      lines.push(`사용한 포인트 ${preview.pointsRestored.toLocaleString()}P 환원`);
    }
    if (preview.couponRestored) {
      lines.push("사용한 쿠폰은 다시 쓸 수 있어요");
    } else if (preview.couponForfeited) {
      lines.push("사용한 쿠폰은 복원되지 않아요 (전액 환불일 때만 돌려드려요)");
    }
    lines.push("취소하면 되돌릴 수 없어요.");
    return lines.join("\n");
  }

  const hasMore = orders.length < totalElements;
  const visibleOrders = orders.filter((o) => matchesFilter(o, filter));

  if (isLoading) {
    return (
      <TextRowsSkeleton rows={5} />
    );
  }
  if (error) {
    return <p className="form-error">{error}</p>;
  }

  return (
    <div>
      <p className="form-notice">
        환불정책: 공연 7일 전까지 100%, 3일 전까지 70%, 1일 전까지 30% 환불돼요. 공연 24시간 전부터는 취소할 수 없어요.
      </p>
      {orders.length === 0 ? (
        <p className="page-status">주문 내역이 없습니다.</p>
      ) : (
        <>
          <div className="event-filter-group order-filters" role="group" aria-label="주문 상태 필터">
            {FILTERS.map((f) => (
              <button
                key={f.key}
                type="button"
                className={`filter-chip ${filter === f.key ? "active" : ""}`}
                aria-pressed={filter === f.key}
                onClick={() => setFilter(f.key)}
              >
                {f.label}
              </button>
            ))}
          </div>

          {visibleOrders.length === 0 ? (
            <p className="page-status">이 상태의 주문이 없어요.</p>
          ) : (
            <ul className="order-list">
              {visibleOrders.map((order) => {
                const isWaiting = order.status === "PENDING" && !!order.holdExpireAt;
                return (
                  <li key={order.orderId} className={`order-card ${statusTone(order)}`}>
                    <div className="order-card-body">
                      <div className="order-card-top">
                        <span className={`order-status ${statusTone(order)}`}>
                          {order.status === "PENDING" && !order.holdExpireAt ? "결제 시간 만료" : STATUS_LABEL[order.status]}
                        </span>
                        {isWaiting && order.holdExpireAt && (
                          <HoldRemaining expireAt={order.holdExpireAt} onExpire={() => handleHoldExpired(order.orderId)} />
                        )}
                        {order.transferStatus && (
                          <span className="order-tag">
                            {order.transferStatus === "PENDING" ? "양도 수락 대기" : "양도 완료"}
                          </span>
                        )}
                        <time className="order-date" dateTime={order.createdAt}>
                          {new Date(order.createdAt).toLocaleString("ko-KR", { dateStyle: "medium", timeStyle: "short" })}
                        </time>
                      </div>

                      <h2 className="order-title">{order.eventTitle}</h2>

                      <dl className="order-meta">
                        <div>
                          <dt>좌석</dt>
                          <dd>
                            <button type="button" className="seat-link" onClick={() => setViewingOrder(order)}>
                              {order.grade}석 · {order.section} · {order.seatNo}
                            </button>
                          </dd>
                        </div>
                        <div>
                          <dt>결제</dt>
                          <dd>
                            <span className="num">{order.totalPrice.toLocaleString()}원</span>
                            {order.paymentMethod && (
                              <small> · {PAY_METHOD_LABEL[order.paymentMethod] ?? order.paymentMethod}</small>
                            )}
                          </dd>
                        </div>
                        {order.status === "PAID" && (
                          <div>
                            <dt>입장권</dt>
                            <dd>
                              {ticketsByOrder.get(order.orderId)?.status === "USED" ? (
                                "입장 완료"
                              ) : ticketsByOrder.has(order.orderId) ? (
                                "QR 발급됨"
                              ) : (
                                <small>공연 2시간 전부터 QR이 발급돼요</small>
                              )}
                            </dd>
                          </div>
                        )}
                        {order.status === "PARTIALLY_REFUNDED" && order.refundedAmount !== null && (
                          <div>
                            <dt>환불</dt>
                            <dd>
                              <span className="num">{order.refundedAmount.toLocaleString()}원</span>
                            </dd>
                          </div>
                        )}
                      </dl>
                    </div>

                    <div className="order-actions">
                      {ticketsByOrder.get(order.orderId)?.status === "ISSUED" && (
                        <button type="button" onClick={() => setQrOrder(order)}>
                          QR 보기
                        </button>
                      )}
                      {isWaiting && (
                        <button type="button" onClick={() => navigate(`/events/${order.eventId}?resume=${order.orderId}`)}>
                          결제하기
                        </button>
                      )}
                      {order.status === "PAID" && !order.transferStatus && (
                        <button
                          type="button"
                          className="btn-secondary"
                          onClick={() => handleStartCancel(order)}
                          disabled={cancellingId === order.orderId}
                        >
                          {cancellingId === order.orderId ? "취소 중..." : "주문 취소"}
                        </button>
                      )}
                      {order.status === "PAID" && order.transferable && (
                        <button type="button" className="btn-secondary" onClick={() => setTransferTarget(order)}>
                          양도
                        </button>
                      )}
                      {order.transferStatus === "PENDING" && (
                        <Link to="/transfers" className="seat-link">
                          양도함 보기
                        </Link>
                      )}
                    </div>
                  </li>
                );
              })}
            </ul>
          )}
          {hasMore && (
            <div className="load-more-row">
              <button type="button" className="btn-secondary" onClick={handleLoadMore} disabled={isLoadingMore}>
                {isLoadingMore ? "불러오는 중..." : "더보기"}
              </button>
            </div>
          )}
        </>
      )}

      {qrOrder && (
        <TicketQrModal orderId={qrOrder.orderId} eventTitle={qrOrder.eventTitle} onClose={() => setQrOrder(null)} />
      )}

      {viewingOrder && (
        <SeatLocationModal
          eventId={viewingOrder.eventId}
          eventTitle={viewingOrder.eventTitle}
          venue={viewingOrder.venue}
          section={viewingOrder.section}
          rowNo={viewingOrder.rowNo}
          seatNumber={viewingOrder.seatNumber}
          onClose={() => setViewingOrder(null)}
        />
      )}

      {transferTarget && (
        <TransferDialog
          orderId={transferTarget.orderId}
          eventTitle={transferTarget.eventTitle}
          seatLabel={`${transferTarget.grade}석 · ${transferTarget.section} · ${transferTarget.seatNo}`}
          onDone={handleTransferSent}
          onCancel={() => setTransferTarget(null)}
        />
      )}

      {cancelTarget && (
        <ConfirmDialog
          title="주문 취소"
          message={describeRefund(cancelTarget)}
          confirmLabel="취소하기"
          danger
          onConfirm={handleConfirmCancel}
          onCancel={() => setCancelTarget(null)}
        />
      )}
    </div>
  );
}
