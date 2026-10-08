import { Link } from "react-router-dom";
import { LegalLayout } from "../components/LegalLayout";
import { useDocumentTitle } from "../hooks/useDocumentTitle";

export function PrivacyPage() {
  useDocumentTitle("개인정보처리방침");

  return (
    <LegalLayout eyebrow="Privacy" title="개인정보처리방침" updatedAt="2026.10.08">
      <section>
        <p>
          픽시트는 이용자의 개인정보를 소중하게 다루며, 서비스 제공에 꼭 필요한 정보만 수집합니다. 이 서비스는 학습·포트폴리오용
          데모이므로 실제 결제 정보나 주민등록번호 같은 민감한 정보는 수집하지 않습니다.
        </p>
      </section>

      <section>
        <h2>1. 수집하는 정보와 이용 목적</h2>
        <table className="legal-table">
          <thead>
            <tr>
              <th>구분</th>
              <th>항목</th>
              <th>목적</th>
            </tr>
          </thead>
          <tbody>
            <tr>
              <td>회원가입</td>
              <td>이메일, 이름, 비밀번호(단방향 암호화), 소셜 로그인 식별자</td>
              <td>회원 식별, 로그인, 인증 메일 발송</td>
            </tr>
            <tr>
              <td>예매·결제</td>
              <td>주문 내역, 결제 금액·수단 구분, 쿠폰·포인트 사용 내역, 구매자 확인 동의 기록</td>
              <td>예매 처리, 환불, 고객 응대</td>
            </tr>
            <tr>
              <td>서비스 이용</td>
              <td>찜한 공연, 후기, 알림 수신 설정, 티켓 양도 내역</td>
              <td>맞춤 기능 제공, 알림 발송</td>
            </tr>
          </tbody>
        </table>
        <p>
          카드번호 등 결제 수단 정보는 결제대행사(PortOne)가 처리하며 픽시트는 저장하지 않습니다. 비밀번호는 복원할 수 없는 방식(bcrypt)으로
          암호화해서 저장합니다.
        </p>
      </section>

      <section>
        <h2>2. 보유 및 이용 기간</h2>
        <p>
          회원 정보는 서비스를 운영하는 동안 보관하며, 삭제를 원하시면 <Link to="/contact">문의하기</Link>로 요청해 주세요. 요청을
          받으면 지체 없이 삭제합니다. 다만 환불·분쟁 대응을 위해 필요한 주문 기록은 관계 법령이 정한 기간 동안 보관할 수 있습니다.
        </p>
      </section>

      <section>
        <h2>3. 외부 서비스 이용 (처리 위탁·제3자 제공)</h2>
        <table className="legal-table">
          <thead>
            <tr>
              <th>제공받는 곳</th>
              <th>이용 목적</th>
            </tr>
          </thead>
          <tbody>
            <tr>
              <td>PortOne</td>
              <td>결제·환불 처리 (테스트 환경)</td>
            </tr>
            <tr>
              <td>Google</td>
              <td>소셜 로그인, reCAPTCHA(로봇 여부 확인), 이메일 발송(Gmail SMTP)</td>
            </tr>
            <tr>
              <td>Kakao</td>
              <td>소셜 로그인</td>
            </tr>
          </tbody>
        </table>
        <p>
          reCAPTCHA는 로그인과 대기열 입장 시 로봇 여부를 확인하기 위해 브라우저 정보를 Google에 전송합니다. 그 밖에 이용자의 정보를
          광고·마케팅 목적으로 제공하지 않습니다.
        </p>
      </section>

      <section>
        <h2>4. 브라우저 저장소</h2>
        <p>
          로그인 상태를 유지하기 위해 인증 토큰을 브라우저 저장소(localStorage)에 보관하며, 로그아웃하면 삭제됩니다. 서비스 자체의
          광고·추적용 쿠키는 사용하지 않습니다.
        </p>
      </section>

      <section>
        <h2>5. 이용자의 권리</h2>
        <p>
          이용자는 언제든지 자신의 정보를 조회·수정할 수 있고(마이페이지), 이메일 알림 수신 여부를 직접 바꿀 수 있습니다. 정보 삭제나
          그 밖의 요청은 <Link to="/contact">문의하기</Link>로 알려 주세요.
        </p>
      </section>

      <section>
        <h2>6. 공공데이터 이용</h2>
        <p>
          일부 공연 정보는 (재)예술경영지원센터 공연예술통합전산망(KOPIS, www.kopis.or.kr)의 공개 데이터에 의거합니다. 이 데이터에는
          이용자의 개인정보가 포함되지 않습니다.
        </p>
      </section>

      <section>
        <h2>7. 방침의 변경</h2>
        <p>방침이 바뀌면 시행일과 함께 이 페이지에 공지합니다.</p>
      </section>
    </LegalLayout>
  );
}
