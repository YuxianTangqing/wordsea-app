package com.sihaishumeng.app.core.checkin

/**
 * 签到奖励档位曲线（批④ 自 web 仓 src/lib/checkin-curve.ts 逐值镜像，M34 站主拍板
 * 「稳步爬升」档位）——⚠️ 数值冻结：与 web 侧同源，改动须双仓同步。
 *
 * 墨点（道具）：第 1-2 天 +1 / 3-6 天 +2 / 7-13 天 +3 / 14-29 天 +4 / 连续 30 天起 +5
 * 侠义（仅正式会员发放）：+1 / +2（连 7）/ +3（连 30）
 * 经验：每天 +5；连续满 7 天当天额外 +10、满 30 天当天额外 +30
 */
data class CheckinTier(
    val at: Int,
    val xp: Int,
    val xpBonus: Int,
    val item: Int,
    val morality: Int,
)

object CheckinCurve {

    val TIERS = listOf(
        CheckinTier(at = 1, xp = 5, xpBonus = 0, item = 1, morality = 1),
        CheckinTier(at = 3, xp = 5, xpBonus = 0, item = 2, morality = 1),
        CheckinTier(at = 7, xp = 5, xpBonus = 10, item = 3, morality = 2),
        CheckinTier(at = 14, xp = 5, xpBonus = 0, item = 4, morality = 2),
        CheckinTier(at = 30, xp = 5, xpBonus = 30, item = 5, morality = 3),
    )

    /** 连续 streak 天对应的档位（表尾往前找第一个 at <= streak；streak<=0 回落首档） */
    fun tierFor(streak: Int): CheckinTier = TIERS.lastOrNull { it.at <= streak } ?: TIERS.first()

    /** 严格大于当前档的下一档（已封顶=null，用于「距下一档还差 N 天」预告） */
    fun nextTier(streak: Int): CheckinTier? = TIERS.firstOrNull { it.at > streak }
}
