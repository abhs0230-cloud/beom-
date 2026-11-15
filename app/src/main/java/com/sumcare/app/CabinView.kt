package com.sumcare.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * 차 안팎 공기 흐름 애니메이션 (위에서 내려다본 차)
 * - 바깥 미세먼지 입자: 등급이 나쁠수록 많고 진하다
 * - 창문이 열리면 그 틈으로 바깥 공기가 들어온다 (나쁨: 먼지 입자, 좋음: 상쾌한 바람 줄기)
 * - 내기순환이 켜지면 실내에서 공기가 돈다, 공기청정이 켜지면 실내 먼지가 사라진다
 */
class CabinView(context: Context) : View(context) {

    // ── 외부에서 바꾸는 상태 ──
    var gradeLevel = 0          // 0 좋음, 1 보통, 2 나쁨, 3 매우나쁨
        set(v) { field = v; targetOutside = intArrayOf(14, 30, 80, 130)[v.coerceIn(0, 3)] }
    var gradeColor = 0xFF3B82F6.toInt()
    var recirc = false
    var cleaner = false
    /** 창문 열림 정도 0..1 (앞왼, 앞오, 뒤왼, 뒤오) */
    val windows = floatArrayOf(0f, 0f, 0f, 0f)

    private class Dot(var x: Float, var y: Float, var vx: Float, var vy: Float, var r: Float, var a: Float)
    private class Streak(var x: Float, var y: Float, var vx: Float, var vy: Float, var life: Float)

    private val outside = mutableListOf<Dot>()
    private val inside = mutableListOf<Dot>()
    private val streaks = mutableListOf<Streak>()
    private var targetOutside = 14
    private var spin = 0f
    private var lastFrame = 0L
    private val rnd = Random(7)

