package com.ragent.ai.service;

import com.ragent.ai.config.ChunkingProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 结构感知中文分片器（替代 TokenTextSplitter）：
 * <ol>
 *   <li>按 Markdown 标题（# / ## / ...）切成小节，维护标题栈生成完整路径（headingPath），
 *       每个切片内容自带标题上下文</li>
 *   <li>小节内按空行分段落，把相邻段落组合到目标块大小</li>
 *   <li>超长段落按中文句号（。！？；）切分，避免从句子中间硬切</li>
 *   <li>块间重叠（overlap）保住边界上下文</li>
 * </ol>
 * 每个切片同时带出在原始文档中的位置：行号范围（startLine/endLine，0 基）与字符偏移
 * （startChar/endChar，0 基、半开区间，指向正文不含标题前缀），供引用溯源/原文跳转使用。
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

    /** 分片结果：content 含标题前缀；headingPath 为完整标题路径；start/end 行与字符指向原始文档正文 */
    public record Chunk(String content, String title, String headingPath,
                        int startLine, int endLine, int startChar, int endChar) {
    }

    /** 段落/句片：text 为片段文本，start/end 行与字符指向原始文档正文（0 基，字符半开区间） */
    private record Para(String text, int startLine, int endLine, int startChar, int endChar) {
    }

    /** 小节：标题 + 完整标题路径 + 正文行号集合（0 基） */
    private record Section(String title, String headingPath, List<Integer> bodyLines) {
    }

    public List<Chunk> chunk(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String[] lines = text.split("\n", -1);
        // 每行在原文中的起始字符偏移，用于把切片的字符范围换算成行号
        int[] lineStart = new int[lines.length];
        int off = 0;
        for (int i = 0; i < lines.length; i++) {
            lineStart[i] = off;
            off += lines[i].length() + 1;
        }
        List<Section> sections = parseSections(lines);
        List<Chunk> result = new ArrayList<>();
        for (Section section : sections) {
            splitSection(section, lineStart, lines, result);
        }
        return result;
    }

    // ==================== 结构解析 ====================

    private List<Section> parseSections(String[] lines) {
        List<Section> sections = new ArrayList<>();
        List<StackEntry> stack = new ArrayList<>();
        String currentTitle = null;
        String currentPath = null;
        List<Integer> body = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            Matcher m = HEADING.matcher(lines[i]);
            if (m.matches()) {
                if (currentTitle != null) {
                    sections.add(new Section(currentTitle, currentPath, body));
                }
                int level = m.group(1).length();
                // 维护标题栈：级别 >= 当前标题的旧标题出栈，当前标题入栈，形成嵌套路径
                while (!stack.isEmpty() && stack.get(stack.size() - 1).level() >= level) {
                    stack.remove(stack.size() - 1);
                }
                // 入向量库前清洗：去掉标题行开头的 # 标记（"# Transformer" → "Transformer"），
                // 只留标题文字；标题级别单独记录，供栈判断层级。
                String headingText = cleanHeading(lines[i].trim());
                stack.add(new StackEntry(headingText, level));
                currentTitle = headingText;
                currentPath = stack.stream().map(StackEntry::text).collect(Collectors.joining(" > "));
                body = new ArrayList<>();
            } else {
                body.add(i);
            }
        }
        if (currentTitle != null) {
            sections.add(new Section(currentTitle, currentPath, body));
        }
        return sections;
    }

    /** 去掉标题行开头的 # 与空格（"## 为什么需要 Transformer" → "为什么需要 Transformer"） */
    private static String cleanHeading(String headingLine) {
        return headingLine.replaceAll("^#{1,6}\\s+", "");
    }

    /** 标题栈条目：text 为清洗后的标题文字，level 为标题级别（# 个数） */
    private record StackEntry(String text, int level) {
    }

    // ==================== 小节切分 ====================

    private void splitSection(Section section, int[] lineStart, String[] lines, List<Chunk> out) {
        List<Para> paras = splitParagraphs(section.bodyLines(), lineStart, lines);
        if (paras.isEmpty()) {
            return;
        }
        // 段落组块：累计到目标块大小落一块；重叠保留上一块最后一段
        List<Para> current = new ArrayList<>();
        int len = 0;
        for (Para p : paras) {
            if (len + p.text().length() > maxChunkChars && !current.isEmpty()) {
                out.add(makeChunk(section, current));
                current = new ArrayList<>(current.subList(current.size() - 1, current.size()));
                len = current.get(0).text().length();
            }
            current.add(p);
            len += p.text().length();
        }
        if (!current.isEmpty()) {
            out.add(makeChunk(section, current));
        }
    }

    /** 把一组相邻片段合成切片：内容 = 标题 + 正文；位置范围取首片段起点到末片段终点 */
    private Chunk makeChunk(Section section, List<Para> paras) {
        String body = paras.stream().map(Para::text).collect(Collectors.joining("\n"));
        Para first = paras.get(0);
        Para last = paras.get(paras.size() - 1);
        return new Chunk(section.title() + "\n" + body, section.title(), section.headingPath(),
                first.startLine(), last.endLine(), first.startChar(), last.endChar());
    }

    /** 按空行（含仅空白行）把节正文切成段落；超长段落先按句子切（见 addBlockPieces） */
    private List<Para> splitParagraphs(List<Integer> bodyLines, int[] lineStart, String[] lines) {
        List<Para> result = new ArrayList<>();
        List<Integer> block = new ArrayList<>();
        for (int li : bodyLines) {
            if (lines[li].isBlank()) {
                if (!block.isEmpty()) {
                    addBlockPieces(result, block, lineStart, lines);
                    block = new ArrayList<>();
                }
            } else {
                block.add(li);
            }
        }
        if (!block.isEmpty()) {
            addBlockPieces(result, block, lineStart, lines);
        }
        return result;
    }

    /** 连续非空行块 → 1 个段落（位置=块的行/字符范围）；块超长则按句子/窗口切成多片 */
    private void addBlockPieces(List<Para> out, List<Integer> block, int[] lineStart, String[] lines) {
        int startLine = block.get(0);
        int endLine = block.get(block.size() - 1);
        String text = block.stream().map(i -> lines[i]).collect(Collectors.joining("\n"));
        int baseChar = lineStart[startLine]; // 段落首字符在原文中的偏移
        if (text.length() <= maxChunkChars) {
            out.add(new Para(text, startLine, endLine, baseChar, baseChar + text.length()));
            return;
        }
        out.addAll(splitParagraph(text, baseChar, lineStart, lines));
    }

    /** 超长段落按中文句号/叹号/问号/分号切句子；单句仍超长再按窗口切。每片给出原文行/字符范围 */
    private List<Para> splitParagraph(String text, int baseChar, int[] lineStart, String[] lines) {
        List<int[]> ranges = new ArrayList<>(); // {start, end} 相对段落文本的字符区间
        int curStart = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (SENTENCE_BOUNDARY.matcher(String.valueOf(c)).matches()) {
                ranges.add(new int[]{curStart, i + 1});
                curStart = i + 1;
            }
        }
        if (curStart < text.length()) {
            ranges.add(new int[]{curStart, text.length()});
        }
        List<Para> out = new ArrayList<>();
        for (int[] r : ranges) {
            int s = r[0];
            int e = r[1];
            if (e - s > maxChunkChars) {
                int start = s;
                while (start < e) {
                    int end = Math.min(start + maxChunkChars, e);
                    out.add(toPara(text, start, end, baseChar, lineStart));
                    if (end >= e) {
                        break;
                    }
                    start = end - overlapChars;
                }
            } else {
                out.add(toPara(text, s, e, baseChar, lineStart));
            }
        }
        return out;
    }

    /** 把段落内 [s,e) 相对偏移换算成原文全局字符偏移与行号 */
    private Para toPara(String text, int s, int e, int baseChar, int[] lineStart) {
        int startChar = baseChar + s;
        int endChar = baseChar + e;
        int startLine = lineAt(startChar, lineStart);
        int endLine = lineAt(Math.max(startChar, endChar - 1), lineStart);
        return new Para(text.substring(s, e), startLine, endLine, startChar, endChar);
    }

    /** 字符偏移 → 行号（0 基）：最后一个起始偏移 <= offset 的行 */
    private static int lineAt(int offset, int[] lineStart) {
        int lo = 0;
        int hi = lineStart.length - 1;
        int ans = 0;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (lineStart[mid] <= offset) {
                ans = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return ans;
    }
}
