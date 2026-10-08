import { useEffect, useRef, useState } from "react";
import { Link, NavLink, useLocation, useNavigate } from "react-router-dom";
import { useAuth } from "../context/AuthContext";
import { ConfirmDialog } from "./ConfirmDialog";

const MENU_LINKS = [
  { to: "/transfers", label: "양도함" },
  { to: "/wishlist", label: "찜한 공연" },
  { to: "/account", label: "마이페이지" },
];

export function Header() {
  const { user, logout } = useAuth();
  const navigate = useNavigate();
  const { pathname } = useLocation();
  // The menu remembers which page it was opened on, so any navigation closes it without an effect.
  const [menuOpenedOn, setMenuOpenedOn] = useState<string | null>(null);
  const isMenuOpen = menuOpenedOn === pathname;
  const closeMenu = () => setMenuOpenedOn(null);
  const [isConfirmingLogout, setIsConfirmingLogout] = useState(false);
  const menuRef = useRef<HTMLDivElement>(null);

  // Besides navigation, the menu closes on Escape and on any click outside it.
  useEffect(() => {
    if (!isMenuOpen) return;
    function onPointerDown(e: PointerEvent) {
      if (menuRef.current && !menuRef.current.contains(e.target as Node)) setMenuOpenedOn(null);
    }
    function onKeyDown(e: KeyboardEvent) {
      if (e.key === "Escape") setMenuOpenedOn(null);
    }
    document.addEventListener("pointerdown", onPointerDown);
    document.addEventListener("keydown", onKeyDown);
    return () => {
      document.removeEventListener("pointerdown", onPointerDown);
      document.removeEventListener("keydown", onKeyDown);
    };
  }, [isMenuOpen]);

  function handleLogout() {
    logout();
    navigate("/login");
  }

  // "공연" stays lit on event detail pages too, so the user always knows which section they're in.
  const onEvents = pathname === "/" || pathname.startsWith("/events");

  return (
    <header className="header">
      <div className="header-inner">
        <Link to="/" className="header-logo">
          <span className="logo-mark" aria-hidden="true" />
          픽시트
        </Link>

        <nav className="header-nav" aria-label="주요 메뉴">
          <Link to="/" className={onEvents ? "active" : undefined} aria-current={onEvents ? "page" : undefined}>
            공연
          </Link>
          <NavLink to="/calendar" className={({ isActive }) => (isActive ? "active" : undefined)}>
            캘린더
          </NavLink>
          {user && (
            <NavLink to="/tickets" className={({ isActive }) => (isActive ? "active" : undefined)}>
              내 티켓
            </NavLink>
          )}
        </nav>

        <div className="header-actions">
          {user ? (
            <div className="user-menu" ref={menuRef}>
              <button
                type="button"
                className="user-menu-trigger"
                aria-haspopup="menu"
                aria-expanded={isMenuOpen}
                onClick={() => setMenuOpenedOn(isMenuOpen ? null : pathname)}
              >
                <span className="user-avatar" aria-hidden="true">
                  {user.name.slice(0, 1)}
                </span>
                <span className="user-menu-name">{user.name}님</span>
                <span className="user-menu-chevron" aria-hidden="true" />
              </button>
              {isMenuOpen && (
                <div className="user-menu-panel" role="menu">
                  {user.email && <p className="user-menu-email">{user.email}</p>}
                  {MENU_LINKS.map((link) => (
                    <Link key={link.to} to={link.to} role="menuitem">
                      {link.label}
                    </Link>
                  ))}
                  <button
                    type="button"
                    role="menuitem"
                    className="menu-danger"
                    onClick={() => {
                      closeMenu();
                      setIsConfirmingLogout(true);
                    }}
                  >
                    로그아웃
                  </button>
                </div>
              )}
            </div>
          ) : (
            <>
              <Link to="/login" className="header-link">
                로그인
              </Link>
              <Link to="/signup" className="header-cta">
                회원가입
              </Link>
            </>
          )}
        </div>
      </div>

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
