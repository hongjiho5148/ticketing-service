import { Link, NavLink, useNavigate } from "react-router-dom";
import { useAuth } from "../context/AuthContext";

const LINKS = [
  { to: "/", label: "대시보드", end: true },
  { to: "/orders", label: "주문 내역", end: false },
  { to: "/events/new", label: "공연 등록", end: false },
  { to: "/import", label: "공연 가져오기", end: false },
  { to: "/coupons", label: "쿠폰 관리", end: false },
  { to: "/scan", label: "입장 스캔", end: false },
];

export function Header() {
  const { user, logout } = useAuth();
  const navigate = useNavigate();

  function handleLogout() {
    logout();
    navigate("/login");
  }

  return (
    <header className="header">
      <div className="header-inner">
        <Link to="/" className="header-logo">
          픽시트
          <span className="header-badge">ADMIN</span>
        </Link>
        {user && (
          <>
            <nav className="header-nav" aria-label="관리 메뉴">
              {LINKS.map((link) => (
                <NavLink
                  key={link.to}
                  to={link.to}
                  end={link.end}
                  className={({ isActive }) => (isActive ? "active" : undefined)}
                >
                  {link.label}
                </NavLink>
              ))}
            </nav>
            <div className="header-user">
              <span>{user.name}님</span>
              <button type="button" onClick={handleLogout}>
                로그아웃
              </button>
            </div>
          </>
        )}
      </div>
    </header>
  );
}
