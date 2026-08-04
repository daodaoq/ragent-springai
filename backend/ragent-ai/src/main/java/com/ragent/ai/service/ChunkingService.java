package com.ragent.ai.service;

import com.ragent.ai.config.ChunkingProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 结构感知中文分片器（替代 TokenTextSplitter）：
 * <ol>
 *   <li>按 Markdown 标题（# / ## / ...）切成小节，维护标题栈生成完整路径（headingPath）；
 *       无标题文档整体作为一个小节（标题用文件名兜底），首个标题前的引言行归入「前言」小节，避免内容丢失；</li>
 *   <li>小节内按空行分段落，把相邻段落组合到目标块大小；代码块（围栏）与表格行作为原子单位，
 *       只按窗口切分、不做标点切分，避免被打碎；</li>
 *   <li>超长段落按中文句号（。！？；）切分，避免从句子中间硬切；</li>
 *   <li>块间重叠（overlap）保住边界上下文；</li>
 *   <li>可选语义分片：长且无标题的小节内，相邻段落 embedding 余弦相似度低于阈值视为主题切换，
 *       在那里强制断片（默认关闭，见 ChunkingProperties）。</li>
 * </ol>
 * 每个切片同时带出在原始文档中的位置：行号范围（startLine/endLine，0 基）与字符偏移
 * （startChar/endChar，0 基、半开区间，指向正文不含标题前缀），供引用溯源/原文跳转使用。
 */
