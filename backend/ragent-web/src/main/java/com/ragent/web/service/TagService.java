package com.ragent.web.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ragent.web.entity.QuestionTag;
import com.ragent.web.entity.Tag;
import com.ragent.web.mapper.QuestionTagMapper;
import com.ragent.web.mapper.TagMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 标签服务
 */
@Service
@RequiredArgsConstructor
public class TagService {

    private final TagMapper tagMapper;
    private final QuestionTagMapper questionTagMapper;

    public List<Tag> listAll() {
        return tagMapper.selectList(new LambdaQueryWrapper<Tag>().orderByAsc(Tag::getId));
    }

    public List<Tag> listByQuestionId(Long questionId) {
        List<Long> tagIds = questionTagMapper.selectList(
                        new LambdaQueryWrapper<QuestionTag>().eq(QuestionTag::getQuestionId, questionId))
                .stream().map(QuestionTag::getTagId).toList();
        if (tagIds.isEmpty()) {
            return List.of();
        }
        return tagMapper.selectBatchIds(tagIds);
    }

    /** 按名称查标签（纯读，不存在返回 null），供 Agent 工具调用 */
    public Tag findByName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        return tagMapper.selectOne(new LambdaQueryWrapper<Tag>().eq(Tag::getName, name.trim()));
    }

    /** 确保标签存在（不存在则创建），返回 tagId 列表 */
    public List<Long> ensureTags(List<String> names) {
        List<Long> ids = new ArrayList<>();
        if (names == null) {
            return ids;
        }
        for (String raw : names) {
            String name = raw == null ? "" : raw.trim();
            if (name.isBlank()) {
                continue;
            }
            Tag tag = tagMapper.selectOne(new LambdaQueryWrapper<Tag>().eq(Tag::getName, name));
            if (tag == null) {
                tag = new Tag();
                tag.setName(name);
                tagMapper.insert(tag);
            }
            ids.add(tag.getId());
        }
        return ids;
    }

    public void linkQuestion(Long questionId, List<Long> tagIds) {
        for (Long tagId : tagIds) {
            QuestionTag qt = new QuestionTag();
            qt.setQuestionId(questionId);
            qt.setTagId(tagId);
            questionTagMapper.insert(qt);
        }
    }
}
