package com.ticketing.eventservice.kopis;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/** KOPIS의 XML을 {@link KopisPerformance}로 바꾼다. 순수 함수라서 네트워크 없이 테스트할 수 있다. */
final class KopisXmlParser {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy.MM.dd");

    private KopisXmlParser() {
    }

    static List<KopisPerformance> parse(String xml) {
        Document document = read(xml);
        List<KopisPerformance> result = new ArrayList<>();
        NodeList entries = document.getElementsByTagName("db");
        for (int i = 0; i < entries.getLength(); i++) {
            Element db = (Element) entries.item(i);
            String returnCode = text(db, "returncode");
            if (returnCode != null) {
                // KOPIS는 API 수준의 오류(잘못된 키, 할당량 초과)를 정상 200 응답 안에 담아 보고한다.
                throw new KopisException("KOPIS 오류 응답: " + returnCode + " " + text(db, "errmsg"));
            }
            String id = text(db, "mt20id");
            String title = text(db, "prfnm");
            if (id == null || title == null) {
                continue;
            }
            result.add(new KopisPerformance(
                    id,
                    title,
                    date(text(db, "prfpdfrom")),
                    date(text(db, "prfpdto")),
                    text(db, "fcltynm"),
                    text(db, "genrenm"),
                    text(db, "prfcast"),
                    text(db, "prfruntime"),
                    text(db, "prfage"),
                    text(db, "pcseguidance"),
                    text(db, "dtguidance"),
                    text(db, "sty")));
        }
        return result;
    }

    private static Document read(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // 응답은 네트워크에서 오므로 DTD와 외부 엔티티는 아예 거절한다(XXE).
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            return factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new KopisException("KOPIS 응답을 해석하지 못했어요.", e);
        }
    }

    private static String text(Element parent, String tag) {
        NodeList nodes = parent.getElementsByTagName(tag);
        if (nodes.getLength() == 0) {
            return null;
        }
        String value = nodes.item(0).getTextContent();
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static LocalDate date(String value) {
        if (value == null) {
            return null;
        }
        try {
            return LocalDate.parse(value, DATE);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
