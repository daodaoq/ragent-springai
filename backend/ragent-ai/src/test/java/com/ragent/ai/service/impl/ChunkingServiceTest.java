package com.ragent.ai.service.impl;

import com.ragent.ai.config.ChunkingProperties;
import com.ragent.ai.service.ChunkingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * P8-8a：结构感知切分器基础行为测试（语义分片关闭，不触发 embedding）。
 */
class ChunkingServiceTest {

    private ChunkingService svc() {
        return new ChunkingService(new ChunkingProperties(), mock(ObjectProvider.class));
    }

    @Test
    void splitsMarkdownByHeadingAndKeepsSizeWithinLimit() {
        String text = """
                # 第一章 基础

                第一段是介绍性的内容，讲述神经网络的基本原理。
                这里继续展开，属于同一个段落。

                第二段换一个话题，讲反向传播的数学推导。

                ## 1.1 小节

                小节内容。
                """;
        List<ChunkingService.Chunk> chunks =
                svc().chunk(text, new ChunkingService.ChunkOptions(800, 100, false, "doc.md"));

        assertFalse(chunks.isEmpty());
        for (ChunkingService.Chunk c : chunks) {
            assertTrue(c.content().length() <= 800 * 1.5, "切片超过长度上限: " + c.content().length());
            assertTrue(c.headingPath() != null);
        }
        // 章节路径保留标题层级信息
        assertTrue(chunks.stream().anyMatch(c -> c.headingPath() != null && c.headingPath().contains("第一章")));
    }

    @Test
    void tinyDocProducesSingleChunkWithLineOffsets() {
        String text = "只有一行内容。";
        List<ChunkingService.Chunk> chunks =
                svc().chunk(text, new ChunkingService.ChunkOptions(800, 100, false, "doc.md"));

        assertEquals(1, chunks.size());
        assertEquals(0, chunks.get(0).startLine());
        assertTrue(chunks.get(0).endLine() >= chunks.get(0).startLine());
        assertEquals(0, chunks.get(0).startChar());
        assertTrue(chunks.get(0).endChar() > chunks.get(0).startChar());
    }
}
