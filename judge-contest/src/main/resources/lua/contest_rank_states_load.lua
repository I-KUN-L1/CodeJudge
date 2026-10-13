--[[
  批量读取用户逐题状态（Redis Lua）。
  ============================================================================
  为什么用 Lua 而不是 Spring Data Redis 的 executePipelined：
    Lettuce 的 pipeline 需要独占连接（无法与其他命令在共享原生连接上多路复用），
    高并发读榜时每个请求都会回退**新建一条专用 Redis 连接** —— 2026-10-10 实测
    50 并发下 15 秒新建 2.8 万条连接，宿主临时端口耗尽后 connect 报
    EADDRNOTAVAIL（Cannot assign requested address），榜单查询大面积失败。
    EVALSHA 是普通命令，与其他命令一样在共享连接上多路复用，从根上消除连接风暴；
    「N 次 HGETALL 往返压到 1 次」的收益与 pipeline 完全相同。

  KEYS[i] = judge:contest:user:{contestId}:{userId}（封榜视图时为冻结副本 key）
  ARGV    = 无

  返回：与 KEYS 等长的 JSON 字符串数组，第 i 个元素是 KEYS[i] 的 Hash 内容
        （cjson 编码的 object：field=problemId，value=状态串）。
        不存在的 key / 空 Hash 返回 '{}'——**必须逐 key 占位**，
        Java 侧按下标把结果映射回 userIds，错位即张冠李戴。
--]]

local out = {}
for i = 1, #KEYS do
    local all = redis.call('HGETALL', KEYS[i])
    if #all == 0 then
        out[i] = '{}'
    else
        local m = {}
        for j = 1, #all, 2 do
            m[all[j]] = all[j + 1]
        end
        out[i] = cjson.encode(m)
    end
end
return out
