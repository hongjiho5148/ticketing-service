import { Link } from "react-router-dom";
import { LegalLayout } from "../components/LegalLayout";
import { useDocumentTitle } from "../hooks/useDocumentTitle";

// Set VITE_CONTACT_EMAIL in frontend/.env to show a real address; the default is a visible placeholder.
const CONTACT_EMAIL = import.meta.env.VITE_CONTACT_EMAIL || "pickseat@example.com";

const FAQ = [
  {
    q: "인증 메일이 오지 않아요.",
    a: "스팸함을 먼저 확인해 보세요. 그래도 없으면 로그인 화면에서 인증 메일을 다시 보낼 수 있어요.",
  },
  {
    q: "QR 입장권은 언제 나오나요?",
    a: "공연 시작 2시간 전부터 자동으로 발급돼요. 내 티켓의 입장권 탭에서 확인하세요.",
  },
  {
    q: "예매를 취소하면 얼마나 환불되나요?",
    a: "공연 7일 전까지 100%, 3일 전까지 70%, 1일 전까지 30%예요. 24시간 이내에는 취소할 수 없어요. 취소 전에 환불 예정 금액이 먼저 표시돼요.",
  },
  {
    q: "결제하다가 창을 닫았어요.",
    a: "좌석이 잡혀 있는 5분 동안은 내 티켓의 주문 내역에서 '결제하기'로 이어서 결제할 수 있어요.",
  },
  {
    q: "다른 사람에게 티켓을 넘기고 싶어요.",
    a: "주문 내역의 '양도'에서 받는 분의 가입 이메일로 요청을 보내세요. 티켓당 한 번, 공연 2시간 전까지 가능해요.",
  },
];

export function ContactPage() {
  useDocumentTitle("문의하기");

  return (
    <LegalLayout eyebrow="Contact" title="문의하기">
      <section>
        <h2>이메일로 문의</h2>
        <p>
          <a href={`mailto:${CONTACT_EMAIL}`} className="legal-mail">
            {CONTACT_EMAIL}
          </a>
        </p>
        <p className="legal-note">
          데모 서비스라 답변이 늦어질 수 있어요. 개인정보 삭제 요청은 가입한 이메일 주소로 보내 주세요. 자세한 내용은{" "}
          <Link to="/privacy">개인정보처리방침</Link>에 있어요.
        </p>
      </section>

      <section>
        <h2>자주 묻는 질문</h2>
        <dl className="legal-faq">
          {FAQ.map((item) => (
            <div key={item.q}>
              <dt>{item.q}</dt>
              <dd>{item.a}</dd>
            </div>
          ))}
        </dl>
      </section>
    </LegalLayout>
  );
}
