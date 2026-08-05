package com.ragent.ai.mapper;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ragent.ai.entity.IngestTask;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 异步摄取任务表 Mapper。
 * {@link #claim} 是 CAS 认领：只有 QUEUED 能转 RUNNING，多实例/多轮询并发也不会重复消费。
 */
public interface IngestTaskMapper extends BaseMapper<IngestTask> {

    /** CAS 认领：返回 1 表示认领成功（QUEUED→RUNNING），返回 0 表示已被他人认领/已变终态 */
    @Update("UPDATE ingest_task SET status='RUNNING', updated_at=NOW() WHERE id=#{id} AND status='QUEUED'")
    int claim(@Param("id") Long id);

    /** 回写 QUEUED（worker 提交被线程池拒绝时恢复可认领状态） */
    @Update("UPDATE ingest_task SET status='QUEUED', updated_at=NOW() WHERE id=#{id} AND status='RUNNING'")
    int requeue(@Param("id") Long id);

    /** 启动时把陈旧 RUNNING 任务回写 QUEUED（上次进程退出遗留的未完成任务） */
    @Update("UPDATE ingest_task SET status='QUEUED', updated_at=NOW() WHERE status='RUNNING'")
    int requeueAllRunning();

    /** 删除文档时取消其未消费/在途任务（QUEUED/RUNNING → CANCELLED），避免 worker 再写回已删文档 */
    default int cancelQueuedByDocument(Long documentId) {
        return update(null, new LambdaUpdateWrapper<IngestTask>()
                .eq(IngestTask::getDocumentId, documentId)
                .in(IngestTask::getStatus, List.of(IngestTask.STATUS_QUEUED, IngestTask.STATUS_RUNNING))
                .set(IngestTask::getStatus, IngestTask.STATUS_CANCELLED));
    }
}
