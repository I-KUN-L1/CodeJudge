package com.codejudge.ai.service;

import com.codejudge.ai.config.RagProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 文本分块器：按 token 滑动窗口切分，相邻块保留 overlap 重叠，避免切断语义。
 * （复用自底座 {@code zx-aigc} 的同名类，仅更换配置类与包名。）
 *
 * <p>token 估算规则（近似）：CJK 字符每个计 1 token；连续 ASCII/数字串按空白分词后每个计 1 token。
 *
 * <p><b>切分边界对齐「词」而非「字符」</b>：{@link #nextBoundary} 在累计达到窗口大小时
 * 落在 token 边界上，因此不会把一个英文单词或一个中文词从中间劈开 ——
 * 这对代码片段尤其重要：「complie」这种被劈开再拼接产生的伪标识符
 * 会显著干扰向量语义与模型理解。
 */
@Component
public class TextSplitter {

    private final RagProperties properties;

    public TextSplitter(RagProperties properties) {
        this.properties = properties;
    }

    /** 使用配置的 chunkSize / overlap 切分 */
    public List<String> split(String text) {
        return split(text, properties.getChunkSize(), properties.getChunkOverlap());
    }

    /** 按 chunkSize（token 窗口）+ overlap（token 重叠）滑动切分 */
    public List<String> split(String text, int chunkSize, int overlap) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return chunks;
        }
        if (chunkSize <= 0) {
            chunkSize = 500;
        }
        if (overlap < 0 || overlap >= chunkSize) {
            overlap = Math.max(0, chunkSize / 2);
        }
        int step = Math.max(1, chunkSize - overlap);
        int n = text.length();
        int windowStart = 0;

        while (windowStart < n) {
            int windowEnd = nextBoundary(text, windowStart, chunkSize);
            String chunk = text.substring(windowStart, windowEnd).trim();
            if (!chunk.isEmpty()) {
                chunks.add(chunk);
            }
            if (windowEnd >= n) {
                break;
            }
            int nextStart = nextBoundary(text, windowStart, step);
            if (nextStart <= windowStart) {
                nextStart = windowStart + 1;
            }
            if (nextStart >= n) {
                break;
            }
            windowStart = nextStart;
        }
        return chunks;
    }

    /**
     * 从 fromIndex 开始扫描，返回「累计达到 targetTokens 个 token」的字符边界（含）。
     */
    private int nextBoundary(String text, int fromIndex, int targetTokens) {
        int count = 0;
        int i = fromIndex;
        int n = text.length();
        while (i < n) {
            int codePoint = text.codePointAt(i);
            int charLen = Character.charCount(codePoint);
            if (isCjk(codePoint) || isSinglePunct(codePoint)) {
                count++;
                i += charLen;
                if (count >= targetTokens) {
                    return i;
                }
            } else {
                // 连续 ASCII/数字串看成一个 token
                while (i < n && !isWordBoundary(text, i)) {
                    i += Character.charCount(text.codePointAt(i));
                }
                count++;
                if (count >= targetTokens) {
                    return i;
                }
                // 跳过该 token 后的空白/分隔
                while (i < n && Character.isWhitespace(text.codePointAt(i))) {
                    i += Character.charCount(text.codePointAt(i));
                }
            }
        }
        return n;
    }

    private boolean isWordBoundary(String text, int i) {
        int codePoint = text.codePointAt(i);
        return Character.isWhitespace(codePoint) || isCjk(codePoint) || isSinglePunct(codePoint);
    }

    private boolean isCjk(int codePoint) {
        return (codePoint >= 0x4E00 && codePoint <= 0x9FFF)
                || (codePoint >= 0x3400 && codePoint <= 0x4DBF)
                || (codePoint >= 0xF900 && codePoint <= 0xFAFF);
    }

    private boolean isSinglePunct(int codePoint) {
        String s = new String(Character.toChars(codePoint));
        return "，。！？；：、（）《》「」【】,.!?;:".contains(s);
    }
}
