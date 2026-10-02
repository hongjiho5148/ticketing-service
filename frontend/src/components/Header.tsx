import { useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { useAuth } from "../context/AuthContext";
import { ConfirmDialog } from "./ConfirmDialog";

export function Header() {
  const { user, logout } = useAuth();
  const navigate = useNavigate();
  const [isConfirmingLogout, setIsConfirmingLogout] = useState(false);

  function handleLogout() {
    logout();
    navigate("/login");
  }

  return (
    <header className="header">
      <Link to="/" className="header-logo">
        픽시트
      </Link>
      <nav className="header-nav">
        {user ? (
          <>
            <Link to="/orders">내 주문</Link>
            <Link to="/account">마이페이지</Link>
            {user.role === "ADMIN" && <Link to="/admin/scan">입장 스캔</Link>}
            <span className="header-user">{user.name}님</span>
            <button type="button" onClick={() => setIsConfirmingLogout(true)}>
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
      {isConfirmingLogout && (
        <ConfirmDialog
          title="로그아웃"
          message="로그아웃 하시겠어요?"
          confirmLabel="로그아웃"
          onConfirm={() => {
            setIsConfirmingLogout(false);
            handleLogout();
          }}
          onCancel={() => setIsConfirmingLogout(false)}
        />
      )}
    </header>
  );
}
