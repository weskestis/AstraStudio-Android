package com.weskestis.astrastudio.project

object ExportFormats {
    val sampleRates=listOf(44100,48000,88200,96000,176400,192000)
    const val MASTER_RATE=192000
    fun label(rate: Int): String {
        require(rate in sampleRates)
        return if(rate%1000==0)"${rate/1000} kHz" else "${rate/1000}.${rate%1000/100} kHz"
    }
    fun bytesPerMinute(rate: Int,floating: Boolean): Long {
        require(rate in sampleRates)
        return rate.toLong()*60*(if(floating)8 else 6)
    }
}
