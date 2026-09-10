import type { Seat } from "../types";

interface SeatGridProps {
  section: string;
  seats: Seat[];
  selectedSeatId: number | null;
  onSelectSeat: (seat: Seat) => void;
  onClose: () => void;
}

export function SeatGrid({ section, seats, selectedSeatId, onSelectSeat, onClose }: SeatGridProps) {
  const rows = [...new Set(seats.map((s) => s.rowNo))].sort((a, b) => a - b);
  const grade = seats[0]?.grade ?? "";

  return (
    <div className="seat-grid-panel">
      <div className="seat-grid-header">
        <h3>{section}</h3>
        <button type="button" className="btn-secondary" onClick={onClose}>
          지도로 돌아가기
        </button>
      </div>
      <div className="seat-grid-scroll">
        {rows.map((rowNo) => {
          const rowSeats = seats.filter((s) => s.rowNo === rowNo).sort((a, b) => a.seatNumber - b.seatNumber);
          return (
            <div key={rowNo} className="seat-grid-row">
              <span className="seat-grid-row-label">{rowNo}열</span>
              {rowSeats.map((seat) => {
                const isSelected = seat.id === selectedSeatId;
                const isAvailable = seat.status === "AVAILABLE";
                const className = [
                  "seat-dot",
                  isSelected ? "seat-dot-selected" : isAvailable ? `seat-dot-available grade-${seat.grade}` : "seat-dot-unavailable",
                ].join(" ");
                return (
                  <button
                    key={seat.id}
                    type="button"
                    className={className}
                    disabled={!isAvailable && !isSelected}
                    title={`${seat.section} ${seat.seatNo}`}
                    onClick={() => onSelectSeat(seat)}
                  >
                    {seat.seatNumber}
                  </button>
                );
              })}
            </div>
          );
        })}
      </div>
      <div className="seat-legend">
        <span>
          <span className={`seat-legend-swatch grade-swatch-${grade}`} />
          선택 가능
        </span>
        <span>
          <span className="seat-legend-swatch" style={{ background: "var(--primary)" }} />
          선택됨
        </span>
        <span>
          <span className="seat-legend-swatch" style={{ background: "var(--sold-bg)" }} />
          선택 불가
        </span>
      </div>
    </div>
  );
}
