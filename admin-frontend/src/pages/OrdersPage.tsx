import { Link, useSearchParams } from "react-router-dom";
import { OrdersTable } from "../components/OrdersTable";
import { useDocumentTitle } from "../hooks/useDocumentTitle";
import type { AdminOrderStatus } from "../types";

const FILTERS: { key: AdminOrderStatus | "ALL"; label: string }[] = [
  { key: "PAID", label: "결제완료" },
  { key: "CANCELLED", label: "취소" },
  { key: "PARTIALLY_REFUNDED", label: "부분환불" },
  { key: "PENDING", label: "결제대기" },
  { key: "FAILED", label: "결제실패" },
  { key: "ALL", label: "전체" },
];

export function OrdersPage() {
  useDocumentTitle("주문 내역");
  const [searchParams, setSearchParams] = useSearchParams();
  const filter = FILTERS.find((f) => f.key === searchParams.get("status"))?.key ?? "PAID";

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
            onClick={() => setSearchParams({ status: f.key }, { replace: true })}
          >
            {f.label}
          </button>
        ))}
      </div>

      <OrdersTable key={filter} filter={filter} />
    </div>
  );
}
