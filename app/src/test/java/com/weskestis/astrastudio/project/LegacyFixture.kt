package com.weskestis.astrastudio.project

import org.json.JSONArray
import org.json.JSONObject

/** Re-create the stored 16-track format; changing just its version is not migration. */
internal fun legacyJson(document: ProjectDocument,version: Int): JSONObject {
    require(version in 1..5)
    val json=JSONObject(ProjectCodec.encode(document)).put("version",version)
    json.remove("trackCount")
    for(key in listOf("tracks","patches","effects","instruments")){
        val source=json.getJSONArray(key);json.put(key,JSONArray(List(16){source.get(it)}))
    }
    fun oldSlot(slot: Int): Int {require(slot%ProjectLimits.TRACKS<16);return slot/ProjectLimits.TRACKS*16+slot%ProjectLimits.TRACKS}
    val clips=json.getJSONArray("clips")
    repeat(clips.length()){val clip=clips.getJSONObject(it);clip.put("slot",oldSlot(clip.getInt("slot")))}
    val regions=json.getJSONArray("regions")
    repeat(regions.length()){val region=regions.getJSONArray(it);region.put(1,oldSlot(region.getInt(1)))}
    return json
}
