package com.codejudge.api.dto.submission;

/**
 * 支持的判题语言（P3）。
 *
 * <p>与 sandbox/docker 下四个判题镜像一一对应：
 * JAVA → judge-java21 ｜ PYTHON → judge-python3.12 ｜ CPP → judge-gcc13 ｜ GO → judge-go1.22。
 * 前端提交时 language 字段必须取本枚举的 {@code name()}。
 */
public enum Language {

    JAVA("Main.java", true),
    PYTHON("main.py", false),
    CPP("main.cpp", true),
    GO("main.go", true);

    /** 源码文件名（沙箱内统一落盘名，编译/运行命令依赖它） */
    private final String sourceFile;

    /** 是否需要编译阶段（PYTHON 解释执行，无 CE） */
    private final boolean compiled;

    Language(String sourceFile, boolean compiled) {
        this.sourceFile = sourceFile;
        this.compiled = compiled;
    }

    public String getSourceFile() {
        return sourceFile;
    }

    public boolean isCompiled() {
        return compiled;
    }

    /** 解析失败返回 null，调用方据此报「不支持的语言」 */
    public static Language of(String name) {
        if (name == null) {
            return null;
        }
        try {
            return Language.valueOf(name.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
