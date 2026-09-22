import { useEffect, useState } from "react";
import { cancelOrder, fetchOrders } from "../api/orders";
import { ConfirmDialog } from "../components/ConfirmDialog";
import { SeatLocationModal } from "../components/SeatLocationModal";
import { TextRowsSkeleton } from "../components/Skeleton";
import { useToast } from "../context/ToastContext";
import { useDocumentTitle } from "../hooks/useDocumentTitle";
import { extractErrorMessage } from "../utils/error";
import type { OrderHistoryItem, OrderStatus } from "../types";

const PAGE_SIZE = 20;

const STATUS_LABEL: Record<OrderStatus, string> = {
  PENDING: "결제대기",
  PAID: "결제완료",
  FAILED: "결제실패",
  CANCELLED: "취소완료",
};

export function MyOrdersPage() {
  useDocumentTitle("내 주문 내역");
  const { showToast } = useToast();

  const [orders, setOrders] = useState<OrderHistoryItem[]>([]);
  const [totalElements, setTotalElements] = useState(0);
  const [page, setPage] = useState(0);
  const [error, setError] = useState<string | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [isLoadingMore, setIsLoadingMore] = useState(false);
  const [cancellingId, setCancellingId] = useState<number | null>(null);
  const [viewingOrder, setViewingOrder] = useState<OrderHistoryItem | null>(null);
  const [cancelTarget, setCancelTarget] = useState<OrderHistoryItem | null>(null);

  useEffect(() => {
    fetchOrders({ page: 0, size: PAGE_SIZE })
      .then((res) => {
        setOrders(res.content);
        setTotalElements(res.totalElements);
      })
      .catch((err) => setError(extractErrorMessage(err)))
      .finally(() => setIsLoading(false));
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

  async function handleConfirmCancel() {
    if (!cancelTarget) return;
    const orderId = cancelTarget.orderId;
    setCancelTarget(null);
    setCancellingId(orderId);
    try {
      await cancelOrder(orderId);
      setOrders((prev) => prev.map((o) => (o.orderId === orderId ? { ...o, status: "CANCELLED" } : o)));
      showToast("주문이 취소됐어요. 결제 금액은 환불됩니다.");
    } catch (err) {
      showToast(extractErrorMessage(err), "error");
    } finally {
      setCancellingId(null);
    }
  }

  const hasMore = orders.length < totalElements;

  if (isLoading) {
    return (
      <div>
        <h1>내 주문 내역</h1>
        <TextRowsSkeleton rows={5} />
      </div>
    );
  }
  if (error) {
    return <p className="form-error">{error}</p>;
  }

  return (
    <div>
      <h1>내 주문 내역</h1>
      <p className="form-notice">
        환불정책: 공연 시작 24시간 전까지 전액 환불 가능합니다. 그 이후에는 취소할 수 없어요.
      </p>
      {orders.length === 0 ? (
        <p className="page-status">주문 내역이 없습니다.</p>
      ) : (
        <>
          <table className="order-table">
            <thead>
              <tr>
                <th>이벤트</th>
                <th>좌석</th>
                <th>결제금액</th>
                <th>상태</th>
                <th>주문일시</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              {orders.map((order) => (
                <tr key={order.orderId}>
                  <td>{order.eventTitle}</td>
                  <td>
                    <button type="button" className="seat-link" onClick={() => setViewingOrder(order)}>
                      {order.grade}석 · {order.section} · {order.seatNo}
                    </button>
                  </td>
                  <td>{order.totalPrice.toLocaleString()}원</td>
                  <td>{STATUS_LABEL[order.status]}</td>
                  <td>{new Date(order.createdAt).toLocaleString()}</td>
                  <td>
                    {order.status === "PAID" && (
                      <button
                        type="button"
                        className="btn-secondary"
                        onClick={() => setCancelTarget(order)}
                        disabled={cancellingId === order.orderId}
                      >
                        {cancellingId === order.orderId ? "취소 중..." : "취소"}
                      </button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          {hasMore && (
            <div className="load-more-row">
              <button type="button" className="btn-secondary" onClick={handleLoadMore} disabled={isLoadingMore}>
                {isLoadingMore ? "불러오는 중..." : "더보기"}
              </button>
            </div>
          )}
        </>
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

      {cancelTarget && (
        <ConfirmDialog
          title="주문 취소"
          message="이 주문을 취소할까요? 결제한 금액은 환불돼요."
          confirmLabel="취소하기"
          danger
          onConfirm={handleConfirmCancel}
          onCancel={() => setCancelTarget(null)}
        />
      )}
    </div>
  );
}
