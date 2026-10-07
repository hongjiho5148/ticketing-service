import { isAxiosError } from "axios";
import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { cancelOrder, fetchOrders, fetchRefundPreview } from "../api/orders";
import { ConfirmDialog } from "../components/ConfirmDialog";
import { SeatLocationModal } from "../components/SeatLocationModal";
import { TransferDialog } from "../components/TransferDialog";
import { TextRowsSkeleton } from "../components/Skeleton";
import { useToast } from "../context/ToastContext";
import { useDocumentTitle } from "../hooks/useDocumentTitle";
import { extractErrorMessage } from "../utils/error";
import type { ApiErrorBody, OrderHistoryItem, OrderStatus, RefundPreview } from "../types";

const PAGE_SIZE = 20;

const STATUS_LABEL: Record<OrderStatus, string> = {
  PENDING: "결제대기",
  PAID: "결제완료",
  FAILED: "결제실패",
  CANCELLED: "취소완료",
  PARTIALLY_REFUNDED: "부분환불",
};

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
  const [cancelTarget, setCancelTarget] = useState<CancelTarget | null>(null);
  const [transferTarget, setTransferTarget] = useState<OrderHistoryItem | null>(null);

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
        prev.map((o) => (o.orderId === order.orderId ? { ...o, status, refundedAmount: preview.refundAmount } : o)),
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
    lines.push("취소하면 되돌릴 수 없어요.");
    return lines.join("\n");
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
        환불정책: 공연 7일 전까지 100%, 3일 전까지 70%, 1일 전까지 30% 환불돼요. 공연 24시간 전부터는 취소할 수 없어요.
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
                <th>결제수단</th>
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
                  <td>{order.paymentMethod ? (PAY_METHOD_LABEL[order.paymentMethod] ?? order.paymentMethod) : "-"}</td>
                  <td>
                    {STATUS_LABEL[order.status]}
                    {order.transferStatus && (
                      <small className="refund-note">
                        {" "}
                        ({order.transferStatus === "PENDING" ? "양도 수락 대기" : "양도 완료"})
                      </small>
                    )}
                    {order.status === "PARTIALLY_REFUNDED" && order.refundedAmount !== null && (
                      <small className="refund-note"> (환불 {order.refundedAmount.toLocaleString()}원)</small>
                    )}
                  </td>
                  <td>{new Date(order.createdAt).toLocaleString()}</td>
                  <td>
                    {order.status === "PAID" && !order.transferStatus && (
                      <button
                        type="button"
                        className="btn-secondary"
                        onClick={() => handleStartCancel(order)}
                        disabled={cancellingId === order.orderId}
                      >
                        {cancellingId === order.orderId ? "취소 중..." : "취소"}
                      </button>
                    )}
                    {order.transferable && (
                      <button type="button" className="btn-secondary" onClick={() => setTransferTarget(order)}>
                        양도
                      </button>
                    )}
                    {order.transferStatus === "PENDING" && (
                      <Link to="/transfers" className="seat-link">
                        양도함 보기
                      </Link>
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
