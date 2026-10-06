import { useEffect } from "react";

export function useDocumentTitle(title: string) {
  useEffect(() => {
    const previous = document.title;
    document.title = `${title} | 픽시트 관리자`;
    return () => {
      document.title = previous;
    };
  }, [title]);
}
