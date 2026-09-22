import { Link } from "react-router-dom";
import { useDocumentTitle } from "../hooks/useDocumentTitle";

export function NotFoundPage() {
  useDocumentTitle("페이지를 찾을 수 없음");

  return (
    <div className="not-found">
      <p className="not-found-code">404</p>
      <h1>페이지를 찾을 수 없어요</h1>
      <p>주소를 다시 확인하거나, 목록으로 돌아가주세요.</p>
      <Link to="/">이벤트 목록으로</Link>
    </div>
  );
}
