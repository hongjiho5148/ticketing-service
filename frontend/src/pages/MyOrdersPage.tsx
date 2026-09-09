import { useEffect, useState } from "react";
import { fetchOrders } from "../api/orders";
import { extractErrorMessage } from "../utils/error";
import type { OrderHistoryItem } from "../types";

export function MyOrdersPage() {
  const [orders, setOrders] = useState<OrderHistoryItem[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [isLoading, setIsLoading] = useState(true);

  useEffect(() => {
    fetchOrders({ page: 0, size: 20 })
      .then(setOrders)
      .catch((err) => setError(extractErrorMessage(err)))
      .finally(() => setIsLoading(false));
  }, []);

  if (isLoading) {
    return <p className="page-status">주문 내역을 불러오는 중...</p>;
  }
  if (error) {
    return <p className="form-error">{error}</p>;
  }

  return (
    <div>
      <h1>내 주문 내역</h1>
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
            </tr>
          </thead>
          <tbody>
            {orders.map((order) => (
              <tr key={order.orderId}>
                <td>{order.eventTitle}</td>
                <td>{order.seatNo}</td>
                <td>{order.totalPrice.toLocaleString()}원</td>
                <td>{order.status}</td>
                <td>{new Date(order.createdAt).toLocaleString()}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}
