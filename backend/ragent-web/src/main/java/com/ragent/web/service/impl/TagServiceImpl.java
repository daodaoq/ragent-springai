package com.ragent.web.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ragent.web.entity.QuestionTag;
import com.ragent.web.entity.Tag;
import com.ragent.web.mapper.QuestionTagMapper;
import com.ragent.web.mapper.TagMapper;
import com.ragent.web.service.TagService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 标签服务实现
 */
@Service
@RequiredArgsConstructor
public class TagServiceImpl implements TagService {

    private final TagMapper tagMapper;
    private final QuestionTagMapper questionTagMapper;

    @Override
    public List<Tag> listAll() {
        return tagMapper.selectList(new LambdaQueryWrapper<Tag>().orderByAsc(Tag::getId));
    }

    @Override
    public List<Tag> listByQuestionId(Long questionId) {
        List<Long> tagIds = questionTagMapper.selectList(
                        new LambdaQueryWrapper<QuestionTag>().eq(QuestionTag::getQuestionId, questionId))
                .stream().map(QuestionTag::getTagId).toList();
        if (tagIds.isEmpty()) {
            return List.of();
        }
        return tagMapper.selectBatchIds(tagIds);
    }

    @Override
    public Tag findByName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        return tagMapper.selectOne(new LambdaQueryWrapper<Tag>().eq(Tag::getName, name.trim()));
    }

    @Override
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

    @Override
    public void linkQuestion(Long questionId, List<Long> tagIds) {
        for (Long tagId : tagIds) {
            QuestionTag qt = new QuestionTag();
            qt.setQuestionId(questionId);
            qt.setTagId(tagId);
            questionTagMapper.insert(qt);
        }
    }
}
