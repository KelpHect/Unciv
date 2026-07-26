package com.unciv.logic

/**
 * Durable canonical record of a completed world wonder.
 *
 * Player projections decide which builder and location fields may be disclosed;
 * this server-side record is never serialized directly to an API-v3 client.
 */
data class WonderCompletionEvent(
    val turn: Int,
    val wonderName: String,
    val builderCivilizationId: String,
    val cityId: String,
    val cityName: String,
    val x: Int,
    val y: Int,
) : IsPartOfGameInfoSerialization {
    @Suppress("unused")
    constructor() : this(0, "", "", "", "", 0, 0)
}
