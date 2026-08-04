package com.ragent.web.service;

import com.ragent.web.entity.Tag;

import java.util.List;

/**
 * 标签服务
 */
public interface TagService {

    List<Tag> listAll();

    List<Tag> listByQuestionId(Long questionId);

    /** 按名称查标签（纯读，不存在返回 null），供 Agent 工具调用 */
    Tag findByName(String name);

    /** 确保标签存在（不存在则创建），返回 tagId 列表 */
    List<Long> ensureTags(List<String> names);

    void linkQuestion(Long questionId, List<Long> tagIds);
}
