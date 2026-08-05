package com.ragent.ai.service.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * P8-8a：查询规范化（A 阶段）单元测试：口头禅去除 / 全半角归一 / 空白合并。
 */
class QueryNormalizerTest {

    @Test
    void stripsLeadingAndTrailingFillers() {
        assertEquals("什么是反向传播", QueryNormalizer.normalize("请问什么是反向传播谢谢"));
        assertEquals("如何配置 DeepSeek", QueryNormalizer.normalize("帮我如何配置 DeepSeek"));
        assertEquals("强化学习", QueryNormalizer.normalize("您好，强化学习"));
    }

    @Test
    void normalizesFullWidthToHalfWidth() {
        assertEquals("CNN:模型如何训练", QueryNormalizer.normalize("CNN：模型如何训练"));
        assertEquals("什么是RAG", QueryNormalizer.normalize("什么是ＲＡＧ"));
    }

    @Test
    void collapsesWhitespaceAndTrims() {
        assertEquals("知识库 检索 优化", QueryNormalizer.normalize("  知识库　　检索  优化  "));
    }

    @Test
    void nullReturnsEmpty() {
        assertEquals("", QueryNormalizer.normalize(null));
    }
}
