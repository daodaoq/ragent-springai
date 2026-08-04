package com.ragent.ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ragent.ai.entity.KbQueryStage;
import com.ragent.ai.mapper.KbQueryStageMapper;
import com.ragent.ai.service.QueryPipelineService;
import com.ragent.ai.service.QueryStage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 阶段配置服务：DB 是运行时真相。首次访问自动播种内置 7 阶段（全启），之后以 DB 行为准。
 */
@Slf4j
@Service
public class QueryPipelineServiceImpl implements QueryPipelineService {

    private final KbQueryStageMapper stageMapper;
    private final Map<String, QueryStage> stageByName;

    public QueryPipelineServiceImpl(KbQueryStageMapper stageMapper, List<QueryStage> stages) {
        this.stageMapper = stageMapper;
        this.stageByName = stages.stream()
                .collect(Collectors.toMap(QueryStage::name, Function.identity()));
    }

    @Override
    public List<StageConfig> listStages() {
        ensureSeeded();
        return stageMapper.selectList(new LambdaQueryWrapper<KbQueryStage>()
                        .orderByAsc(KbQueryStage::getSortOrder))
                .stream()
                .map(s -> new StageConfig(s.getName(),
                        stageByName.containsKey(s.getName())
                                ? stageByName.get(s.getName()).description() : "",
                        Boolean.TRUE.equals(s.getEnabled()),
                        s.getSortOrder() == null ? 0 : s.getSortOrder()))
                .toList();
    }

    @Override
    public void updateStages(List<StageConfig> configs) {
        if (configs == null) {
            return;
        }
        Map<String, KbQueryStage> byName = stageMapper.selectList(null).stream()
                .collect(Collectors.toMap(KbQueryStage::getName, Function.identity()));
        for (StageConfig c : configs) {
            KbQueryStage row = byName.get(c.name());
            if (row == null) {
                row = new KbQueryStage();
                row.setName(c.name());
                stageMapper.insert(row);
                byName.put(c.name(), row);
            }
            row.setEnabled(c.enabled());
            row.setSortOrder(c.sortOrder());
            stageMapper.updateById(row);
        }
    }

    /** 首次访问无行时，用内置默认 7 阶段播种（全启，顺序 context→normalize→intent→rewrite→multiQuery→hyde→entity） */
    private void ensureSeeded() {
        Long count = stageMapper.selectCount(null);
        if (count != null && count > 0) {
            return;
        }
        for (QueryStage stage : stageByName.values()) {
            KbQueryStage row = new KbQueryStage();
            row.setName(stage.name());
            row.setEnabled(true);
            row.setSortOrder(stage.defaultOrder());
            stageMapper.insert(row);
        }
        log.info("查询处理管线阶段配置已播种: {} 个阶段", stageByName.size());
    }
}
