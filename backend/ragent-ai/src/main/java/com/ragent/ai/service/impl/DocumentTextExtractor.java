package com.ragent.ai.service.impl;

import com.ragent.common.exception.BusinessException;
import com.ragent.common.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.sax.ToXMLContentHandler;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Component;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 文档文本提取（P1/P3 提取层改造）：
 * <ul>
 *   <li>.docx/.doc/.xlsx/.pptx/.rtf → Apache Tika 解析出 XHTML，再转成带结构的 Markdown 文本
 *       （Word 标题样式 → {@code #}，表格行 → 制表符拼接），复用切分器的标题栈管线；</li>
 *   <li>.pdf → 保留 {@link PagePdfDocumentReader}（保住逐行页码），叠加噪声清洗：丢弃纯数字页码、
 *       页眉页脚重复短行、纯 URL 行，压缩连续空行；</li>
 *   <li>其他 → UTF-8 直接读取。</li>
 * </ul>
 */
@Slf4j
@Component
public class DocumentTextExtractor {

    /** 纯数字行（页码） */
    private static final Pattern PURE_NUMBER = Pattern.compile("\\d+");
    /** 中文页码「第 X 页」/ 英文「Page X」/ 「- X -」 */
    private static final Pattern PAGE_MARKER = Pattern.compile(
            "第\\s*\\d+\\s*页|page\\s*\\d+|-\\s*\\d+\\s*-");
    /** 纯 URL 行 */
    private static final Pattern URL_ONLY = Pattern.compile("^(https?://|www\\.)\\S+$");

    /** 页眉页脚等重复短行的长度上限与最少重复次数 */
    private static final int REPEAT_MAX_LEN = 20;
    private static final int REPEAT_MIN_COUNT = 3;

    /** 提取结果：text 为拼接文本；linePages 为 PDF 时每行→页码（1 基，行索引 0 基），非 PDF 为 null */
    public record ExtractedText(String text, int[] linePages) {
    }

    public ExtractedText extract(String filename, byte[] bytes) throws IOException {
        String lower = filename.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".docx") || lower.endsWith(".doc") || lower.endsWith(".xlsx")
                || lower.endsWith(".pptx") || lower.endsWith(".rtf")) {
            return new ExtractedText(extractWithTika(bytes), null);
        }
        if (lower.endsWith(".pdf")) {
            return extractPdf(bytes, filename);
        }
        return new ExtractedText(new String(bytes, StandardCharsets.UTF_8), null);
    }

    // ==================== Tika：结构化 Office 文档 ====================

    /** 用 Tika 解析为 XHTML，再转成带 # 标题 / 制表符表格的 Markdown 文本 */
    private String extractWithTika(byte[] bytes) {
        try (InputStream in = new ByteArrayInputStream(bytes)) {
            // ToXMLContentHandler 直接作为解析 handler：经 BodyContentHandler 包装会抛 TIKA-237，
            // 直接解析输出完整 XHTML（含 <head>/<body>），由 xhtmlToText 自行跳过非正文部分。
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            AutoDetectParser parser = new AutoDetectParser();
            parser.parse(in, new ToXMLContentHandler(baos, "UTF-8"), new Metadata());
            return xhtmlToText(baos.toString(StandardCharsets.UTF_8));
        } catch (TikaException | SAXException e) {
            log.warn("Tika 解析文档失败: {}", e.getMessage());
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "无法解析文档（请确认是受支持的 Office/RTF 格式）: " + shortMessage(e.getMessage()));
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "读取文档失败: " + shortMessage(e.getMessage()));
        }
    }

    /**
     * XHTML → Markdown 文本。DOM 解析成功走结构转换（h1~h6→#、表格→\t 行、li→- 前缀），
     * 解析失败退化为纯标签剥离兜底。
     */
    private String xhtmlToText(String xhtml) {
        String safe = xhtml.replace("&nbsp;", " ").replaceAll("(?s)^<\\?xml.*?\\?>", "");
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            org.w3c.dom.Document doc = factory.newDocumentBuilder()
                    .parse(new ByteArrayInputStream(safe.getBytes(StandardCharsets.UTF_8)));
            StringBuilder sb = new StringBuilder();
            render(doc.getDocumentElement(), sb);
            return sb.toString();
        } catch (Exception e) {
            log.warn("XHTML 结构解析失败，退化为标签剥离: {}", e.getMessage());
            return stripTags(safe);
        }
    }

    /** 递归遍历 XHTML 元素，产出 Markdown 结构 */
    private void render(Element el, StringBuilder sb) {
        String name = el.getLocalName() != null ? el.getLocalName() : el.getTagName();
        if (name != null) {
            switch (name) {
                case "h1", "h2", "h3", "h4", "h5", "h6" -> {
                    int level = name.charAt(1) - '0';
                    String text = el.getTextContent().trim();
                    if (!text.isEmpty()) {
                        sb.append("#".repeat(level)).append(' ').append(text).append('\n');
                    }
                    return;
                }
                case "table" -> {
                    renderTable(el, sb);
                    return;
                }
                case "li" -> {
                    String text = el.getTextContent().trim();
                    if (!text.isEmpty()) {
                        sb.append("- ").append(text).append('\n');
                    }
                    return;
                }
                case "p", "div" -> {
                    String text = el.getTextContent().trim();
                    if (!text.isEmpty()) {
                        sb.append(text).append('\n');
                    }
                    return;
                }
                case "head", "meta", "title", "script", "style" -> {
                    return; // 非正文，跳过
                }
                default -> {
                    // 其他容器元素：递归子节点
                }
            }
        }
        NodeList children = el.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n instanceof Element e) {
                render(e, sb);
            } else if (n.getNodeType() == Node.TEXT_NODE) {
                String t = n.getTextContent().trim();
                if (!t.isEmpty()) {
                    sb.append(t).append('\n');
                }
            }
        }
    }

    /** 表格 → 每行用制表符拼接单元格文本 */
    private void renderTable(Element table, StringBuilder sb) {
        NodeList rows = table.getElementsByTagNameNS("*", "tr");
        for (int i = 0; i < rows.getLength(); i++) {
            Element tr = (Element) rows.item(i);
            NodeList cells = tr.getElementsByTagNameNS("*", "td");
            List<String> parts = new ArrayList<>();
            for (int j = 0; j < cells.getLength(); j++) {
                String c = ((Element) cells.item(j)).getTextContent().trim();
                if (!c.isEmpty()) {
                    parts.add(c);
                }
            }
            if (parts.isEmpty()) {
                NodeList ths = tr.getElementsByTagNameNS("*", "th");
                for (int j = 0; j < ths.getLength(); j++) {
                    String c = ((Element) ths.item(j)).getTextContent().trim();
                    if (!c.isEmpty()) {
                        parts.add(c);
                    }
                }
            }
            if (!parts.isEmpty()) {
                sb.append(String.join("\t", parts)).append('\n');
            }
        }
    }

    /** 兜底：只剥离标签、还原基本实体，不做结构转换 */
    private String stripTags(String html) {
        String text = html.replaceAll("(?s)<[^>]+>", "\n").replace("&amp;", "&")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"");
        return text.replaceAll("(?m)^\\s+$", "").replaceAll("(?m)\\n{3,}", "\n\n").trim();
    }

    // ==================== PDF：保留逐行页码 + 噪声清洗 ====================

    private ExtractedText extractPdf(byte[] bytes, String filename) throws IOException {
        PagePdfDocumentReader reader = new PagePdfDocumentReader(new ByteArrayResource(bytes, filename));
        List<Document> pages = reader.get();

        List<String> lines = new ArrayList<>();      // 原始行（含页间分隔空行）
        List<Integer> linePages = new ArrayList<>(); // 每行对应页码（1 基）
        for (int p = 0; p < pages.size(); p++) {
            String[] pageLines = pages.get(p).getText().split("\n", -1);
            for (String line : pageLines) {
                lines.add(line);
                linePages.add(p + 1);
            }
            if (p < pages.size() - 1) {
                lines.add("");   // 页间分隔：避免跨页段落拼成一行
                linePages.add(p + 1);
            }
        }

        // 统计重复短行（页眉/页脚通常在每页重复出现）
        Map<String, Integer> freq = new HashMap<>();
        for (String line : lines) {
            String t = line.trim();
            if (!t.isEmpty() && t.length() <= REPEAT_MAX_LEN) {
                freq.merge(t, 1, Integer::sum);
            }
        }

        List<String> cleaned = new ArrayList<>();
        List<Integer> cleanedPages = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            if (isNoise(lines.get(i), freq)) {
                continue;
            }
            // 压缩连续空行：最多保留 1 个
            if (lines.get(i).isBlank() && !cleaned.isEmpty() && cleaned.get(cleaned.size() - 1).isBlank()) {
                continue;
            }
            cleaned.add(lines.get(i));
            cleanedPages.add(linePages.get(i));
        }

        int[] pagesArray = new int[cleanedPages.size() + 1];
        pagesArray[0] = 1; // 第 0 行恒为第 1 页（与分片器行索引 0 基对齐）
        for (int i = 0; i < cleanedPages.size(); i++) {
            pagesArray[i + 1] = cleanedPages.get(i);
        }
        return new ExtractedText(String.join("\n", cleaned), pagesArray);
    }

    private boolean isNoise(String line, Map<String, Integer> freq) {
        String t = line.trim();
        if (t.isEmpty()) {
            return false;
        }
        if (PURE_NUMBER.matcher(t).matches()) {
            return true; // 纯数字页码
        }
        if (PAGE_MARKER.matcher(t).find()) {
            return true; // 第 X 页 / Page X / - X -
        }
        if (URL_ONLY.matcher(t).matches()) {
            return true; // 纯 URL
        }
        // 短且重复 >=3 次 → 页眉/页脚
        return t.length() <= REPEAT_MAX_LEN && freq.getOrDefault(t, 0) >= REPEAT_MIN_COUNT;
    }

    private static String shortMessage(String msg) {
        if (msg == null || msg.isBlank()) {
            return "未知错误";
        }
        String m = msg.replace('\n', ' ').replace('\r', ' ').trim();
        return m.length() > 120 ? m.substring(0, 120) + "…" : m;
    }
}
