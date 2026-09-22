package com.codejudge.submission.service;

import com.codejudge.api.dto.submission.SubmissionProgressMessage;
import com.codejudge.api.dto.submission.SubmissionResultMessage;
import com.codejudge.common.ws.RedisPushChannel;
import com.codejudge.common.ws.WsEnvelope;
import com.codejudge.common.ws.WsMessageType;
import com.codejudge.common.ws.WsSequencer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 判题进度/结果推送（judge-submission 侧出口）。
 *
 * <p><b>推送路径</b>：MQ 消费 → 本服务构造报文 → Redis 广播通道 → 各实例投递给
 * 自己持有的会话。之所以不直接调会话注册表：用户可能连在本服务的**另一个实例**上，
 * 只有广播才能保证「无论连到哪台都能收到」。
 *
 * <p><b>频率控制（消费端闸门）</b>：
 * <ul>
 *   <li>生产端（worker）已按最小间隔丢弃中间态，这里不再重复削峰；</li>
 *   <li>但 MQ 至少一次投递会带来重复消费，因此按「submissionId + 阶段签名」去重，
 *       避免同一进度被推两遍（客户端会看到进度回退又前进的抖动）；</li>
 *   <li>终态推送后清理去重表，防止长跑进程的内存随提交数增长。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SubmissionProgressPushService {

    private final RedisPushChannel pushChannel;
    private final WsSequencer sequencer;

    /** submissionId -> 上次推送的内容签名（去重） */
    private final Map<Long, String> lastSignature = new ConcurrentHashMap<>();

    /** 推送主题：一条提交一个主题，订阅者只有提交者本人与教师/管理员 */
    public String topic(Long submissionId) {
        return "sub:" + submissionId;
    }

    /** 推送判题进度（MQ tag=PROGRESS） */
    public void pushProgress(SubmissionProgressMessage msg) {
        if (msg == null || msg.getSubmissionId() == null) {
            return;
        }
        String signature = "P|" + msg.getStage() + "|" + msg.getCaseSeq() + "|" + msg.getVerdict();
        if (signature.equals(lastSignature.get(msg.getSubmissionId()))) {
            log.debug("进度内容重复，跳过推送：submissionId={} stage={}", msg.getSubmissionId(), msg.getStage());
            return;
        }
        lastSignature.put(msg.getSubmissionId(), signature);
        String topic = topic(msg.getSubmissionId());
        WsEnvelope envelope = WsEnvelope.of(WsMessageType.SUB_PROGRESS, topic, msg);
        envelope.setSeq(sequencer.next(topic));
        pushChannel.publish(envelope);
    }

    /** 推送判题终态（MQ tag=RESULT）；终态之后不再有该提交的进度推送 */
    public void pushResult(SubmissionResultMessage msg) {
        if (msg == null || msg.getSubmissionId() == null) {
            return;
        }
        String signature = "R|" + msg.getVerdict() + "|" + msg.getScore();
        if (signature.equals(lastSignature.get(msg.getSubmissionId()))) {
            return;
        }
        lastSignature.remove(msg.getSubmissionId());
        String topic = topic(msg.getSubmissionId());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("submissionId", msg.getSubmissionId());
        data.put("status", "SUCCESS");
        data.put("verdict", msg.getVerdict());
        data.put("score", msg.getScore());
        data.put("timeMs", msg.getTimeMs());
        data.put("memoryKb", msg.getMemoryKb());
        data.put("passedCount", msg.getPassedCount());
        data.put("totalCount", msg.getTotalCount());
        data.put("contestId", msg.getContestId());
        WsEnvelope envelope = WsEnvelope.of(WsMessageType.SUB_RESULT, topic, data);
        envelope.setSeq(sequencer.next(topic));
        pushChannel.publish(envelope);
        log.info("推送判题结果：submissionId={} verdict={} score={}",
                msg.getSubmissionId(), msg.getVerdict(), msg.getScore());
    }

    /** 连接即推的快照（不经 MQ，直接单发）；同时把当前版本号对齐给客户端 */
    public long currentVersion(Long submissionId) {
        return sequencer.current(topic(submissionId));
    }
}
