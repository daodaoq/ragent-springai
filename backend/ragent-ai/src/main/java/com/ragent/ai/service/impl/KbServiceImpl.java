package com.ragent.ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.ragent.ai.entity.Kb;
import com.ragent.ai.entity.KbDocument;
import com.ragent.ai.mapper.KbDocumentMapper;
import com.ragent.ai.mapper.KbMapper;
import com.ragent.ai.service.KbService;
import com.ragent.common.exception.BusinessException;
import com.ragent.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 知识库服务（P9）：共享池 + 分级管理。
 * 所有登录用户可见全部库并都可检索；仅 TEACHER/ADMIN 创建/管理；学生只读。
 * owner_id 仅记录创建人；检索不按 owner 过滤（无成员表）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KbServiceImpl implements KbService {

    private final KbMapper kbMapper;
    private final KbDocumentMapper kbDocumentMapper;

    @Override
    public List<KbVO> listKbs() {
        List<Kb> kbs = kbMapper.selectList(new LambdaQueryWrapper<Kb>()
                .orderByDesc(Kb::getIsDefault)
                .orderByAsc(Kb::getId));
        Map<Long, Long> counts = docCountsByKb();
        return kbs.stream().map(k -> new KbVO(k.getId(), k.getName(), k.getDescription(),
                Boolean.TRUE.equals(k.getIsDefault()), counts.getOrDefault(k.getId(), 0L), k.getCreatedAt()))
                .toList();
    }

    /** 每库 UPLOAD 文档数（GROUP BY kb_id；仅统计未逻辑删除的 UPLOAD 文档，评测样例不计） */
    private Map<Long, Long> docCountsByKb() {
        try {
            List<Map<String, Object>> rows = kbDocumentMapper.selectMaps(new QueryWrapper<KbDocument>()
                    .select("kb_id", "COUNT(*) AS cnt")
                    .eq("source", "UPLOAD")
                    .eq("deleted", 0)
                    .isNotNull("kb_id")
                    .groupBy("kb_id"));
            return rows.stream().collect(Collectors.toMap(
                    r -> Long.valueOf(String.valueOf(r.get("kb_id"))),
                    r -> Long.valueOf(String.valueOf(r.get("cnt")))));
        } catch (Exception e) {
            log.warn("统计知识库文档数失败: {}", e.getMessage());
            return Map.of();
        }
    }

    @Override
    public Kb create(String name, String description, Long ownerId) {
        if (name == null || name.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "知识库名称不能为空");
        }
        if (name.length() > 100) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "知识库名称不能超过 100 字符");
        }
        requireNameUnique(name, null);
        Kb kb = new Kb();
        kb.setName(name.trim());
        kb.setDescription(description == null || description.isBlank() ? null : description.trim());
        kb.setOwnerId(ownerId);
        kb.setIsDefault(false);
        kbMapper.insert(kb);
        log.info("创建知识库: {} (id={}, owner={})", kb.getName(), kb.getId(), ownerId);
        return kb;
    }

    @Override
    public Kb update(Long id, String name, String description) {
        Kb kb = requireById(id);
        if (name != null && !name.isBlank()) {
            if (name.length() > 100) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "知识库名称不能超过 100 字符");
            }
            requireNameUnique(name.trim(), id);
            kb.setName(name.trim());
        }
        if (description != null) {
            kb.setDescription(description.isBlank() ? null : description.trim());
        }
        kbMapper.updateById(kb);
        return kb;
    }

    @Override
    public void delete(Long id) {
        Kb kb = requireById(id);
        if (Boolean.TRUE.equals(kb.getIsDefault())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "默认知识库不可删除");
        }
        Long docCount = kbDocumentMapper.selectCount(new LambdaQueryWrapper<KbDocument>()
                .eq(KbDocument::getKbId, id));
        if (docCount > 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "该知识库下还有 " + docCount + " 个文档，请先删除或移动后再删除知识库");
        }
        kbMapper.deleteById(id);
        log.info("删除知识库: {} (id={})", kb.getName(), id);
    }

    @Override
    public Long getDefaultKbId() {
        Kb kb = kbMapper.selectOne(new LambdaQueryWrapper<Kb>()
                .eq(Kb::getIsDefault, true)
                .last("LIMIT 1"));
        return kb == null ? null : kb.getId();
    }

    @Override
    public Kb requireById(Long id) {
        if (id == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "知识库ID不能为空");
        }
        Kb kb = kbMapper.selectById(id);
        if (kb == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "知识库不存在");
        }
        return kb;
    }

    private void requireNameUnique(String name, Long excludeId) {
        long n = kbMapper.selectCount(new LambdaQueryWrapper<Kb>()
                .eq(Kb::getName, name)
                .ne(excludeId != null, Kb::getId, excludeId));
        if (n > 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "知识库名称已存在");
        }
    }
}
