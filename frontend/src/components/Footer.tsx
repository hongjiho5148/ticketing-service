export function Footer() {
  return (
    <footer className="footer">
      <div className="footer-links">
        <a href="#">이용약관</a>
        <a href="#">개인정보처리방침</a>
        <a href="#">문의하기</a>
      </div>
      <p className="footer-copyright">© {new Date().getFullYear()} 픽시트. All rights reserved.</p>
    </footer>
  );
}
