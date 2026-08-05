package com.ragent.ai.service;

import com.ragent.ai.entity.Kb;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 知识库（P9 多知识库：共享池 + 分级管理）。
 * 所有登录用户可见全部库并都可检索；仅 TEACHER/ADMIN 创建/管理；学生只读。
 */
public interface KbService {

    /** 全部可见库（is_default 优先，其余按 id）：含每库 UPLOAD 文档数 */
    List<KbVO> listKbs();

    /** 创建库（重名校验）；ownerId 为创建人（仅记录） */
    Kb create(String name, String description, Long ownerId);

    /** 更新名称/描述（重名校验，排除自身） */
    Kb update(Long id, String name, String description);

    /** 删除库：默认库拒、非空（仍有 UPLOAD 文档）拒 */
    void delete(Long id);

    /** 系统默认库 ID（历史文档/评测文档归此）；迁移未执行时返回 null */
    Long getDefaultKbId();

    /** 校验库存在（不存在抛 404）；null 校验 */
    Kb requireById(Long id);

    /** 知识库展示对象（含每库 UPLOAD 文档数） */
    record KbVO(Long id, String name, String description, boolean isDefault,
                long docCount, LocalDateTime createdAt) {
    }
}
