import { Link } from "react-router-dom";

export function Footer() {
  return (
    <footer className="footer">
      <div className="footer-inner">
        <div className="footer-brand">
          <strong>픽시트</strong>
          <span>먼저 잡는 사람이 앉는 자리, 선착순 티켓팅</span>
          <p className="footer-copyright">© {new Date().getFullYear()} PickSeat</p>
        </div>
        <nav className="footer-links" aria-label="안내">
          <Link to="/">공연</Link>
          <Link to="/calendar">캘린더</Link>
          <a href="#">이용약관</a>
          <a href="#">개인정보처리방침</a>
          <a href="#">문의하기</a>
        </nav>
      </div>
    </footer>
  );
}
