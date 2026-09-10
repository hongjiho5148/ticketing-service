import { useEffect, useState } from "react";
import { fetchEventDetail, fetchEventSeats } from "../api/events";
import { VenueSeatMap } from "./VenueSeatMap";
import { SeatGrid } from "./SeatGrid";
import type { EventDetail, Seat } from "../types";

interface SeatLocationModalProps {
  eventId: number;
  eventTitle: string;
  venue: string;
  section: string;
  rowNo: number;
  seatNumber: number;
  onClose: () => void;
}

export function SeatLocationModal({
  eventId,
  eventTitle,
  venue,
  section,
  rowNo,
  seatNumber,
  onClose,
}: SeatLocationModalProps) {
  const [event, setEvent] = useState<EventDetail | null>(null);
  const [seats, setSeats] = useState<Seat[]>([]);

  useEffect(() => {
    fetchEventDetail(eventId).then(setEvent).catch(() => undefined);
    fetchEventSeats(eventId).then(setSeats).catch(() => undefined);
  }, [eventId]);

  const sectionSeats = seats.filter((seat) => seat.section === section);
  const mySeat = sectionSeats.find((seat) => seat.rowNo === rowNo && seat.seatNumber === seatNumber);

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal-content" onClick={(e) => e.stopPropagation()}>
        <div className="modal-header">
          <div>
            <h3>내 좌석 위치</h3>
            <p className="modal-subtitle">
              {eventTitle} · {venue}
            </p>
          </div>
          <button type="button" className="btn-secondary" onClick={onClose}>
            닫기
          </button>
        </div>

        {event && (
          <VenueSeatMap sections={event.sectionSummary} selectedSection={section} onSelectSection={() => undefined} />
        )}

        {sectionSeats.length > 0 && (
          <SeatGrid
            section={section}
            seats={sectionSeats}
            selectedSeatId={mySeat?.id ?? null}
            onSelectSeat={() => undefined}
            onClose={onClose}
          />
        )}
      </div>
    </div>
  );
}
