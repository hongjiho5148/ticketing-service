import type { ReactNode } from "react";
import { Link } from "react-router-dom";

interface LegalLayoutProps {
  eyebrow: string;
  title: string;
  updatedAt?: string;
  children: ReactNode;
}

// Shared frame for the terms / privacy / contact pages: a readable column with a quiet sub-nav.
export function LegalLayout({ eyebrow, title, updatedAt, children }: LegalLayoutProps) {
  return (
    <div className="legal">
      <div className="page-head">
        <div>
          <p className="eyebrow">{eyebrow}</p>
          <h1>{title}</h1>
          {updatedAt && <p>시행일 {updatedAt}</p>}
        </div>
      </div>

      <nav className="legal-nav" aria-label="안내 문서">
        <Link to="/terms">이용약관</Link>
        <Link to="/privacy">개인정보처리방침</Link>
        <Link to="/contact">문의하기</Link>
      </nav>

      <article className="legal-body">{children}</article>
    </div>
  );
}
