package me.rerere.rikkahub.subagent

import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

/**
 * G 批第二片 — 子代理嵌套的规则（对齐 Claude Code 的 depth-capped subagents）。
 *
 * CC 里子代理可以再派子代理，深度由配置封顶（默认 3）。Nazarick 之前是硬拦：`no_recursion` +
 * `subagent_` 前缀整体从子代理工具面里剥掉。现在放开成**有深度上限**的嵌套，上限跟着既有的
 * 派发档位走 —— 用户选的还是那个五档开关，不需要再学一个新旋钮。
 *
 * 这里是纯规则：深度怎么算、能不能再派。真正的拦截在 [SubAgentEngine.dispatch]（它还得看
 * 调用者到底是不是子代理），工具面的过滤在 [SubAgentSurface]。
 *
 * ## 为什么上限要跟着档位走
 *
 * 一个嵌套的子代理是 headless 的：它拿不到审批，工具是自动放行的。所以每多一层，就多一层
 * 「没人看着的自动化」。把上限挂在用户已经理解的档位上（ASSIST = 不嵌套、NORMAL = 两层），
 * 比单独再放一个数字输入更不容易被误开。
 */
object SubAgentNestingRules {

    /** 用户自己的会话：任何一层派发都是从这里出发的。 */
    const val TOP_LEVEL_DEPTH: Int = 0

    /**
     * 档位 → 允许的最大 run 深度。
     *
     * - `NEVER` 0：派发本身被禁，深度无意义；
     * - `ASSIST` 1：用户会话可以直接派子代理，子代理不能再派（= 没有嵌套）；
     * - `NORMAL` 2：子代理可以再派一层孙代理（CC 的默认节奏）；
     * - `BURST` 3：再深一层，给大扇出用；
     * - `UNLEASHED` 4：封顶，再深收益递减而失控风险上升。
     */
    fun capFor(profile: SubAgentDispatchProfile): Int = when (profile) {
        SubAgentDispatchProfile.NEVER -> 0
        SubAgentDispatchProfile.ASSIST -> 1
        SubAgentDispatchProfile.NORMAL -> 2
        SubAgentDispatchProfile.BURST -> 3
        SubAgentDispatchProfile.UNLEASHED -> 4
    }

    /** 一个深度为 [parentDepth] 的 run 派出去的子代理，深度是父 + 1。 */
    fun childDepth(parentDepth: Int): Int = parentDepth + 1

    /**
     * 深度为 [parentDepth] 的 run 现在还能不能再派一个。
     *
     * 判据是「子代理的深度是否会超过上限」：上限 2 时，深度 1 的 run 还能派出深度 2 的子代理，
     * 深度 2 的 run 就不能再派了。
     */
    fun canSpawn(parentDepth: Int, cap: Int): Boolean = childDepth(parentDepth) <= cap
}

/**
 * 进程级的「这个会话是第几层子代理」表。
 *
 * 深度只对**正在跑的**子代理会话有意义：会话本身是持久的，但「它是第几层」这件事只有在它的
 * run 活着的时候才需要 —— 一个结束的 run 被 `subagent_send` 续上时，深度从 run 记录里恢复。
 *
 * 键是会话 id，值是深度。没有记录 = 不是子代理（用户会话 / cron / workflow）。
 * [SubAgentEngine] 在创建（或续上）子代理会话时写入，run 结束时清掉。
 */
object SubAgentDepths {

    /**
     * 一个子代理会话在树里的位置。
     *
     * [rootConversationId] 是这棵树的根（用户会话）。它存在的意义是**预算**：用量表按
     * `parent_run_id` 汇总，如果每层只记自己的父会话，祖父就看不到孙子的花费，嵌套会成为一个
     * 绕过 orchestration budget 的口子。所以整棵树都记同一个根。
     */
    data class Placement(val depth: Int, val rootConversationId: String)

    private val byConversation = ConcurrentHashMap<Uuid, Placement>()

    fun mark(conversationId: Uuid, depth: Int, rootConversationId: String) {
        byConversation[conversationId] = Placement(
            depth = depth.coerceAtLeast(SubAgentNestingRules.TOP_LEVEL_DEPTH),
            rootConversationId = rootConversationId,
        )
    }

    fun placementOf(conversationId: Uuid): Placement? = byConversation[conversationId]

    /** 这个会话的深度；null = 它不是一个正在跑的子代理会话。 */
    fun depthOf(conversationId: Uuid): Int? = byConversation[conversationId]?.depth

    fun release(conversationId: Uuid) {
        byConversation.remove(conversationId)
    }

    /** Test hook — never called from production code. */
    fun clearAll() {
        byConversation.clear()
    }
}
