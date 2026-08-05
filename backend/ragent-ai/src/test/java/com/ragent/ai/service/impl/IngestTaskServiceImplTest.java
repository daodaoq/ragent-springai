package com.ragent.ai.service.impl;

import com.ragent.ai.config.IngestProperties;
import com.ragent.ai.entity.IngestTask;
import com.ragent.ai.entity.KbDocument;
import com.ragent.ai.mapper.IngestTaskMapper;
import com.ragent.ai.mapper.KbDocumentMapper;
import com.ragent.ai.service.KnowledgeBaseService;
import com.ragent.ai.service.ingest.KbFilenameLock;
import com.ragent.common.exception.BusinessException;
import com.ragent.common.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P9-5a：异步摄取任务状态机单元测试——瞬时失败重试、永久失败直进 DLQ、超限 DLQ、成功 SUCCESS。
 * 直接调用包内 {@code execute}（绕开线程池轮询，确定性验证）。
 */
class IngestTaskServiceImplTest {

    private IngestTaskMapper taskMapper;
    private KbDocumentMapper documentMapper;
    private KnowledgeBaseService kbService;
    private IngestTaskServiceImpl svc;

    @BeforeEach
    void setUp() {
        taskMapper = mock(IngestTaskMapper.class);
        documentMapper = mock(KbDocumentMapper.class);
        kbService = mock(KnowledgeBaseService.class);
        IngestProperties props = new IngestProperties();
        props.setMaxAttempts(3);
        svc = new IngestTaskServiceImpl(taskMapper, documentMapper, kbService, props, new KbFilenameLock());
    }

    @Test
    void transientFailureRequeuesUntilMaxThenDlq() {
        IngestTask task = task(100L, 0, 3);
        KbDocument doc = doc(100L);
        doThrow(new BusinessException(ErrorCode.SYSTEM_ERROR, "嵌入接口超时"))
                .when(kbService).processDocument(100L, "UPLOAD");

        // 前 3 次：attempt 递增、回 QUEUED、文档回 PENDING（避免 FAILED↔PROCESSING 闪烁）
        svc.execute(task, doc);
        assertEquals(IngestTask.STATUS_QUEUED, task.getStatus());
        assertEquals(1, task.getAttempt());
        assertEquals("PENDING", doc.getStatus());
        assertNull(doc.getErrorMsg());

        svc.execute(task, doc);
        assertEquals(2, task.getAttempt());

        svc.execute(task, doc);
        assertEquals(3, task.getAttempt());
        assertEquals(IngestTask.STATUS_QUEUED, task.getStatus());

        // 第 4 次：attempt 达上限 → DLQ + 文档 FAILED + 错误原因
        svc.execute(task, doc);
        assertEquals(IngestTask.STATUS_DLQ, task.getStatus());
        assertEquals("FAILED", doc.getStatus());
        assertEquals("嵌入接口超时", doc.getErrorMsg());
        // 四次失败每次都会把文档状态持久化（PENDING→PENDING→PENDING→FAILED）
        verify(documentMapper, times(4)).updateById(doc);
    }

    @Test
    void permanentFailureGoesStraightToDlq() {
        IngestTask task = task(100L, 0, 3);
        KbDocument doc = doc(100L);
        // BAD_REQUEST(400) = 永久失败（如扫描件无文本）：不重试直接 DLQ
        doThrow(new BusinessException(ErrorCode.BAD_REQUEST, "未提取到文本内容"))
                .when(kbService).processDocument(100L, "UPLOAD");

        svc.execute(task, doc);

        assertEquals(IngestTask.STATUS_DLQ, task.getStatus());
        assertEquals(0, task.getAttempt()); // 一次都没重试
        assertEquals("FAILED", doc.getStatus());
        assertEquals("未提取到文本内容", doc.getErrorMsg());
    }

    @Test
    void successMarksTaskSuccess() {
        IngestTask task = task(100L, 0, 3);
        KbDocument doc = doc(100L);
        when(kbService.processDocument(100L, "RETRY")).thenReturn(doc);

        svc.execute(task, doc);

        assertEquals(IngestTask.STATUS_SUCCESS, task.getStatus());
        verify(taskMapper).updateById(task);
    }

    private static IngestTask task(Long documentId, int attempt, int maxAttempts) {
        IngestTask t = new IngestTask();
        t.setId(1L);
        t.setDocumentId(documentId);
        t.setTaskType(IngestTask.TYPE_UPLOAD);
        t.setStatus(IngestTask.STATUS_QUEUED);
        t.setAttempt(attempt);
        t.setMaxAttempts(maxAttempts);
        return t;
    }

    private static KbDocument doc(Long id) {
        KbDocument d = new KbDocument();
        d.setId(id);
        d.setStatus("PENDING");
        return d;
    }
}
