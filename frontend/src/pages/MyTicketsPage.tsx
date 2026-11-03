import { Link, useSearchParams } from "react-router-dom";
import { OrdersPanel } from "../components/OrdersPanel";
import { TicketsPanel } from "../components/TicketsPanel";
import { useDocumentTitle } from "../hooks/useDocumentTitle";

const TABS = [
  { key: "tickets", label: "입장권" },
  { key: "orders", label: "주문 내역" },
] as const;

// 구매에 대한 모든 것을 한곳에 둔다. 입장 티켓(QR)과 그 뒤의 주문이다.
export function MyTicketsPage() {
  const [searchParams] = useSearchParams();
  const tab = TABS.find((t) => t.key === searchParams.get("tab"))?.key ?? "tickets";
  useDocumentTitle(tab === "orders" ? "내 티켓 · 주문 내역" : "내 티켓");

  return (
    <div>
      <div className="page-head">
        <div>
          <p className="eyebrow">My tickets</p>
          <h1>내 티켓</h1>
        </div>
      </div>

      <nav className="page-tabs" aria-label="내 티켓 메뉴">
        {TABS.map((t) => (
          <Link
            key={t.key}
            to={`?tab=${t.key}`}
            replace
            className={t.key === tab ? "active" : undefined}
            aria-current={t.key === tab ? "page" : undefined}
          >
            {t.label}
          </Link>
        ))}
      </nav>

      {tab === "orders" ? <OrdersPanel /> : <TicketsPanel />}
    </div>
  );
}