    private val dp = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; color = 0xFFE6EDF5.toInt() }

    private val body = RectF()
    private val cabin = RectF()

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val bw = min(w * 0.46f, h * 0.4f)
        val bh = bw * 1.95f
        val cx = w / 2f
        val cy = h * 0.46f
        body.set(cx - bw / 2, cy - bh / 2, cx + bw / 2, cy + bh / 2)
        cabin.set(body.left + bw * 0.12f, body.top + bh * 0.24f, body.right - bw * 0.12f, body.bottom - bh * 0.2f)
    }

    override fun onDraw(c: Canvas) {
        val now = System.nanoTime()
        val dt = if (lastFrame == 0L) 0.016f else ((now - lastFrame) / 1e9f).coerceAtMost(0.05f)
        lastFrame = now
        step(dt)

        // 바깥 먼지
        fill.color = gradeColor
        for (d in outside) {
            fill.alpha = (d.a * 255).toInt()
            c.drawCircle(d.x, d.y, d.r, fill)
        }

        // 차체
        fill.color = 0xFF2A3754.toInt(); fill.alpha = 255
        c.drawRoundRect(body, body.width() * 0.32f, body.width() * 0.32f, fill)
        fill.color = 0xFF0E1628.toInt()
        c.drawRoundRect(cabin, body.width() * 0.16f, body.width() * 0.16f, fill)
        // 앞유리/뒷유리 표시
        fill.color = 0xFF3B4A6B.toInt()
        c.drawRoundRect(RectF(cabin.left + 6 * dp, body.top + body.height() * 0.13f, cabin.right - 6 * dp, cabin.top - 8 * dp), 10 * dp, 10 * dp, fill)
        c.drawRoundRect(RectF(cabin.left + 10 * dp, cabin.bottom + 8 * dp, cabin.right - 10 * dp, body.bottom - body.height() * 0.08f), 10 * dp, 10 * dp, fill)

        // 창문 (양옆 4개)
        for (i in 0..3) {
            val (x, y0, y1) = windowSeg(i)
            stroke.strokeWidth = 7 * dp
            stroke.color = 0xFF55657F.toInt()
            c.drawLine(x, y0, x, y1, stroke)
            val open = windows[i]
            if (open > 0f) {
                stroke.color = if (gradeLevel >= 2) 0xFFF59E0B.toInt() else 0xFF5EEAD4.toInt()
                c.drawLine(x, y0, x, y0 + (y1 - y0) * open, stroke)
            }
        }

        // 바람 줄기 (좋은 공기 유입)
        stroke.strokeWidth = 4 * dp
        for (s in streaks) {
            stroke.color = 0xFF5EEAD4.toInt()
            stroke.alpha = (s.life.coerceIn(0f, 1f) * 200).toInt()
            c.drawLine(s.x, s.y, s.x - s.vx * 0.12f, s.y - s.vy * 0.12f, stroke)
        }
        stroke.alpha = 255

        // 실내 먼지
        fill.color = gradeColor
        for (d in inside) {
            fill.alpha = (d.a * 255).toInt()
            c.drawCircle(d.x, d.y, d.r, fill)
        }

        // 내기순환 화살표
        if (recirc) {
            val cx = cabin.centerX(); val cy = cabin.centerY()
            val r = cabin.width() * 0.3f
            stroke.color = 0xFF5EEAD4.toInt(); stroke.strokeWidth = 6 * dp
            val oval = RectF(cx - r, cy - r, cx + r, cy + r)
            for (k in 0..1) {
                val start = spin + k * 180f
                c.drawArc(oval, start, 120f, false, stroke)
                val ang = Math.toRadians((start + 120f).toDouble())
                val ax = cx + r * cos(ang).toFloat(); val ay = cy + r * sin(ang).toFloat()
                val tang = ang + PI / 2
                val p = Path()
                val hl = 14 * dp
                p.moveTo(ax + hl * cos(tang).toFloat(), ay + hl * sin(tang).toFloat())
                p.lineTo(ax + hl * 0.7f * cos(ang).toFloat() - hl * 0.5f * cos(tang).toFloat(), ay + hl * 0.7f * sin(ang).toFloat() - hl * 0.5f * sin(tang).toFloat())
                p.lineTo(ax - hl * 0.7f * cos(ang).toFloat() - hl * 0.5f * cos(tang).toFloat(), ay - hl * 0.7f * sin(ang).toFloat() - hl * 0.5f * sin(tang).toFloat())
                p.close()
                fill.color = 0xFF5EEAD4.toInt(); fill.alpha = 255
                c.drawPath(p, fill)
            }
        }

        // 상태 설명
        text.textSize = 22 * dp
        c.drawText(statusLine(), width / 2f, height - 18 * dp, text)

        postInvalidateOnAnimation()
    }

    private fun statusLine(): String {
        val anyOpen = windows.any { it > 0f }
        return when {
            anyOpen && gradeLevel >= 2 -> "⚠ 열린 창문으로 미세먼지가 들어오는 중"
            anyOpen -> "환기 중 · 상쾌한 바깥 공기가 들어와요"
            recirc && cleaner && inside.isNotEmpty() -> "내기순환 + 공기청정 · 실내 공기 정화 중"
            recirc -> "내기순환 중 · 바깥 공기 차단"
            gradeLevel >= 2 -> "창문 닫힘 · 외기 유입 중"
            else -> "바깥 공기 양호"
        }
    }

    /** 창문 i의 (x, 시작 y, 끝 y) */
    private fun windowSeg(i: Int): Triple<Float, Float, Float> {
        val left = i % 2 == 0
        val x = if (left) body.left + 3 * dp else body.right - 3 * dp
        val h = cabin.height()
        val (a, b) = if (i < 2) cabin.top + h * 0.05f to cabin.top + h * 0.47f else cabin.top + h * 0.53f to cabin.top + h * 0.92f
        return Triple(x, a, b)
    }

    private fun step(dt: Float) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w == 0f) return
        spin = (spin + dt * 120f) % 360f

        // 바깥 입자 수 맞추기
        while (outside.size < targetOutside) {
            outside += Dot(rnd.nextFloat() * w, rnd.nextFloat() * (h - 40 * dp), 0f, 0f, (3 + rnd.nextFloat() * 5) * dp, 0f)
        }
        if (outside.size > targetOutside) {
            outside.take(outside.size - targetOutside).forEach { it.a -= dt * 1.5f }
            outside.removeAll { it.a <= 0f && outside.size > targetOutside }
        }
        val maxA = if (gradeLevel >= 2) 0.75f else 0.45f
        for (d in outside) {
            d.vx += (rnd.nextFloat() - 0.4f) * 30f * dt
            d.vy += (rnd.nextFloat() - 0.5f) * 30f * dt
            d.vx = d.vx.coerceIn(-15f, 40f) ; d.vy = d.vy.coerceIn(-20f, 20f)
            d.x += d.vx * dp * dt; d.y += d.vy * dp * dt
            if (d.x > w) d.x = 0f; if (d.x < 0) d.x = w
            if (d.y > h - 40 * dp) d.y = 0f; if (d.y < 0) d.y = h - 40 * dp
            if (d.a < maxA) d.a = (d.a + dt).coerceAtMost(maxA)
        }

        // 열린 창문으로 유입
        for (i in 0..3) {
            val open = windows[i]
            if (open <= 0f) continue
            val (x, y0, y1) = windowSeg(i)
            val dir = if (i % 2 == 0) 1f else -1f
            val rate = open * dt * 6f
            if (gradeLevel >= 2) {
                if (inside.size < 90 && rnd.nextFloat() < rate * (1 + gradeLevel)) {
                    inside += Dot(x, y0 + rnd.nextFloat() * (y1 - y0) * open, dir * (60 + rnd.nextFloat() * 50), (rnd.nextFloat() - 0.5f) * 30f, (3 + rnd.nextFloat() * 4) * dp, 0.85f)
                }
            } else if (rnd.nextFloat() < rate * 2.5f) {
                streaks += Streak(x - dir * 30 * dp, y0 + rnd.nextFloat() * (y1 - y0), dir * 260f * dp, (rnd.nextFloat() - 0.5f) * 40f * dp, 1f)
            }
        }

        // 실내 입자
        val purge = (if (recirc) 0.12f else 0f) + (if (cleaner) 0.5f else 0f)
        for (d in inside) {
            d.vx *= 0.97f; d.vy *= 0.97f
            d.vx += (rnd.nextFloat() - 0.5f) * 40f * dt; d.vy += (rnd.nextFloat() - 0.5f) * 40f * dt
            if (recirc) { // 실내에서 원을 그리며 돈다
                val ang = kotlin.math.atan2(d.y - cabin.centerY(), d.x - cabin.centerX())
                d.vx += -sin(ang) * 40f * dt; d.vy += cos(ang) * 40f * dt
            }
            d.x += d.vx * dt; d.y += d.vy * dt
            d.x = d.x.coerceIn(cabin.left + 6 * dp, cabin.right - 6 * dp)
            d.y = d.y.coerceIn(cabin.top + 6 * dp, cabin.bottom - 6 * dp)
            d.a -= purge * dt
        }
        inside.removeAll { it.a <= 0f }

        for (s in streaks) { s.x += s.vx * dt; s.y += s.vy * dt; s.life -= dt * 1.3f }
        streaks.removeAll { it.life <= 0f || !cabin.contains(it.x, it.y) && it.life < 0.6f }
    }

    init { setBackgroundColor(Color.TRANSPARENT) }
}
