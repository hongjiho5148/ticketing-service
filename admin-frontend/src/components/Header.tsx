import { Link, useNavigate } from "react-router-dom";
import { useAuth } from "../context/AuthContext";

export function Header() {
  const { user, logout } = useAuth();
  const navigate = useNavigate();

  function handleLogout() {
    logout();
    navigate("/login");
  }

  return (
    <header className="header">
      <Link to="/" className="header-logo">
        픽시트 관리자
      </Link>
      {user && (
        <nav className="header-nav">
          <Link to="/">대시보드</Link>
          <Link to="/events/new">공연 등록</Link>
          <Link to="/coupons">쿠폰 관리</Link>
          <Link to="/scan">입장 스캔</Link>
          <span className="header-user">{user.name}님</span>
          <button type="button" onClick={handleLogout}>
            로그아웃
          </button>
        </nav>
      )}
    </header>
  );
}
