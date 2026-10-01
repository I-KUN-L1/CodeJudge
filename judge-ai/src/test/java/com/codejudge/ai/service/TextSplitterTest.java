package com.codejudge.ai.service;

import com.codejudge.ai.config.RagProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TextSplitter 分块器单测（RAG 入库链路的切分语义）。
 *
 * <p>运行：mvn -pl judge-ai -am test
 *
 * <p>覆盖：空输入短路、非法参数钳制（chunkSize<=0 / overlap 越界，且**不得死循环**）、
 * ASCII 词边界（不把英文单词劈开）、CJK 逐字切分、短文本单块。
 */
class TextSplitterTest {

    private RagProperties props;
    private TextSplitter splitter;

    @BeforeEach
    void setUp() {
        props = new RagProperties();
        splitter = new TextSplitter(props);
    }

    @Nested
    @DisplayName("输入短路")
    class ShortCircuit {

        @Test
        @DisplayName("null 与空白文本 → 空列表")
        void blankInputs() {
            assertThat(splitter.split(null, 100, 10)).isEmpty();
            assertThat(splitter.split("", 100, 10)).isEmpty();
            assertThat(splitter.split("   \n\t ", 100, 10)).isEmpty();
        }
    }

    @Nested
    @DisplayName("参数钳制")
    class ParameterClamping {

        @Test
        @DisplayName("chunkSize<=0 → 回退 500，正常产出且不死循环")
        void nonPositiveChunkSize() {
            List<String> chunks = splitter.split(repeat("word ", 300), 0, 10);
            assertThat(chunks).isNotEmpty();
        }

        @Test
        @DisplayName("overlap>=chunkSize → 钳制为 chunkSize/2，步长>=1 不死循环")
        void overlapOutOfBounds() {
            List<String> chunks = splitter.split(repeat("word ", 300), 20, 20);
            assertThat(chunks).isNotEmpty();
            // step = max(1, 20 - 10) = 10，必然推进
            assertThat(chunks.size()).isGreaterThanOrEqualTo(2);
        }

        @Test
        @DisplayName("split(text) 走配置默认值（500/50）")
        void configuredDefaults() {
            props.setChunkSize(30);
            props.setChunkOverlap(5);
            List<String> chunks = splitter.split(repeat("token ", 60));
            assertThat(chunks).isNotEmpty();
            assertThat(chunks.size()).isGreaterThanOrEqualTo(2);
        }
    }

    @Nested
    @DisplayName("切分边界")
    class Boundaries {

        @Test
        @DisplayName("ASCII 文本不劈词：所有 chunk 的 token 都来自原词表")
        void asciiWordsKeptIntact() {
            Set<String> vocabulary = new HashSet<>(Arrays.asList("alpha", "beta", "gamma", "delta"));
            String text = repeat("alpha beta gamma delta ", 40);
            for (String chunk : splitter.split(text, 6, 2)) {
                for (String token : chunk.split("\\s+")) {
                    assertThat(token).isIn(vocabulary);
                }
            }
        }

        @Test
        @DisplayName("CJK 按字切分：短句整块产出，长文可切多块")
        void cjkSplits() {
            String shortText = "二分查找的关键在于单调性";
            assertThat(splitter.split(shortText, 100, 10)).containsExactly(shortText);

            StringBuilder longText = new StringBuilder();
            for (int i = 0; i < 200; i++) {
                longText.append("动态规划的状态转移必须无后效性。");
            }
            assertThat(splitter.split(longText.toString(), 50, 10).size()).isGreaterThanOrEqualTo(2);
        }

        @Test
        @DisplayName("单块场景：不超窗口的文本只产出一块")
        void singleChunk() {
            List<String> chunks = splitter.split("hello world foo bar", 100, 10);
            assertThat(chunks).hasSize(1);
            assertThat(chunks.get(0)).isEqualTo("hello world foo bar");
        }
    }

    private String repeat(String s, int times) {
        return s.repeat(times);
    }
}
