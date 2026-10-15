package com.jianyu.ds_pet.pet

import android.os.Handler
import android.os.SystemClock

/** 余额和碎碎念的周期。只在亮屏、并且已经保存密钥时排队。 */
internal class PetAiScheduler(
    private val clock: Clock,
    private val enabled: () -> Boolean,
    private val balancePeriodMs: () -> Long,
    private val whisperPeriodMs: () -> Long,
    private val petIds: () -> List<String>,
    private val refreshBalance: () -> Unit,
    private val publishWhisper: (String) -> Unit,
) {
    interface Clock {
        fun now(): Long
        fun post(delayMs: Long, task: Runnable)
        fun cancel(task: Runnable)
    }

    private var alive = false
    private var screenOn = true
    private var lastBalanceAt = Long.MIN_VALUE
    private var lastWhisperAt = Long.MIN_VALUE
    private val balanceTask = Runnable { onBalance() }
    private val whisperTask = Runnable { onWhisper() }

    fun start(screenOn: Boolean) {
        alive = true
        this.screenOn = screenOn
        armBalance()
        armWhisper()
    }

    fun setScreenOn(on: Boolean) {
        screenOn = on
        if (!alive) return
        if (on) {
            armBalance()
            armWhisper()
        } else {
            clock.cancel(balanceTask)
            clock.cancel(whisperTask)
        }
    }

    /** 换了一把密钥：马上再查一次余额，碎碎念仍按原来的周期。 */
    fun kickBalance() {
        lastBalanceAt = Long.MIN_VALUE
        if (!alive) return
        armBalance()
    }

    /** 刚保存或清除密钥：下一拍马上跑；没有密钥时把已排队的周期拿掉。 */
    fun kick() {
        lastBalanceAt = Long.MIN_VALUE
        lastWhisperAt = Long.MIN_VALUE
        if (!alive) return
        clock.cancel(balanceTask)
        clock.cancel(whisperTask)
        armBalance()
        armWhisper()
    }

    fun stop() {
        alive = false
        clock.cancel(balanceTask)
        clock.cancel(whisperTask)
    }

    private fun armBalance() {
        clock.cancel(balanceTask)
        if (!canRun()) return
        clock.post(delayUntil(lastBalanceAt, balancePeriodMs()), balanceTask)
    }

    private fun armWhisper() {
        clock.cancel(whisperTask)
        if (!canRun() || petIds().isEmpty()) return
        clock.post(delayUntil(lastWhisperAt, whisperPeriodMs()), whisperTask)
    }

    private fun onBalance() {
        if (!canRun()) return
        lastBalanceAt = clock.now()
        refreshBalance()
        armBalance()
    }

    private fun onWhisper() {
        if (!canRun()) return
        lastWhisperAt = clock.now()
        for (id in petIds()) publishWhisper(id)
        armWhisper()
    }

    private fun canRun(): Boolean = alive && screenOn && enabled()

    private fun delayUntil(lastAt: Long, periodMs: Long): Long {
        val period = periodMs.coerceAtLeast(1000L)
        if (lastAt == Long.MIN_VALUE) return 0L
        val elapsed = clock.now() - lastAt
        return if (elapsed >= period) 0L else period - elapsed
    }
}

internal class MainClock(private val handler: Handler) : PetAiScheduler.Clock {
    private val posted = HashMap<Runnable, Runnable>()

    override fun now(): Long = SystemClock.elapsedRealtime()

    override fun post(delayMs: Long, task: Runnable) {
        cancel(task)
        val wrapped = Runnable { task.run() }
        posted[task] = wrapped
        handler.postDelayed(wrapped, delayMs.coerceAtLeast(0L))
    }

    override fun cancel(task: Runnable) {
        posted.remove(task)?.let { handler.removeCallbacks(it) }
    }
}
