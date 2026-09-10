import type { SeatSectionSummary } from "../types";

const GRADE_ORDER = ["VIP", "R", "S"];
const GRADE_TIER_LABEL: Record<string, string> = {
  VIP: "플로어 (VIP석)",
  R: "1층 (R석)",
  S: "2층 (S석)",
};

interface VenueSeatMapProps {
  sections: SeatSectionSummary[];
  selectedSection: string | null;
  onSelectSection: (section: string) => void;
}

export function VenueSeatMap({ sections, selectedSection, onSelectSection }: VenueSeatMapProps) {
  const byGrade = GRADE_ORDER.map((grade) => ({
    grade,
    blocks: sections.filter((s) => s.grade === grade),
  })).filter((tier) => tier.blocks.length > 0);

  return (
    <div className="venue-map">
      <div className="venue-stage">S T A G E</div>
      {byGrade.map((tier) => (
        <div key={tier.grade}>
          <div className="venue-tier-label">{GRADE_TIER_LABEL[tier.grade] ?? tier.grade}</div>
          <div className="venue-tier">
            {tier.blocks.map((block) => {
              const soldOut = block.availableCount === 0;
              return (
                <button
                  key={block.section}
                  type="button"
                  className={[
                    "venue-block",
                    `venue-block-${block.grade.toLowerCase()}`,
                    block.section === selectedSection ? "selected" : "",
                    soldOut ? "sold-out" : "",
                  ]
                    .filter(Boolean)
                    .join(" ")}
                  disabled={soldOut}
                  onClick={() => onSelectSection(block.section)}
                >
                  <div className="venue-block-name">{block.section}</div>
                  <div className="venue-block-count">
                    {soldOut ? "매진" : `잔여 ${block.availableCount}/${block.totalCount}`}
                  </div>
                </button>
              );
            })}
          </div>
        </div>
      ))}
    </div>
  );
}
