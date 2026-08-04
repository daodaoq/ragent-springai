package com.ragent.ai.service;

import com.ragent.ai.config.ChunkingProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 结构感知中文分片器（替代 TokenTextSplitter）：
 * <ol>
 *   <li>按 Markdown 标题（# / ## / ...）切成小节，每个切片内容自带标题上下文</li>
 *   <li>小节内按空行分段落，把相邻段落组合到目标块大小</li>
 *   <li>超长段落按中文句号（。！？；）切分，避免从句子中间硬切</li>
 *   <li>块间重叠（overlap）保住边界上下文</li>
 * </ol>
 * 配置见 {@link ChunkingProperties}（ragent.chunking.max-chunk-chars / overlap-chars）。
 */
@Component
public class ChunkingService {

    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*)$");
    private static final Pattern SENTENCE_BOUNDARY = Pattern.compile("[。！？；]");

    private final int maxChunkChars;
    private final int overlapChars;

    public ChunkingService(ChunkingProperties properties) {
        this.maxChunkChars = properties.getMaxChunkChars();
        this.overlapChars = properties.getOverlapChars();
    }

    /** 分片结果：content 含标题前缀，title 为小节标题 */
    public record Chunk(String content, String title) {
    }

    public List<Chunk> chunk(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<Section> sections = parseSections(text);
        List<Chunk> result = new ArrayList<>();
        for (Section section : sections) {
            splitSection(section, result);
        }
        return result;
    }

    // ==================== 结构解析 ====================

    private record Section(String title, String body) {
    }

    private List<Section> parseSections(String text) {
        List<Section> sections = new ArrayList<>();
        StringBuilder body = new StringBuilder();
        String currentTitle = null;
        for (String line : text.split("\n", -1)) {
            Matcher m = HEADING.matcher(line);
            if (m.matches()) {
                if (currentTitle != null) {
                    sections.add(new Section(currentTitle, body.toString()));
                }
                // 保留 "# 标题" 原文作为上下文前缀
                currentTitle = line.trim();
                body = new StringBuilder();
            } else {
                body.append(line).append('\n');
            }
        }
        if (currentTitle != null) {
            sections.add(new Section(currentTitle, body.toString()));
        }
        return sections;
    }

    // ==================== 小节切分 ====================

    private void splitSection(Section section, List<Chunk> out) {
        String body = section.body().trim();
        if (body.isEmpty()) {
            return;
        }
        // 小节不超长：整体一块
        if (body.length() <= maxChunkChars) {
            out.add(new Chunk(section.title() + "\n" + body, section.title()));
            return;
        }
        // 段落组块
        List<String> paragraphs = splitParagraphs(body);
        List<String> current = new ArrayList<>();
        int len = 0;
        for (String p : paragraphs) {
            if (len + p.length() > maxChunkChars && !current.isEmpty()) {
                out.add(new Chunk(section.title() + "\n" + String.join("\n", current), section.title()));
                // 重叠：保留上一块最后一段
                current = new ArrayList<>(current.subList(Math.max(0, current.size() - 1), current.size()));
                len = current.stream().mapToInt(String::length).sum();
            }
            current.add(p);
            len += p.length();
        }
        if (!current.isEmpty()) {
            out.add(new Chunk(section.title() + "\n" + String.join("\n", current), section.title()));
        }
    }

    /** 按空行分段落；超长段落先按句子切 */
    private List<String> splitParagraphs(String body) {
        List<String> result = new ArrayList<>();
        for (String raw : body.split("\n\\s*\n")) {
            String p = raw.trim();
            if (p.isEmpty()) {
                continue;
            }
            if (p.length() > maxChunkChars) {
                result.addAll(splitSentences(p));
            } else {
                result.add(p);
            }
        }
        return result;
    }

    /** 按中文句号/叹号/问号/分号切分句子；超长句子再按窗口切 */
    private List<String> splitSentences(String paragraph) {
        List<String> sentences = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < paragraph.length(); i++) {
            char c = paragraph.charAt(i);
            cur.append(c);
            if (SENTENCE_BOUNDARY.matcher(String.valueOf(c)).matches()) {
                sentences.add(cur.toString());
                cur.setLength(0);
            }
        }
        if (cur.length() > 0) {
            sentences.add(cur.toString());
        }
        List<String> result = new ArrayList<>();
        for (String s : sentences) {
            if (s.length() > maxChunkChars) {
                result.addAll(splitByWindow(s));
            } else {
                result.add(s);
            }
        }
        return result;
    }

    /** 滑动窗口切分 + 重叠 */
    private List<String> splitByWindow(String text) {
        List<String> result = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + maxChunkChars, text.length());
            result.add(text.substring(start, end));
            if (end >= text.length()) {
                break;
            }
            start = end - overlapChars;
        }
        return result;
    }
}
