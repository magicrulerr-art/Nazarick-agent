package me.rerere.rikkahub.subagent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * 长跑用的子代理规则（能力对齐 Claude Code 的 SendMessage / 并行 fan-out）。
 *
 * 两件事，都是**纯函数**，所以能在裸 JVM 上逐条钉住（见 `SubAgentLongRunRulesTest`）：
 *
 * 1. **续聊**：一个已经结束的子代理可以被同一个父会话再推一句话，保留它原来的上下文、
 *    工具白名单和专家身份。CC 里这是 `SendMessage(agent_id, ...)`。
 * 2. **并行 fan-out**：一次调用里给出 N 个互相独立的任务，并发跑完再一起返回。CC 里这是
 *    一条消息里并发多个 Task 调用；Nazarick 的生成循环是顺序执行工具调用的，所以并发必须由
 *    dispatch 自己提供（见 [SubAgentFanoutRules]）。
 *
 * 这里只放「能不能续」「怎么把 fan-out 的 JSON 拆成请求」这类判断，真正的执行仍在
 * [SubAgentEngine] 里 —— 规则可测，执行不可测（要 ChatService），两者分开。
 */
object SubAgentResumeRules {

    /**
     * 能续聊的状态。刻意排除 PENDING / RUNNING（并发跑着再推一句会打架）和 CANCELLED
     * （用户明确掐掉的，不应被一句话复活）。
     */
    private val RESUMABLE = setOf(
        SubAgentStatus.SUCCEEDED,
        SubAgentStatus.FAILED,
        SubAgentStatus.TIMED_OUT,
    )

    /** 续聊的判定结果：可以，或一句人话的原因。 */
    sealed interface Verdict {
        object Ok : Verdict
        data class Refused(val code: String, val reason: String) : Verdict
    }

    /**
     * [run] 现在能不能被续聊。
     *
     * 除了状态，还要求这次 run 留下了执行会话 —— 没有会话就没有历史，续聊等于新开一个，
     * 那应该走 `subagent_dispatch` 而不是 `subagent_send`。
     */
    fun canResume(run: SubAgentRun): Verdict {
        if (run.status !in RESUMABLE) {
            return Verdict.Refused(
                "not_terminal",
                "run ${run.id} is ${run.status.name}; only a finished run (SUCCEEDED / FAILED / TIMED_OUT) can be continued",
            )
        }
        if (run.conversationId.isNullOrBlank()) {
            return Verdict.Refused(
                "no_conversation",
                "run ${run.id} has no execution conversation (it was recorded before conversation tracking); dispatch a new task instead",
            )
        }
        return Verdict.Ok
    }

    /**
     * 把「续聊一句话」组装成一次新的 run 请求。
     *
     * 继承原 run 的模型 / 专家 / 工具白名单 / 步数上限 / 思考深度 —— 续聊是同一个子代理接着说，
     * 不是换个配置重开。超时与后台开关允许本次覆盖（一个追问问 3 分钟，不该复用上轮的 30 分钟）。
     */
    fun resumeRequest(
        run: SubAgentRun,
        message: String,
        timeoutSeconds: Int? = null,
        runInBackground: Boolean = false,
    ): SubAgentRequest = SubAgentRequest(
        task = message,
        modelId = run.modelId,
        agentName = run.agentName,
        tools = run.tools,
        runInBackground = runInBackground,
        timeoutSeconds = timeoutSeconds ?: run.timeoutSeconds,
        maxTrips = run.maxTrips,
        reasoningLevel = run.reasoningLevel,
        label = run.label,
        // 续聊不再重新引用父会话的历史：它自己的会话就是历史。
        contextRefs = null,
        attachParentMedia = false,
    )
}

/**
 * fan-out 的解析与上限。
 *
 * 形状刻意简单 —— `tasks` 是一个对象数组，每个对象就是一次 `subagent_dispatch` 的参数子集。
 * 不给每个任务单独写 schema，因为模型已经会写 dispatch 的参数了，复用它的习惯比记两套便宜。
 */
object SubAgentFanoutRules {

    /** 一次 fan-out 最多几个任务。再多的收益递减，而并发闸门本来就会让它们排队。 */
    const val MAX_TASKS: Int = 6

    /** 单个任务文本的上限（防止把整篇文档塞进一次派发）。 */
    const val MAX_TASK_CHARS: Int = 4_000

    /** 解析结果。 */
    sealed interface Plan {
        data class Ok(val requests: List<SubAgentRequest>) : Plan
        data class Invalid(val code: String, val reason: String) : Plan
    }

    /**
     * 把 tool 调用里的 `tasks` 数组解析成 [SubAgentRequest] 列表。
     *
     * 所有校验都在这里做（数量、空白、单条长度），因为一旦开始并发执行，半路失败就只剩
     * 一半结果 —— 宁可整批拒掉让模型重写。
     */
    fun plan(
        tasks: JsonArray?,
        runInBackground: Boolean,
        defaultTimeoutSeconds: Int? = null,
        defaultMaxTrips: Int? = null,
    ): Plan {
        if (tasks == null || tasks.isEmpty()) {
            return Plan.Invalid("missing_tasks", "tasks must be a non-empty array of {task, ...} objects")
        }
        if (tasks.size > MAX_TASKS) {
            return Plan.Invalid(
                "too_many_tasks",
                "tasks has ${tasks.size} entries; at most $MAX_TASKS per fan-out call (split it into two calls)",
            )
        }
        val requests = ArrayList<SubAgentRequest>(tasks.size)
        tasks.forEachIndexed { index, element ->
            val obj = element as? JsonObject
                ?: return Plan.Invalid("invalid_task", "tasks[$index] must be an object")
            val task = obj["task"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (task.isEmpty()) {
                return Plan.Invalid("invalid_task", "tasks[$index].task is required and must not be blank")
            }
            if (task.length > MAX_TASK_CHARS) {
                return Plan.Invalid(
                    "task_too_long",
                    "tasks[$index].task is ${task.length} chars; keep each task under $MAX_TASK_CHARS",
                )
            }
            requests += SubAgentRequest(
                task = task,
                modelId = obj["model_id"]?.jsonPrimitive?.contentOrNull,
                agentName = obj["agent"]?.jsonPrimitive?.contentOrNull,
                tools = null,
                runInBackground = runInBackground,
                noResult = obj["no_result"]?.jsonPrimitive?.booleanOrNull ?: false,
                timeoutSeconds = obj["timeout_seconds"]?.jsonPrimitive?.intOrNull
                    ?: defaultTimeoutSeconds
                    ?: SubAgentDefaults.resolveTimeoutSeconds(null, runInBackground),
                maxTrips = obj["max_trips"]?.jsonPrimitive?.intOrNull
                    ?: defaultMaxTrips
                    ?: SubAgentDefaults.DEFAULT_MAX_TRIPS,
                label = obj["label"]?.jsonPrimitive?.contentOrNull,
            )
        }
        return Plan.Ok(requests)
    }
}
