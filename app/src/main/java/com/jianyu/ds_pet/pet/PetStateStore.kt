package com.jianyu.ds_pet.pet

import org.json.JSONObject

class PetStateStore {
    private val sections = mutableMapOf<String, JSONObject>()
    private val pets = mutableMapOf<String, MutableMap<String, JSONObject>>()

    @Synchronized fun snapshot(): String {
        val s = JSONObject()
        sections.forEach { (k, v) -> s.put(k, v) }
        val p = JSONObject()
        pets.forEach { (id, leaves) ->
            p.put(id, JSONObject().apply { leaves.forEach { (k, v) -> put(k, v) } })
        }
        return JSONObject().put("sections", s).put("pets", p).toString()
    }

    @Synchronized fun writeSection(name: String, data: JSONObject) {
        val counter = (sections[name]?.optInt("counter") ?: 0) + 1
        sections[name] = JSONObject().put("counter", counter).put("data", data)
    }

    @Synchronized fun writePet(petId: String, name: String, data: JSONObject) {
        val leaves = pets.getOrPut(petId) { mutableMapOf() }
        val counter = (leaves[name]?.optInt("counter") ?: 0) + 1
        leaves[name] = JSONObject().put("counter", counter).put("data", data)
    }
}
