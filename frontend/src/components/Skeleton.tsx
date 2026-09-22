export function Skeleton({ className = "" }: { className?: string }) {
  return <div className={`skeleton ${className}`} />;
}

export function EventListSkeleton() {
  return (
    <div className="event-list">
      {Array.from({ length: 6 }).map((_, i) => (
        <div key={i} className="event-card event-card-skeleton">
          <Skeleton className="skeleton-poster" />
          <div className="event-card-body">
            <Skeleton className="skeleton-line skeleton-line-lg" />
            <Skeleton className="skeleton-line" />
            <Skeleton className="skeleton-line skeleton-line-sm" />
          </div>
        </div>
      ))}
    </div>
  );
}

export function TextRowsSkeleton({ rows = 4 }: { rows?: number }) {
  return (
    <div className="skeleton-rows">
      {Array.from({ length: rows }).map((_, i) => (
        <Skeleton key={i} className="skeleton-line" />
      ))}
    </div>
  );
}
