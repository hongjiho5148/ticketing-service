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
        선착순 티켓팅
      </Link>
      <nav className="header-nav">
        {user ? (
          <>
            <Link to="/orders">내 주문</Link>
            <span className="header-user">{user.name}님</span>
            <button type="button" onClick={handleLogout}>
              로그아웃
            </button>
          </>
        ) : (
          <>
            <Link to="/login">로그인</Link>
            <Link to="/signup">회원가입</Link>
          </>
        )}
      </nav>
    </header>
  );
}