@Slf4j
@Component
public class ChunkingService {

    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*)$");
    private static final Pattern SENTENCE_BOUNDARY = Pattern.compile("[。！？；]");
    /** 围栏代码块起止行（``` 或 ~~~） */
    private static final Pattern FENCE = Pattern.compile("^\\s*(```+|~~~+)\\s*$");
    /** 无标题文档 / 引言小节的通用标题 */
    private static final String PREAMBLE_TITLE = "前言";

    private final ChunkingProperties props;
    /** 语义分片用；注入失败（未配置 embedding）时为 null，自动禁用语义分片 */
    private final EmbeddingModel embeddingModel;

    public ChunkingService(ChunkingProperties properties, ObjectProvider<EmbeddingModel> embeddingProvider) {
        this.props = properties;
        this.embeddingModel = embeddingProvider.getIfAvailable();
    }

    /** 分片参数：调用方按「每文档覆盖 > 全局设置 > yml 默认」解析后传入 */
    public record ChunkOptions(int maxChunkChars, int overlapChars, boolean semanticEnabled, String fallbackTitle) {
        public static ChunkOptions defaults(ChunkingProperties p, String fallbackTitle) {
            return new ChunkOptions(p.getMaxChunkChars(), p.getOverlapChars(), p.isSemanticEnabled(), fallbackTitle);
        }
    }

    /** 分片结果：content 含完整章节路径前缀；headingPath 为完整标题路径；start/end 行与字符指向原始文档正文 */
    public record Chunk(String content, String title, String headingPath,
                        int startLine, int endLine, int startChar, int endChar) {
    }

    /** 段落/句片：text 为片段文本，start/end 行与字符指向原始文档正文（0 基，字符半开区间） */
    private record Para(String text, int startLine, int endLine, int startChar, int endChar) {
    }

    /** 小节：标题 + 完整标题路径 + 正文行号集合（0 基） */
    private record Section(String title, String headingPath, List<Integer> bodyLines) {
    }

    /** 标题栈条目：text 为清洗后的标题文字，level 为标题级别（# 个数） */
    private record StackEntry(String text, int level) {
    }

    /** 块类型：TEXT 可按句子切分；CODE/TABLE 只按窗口切分（原子单位，避免打碎） */
    private enum BlockKind { TEXT, CODE, TABLE }

    private record Block(BlockKind kind, List<Integer> lines) {
    }

    public List<Chunk> chunk(String text, ChunkOptions opts) {
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
        List<Section> sections = parseSections(lines, opts.fallbackTitle());
        List<Chunk> result = new ArrayList<>();
        for (Section section : sections) {
            splitSection(section, lineStart, lines, opts, result);
        }
        return result;
    }

    // ==================== 结构解析 ====================

    /**
     * 解析小节。P0 修复：首个标题前的引言行作为「前言」小节保留；
     * 全文无标题时整篇作为单个小节（标题用文件名兜底），不再静默产出 0 切片。
     */
    private List<Section> parseSections(String[] lines, String fallbackTitle) {
        List<Section> sections = new ArrayList<>();
        List<StackEntry> stack = new ArrayList<>();
        String currentTitle = null;
        String currentPath = null;
        List<Integer> body = new ArrayList<>();
        List<Integer> preamble = new ArrayList<>(); // 首个标题前的引言行
        boolean seenHeading = false;

        for (int i = 0; i < lines.length; i++) {
            Matcher m = HEADING.matcher(lines[i]);
            if (m.matches()) {
                if (!seenHeading) {
                    seenHeading = true;
                    if (!preamble.isEmpty()) {
                        sections.add(new Section(PREAMBLE_TITLE, PREAMBLE_TITLE, preamble));
                    }
                } else if (currentTitle != null) {
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
                if (seenHeading) {
                    body.add(i);
                } else {
                    preamble.add(i);
                }
            }
        }
        if (currentTitle != null) {
            sections.add(new Section(currentTitle, currentPath, body));
        } else if (!preamble.isEmpty()) {
            // 全文无标题：整篇作为单个小节
            sections.add(new Section(fallbackTitle, fallbackTitle, preamble));
        }
        return sections;
    }

    /** 去掉标题行开头的 # 与空格（"## 为什么需要 Transformer" → "为什么需要 Transformer"） */
    private static String cleanHeading(String headingLine) {
        return headingLine.replaceAll("^#{1,6}\\s+", "");
    }

    // ==================== 块感知切分 ====================

    /** 小节切分：先解析块（TEXT/CODE/TABLE），再按语义组/长度组块 */
    private void splitSection(Section section, int[] lineStart, String[] lines, ChunkOptions opts, List<Chunk> out) {
        List<Block> blocks = splitBlocks(section.bodyLines(), lines);
        if (blocks.isEmpty()) {
            return;
        }
        List<List<Block>> groups;
        if (opts.semanticEnabled() && embeddingModel != null
                && blocks.size() >= props.getSemanticMinBlocks()
                && totalLen(blocks, lines) > opts.maxChunkChars() * 2.5) {
            groups = semanticGroups(blocks, lines, opts);
        } else {
            groups = List.of(blocks);
        }
        for (List<Block> group : groups) {
            groupBlocksIntoChunks(section, group, lineStart, lines, opts, out);
        }
    }

    /**
     * 把 body 行序列解析为块：围栏代码块 → CODE；连续表格行（| 开头或含 \t）→ TABLE；
     * 其余非空行按空行分组 → TEXT。
     */
    private List<Block> splitBlocks(List<Integer> bodyLines, String[] lines) {
        List<Block> blocks = new ArrayList<>();
        List<Integer> text = new ArrayList<>();
        int i = 0;
        while (i < bodyLines.size()) {
            int li = bodyLines.get(i);
            String line = lines[li];
            if (FENCE.matcher(line).matches()) {
                flushTextBlock(text, blocks);
                List<Integer> code = new ArrayList<>();
                code.add(li);
                int j = i + 1;
                while (j < bodyLines.size()) {
                    code.add(bodyLines.get(j));
                    if (FENCE.matcher(lines[bodyLines.get(j)]).matches()) {
                        j++;
                        break;
                    }
                    j++;
                }
                blocks.add(new Block(BlockKind.CODE, code));
                i = j;
                continue;
            }
            if (isTableRow(line)) {
                flushTextBlock(text, blocks);
                List<Integer> table = new ArrayList<>();
                while (i < bodyLines.size() && isTableRow(lines[bodyLines.get(i)])) {
                    table.add(bodyLines.get(i));
                    i++;
                }
                blocks.add(new Block(BlockKind.TABLE, table));
                continue;
            }
            if (line.isBlank()) {
                flushTextBlock(text, blocks);
                i++;
                continue;
            }
            text.add(li);
            i++;
        }
        flushTextBlock(text, blocks);
        return blocks;
    }

    private boolean isTableRow(String line) {
        String t = line.trim();
        if (t.isEmpty()) {
            return false;
        }
        return t.startsWith("|") || line.contains("\t");
    }

    private void flushTextBlock(List<Integer> text, List<Block> blocks) {
        if (!text.isEmpty()) {
            // 必须拷贝：text 是复用的累积列表，随后会被 clear()，直接引用会让 Block 里的行号列表被清空
            blocks.add(new Block(BlockKind.TEXT, new ArrayList<>(text)));
            text.clear();
        }
    }

    /** 块文本（代码块去掉围栏行） */
    private String blockText(Block block, String[] lines) {
        List<String> parts = new ArrayList<>();
        for (int li : block.lines()) {
            if (block.kind() == BlockKind.CODE && FENCE.matcher(lines[li]).matches()) {
                continue; // 围栏行不进内容
            }
            parts.add(lines[li]);
        }
        return String.join("\n", parts);
    }

    private int totalLen(List<Block> blocks, String[] lines) {
        int len = 0;
        for (Block b : blocks) {
            len += blockText(b, lines).length();
        }
        return len;
    }

    // ==================== 语义分片 ====================

    /** 相邻块 embedding 余弦相似度低于阈值处断开，返回分组（各组分头组块，切片不跨语义边界） */
    private List<List<Block>> semanticGroups(List<Block> blocks, String[] lines, ChunkOptions opts) {
        try {
            List<String> texts = blocks.stream().map(b -> blockText(b, lines)).toList();
            if (texts.size() < 2) {
                return List.of(blocks);
            }
            float[][] embs = embedBatch(texts);
            double threshold = props.getSemanticThreshold();
            List<List<Block>> groups = new ArrayList<>();
            List<Block> current = new ArrayList<>();
            for (int i = 0; i < blocks.size(); i++) {
                current.add(blocks.get(i));
                if (i < blocks.size() - 1 && cosine(embs[i], embs[i + 1]) < threshold) {
                    groups.add(current);
                    current = new ArrayList<>();
                }
            }
            if (!current.isEmpty()) {
                groups.add(current);
            }
            return groups;
        } catch (Exception e) {
            log.warn("语义分片失败，回退结构分片: {}", e.getMessage());
            return List.of(blocks);
        }
    }

    private float[][] embedBatch(List<String> texts) {
        List<float[]> all = new ArrayList<>();
        for (int i = 0; i < texts.size(); i += MAX_EMBED_BATCH) {
            List<String> batch = texts.subList(i, Math.min(i + MAX_EMBED_BATCH, texts.size()));
            List<float[]> res = AiRetry.callWithRetry(() -> embeddingModel.embed(batch));
            all.addAll(res);
        }
        return all.toArray(new float[0][]);
    }

    private static final int MAX_EMBED_BATCH = 10;

    private static double cosine(float[] a, float[] b) {
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            na += (double) a[i] * a[i];
            nb += (double) b[i] * b[i];
        }
        if (na == 0 || nb == 0) {
            return 0;
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    // ==================== 组块与切句 ====================

    /** 块 → 段落（超长块按类型切），再按目标块大小组块；重叠保留上一块最后一段 */
    private void groupBlocksIntoChunks(Section section, List<Block> blocks, int[] lineStart, String[] lines,
                                       ChunkOptions opts, List<Chunk> out) {
        List<Para> paras = new ArrayList<>();
        for (Block b : blocks) {
            paras.addAll(blockToParas(b, lineStart, lines, opts));
        }
        if (paras.isEmpty()) {
            return;
        }
        List<Para> current = new ArrayList<>();
        int len = 0;
        for (Para p : paras) {
            if (len + p.text().length() > opts.maxChunkChars() && !current.isEmpty()) {
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

    /** 块 → 一个或多个段落。超长时 TEXT 走句子切→窗口切，CODE/TABLE 只走窗口切（原子单位） */
    private List<Para> blockToParas(Block block, int[] lineStart, String[] lines, ChunkOptions opts) {
        String text = blockText(block, lines);
        if (text.isEmpty()) {
            return List.of();
        }
        int startLine = block.lines().get(0);
        int endLine = block.lines().get(block.lines().size() - 1);
        if (block.kind() == BlockKind.CODE) {
            // 代码块内容位置从首/尾非围栏行算起，避免围栏行污染字符偏移
            for (int l : block.lines()) {
                if (!FENCE.matcher(lines[l]).matches()) {
                    startLine = l;
                    break;
                }
            }
            for (int i = block.lines().size() - 1; i >= 0; i--) {
                int l = block.lines().get(i);
                if (!FENCE.matcher(lines[l]).matches()) {
                    endLine = l;
                    break;
                }
            }
        }
        int baseChar = lineStart[startLine];
        if (text.length() <= opts.maxChunkChars()) {
            return List.of(new Para(text, startLine, endLine, baseChar, baseChar + text.length()));
        }
        if (block.kind() == BlockKind.TEXT) {
            return splitParagraph(text, baseChar, lineStart, lines, opts);
        }
        return windowSplitParagraph(text, baseChar, lineStart, lines, opts);
    }

    /** 合成切片：内容 = 完整章节路径 + 正文（P2a，提升深层切片检索上下文）；位置取首段起点到末段终点 */
    private Chunk makeChunk(Section section, List<Para> paras) {
        String body = paras.stream().map(Para::text).collect(Collectors.joining("\n"));
        Para first = paras.get(0);
        Para last = paras.get(paras.size() - 1);
        String path = section.headingPath();
        String content = (path != null && !path.isBlank()) ? path + "\n" + body : body;
        return new Chunk(content, section.title(), section.headingPath(),
                first.startLine(), last.endLine(), first.startChar(), last.endChar());
    }

    /** 超长段落按中文句号/叹号/问号/分号切句子；单句仍超长再按窗口切。每片给出原文行/字符范围 */
    private List<Para> splitParagraph(String text, int baseChar, int[] lineStart, String[] lines, ChunkOptions opts) {
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
            if (e - s > opts.maxChunkChars()) {
                int start = s;
                while (start < e) {
                    int end = Math.min(start + opts.maxChunkChars(), e);
                    out.add(toPara(text, start, end, baseChar, lineStart));
                    if (end >= e) {
                        break;
                    }
                    start = end - opts.overlapChars();
                }
            } else {
                out.add(toPara(text, s, e, baseChar, lineStart));
            }
        }
        return out;
    }

    /** 只按固定窗口切（代码/表格等原子单位超长时用），不做标点切分 */
    private List<Para> windowSplitParagraph(String text, int baseChar, int[] lineStart, String[] lines,
                                            ChunkOptions opts) {
        List<Para> out = new ArrayList<>();
        int start = 0;
        int e = text.length();
        while (start < e) {
            int end = Math.min(start + opts.maxChunkChars(), e);
            out.add(toPara(text, start, end, baseChar, lineStart));
            if (end >= e) {
                break;
            }
            start = end - opts.overlapChars();
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
