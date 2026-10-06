import { useEffect, useState, type FormEvent } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import { createEvent, createSeats, updateEvent } from "../api/admin";
import { fetchEventDetail } from "../api/events";
import { useToast } from "../context/ToastContext";
import { useDocumentTitle } from "../hooks/useDocumentTitle";
import { extractErrorMessage } from "../utils/error";
import type { EventStatus, EventUpsertPayload, SeatBlockPayload } from "../types";

const STATUS_OPTIONS: { value: EventStatus; label: string }[] = [
  { value: "UPCOMING", label: "오픈예정" },
  { value: "OPEN", label: "예매중" },
  { value: "CLOSED", label: "예매종료" },
];

const EMPTY_BLOCK: SeatBlockPayload = { section: "", grade: "VIP", price: 100000, rows: 5, seatsPerRow: 10 };

// <input type="datetime-local"> wants "YYYY-MM-DDTHH:mm" - the API returns seconds too.
function toInputValue(iso: string) {
  return iso.slice(0, 16);
}

export function EventFormPage() {
  const { eventId } = useParams<{ eventId: string }>();
  const isEdit = Boolean(eventId);
  useDocumentTitle(isEdit ? "공연 수정" : "공연 등록");

  const navigate = useNavigate();
  const { showToast } = useToast();

  const [form, setForm] = useState<EventUpsertPayload>({
    title: "",
    venue: "",
    description: "",
    startAt: "",
    openAt: "",
    status: "UPCOMING",
  });
  const [error, setError] = useState<string | null>(null);
  const [isSaving, setIsSaving] = useState(false);

  const [blocks, setBlocks] = useState<SeatBlockPayload[]>([{ ...EMPTY_BLOCK }]);
  const [seatError, setSeatError] = useState<string | null>(null);
  const [isSavingSeats, setIsSavingSeats] = useState(false);

  useEffect(() => {
    if (!eventId) return;
    fetchEventDetail(Number(eventId))
      .then((event) =>
        setForm({
          title: event.title,
          venue: event.venue,
          description: event.description ?? "",
          startAt: toInputValue(event.startAt),
          openAt: toInputValue(event.openAt),
          status: event.status,
        }),
      )
      .catch((err) => setError(extractErrorMessage(err)));
  }, [eventId]);

  function setField<K extends keyof EventUpsertPayload>(key: K, value: EventUpsertPayload[K]) {
    setForm((prev) => ({ ...prev, [key]: value }));
  }

  async function handleSubmit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    setIsSaving(true);
    try {
      if (eventId) {
        await updateEvent(Number(eventId), form);
        showToast("공연 정보가 수정됐어요.");
      } else {
        const created = await createEvent(form);
        showToast("공연이 등록됐어요. 이어서 좌석을 만들어주세요.");
        navigate(`/events/${created.id}/edit`);
      }
    } catch (err) {
      setError(extractErrorMessage(err));
    } finally {
      setIsSaving(false);
    }
  }

  function setBlock(index: number, patch: Partial<SeatBlockPayload>) {
    setBlocks((prev) => prev.map((b, i) => (i === index ? { ...b, ...patch } : b)));
  }

  async function handleCreateSeats(e: FormEvent) {
    e.preventDefault();
    if (!eventId) return;
    setSeatError(null);
    setIsSavingSeats(true);
    try {
      const result = await createSeats(Number(eventId), blocks);
      showToast(`좌석 ${result.createdCount.toLocaleString()}석이 생성됐어요.`);
      setBlocks([{ ...EMPTY_BLOCK }]);
    } catch (err) {
      setSeatError(extractErrorMessage(err));
    } finally {
      setIsSavingSeats(false);
    }
  }

  const totalNewSeats = blocks.reduce((sum, b) => sum + b.rows * b.seatsPerRow, 0);

  return (
    <div className="form-page admin-form-page">
      <Link to="/" className="detail-back">
        ← 대시보드
      </Link>
      <h1>{isEdit ? "공연 수정" : "공연 등록"}</h1>

      <form onSubmit={handleSubmit} className="form">
        <label>
          공연명
          <input value={form.title} onChange={(e) => setField("title", e.target.value)} maxLength={200} required />
        </label>
        <label>
          장소
          <input value={form.venue} onChange={(e) => setField("venue", e.target.value)} maxLength={200} required />
        </label>
        <label>
          소개
          <textarea
            value={form.description}
            onChange={(e) => setField("description", e.target.value)}
            rows={4}
            maxLength={5000}
          />
        </label>
        <label>
          공연 시작
          <input
            type="datetime-local"
            value={form.startAt}
            onChange={(e) => setField("startAt", e.target.value)}
            required
          />
        </label>
        <label>
          예매 오픈
          <input
            type="datetime-local"
            value={form.openAt}
            onChange={(e) => setField("openAt", e.target.value)}
            required
          />
        </label>
        <label>
          상태
          <select value={form.status} onChange={(e) => setField("status", e.target.value as EventStatus)}>
            {STATUS_OPTIONS.map((o) => (
              <option key={o.value} value={o.value}>
                {o.label}
              </option>
            ))}
          </select>
        </label>
        {error && <p className="form-error">{error}</p>}
        <button type="submit" disabled={isSaving}>
          {isSaving ? "저장 중..." : isEdit ? "수정 저장" : "공연 등록"}
        </button>
      </form>

      {isEdit && (
        <section className="account-section">
          <h2>좌석 만들기</h2>
          <p className="form-notice">
            구역마다 행 × 열 개수만큼 같은 등급·가격의 좌석이 만들어져요. 이미 있는 구역 이름은 다시 쓸 수 없어요.
          </p>
          <form onSubmit={handleCreateSeats} className="form">
            {blocks.map((block, index) => (
              <div key={index} className="seat-block-row">
                <input
                  placeholder="구역명 (예: 플로어 A블럭)"
                  value={block.section}
                  onChange={(e) => setBlock(index, { section: e.target.value })}
                  maxLength={50}
                  required
                />
                <label>
                  등급
                  <input
                    value={block.grade}
                    onChange={(e) => setBlock(index, { grade: e.target.value })}
                    maxLength={20}
                    required
                  />
                </label>
                <label>
                  가격(원)
                  <input
                    type="number"
                    min={0}
                    value={block.price}
                    onChange={(e) => setBlock(index, { price: Number(e.target.value) })}
                    required
                  />
                </label>
                <label>
                  행 수
                  <input
                    type="number"
                    min={1}
                    max={100}
                    value={block.rows}
                    onChange={(e) => setBlock(index, { rows: Number(e.target.value) })}
                    required
                  />
                </label>
                <label>
                  열당 좌석 수
                  <input
                    type="number"
                    min={1}
                    max={100}
                    value={block.seatsPerRow}
                    onChange={(e) => setBlock(index, { seatsPerRow: Number(e.target.value) })}
                    required
                  />
                </label>
                {blocks.length > 1 && (
                  <button
                    type="button"
                    className="btn-secondary seat-block-remove"
                    onClick={() => setBlocks((prev) => prev.filter((_, i) => i !== index))}
                  >
                    이 구역 삭제
                  </button>
                )}
              </div>
            ))}
            <button type="button" className="btn-secondary" onClick={() => setBlocks((prev) => [...prev, { ...EMPTY_BLOCK }])}>
              + 구역 추가
            </button>
            {seatError && <p className="form-error">{seatError}</p>}
            <button type="submit" disabled={isSavingSeats}>
              {isSavingSeats ? "생성 중..." : `좌석 ${totalNewSeats.toLocaleString()}석 생성`}
            </button>
          </form>
        </section>
      )}
    </div>
  );
}
