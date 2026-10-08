import { useState, type FormEvent } from "react";
import { importFromKopis } from "../api/admin";
import { useToast } from "../context/ToastContext";
import { useDocumentTitle } from "../hooks/useDocumentTitle";
import { extractErrorMessage } from "../utils/error";
import type { KopisGenre, KopisImportResult } from "../types";

const GENRES: { value: KopisGenre; label: string }[] = [
  { value: "PLAY", label: "연극" },
  { value: "MUSICAL", label: "뮤지컬" },
  { value: "CLASSIC", label: "클래식(서양음악)" },
  { value: "POPULAR_MUSIC", label: "대중음악(콘서트)" },
  { value: "KOREAN_MUSIC", label: "국악" },
  { value: "DANCE", label: "무용" },
];

export function ImportPage() {
  useDocumentTitle("공연 가져오기");
  const { showToast } = useToast();

  const [genre, setGenre] = useState<KopisGenre>("PLAY");
  const [limit, setLimit] = useState(10);
  const [isImporting, setIsImporting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [result, setResult] = useState<KopisImportResult | null>(null);

  async function handleSubmit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    setResult(null);
    setIsImporting(true);
    try {
      const imported = await importFromKopis({ genre, limit });
      setResult(imported);
      showToast(`${imported.created}개의 공연을 가져왔어요.`);
    } catch (err) {
      setError(extractErrorMessage(err));
    } finally {
      setIsImporting(false);
    }
  }

  return (
    <div>
      <div className="admin-header">
        <div>
          <p className="eyebrow">KOPIS</p>
          <h1>공연 가져오기</h1>
        </div>
      </div>

      <div className="form-page admin-form-page coupon-form-card">
        <p className="form-notice">
          공연예술통합전산망(KOPIS) 공개 데이터에서 앞으로 90일 안에 열리는 공연을 가져와요. 제목·장소·기간·출연진·줄거리·가격
          안내는 실제 데이터이고, 좌석 배치는 가격대에 맞춰 자동으로 만들어요(데모용). 이미 가져온 공연은 건너뛰어요. KOPIS 이용 조건에 맞춰 초당 5회 이하로 천천히 호출하고, 화면에는 출처를 표시해요.
        </p>
        <form onSubmit={handleSubmit} className="form">
          <label>
            장르
            <select value={genre} onChange={(e) => setGenre(e.target.value as KopisGenre)}>
              {GENRES.map((g) => (
                <option key={g.value} value={g.value}>
                  {g.label}
                </option>
              ))}
            </select>
          </label>
          <label>
            가져올 개수 (최대 30)
            <input
              type="number"
              min={1}
              max={30}
              value={limit}
              onChange={(e) => setLimit(Number(e.target.value))}
              required
            />
          </label>
          {error && <p className="form-error">{error}</p>}
          <button type="submit" disabled={isImporting}>
            {isImporting ? "가져오는 중... (공연마다 상세를 조회해서 조금 걸려요)" : "KOPIS에서 가져오기"}
          </button>
        </form>
      </div>

      {result && (
        <section className="form-page admin-form-page import-result">
          <h2>가져오기 결과</h2>
          <ul className="wallet-list">
            <li>
              <span>새로 만든 공연</span>
              <strong className="plus">{result.created}개</strong>
            </li>
            <li>
              <span>이미 가져와서 건너뜀</span>
              <strong>{result.alreadyImported}개</strong>
            </li>
            <li>
              <span>이미 끝났거나 일정이 없어 건너뜀</span>
              <strong>{result.notUpcoming}개</strong>
            </li>
            <li>
              <span>실패</span>
              <strong>{result.failed}개</strong>
            </li>
          </ul>
          {result.createdTitles.length > 0 && (
            <ul className="import-titles">
              {result.createdTitles.map((title) => (
                <li key={title}>{title}</li>
              ))}
            </ul>
          )}
        </section>
      )}
    </div>
  );
}
