package com.weskestis.astrastudio.fx

import kotlin.math.*

/** RBJ biquad magnitude, matching the native EQ's steady-state coefficients. */
object EqResponse {
    fun db(config: FxConfig, frequency: Double, rate: Double, channel: Int): Float {
        if (config[FxSchema.EqOn] == 0f) return 0f
        val p = config.effective()
        var result = 0.0
        repeat(8) { band ->
            val at = FxSchema.Eq1On + band * 6
            if (p[at] == 0f || (p[FxSchema.EqMs] != 0f && p[at+5] != 0f && p[at+5].toInt() != channel)) return@repeat
            val w = 2 * PI * p[at+2].toDouble().coerceIn(10.0, .45 * rate) / rate
            val c = cos(w); val alpha = sin(w) / (2 * p[at+4]); val a = 10.0.pow(p[at+3] / 40.0)
            val beta = 2 * sqrt(a) * alpha
            val co = when (p[at+1].toInt()) {
                0 -> doubleArrayOf(1+alpha*a, -2*c, 1-alpha*a, 1+alpha/a, -2*c, 1-alpha/a)
                1 -> doubleArrayOf(a*((a+1)-(a-1)*c+beta),2*a*((a-1)-(a+1)*c),a*((a+1)-(a-1)*c-beta),
                    (a+1)+(a-1)*c+beta,-2*((a-1)+(a+1)*c),(a+1)+(a-1)*c-beta)
                2 -> doubleArrayOf(a*((a+1)+(a-1)*c+beta),-2*a*((a-1)+(a+1)*c),a*((a+1)+(a-1)*c-beta),
                    (a+1)-(a-1)*c+beta,2*((a-1)-(a+1)*c),(a+1)-(a-1)*c-beta)
                3 -> doubleArrayOf((1-c)*.5,1-c,(1-c)*.5,1+alpha,-2*c,1-alpha)
                4 -> doubleArrayOf((1+c)*.5,-(1+c),(1+c)*.5,1+alpha,-2*c,1-alpha)
                else -> doubleArrayOf(1.0,-2*c,1.0,1+alpha,-2*c,1-alpha)
            }
            val theta = 2 * PI * frequency / rate
            fun power(offset: Int): Double {
                val re = co[offset] + co[offset+1]*cos(theta) + co[offset+2]*cos(2*theta)
                val im = -co[offset+1]*sin(theta) - co[offset+2]*sin(2*theta)
                return max(1e-24,re*re+im*im)
            }
            result += 10 * log10(power(0)/power(3))
        }
        return result.toFloat().coerceIn(-96f,96f)
    }
}
