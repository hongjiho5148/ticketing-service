// 로그인/가입 화면의 포스터 쪽 절반이다. 순전히 장식이다. 여기 적힌 말은 모두 서비스에 대해 사실이다.
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
