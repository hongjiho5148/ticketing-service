package com.ticketing.orderservice.notification;

import java.util.List;
import java.util.Map;

/**
 * PickSeat의 모든 메일이 함께 쓰는 하나의 HTML 레이아웃이다(서비스마다 MailService처럼 자기 복사본을 둔다).
 * 메일 클라이언트는 2000년대 초반에 머물러 있어서 그에 맞게 만들었다. 표 레이아웃, 인라인 스타일,
 * 외부 이미지와 폰트 없음(많은 클라이언트가 막는다), 그리고 링크를 막거나 끄는 클라이언트를 위해 버튼
 * 아래에 링크를 그대로 보여 준다. 사용자나 DB에서 온 값(이름, 공연 제목)은 모두 HTML 이스케이프해서 넣는다.
 */
final class MailTemplate {

    private static final String FONT =
            "-apple-system,BlinkMacSystemFont,'Apple SD Gothic Neo','Malgun Gothic','Noto Sans KR',Arial,sans-serif";

    private MailTemplate() {
    }

    /**
     * @param title 카드 안의 제목
     * @param paragraphs 본문 단락, 하나에 한 단락(일반 텍스트이며 여기서 이스케이프한다)
     * @param details 색이 들어간 상자에 보여 줄 라벨/값 행(공연, 날짜 등). 비어 있어도 된다
     * @param buttonLabel 행동 유도 문구. 버튼이 없으면 null
     * @param buttonUrl 버튼이 가는 주소(buttonLabel이 있으면 필수)
     * @param note 카드 내용 아래의 작은 글씨. 없으면 null
     */
    static String render(
            String title,
            List<String> paragraphs,
            Map<String, String> details,
            String buttonLabel,
            String buttonUrl,
            String note) {
        StringBuilder body = new StringBuilder();
        for (String paragraph : paragraphs) {
            body.append("<p style=\"margin:0 0 14px;font-size:15px;line-height:1.65;color:#372f4d;\">")
                    .append(escape(paragraph))
                    .append("</p>");
        }

        if (details != null && !details.isEmpty()) {
            body.append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" ")
                    .append("style=\"margin:6px 0 18px;background:#f4eefa;border-radius:12px;\"><tr><td style=\"padding:14px 18px;\">");
            for (Map.Entry<String, String> row : details.entrySet()) {
                body.append("<div style=\"font-size:13px;line-height:1.9;color:#8d84a3;\">")
                        .append(escape(row.getKey()))
                        .append(" <span style=\"color:#372f4d;font-weight:600;margin-left:6px;\">")
                        .append(escape(row.getValue()))
                        .append("</span></div>");
            }
            body.append("</td></tr></table>");
        }

        if (buttonLabel != null) {
            body.append("<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" style=\"margin:8px 0 4px;\"><tr>")
                    .append("<td style=\"background:#9683c9;border-radius:10px;\">")
                    .append("<a href=\"").append(escape(buttonUrl)).append("\" ")
                    .append("style=\"display:inline-block;padding:13px 28px;font-size:15px;font-weight:700;color:#ffffff;")
                    .append("text-decoration:none;border-radius:10px;\">")
                    .append(escape(buttonLabel))
                    .append("</a></td></tr></table>")
                    .append("<p style=\"margin:14px 0 0;font-size:12px;line-height:1.6;color:#8d84a3;word-break:break-all;\">")
                    .append("버튼이 눌리지 않으면 아래 주소를 브라우저에 붙여넣어 주세요.<br>")
                    .append("<a href=\"").append(escape(buttonUrl)).append("\" style=\"color:#7a66b0;\">")
                    .append(escape(buttonUrl)).append("</a></p>");
        }

        if (note != null) {
            body.append("<p style=\"margin:22px 0 0;padding-top:16px;border-top:1px solid #efe7f8;")
                    .append("font-size:12px;line-height:1.6;color:#8d84a3;\">")
                    .append(escape(note))
                    .append("</p>");
        }

        return "<!doctype html><html lang=\"ko\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>" + escape(title) + "</title></head>"
                + "<body style=\"margin:0;padding:0;background:#faf8fd;\">"
                + "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" bgcolor=\"#faf8fd\" "
                + "style=\"background:#faf8fd;\"><tr><td align=\"center\" style=\"padding:32px 16px;font-family:" + FONT + ";\">"
                + "<table role=\"presentation\" width=\"480\" cellpadding=\"0\" cellspacing=\"0\" "
                + "style=\"width:100%;max-width:480px;\">"
                + "<tr><td style=\"padding:0 0 14px 4px;font-size:20px;font-weight:800;color:#7a66b0;\">픽시트</td></tr>"
                + "<tr><td style=\"background:#ffffff;border-radius:18px;padding:32px 28px;\">"
                + "<h1 style=\"margin:0 0 18px;font-size:21px;line-height:1.4;color:#372f4d;\">" + escape(title) + "</h1>"
                + body
                + "</td></tr>"
                + "<tr><td style=\"padding:18px 4px 0;font-size:12px;line-height:1.6;color:#b6adc8;\">"
                + "이 메일은 발신 전용입니다. 알림 메일은 마이페이지에서 끄실 수 있어요.</td></tr>"
                + "</table></td></tr></table></body></html>";
    }

    static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
