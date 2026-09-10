import { useEffect, useState } from "react";
import { cancelOrder, fetchOrders } from "../api/orders";
import { extractErrorMessage } from "../utils/error";
import { SeatLocationModal } from "../components/SeatLocationModal";
import type { OrderHistoryItem, OrderStatus } from "../types";

const STATUS_LABEL: Record<OrderStatus, string> = {
  PENDING: "결제대기",
  PAID: "결제완료",
  FAILED: "결제실패",
  CANCELLED: "취소완료",
};

export function MyOrdersPage() {
  const [orders, setOrders] = useState<OrderHistoryItem[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [cancellingId, setCancellingId] = useState<number | null>(null);
  const [cancelError, setCancelError] = useState<string | null>(null);
  const [viewingOrder, setViewingOrder] = useState<OrderHistoryItem | null>(null);

  useEffect(() => {
    fetchOrders({ page: 0, size: 20 })
      .then(setOrders)
      .catch((err) => setError(extractErrorMessage(err)))
      .finally(() => setIsLoading(false));
  }, []);

  async function handleCancel(orderId: number) {
    if (!window.confirm("이 주문을 취소할까요? 결제한 금액은 환불돼요.")) return;
    setCancelError(null);
    setCancellingId(orderId);
    try {
      await cancelOrder(orderId);
      setOrders((prev) => prev.map((o) => (o.orderId === orderId ? { ...o, status: "CANCELLED" } : o)));
    } catch (err) {
      setCancelError(extractErrorMessage(err));
    } finally {
      setCancellingId(null);
    }
  }

  if (isLoading) {
    return <p className="page-status">주문 내역을 불러오는 중...</p>;
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
      {cancelError && <p className="form-error">{cancelError}</p>}
      {orders.length === 0 ? (
        <p className="page-status">주문 내역이 없습니다.</p>
      ) : (
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
                      onClick={() => handleCancel(order.orderId)}
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
    </div>
  );
}
