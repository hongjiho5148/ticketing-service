import type { EventCategory } from "../types";

export const CATEGORY_LABEL: Record<EventCategory, string> = {
  CONCERT: "콘서트",
  MUSICAL: "뮤지컬",
  SPORTS: "스포츠",
  EXHIBITION: "전시",
  FESTIVAL: "페스티벌",
  ETC: "기타",
};

export const CATEGORY_ORDER: EventCategory[] = ["CONCERT", "MUSICAL", "SPORTS", "EXHIBITION", "FESTIVAL", "ETC"];
