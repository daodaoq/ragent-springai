package com.ragent.web.agent;

import cn.dev33.satoken.stp.StpUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragent.common.context.RagentContext;
import com.ragent.common.exception.BusinessException;
import com.ragent.web.entity.Tag;
import com.ragent.web.service.QuestionService;
import com.ragent.web.service.StatsService;
import com.ragent.web.service.TagService;
import com.ragent.web.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Agent 可用工具集（全部只读，返回 JSON 字符串）。
 * 复用领域服务：QuestionService / TagService / UserService / StatsService。
 */
@Component
@RequiredArgsConstructor
public class AgentTools {

    private final QuestionService questionService;
    private final TagService tagService;
    private final UserService userService;
    private final StatsService statsService;
    private final ObjectMapper objectMapper;

    @Tool(description = "按关键词搜索题库问题，返回问题列表（标题、状态、回答数、标签）。")
    public String searchQuestions(@ToolParam(description = "搜索关键词") String keyword,
                                  @ToolParam(description = "返回条数上限，默认 5") Integer limit) {
        int n = limit == null || limit <= 0 ? 5 : Math.min(limit, 20);
        return toJson(questionService.list(1, n, null, keyword).getRecords());
    }

    @Tool(description = "查询问题完整详情（含内容、作者、标签、回答列表）。")
    public String getQuestionDetail(@ToolParam(description = "问题ID") Long id) {
        try {
            return toJson(questionService.detailReadOnly(id));
        } catch (BusinessException e) {
            return toJson(Map.of("error", e.getMessage()));
        }
    }

    @Tool(description = "列出题库全部标签。")
    public String listTags() {
        return toJson(tagService.listAll());
    }

    @Tool(description = "统计某个标签下的问题数量。")
    public String countQuestionsByTag(@ToolParam(description = "标签名") String tagName) {
        Tag tag = tagService.findByName(tagName);
        if (tag == null) {
            return toJson(Map.of("tagName", tagName, "count", 0));
        }
        return toJson(Map.of("tagName", tag.getName(), "count", questionService.list(1, 1, tag.getId(), null).getTotal()));
    }

    @Tool(description = "题库总览统计：问题数、回答数、用户数、标签数。")
    public String getQuestionStats() {
        return toJson(statsService.overview());
    }

    @Tool(description = "当前登录用户信息；未登录时返回 null。")
    public String getCurrentUserInfo() {
        Long uid = null;
        try {
            if (StpUtil.isLogin()) {
                uid = StpUtil.getLoginIdAsLong();
            }
        } catch (Exception ignored) {
            // 异步线程可能无 Sa-Token 上下文，退化为透传的 RagentContext
        }
        if (uid == null) {
            RagentContext ctx = RagentContext.current();
            uid = ctx == null ? null : ctx.userId();
        }
        if (uid == null) {
            return toJson(null);
        }
        return toJson(userService.me(uid));
    }

    private String toJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            return "{}";
        }
    }
}
