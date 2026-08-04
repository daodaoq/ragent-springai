package com.ragent.ai.service.impl;

import java.util.regex.Pattern;

/**
 * 查询规范化（A 阶段）：去掉开头/结尾口头禅、全半角归一、合并空白。纯规则，恒定生效。
 */
public final class QueryNormalizer {

    /** 开头口头禅：请/帮我/请问/你好… */
    private static final Pattern LEADING_FILLER = Pattern.compile("^(?:请|帮我|麻烦|请问|你好|您好|帮忙|求)+");
    /** 结尾口头禅：谢谢/感谢/一下/看看… */
    private static final Pattern TRAILING_FILLER = Pattern.compile("(?:谢谢|感谢|多谢|辛苦了|一下|看看|呗|哦)+$");

    private QueryNormalizer() {
    }

    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim();
        s = LEADING_FILLER.matcher(s).replaceFirst("");
        s = TRAILING_FILLER.matcher(s).replaceFirst("");
        s = fullToHalf(s);
        s = s.replaceAll("[\\s\\u00A0]+", " ").trim();
        return s;
    }

    /** 常见全角标点/ASCII → 半角（保留中文句读）。 */
    private static String fullToHalf(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '　' -> sb.append(' ');    // 全角空格
                case '：' -> sb.append(':');
                case '；' -> sb.append(';');
                case '，' -> sb.append(',');
                case '（' -> sb.append('(');
                case '）' -> sb.append(')');
                case '！' -> sb.append('!');
                case '？' -> sb.append('?');
                case '“', '”' -> sb.append('"');
                case '‘', '’' -> sb.append('\'');
                default -> {
                    if (c >= '！' && c <= '～') {
                        sb.append((char) (c - 0xFEE0));  // 全角 ASCII 块 → 半角
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
