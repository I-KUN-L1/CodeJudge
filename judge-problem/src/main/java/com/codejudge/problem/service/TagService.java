package com.codejudge.problem.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.codejudge.common.exceptions.BadRequestException;
import com.codejudge.common.exceptions.BizIllegalException;
import com.codejudge.common.utils.StringUtils;
import com.codejudge.common.utils.UserContext;
import com.codejudge.problem.domain.dto.TagFormDTO;
import com.codejudge.problem.domain.po.ProblemTag;
import com.codejudge.problem.domain.po.Tag;
import com.codejudge.problem.domain.vo.TagVO;
import com.codejudge.problem.mapper.ProblemTagMapper;
import com.codejudge.problem.mapper.TagMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 标签服务
 *
 * <p>标签是全局共享字典（{@code uk_tag_name} 保证不重名），与题目多对多。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TagService {

    /** 标签类型缺省值 */
    private static final String DEFAULT_TYPE = "ALGORITHM";

    private final TagMapper tagMapper;
    private final ProblemTagMapper problemTagMapper;

    /**
     * 标签全量列表（登录用户可见）。
     * <p>标签数量有限（算法分类通常几十个），一次全量返回，不做分页。
     */
    public List<TagVO> listAll(String type) {
        LambdaQueryWrapper<Tag> wrapper = new LambdaQueryWrapper<Tag>()
                .eq(StringUtils.isNotBlank(type), Tag::getType, type)
                .orderByAsc(Tag::getId);
        return tagMapper.selectList(wrapper).stream()
                .map(t -> TagVO.of(t.getId(), t.getName(), t.getType()))
                .toList();
    }

    /**
     * 新建标签（管理员）。
     * <p>先查重给出可读提示；并发的极端情况下由 {@code uk_tag_name} 兜底，
     * 把 {@link DuplicateKeyException} 翻译成同一句业务提示，避免暴露 500。
     */
    public Long create(TagFormDTO form) {
        if (form == null || StringUtils.isBlank(form.getName())) {
            throw new BadRequestException("标签名不能为空");
        }
        String name = form.getName().trim();
        if (name.length() > 64) {
            throw new BadRequestException("标签名长度不能超过 64");
        }
        Long exists = tagMapper.selectCount(new LambdaQueryWrapper<Tag>().eq(Tag::getName, name));
        if (exists != null && exists > 0) {
            throw new BizIllegalException("标签已存在：" + name);
        }
        Tag tag = new Tag();
        tag.setName(name);
        tag.setType(StringUtils.isBlank(form.getType()) ? DEFAULT_TYPE : form.getType().trim());
        tag.setCreater(UserContext.getUser());
        try {
            tagMapper.insert(tag);
        } catch (DuplicateKeyException e) {
            throw new BizIllegalException("标签已存在：" + name);
        }
        log.info("新建标签：id={}, name={}, type={}", tag.getId(), name, tag.getType());
        return tag.getId();
    }

    /**
     * 删除标签（管理员）。
     *
     * <p>策略：**被任何题目引用时拒绝删除**，而不是级联摘除题目标签。
     * 理由是级联会让"某道题突然少了个标签"这种副作用发生在无人察觉的静默路径上；
     * 明确报错让管理员先解绑更安全。
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        Tag tag = tagMapper.selectById(id);
        if (tag == null) {
            throw new BadRequestException("标签不存在");
        }
        Long used = problemTagMapper.selectCount(
                new LambdaQueryWrapper<ProblemTag>().eq(ProblemTag::getTagId, id));
        if (used != null && used > 0) {
            throw new BizIllegalException("该标签已被 " + used + " 道题目使用，请先解除关联再删除");
        }
        // 物理删除：uk_tag_name 不含 deleted 列，逻辑删除会导致同名标签无法重建
        tagMapper.deletePhysicallyById(id);
        log.info("删除标签：id={}, name={}, operatorId={}", id, tag.getName(), UserContext.getUserId());
    }
}
