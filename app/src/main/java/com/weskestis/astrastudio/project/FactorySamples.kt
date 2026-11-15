package com.weskestis.astrastudio.project

import java.io.File
import java.io.BufferedOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.*

/** Original deterministic percussion. No downloaded recordings or licensed loops. */
object FactorySamples {
    val names=listOf("Sub kick","Punch kick","Heavy snare","Tight snare","Clap","Rim","Closed hat","Metal hit","Low tom","High tom","Open hat","Ride","Crash","Reverse crash","808 sub","Noise rise")
    private val seconds=listOf(.7,.45,.5,.26,.35,.18,.12,.55,.5,.35,.8,1.0,1.6,1.6,2.8,1.8)
    fun write(index: Int,file: File,cancel: AtomicBoolean) {
        require(index in names.indices)
        val rate=48000;val frames=(seconds[index]*rate).toInt();var seed=0x41535452+index*3719
        fun noise(): Double {seed=seed xor (seed shl 13);seed=seed xor (seed ushr 17);seed=seed xor (seed shl 5);return (seed ushr 8)/8388608.0-1}
        BufferedOutputStream(file.outputStream()).use{out->
            fun short(v: Int){out.write(v and 255);out.write((v ushr 8) and 255)}
            fun word(v: Int){short(v);short(v ushr 16)}
            out.write("RIFF".toByteArray());word(36+frames*2);out.write("WAVEfmt ".toByteArray());word(16);short(1);short(1);word(rate);word(rate*2);short(2);short(16);out.write("data".toByteArray());word(frames*2)
            var phase=0.0;var filtered=0.0;var previousNoise=0.0
            for(i in 0 until frames){if(i%4096==0)check(!cancel.get()){ "Cancelled." };val t=i.toDouble()/rate;val n=noise();filtered+=.12*(n-filtered);val high=n-filtered
                val duration=seconds[index];val attack=min(1.0,t/.0015);val fade=min(1.0,(duration-t)/.015)
                val value=when(index){
                    0,1->{val freq=(if(index==0)48 else 56)+130*exp(-t*42);phase+=2*PI*freq/rate;tanh(1.8*sin(phase))*exp(-t*(if(index==0)8 else 13))+.18*high*exp(-t*240)}
                    2,3->{val body=sin(2*PI*(if(index==2)185 else 225)*t)*exp(-t*22);.65*body+.7*high*exp(-t*(if(index==2)13 else 24))}
                    4->{val burst=if(t<.035)exp(-(t%.012)*180) else exp(-(t-.035)*22);.85*high*burst}
                    5->.7*(sin(2*PI*830*t)+.4*sin(2*PI*1471*t))*exp(-t*55)
                    6,10->{val metal=(sin(2*PI*4311*t)*sin(2*PI*7237*t)+high)*.5;metal*exp(-t*(if(index==6)60 else 8))}
                    7->.55*sin(2*PI*330*t+4*sin(2*PI*792*t))*exp(-t*8)+.12*high*exp(-t*30)
                    8,9->{phase+=2*PI*((if(index==8)92 else 151)+65*exp(-t*24))/rate;sin(phase)*exp(-t*12)+.06*n*exp(-t*100)}
                    11->.4*(sin(2*PI*2243*t)+.4*sin(2*PI*3511*t)+high)*exp(-t*6)
                    12,13->{val e=if(index==13)exp(-(duration-t)*3.5) else exp(-t*3.5);.6*high*e+.13*sin(2*PI*6341*t)*e}
                    14->{phase+=2*PI*(43.6535+70*exp(-t*65))/rate;tanh(1.3*sin(phase))*exp(-t*1.8)}
                    else->{val blend=t/duration;(.6*filtered+.15*(n-previousNoise))*blend*blend}
                }
                previousNoise=n;short((value*attack*fade*.75*32767).roundToInt().coerceIn(-32767,32767))
            }
        }
    }
}
