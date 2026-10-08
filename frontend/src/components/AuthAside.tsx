// The poster half of the sign-in / sign-up screens. Purely decorative - everything it says is true of the product.
export function AuthAside() {
  return (
    <aside className="auth-aside poster-theme-1" aria-hidden="true">
      <p className="auth-aside-eyebrow">PICKSEAT</p>
      <p className="auth-aside-title">
        먼저 잡는
        <br />
        사람이
        <br />
        앉는 자리
      </p>
      <ul className="auth-aside-points">
        <li>
          <span className="num">01</span>
          대기열 순서대로 공정하게 입장
        </li>
        <li>
          <span className="num">02</span>
          5분 동안 좌석을 잡아두고 결제
        </li>
        <li>
          <span className="num">03</span>
          공연 2시간 전부터 입장 QR 발급
        </li>
      </ul>
    </aside>
  );
}
